"""收藏夹 Vault — server-side encrypted storage.

Faithful port of android `data/vault/VaultCrypto.kt`, `data/repository/VaultRepository.kt`,
`VaultItemPayload.kt` and `VaultMetadata.kt`:

- Key derivation: PBKDF2-HMAC-SHA256, 120,000 iterations, random 16-byte salt,
  256-bit AES key. Encryption: AES-256-GCM with a random 12-byte IV and the
  standard 128-bit auth tag (Java `AES/GCM/NoPadding` layout: ciphertext||tag).
- Metadata (salt/verifier/generation descriptors) lives in
  ``private/vault-meta.json`` using the Android DataStore field names. A password
  change writes active+pending descriptors first, then replaces all ciphertext and
  the hidden generation marker row in ONE sqlite transaction, then stabilizes.
- Item payloads are versioned JSON (`{"version":2,"content":...,"note":?}`);
  legacy v1 rows (`title`/`content`) still decode.
- The derived key exists only in this module's memory cache and is zeroized on
  lock. Plaintext never reaches disk; passwords/keys are never logged.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import threading
import time
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from ..core.config import PRIVATE_DIR
from ..core.db import write_lock
from ..core.errors import ApiError
from ..core.fs import safe_write_text

# --- VaultCrypto.kt constants -------------------------------------------------
KDF_ITERATIONS = 120_000
SALT_BYTES = 16
IV_BYTES = 12
KEY_BYTES = 32  # 256-bit AES key
VERIFIER_PLAINTEXT = "deskcubby-vault-verifier"

# --- VaultMetadata.kt ---------------------------------------------------------
METADATA_VERSION = 2
MIGRATION_STATE_PREPARED = "prepared_v1"
MAX_SALT_BYTES = 1_024
MAX_KDF_ITERATIONS = 10_000_000
GENERATION_ID_REGEX = re.compile(r"[A-Za-z0-9-]{1,64}")

# Reserved non-user row id written in the same transaction as re-encrypted items
# (Long.MIN_VALUE on Android; auto-generated user ids are positive).
VAULT_KEY_MARKER_ENTITY_ID = -(2**63)
VAULT_KEY_MARKER_PREFIX = "deskcubby-vault-key-generation:"

MIN_PASSWORD_CODE_POINTS = 1
MAX_ITEM_CONTENT_CHARS = 200_000
MAX_ITEM_NOTE_CHARS = 20_000
MAX_ITEMS = 50_000

METADATA_PATH = PRIVATE_DIR / "vault-meta.json"

_metadata_mutex = threading.RLock()


class _Session:
    """Memory-only unlock state; mirrors mutableSessionKey/lockEpoch/lockState."""

    def __init__(self) -> None:
        self.mutex = threading.RLock()
        self.key: bytearray | None = None
        self.state = "NOT_SET"  # NOT_SET | LOCKED | UNLOCKED
        self.epoch = 0

    def install(self, key: bytes, operation_epoch: int) -> None:
        if operation_epoch != self.epoch:
            self.clear()
            self.state = "LOCKED"
            return
        self.key = bytearray(key)
        self.state = "UNLOCKED"

    def clear(self) -> None:
        if self.key is not None:
            for index in range(len(self.key)):
                self.key[index] = 0
        self.key = None


_session = _Session()


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------
# Crypto primitives (VaultCrypto.kt)
# ---------------------------------------------------------------------------

def derive_key(password: str, salt: bytes, iterations: int = KDF_ITERATIONS) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations, dklen=KEY_BYTES)


def encrypt(key: bytes, plaintext: str) -> tuple[str, str]:
    """Returns (cipherBase64, ivBase64), NO_WRAP style like the Room columns."""
    iv = os.urandom(IV_BYTES)
    cipher_bytes = AESGCM(key).encrypt(iv, plaintext.encode("utf-8"), None)
    return base64.b64encode(cipher_bytes).decode("ascii"), base64.b64encode(iv).decode("ascii")


def decrypt(key: bytes, cipher_b64: str, iv_b64: str) -> str | None:
    """Opaque failure: wrong key/tampered data/malformed base64 all return None."""
    try:
        cipher_bytes = base64.b64decode(cipher_b64.encode("ascii"), validate=True)
        iv = base64.b64decode(iv_b64.encode("ascii"), validate=True)
        if len(iv) != IV_BYTES or len(cipher_bytes) < 16:
            return None
        plain = AESGCM(key).decrypt(iv, cipher_bytes, None)
        return plain.decode("utf-8")
    except Exception:  # noqa: BLE001 - deliberately opaque, no key/data material leaks
        return None


# ---------------------------------------------------------------------------
# Versioned item payload (VaultItemPayload.kt)
# ---------------------------------------------------------------------------

CURRENT_PAYLOAD_VERSION = 2


def encode_item_payload(content: str, note: str | None) -> str:
    payload: dict[str, Any] = {"version": CURRENT_PAYLOAD_VERSION, "content": content}
    trimmed_note = (note or "").strip()
    if trimmed_note:
        payload["note"] = trimmed_note
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def decode_item_payload(plaintext: str) -> dict[str, Any] | None:
    try:
        json_obj = json.loads(plaintext)
    except ValueError:
        return None
    if not isinstance(json_obj, dict):
        return None
    if "version" in json_obj:
        raw_version = json_obj.get("version")
        if isinstance(raw_version, bool) or not isinstance(raw_version, (int, float)):
            return None
        if float(raw_version) != float(CURRENT_PAYLOAD_VERSION):
            return None
        content = json_obj.get("content")
        if not isinstance(content, str):
            return None
        note_value = json_obj.get("note")
        if note_value is not None and not isinstance(note_value, str):
            return None
        note = note_value.strip() if isinstance(note_value, str) else ""
        return {"content": content, "note": note or None}
    # Legacy v1 rows stored `title` plus `content`.
    legacy_title = json_obj.get("title")
    legacy_content = json_obj.get("content")
    if legacy_title is not None and not isinstance(legacy_title, str):
        return None
    if legacy_content is not None and not isinstance(legacy_content, str):
        return None
    title = legacy_title or ""
    content_text = legacy_content or ""
    if content_text:
        return {"content": content_text, "note": title or None}
    return {"content": title, "note": None}


# ---------------------------------------------------------------------------
# Metadata store (vault-meta.json; VaultMetadata.kt field names)
# ---------------------------------------------------------------------------

def vault_key_marker_plaintext(generation_id: str) -> str:
    return VAULT_KEY_MARKER_PREFIX + generation_id


def vault_database_marker_matches(
    generation_id: str | None, marker_present: bool, decrypted_marker: str | None
) -> bool:
    if generation_id is None:
        return not marker_present
    return bool(marker_present) and decrypted_marker == vault_key_marker_plaintext(generation_id)


def _decode_key_metadata(
    salt_b64: str | None,
    verifier_cipher: str | None,
    verifier_iv: str | None,
    iterations: int | None,
    generation_id: str | None,
    *,
    generation_required: bool,
) -> dict[str, Any] | None:
    if salt_b64 is None or len(salt_b64) > MAX_SALT_BYTES * 2:
        return None
    try:
        salt = base64.b64decode(salt_b64.encode("ascii"), validate=True)
    except Exception:  # noqa: BLE001 - malformed base64 means invalid descriptor
        return None
    if not salt or len(salt) > MAX_SALT_BYTES:
        return None
    cipher = verifier_cipher if verifier_cipher and verifier_cipher.strip() else None
    iv = verifier_iv if verifier_iv and verifier_iv.strip() else None
    if cipher is None or iv is None:
        return None
    safe_iterations = iterations if iterations is not None and 1 <= iterations <= MAX_KDF_ITERATIONS else None
    if iterations is None or safe_iterations is None:
        return None
    safe_generation_id = (
        generation_id if generation_id is not None and GENERATION_ID_REGEX.fullmatch(generation_id) else None
    )
    if generation_required and safe_generation_id is None:
        return None
    if generation_id is not None and safe_generation_id is None:
        return None
    return {
        "salt": salt,
        "saltBase64": salt_b64,
        "verifierCipher": cipher,
        "verifierIv": iv,
        "iterations": safe_iterations,
        "generationId": safe_generation_id,
    }


def read_stored_metadata() -> dict[str, Any] | None:
    """Decoded {active, pending?} descriptors, or None when absent/damaged."""
    with _metadata_mutex:
        try:
            raw = METADATA_PATH.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            return None
        raw = raw.strip()
        if not raw:
            return None
        try:
            root = json.loads(raw)
        except ValueError:
            return None
        if not isinstance(root, dict):
            return None
        has_any_field = any(root.get(key) is not None for key in root)
        if not has_any_field:
            return None
        active = _decode_key_metadata(
            root.get("saltBase64"),
            root.get("verifierCipher"),
            root.get("verifierIv"),
            root.get("kdfIterations"),
            root.get("activeGenerationId"),
            generation_required=False,
        )
        if active is None:
            # A damaged/partial record must never be mistaken for first-time setup;
            # callers treat "metadata file present but undecodable" as locked.
            return None
        pending = None
        if root.get("migrationState") == MIGRATION_STATE_PREPARED:
            pending = _decode_key_metadata(
                root.get("pendingSaltBase64"),
                root.get("pendingVerifierCipher"),
                root.get("pendingVerifierIv"),
                root.get("pendingKdfIterations"),
                root.get("pendingGenerationId"),
                generation_required=True,
            )
        return {"active": active, "pending": pending}


def metadata_file_exists() -> bool:
    return METADATA_PATH.is_file()


def _write_stable(metadata: dict[str, Any]) -> None:
    document: dict[str, Any] = {
        "metadataVersion": METADATA_VERSION,
        "saltBase64": metadata["saltBase64"],
        "verifierCipher": metadata["verifierCipher"],
        "verifierIv": metadata["verifierIv"],
        "kdfIterations": metadata["iterations"],
    }
    if metadata["generationId"] is not None:
        document["activeGenerationId"] = metadata["generationId"]
    with _metadata_mutex:
        METADATA_PATH.parent.mkdir(parents=True, exist_ok=True)
        safe_write_text(METADATA_PATH, json.dumps(document, ensure_ascii=False, indent=2))


def _write_prepared(active: dict[str, Any], pending: dict[str, Any]) -> None:
    assert pending["generationId"] is not None
    document: dict[str, Any] = {
        "metadataVersion": METADATA_VERSION,
        "saltBase64": active["saltBase64"],
        "verifierCipher": active["verifierCipher"],
        "verifierIv": active["verifierIv"],
        "kdfIterations": active["iterations"],
        "activeGenerationId": active["generationId"],
        "migrationState": MIGRATION_STATE_PREPARED,
        "pendingSaltBase64": pending["saltBase64"],
        "pendingVerifierCipher": pending["verifierCipher"],
        "pendingVerifierIv": pending["verifierIv"],
        "pendingKdfIterations": pending["iterations"],
        "pendingGenerationId": pending["generationId"],
    }
    with _metadata_mutex:
        METADATA_PATH.parent.mkdir(parents=True, exist_ok=True)
        safe_write_text(METADATA_PATH, json.dumps(document, ensure_ascii=False, indent=2))


# ---------------------------------------------------------------------------
# Session + database resolution (VaultRepository.kt)
# ---------------------------------------------------------------------------

def status() -> dict[str, Any]:
    with _session.mutex:
        unlocked = _session.state == "UNLOCKED" and _session.key is not None
        if not metadata_file_exists():
            has_password = False
            if _session.state == "UNLOCKED":
                # Metadata vanished underneath us: drop the stale session.
                _session.clear()
                _session.state = "NOT_SET"
                unlocked = False
        else:
            has_password = True
            if _session.state == "NOT_SET":
                _session.state = "LOCKED"
        return {"hasPassword": has_password, "unlocked": unlocked}


def is_unlocked() -> bool:
    return bool(status()["unlocked"])


def _key_verifies(metadata: dict[str, Any], key: bytes) -> bool:
    return decrypt(key, metadata["verifierCipher"], metadata["verifierIv"]) == VERIFIER_PLAINTEXT


def _marker_row(con) -> Any:
    return con.execute(
        "SELECT id, cipherText, iv FROM vault_items WHERE id = ? LIMIT 1",
        (VAULT_KEY_MARKER_ENTITY_ID,),
    ).fetchone()


def _database_uses_key(con, metadata: dict[str, Any], key: bytes) -> bool:
    marker = _marker_row(con)
    decrypted = decrypt(key, marker["cipherText"], marker["iv"]) if marker is not None else None
    return vault_database_marker_matches(
        generation_id=metadata["generationId"],
        marker_present=marker is not None,
        decrypted_marker=decrypted,
    )


def resolve_password(con, metadata: dict[str, Any], password: str) -> tuple[dict[str, Any], bytes] | None:
    candidates = [metadata["active"]] + ([metadata["pending"]] if metadata.get("pending") else [])
    needs_generation_evidence = metadata.get("pending") is not None
    for candidate in candidates:
        key = derive_key(password, candidate["salt"], candidate["iterations"])
        if _key_verifies(candidate, key) and (
            not needs_generation_evidence or _database_uses_key(con, candidate, key)
        ):
            if needs_generation_evidence:
                _best_effort_stabilize(candidate)
            return candidate, key
    return None


def _resolve_existing_key(con, metadata: dict[str, Any], key: bytes) -> dict[str, Any] | None:
    candidates = [metadata["active"]] + ([metadata["pending"]] if metadata.get("pending") else [])
    needs_generation_evidence = metadata.get("pending") is not None
    for candidate in candidates:
        if _key_verifies(candidate, key) and (
            not needs_generation_evidence or _database_uses_key(con, candidate, key)
        ):
            if needs_generation_evidence:
                _best_effort_stabilize(candidate)
            return candidate
    return None


def _best_effort_stabilize(metadata: dict[str, Any]) -> None:
    try:
        _write_stable(metadata)
    except Exception:  # noqa: BLE001 - dual descriptors remain sufficient for a later retry
        pass


def _require_unlocked_key(con) -> bytes:
    """Re-validates the memory key against metadata and marker before every mutation."""
    with _session.mutex:
        if _session.state != "UNLOCKED" or _session.key is None:
            raise ApiError(423, "locked", "收藏夹已锁定")
        key = bytes(_session.key)
    metadata = read_stored_metadata()
    if metadata is None:
        raise ApiError(423, "locked", "收藏夹已锁定")
    if _resolve_existing_key(con, metadata, key) is None:
        raise ApiError(423, "locked", "收藏夹已锁定")
    return key


# ---------------------------------------------------------------------------
# Public API used by the router
# ---------------------------------------------------------------------------

def validate_new_password(password: str) -> bool:
    return len(password) >= MIN_PASSWORD_CODE_POINTS


def setup_password(con, password: str) -> None:
    with _session.mutex, _metadata_mutex:
        if not validate_new_password(password):
            raise ApiError(400, "invalid_password", "密码至少需要 1 个字符")
        if metadata_file_exists():
            raise ApiError(409, "already_set", "收藏夹密码已设置")
        operation_epoch = _session.epoch
        salt = os.urandom(SALT_BYTES)
        key = derive_key(password, salt, KDF_ITERATIONS)
        cipher_b64, iv_b64 = encrypt(key, VERIFIER_PLAINTEXT)
        metadata = {
            "salt": salt,
            "saltBase64": base64.b64encode(salt).decode("ascii"),
            "verifierCipher": cipher_b64,
            "verifierIv": iv_b64,
            "iterations": KDF_ITERATIONS,
            "generationId": None,
        }
        _write_stable(metadata)
        _session.install(key, operation_epoch)


def unlock(con, password: str) -> None:
    with _session.mutex:
        operation_epoch = _session.epoch
        metadata = read_stored_metadata()
        if metadata is None:
            if not metadata_file_exists():
                _session.state = "NOT_SET"
                raise ApiError(400, "not_set", "尚未设置收藏夹密码")
            # Damaged metadata keeps the vault pessimistically locked.
            _session.state = "LOCKED"
            raise ApiError(500, "metadata_damaged", "收藏夹元数据无法读取，已保持锁定")
        resolved = resolve_password(con, metadata, password)
        if resolved is None:
            raise ApiError(401, "wrong_password", "密码错误")
        _session.install(resolved[1], operation_epoch)


def lock() -> None:
    with _session.mutex:
        _session.epoch += 1
        if _session.state == "UNLOCKED":
            _session.state = "LOCKED"
        _session.clear()


def change_password(con, old_password: str, new_password: str) -> None:
    """Full re-encrypt in one recoverable protocol (VaultRepository.changePassword).

    1. Persist active+pending descriptors atomically (PREPARED).
    2. Replace all ciphertext rows + generation marker in ONE sqlite transaction.
    3. Stabilize the pending descriptor as canonical.
    """
    with _session.mutex:
        if not validate_new_password(new_password):
            raise ApiError(400, "invalid_new_password", "新密码至少需要 1 个字符")
        operation_epoch = _session.epoch
        metadata = read_stored_metadata()
        current_metadata = metadata["active"] if metadata else None
        if current_metadata is None:
            raise ApiError(401, "wrong_password", "旧密码错误")
        resolved = resolve_password(con, metadata, old_password)
        if resolved is None:
            raise ApiError(401, "wrong_password", "旧密码错误")
        _, current_key = resolved

        rows = con.execute(
            "SELECT * FROM vault_items WHERE id != ? ORDER BY sortOrder ASC, id ASC",
            (VAULT_KEY_MARKER_ENTITY_ID,),
        ).fetchall()
        if len(rows) > MAX_ITEMS:
            raise ApiError(413, "too_many_items", "收藏条目数量超出限制")
        plaintext_rows: list[tuple[Any, str]] = []
        for row in rows:
            plaintext = decrypt(current_key, row["cipherText"], row["iv"])
            if plaintext is None or decode_item_payload(plaintext) is None:
                raise ApiError(409, "corrupted_items", "存在无法解密的条目，已中止修改密码")
            plaintext_rows.append((row, plaintext))

        new_salt = os.urandom(SALT_BYTES)
        new_key = derive_key(new_password, new_salt, KDF_ITERATIONS)
        cipher_b64, iv_b64 = encrypt(new_key, VERIFIER_PLAINTEXT)
        pending = {
            "salt": new_salt,
            "saltBase64": base64.b64encode(new_salt).decode("ascii"),
            "verifierCipher": cipher_b64,
            "verifierIv": iv_b64,
            "iterations": KDF_ITERATIONS,
            "generationId": _uuid4_str(),
        }

        re_encrypted: list[tuple[int, str, str, int, int, int]] = []
        for row, plaintext in plaintext_rows:
            enc_cipher, enc_iv = encrypt(new_key, plaintext)
            re_encrypted.append(
                (
                    int(row["id"]),
                    enc_cipher,
                    enc_iv,
                    int(row["createdAt"]),
                    int(row["updatedAt"]),
                    int(row["sortOrder"]),
                )
            )
        marker_cipher, marker_iv = encrypt(
            new_key, vault_key_marker_plaintext(pending["generationId"])
        )

        replacement_started = False
        try:
            _write_prepared(current_metadata, pending)
            replacement_started = True
            with write_lock():
                with con:
                    con.execute("DELETE FROM vault_items")
                    for item_id, enc_cipher, enc_iv, created, updated, sort_order in re_encrypted:
                        con.execute(
                            "INSERT INTO vault_items(id, cipherText, iv, createdAt, updatedAt, sortOrder)"
                            " VALUES(?,?,?,?,?,?)",
                            (item_id, enc_cipher, enc_iv, created, updated, sort_order),
                        )
                    con.execute(
                        "INSERT INTO vault_items(id, cipherText, iv, createdAt, updatedAt, sortOrder)"
                        " VALUES(?,?,?,0,0,0)",
                        (VAULT_KEY_MARKER_ENTITY_ID, marker_cipher, marker_iv),
                    )
            _session.install(new_key, operation_epoch)
            _write_stable(pending)
            _session.install(new_key, operation_epoch)
        except Exception:
            if replacement_started:
                # An ambiguous transaction must not authorize later mutations.
                _session.clear()
                _session.state = "LOCKED"
            raise


def _uuid4_str() -> str:
    import uuid

    return str(uuid.uuid4())


def list_items(con) -> dict[str, Any]:
    key = _require_unlocked_key(con)
    rows = con.execute(
        "SELECT * FROM vault_items WHERE id != ? ORDER BY sortOrder ASC, updatedAt DESC, id DESC",
        (VAULT_KEY_MARKER_ENTITY_ID,),
    ).fetchall()
    items: list[dict[str, Any]] = []
    corrupted_count = 0
    for row in rows:
        plaintext = decrypt(key, row["cipherText"], row["iv"])
        payload = decode_item_payload(plaintext) if plaintext is not None else None
        if payload is None:
            corrupted_count += 1
            continue
        items.append(
            {
                "id": int(row["id"]),
                "content": payload["content"],
                "note": payload["note"],
                "createdAt": int(row["createdAt"]),
                "updatedAt": int(row["updatedAt"]),
                "sortOrder": int(row["sortOrder"]),
            }
        )
    return {"items": items, "corruptedItemCount": corrupted_count}


def add_item(con, content: str, note: str | None) -> dict[str, Any]:
    if content is None or not content.strip():
        raise ApiError(400, "empty_content", "内容不能为空")
    if len(content) > MAX_ITEM_CONTENT_CHARS or len(note or "") > MAX_ITEM_NOTE_CHARS:
        raise ApiError(413, "content_too_long", "内容过长")
    key = _require_unlocked_key(con)
    cipher_b64, iv_b64 = encrypt(key, encode_item_payload(content, note))
    now = now_ms()
    with write_lock(), con:
        next_sort = con.execute(
            "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM vault_items"
        ).fetchone()[0]
        cur = con.execute(
            "INSERT INTO vault_items(cipherText, iv, createdAt, updatedAt, sortOrder)"
            " VALUES(?,?,?,?,?)",
            (cipher_b64, iv_b64, now, now, int(next_sort)),
        )
        item_id = int(cur.lastrowid)
    return get_item(con, item_id)


def update_item(con, item_id: int, content: str, note: str | None) -> dict[str, Any]:
    if item_id == VAULT_KEY_MARKER_ENTITY_ID:
        raise ApiError(404, "not_found", "条目不存在")
    if content is None or not content.strip():
        raise ApiError(400, "empty_content", "内容不能为空")
    if len(content) > MAX_ITEM_CONTENT_CHARS or len(note or "") > MAX_ITEM_NOTE_CHARS:
        raise ApiError(413, "content_too_long", "内容过长")
    key = _require_unlocked_key(con)
    cipher_b64, iv_b64 = encrypt(key, encode_item_payload(content, note))
    with write_lock(), con:
        cur = con.execute(
            "UPDATE vault_items SET cipherText = ?, iv = ?, updatedAt = ? WHERE id = ?",
            (cipher_b64, iv_b64, now_ms(), item_id),
        )
        if cur.rowcount == 0:
            raise ApiError(404, "not_found", "条目不存在")
    return get_item(con, item_id)


def delete_item(con, item_id: int) -> None:
    if item_id == VAULT_KEY_MARKER_ENTITY_ID:
        raise ApiError(404, "not_found", "条目不存在")
    _require_unlocked_key(con)
    with write_lock(), con:
        cur = con.execute("DELETE FROM vault_items WHERE id = ?", (item_id,))
        if cur.rowcount == 0:
            raise ApiError(404, "not_found", "条目不存在")


def reorder_items(con, ordered_ids: list[int]) -> dict[str, Any]:
    key = _require_unlocked_key(con)  # noqa: F841 - mutation gate, mirrors Android
    with write_lock(), con:
        current_ids = [
            int(r[0])
            for r in con.execute(
                "SELECT id FROM vault_items WHERE id != ? ORDER BY sortOrder ASC, updatedAt DESC, id DESC",
                (VAULT_KEY_MARKER_ENTITY_ID,),
            ).fetchall()
        ]
        ordered = [int(item) for item in ordered_ids]
        if (
            len(ordered) != len(current_ids)
            or len(set(ordered)) != len(ordered)
            or set(ordered) != set(current_ids)
        ):
            raise ApiError(400, "reorder_mismatch", "排序条目与现有列表不一致")
        for index, item_id in enumerate(ordered):
            con.execute(
                "UPDATE vault_items SET sortOrder = ? WHERE id = ?", (index, item_id)
            )
    return list_items(con)


def get_item(con, item_id: int) -> dict[str, Any]:
    listing = list_items(con)
    for item in listing["items"]:
        if item["id"] == int(item_id):
            return item
    raise ApiError(404, "not_found", "条目不存在")
