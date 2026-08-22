"""Cloud sync engine (web port of android `data/sync/CloudSyncEngine.kt` +
`RemoteManifestStore.kt` + `CloudSyncUndoStore.kt`, scoped to the web layout).

One run reconciles a single remote snapshot with the local workspace:

- Local inventory: `diary/*.md`, the whole `media/**` tree including
  `dc-media.json`, plus one app-level v34 JSON snapshot (`records/app-backup.json`)
  built by `backup_codec.export_backup`.
- Remote layout mirrors Android's manifest store: payloads are immutable,
  content-addressed blobs (`.deskcubby-object-<keyhash>-<contenthash>`) and a
  small mutable manifest (`.deskcubby-sync-v1.manifest`) is the only mutable
  object. A missing manifest is created on first sync.
- Modes mirror `CloudSyncRunMode`: `now` (three-way by manifest base hash),
  `force_upload` / `force_download` (pick one side, skip the other direction;
  deletions never propagate).
- Conflicts never silently overwrite: the local bytes are preserved in a
  sibling `<name>.conflict-<ts>` copy before the remote version is applied.
- Before any overwrite/create is applied locally an undo snapshot (overwritten
  originals + created-this-round list) is stored in `cloud_sync_state.undoSnapshotJson`,
  keeping only the latest run; "撤回一次" restores it.
- The aggregate `{uploaded, downloaded, conflicts, finishedAtMs}` result is
  persisted to `cloud_sync_state.lastResultJson` and returned.

Runs are serialized process-wide by a module-level asyncio.Lock.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import time
from pathlib import Path
from typing import Any

from ..core.config import DATA_DIR, DIARY_DIR, MEDIA_DIR
from ..core.db import write_lock
from ..core.errors import ApiError

MANIFEST_STORAGE_NAME = ".deskcubby-sync-v1.manifest"
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
MAX_OBJECT_BYTES = 64 * 1024 * 1024          # CloudSyncLimits.maxObjectBytes
MAX_OBJECTS = 10_000                          # CloudSyncLimits.maxObjects
MAX_UNDO_ENTRY_BYTES = 8 * 1024 * 1024        # per-entry cap for inline originals
MAX_UNDO_TOTAL_BYTES = 32 * 1024 * 1024
UNDO_KEEP_LATEST_ONLY = True                  # CloudSyncUndoStore keeps one run

MODES = ("now", "force_upload", "force_download")

sync_lock = asyncio.Lock()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def object_storage_name(key: str, content_hash: str) -> str:
    key_hash = hashlib.sha256(key.encode("utf-8")).hexdigest()[:32]
    return f".deskcubby-object-{key_hash}-{content_hash}"


# ---------------------------------------------------------------------------
# Config access helpers
# ---------------------------------------------------------------------------

def load_configs(con) -> list[dict[str, Any]]:
    from .settings_store import load_settings

    configs = load_settings(con).get("cloudSyncConfigs")
    return [dict(c) for c in configs if isinstance(c, dict)] if isinstance(configs, list) else []


def find_config(con, config_id: str) -> dict[str, Any]:
    for config in load_configs(con):
        if config.get("id") == config_id:
            return config
    raise ApiError(404, "config_not_found", "Sync configuration not found")


def read_secrets(config_id: str) -> dict[str, Any]:
    """Server-private credentials keyed by config id (never returned via API)."""
    path = DATA_DIR / "private" / "cloud-secrets.json"
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    secrets = data.get(config_id) if isinstance(data, dict) else None
    return dict(secrets) if isinstance(secrets, dict) else {}


def write_secrets(config_id: str, secrets: dict[str, Any]) -> None:
    path = DATA_DIR / "private" / "cloud-secrets.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    except (OSError, ValueError):
        data = {}
    if not isinstance(data, dict):
        data = {}
    cleaned = {k: v for k, v in secrets.items() if isinstance(v, str) and v}
    if cleaned:
        data[config_id] = cleaned
    else:
        data.pop(config_id, None)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    try:
        path.chmod(0o600)
    except OSError:  # pragma: no cover - best effort on filesystems without perms
        pass


def delete_secrets(config_id: str) -> None:
    write_secrets(config_id, {})


def hydrate_config(con, config_id: str) -> dict[str, Any]:
    """Merge stored non-secret metadata with server-side secrets."""
    config = find_config(con, config_id)
    merged = {**config, **read_secrets(config_id)}
    validate_config_for_sync(merged)
    return merged


def validate_config_for_sync(config: dict[str, Any]) -> None:
    from urllib.parse import urlsplit

    if not config.get("enabled"):
        raise ApiError(400, "sync_disabled", "This sync configuration is not enabled")
    endpoint = str(config.get("endpointUrl") or "").strip()
    parts = urlsplit(endpoint)
    scheme = (parts.scheme or "").lower()
    allow_http = bool(config.get("allowInsecureHttp"))
    if scheme == "http" and not allow_http:
        raise ApiError(400, "insecure_endpoint",
                       "HTTP sync is disabled by default; explicitly allow HTTP for trusted LAN services")
    if scheme not in ("https",) and not (scheme == "http" and allow_http):
        raise ApiError(400, "invalid_endpoint", "Cloud service URL must use HTTPS")
    if not parts.hostname or parts.username or parts.query or parts.fragment:
        raise ApiError(400, "invalid_endpoint", "Cloud service URL must not embed account info")
    service_type = config.get("serviceType")
    if service_type not in ("WEBDAV", "S3_COMPATIBLE"):
        raise ApiError(400, "invalid_service", "Unknown cloud service type")
    if service_type == "S3_COMPATIBLE" and (
        not str(config.get("s3AccessKey") or "") or not str(config.get("s3SecretKey") or "")
    ):
        raise ApiError(400, "missing_credentials", "S3 Access Key and Secret Key are required")


# ---------------------------------------------------------------------------
# Local inventory
# ---------------------------------------------------------------------------

def _local_objects() -> dict[str, Path]:
    objects: dict[str, Path] = {}

    def walk(base: Path, prefix: str) -> None:
        if not base.is_dir():
            return
        for path in sorted(base.rglob("*")):
            if not path.is_file() or ".trash" in path.parts:
                continue
            rel = path.relative_to(base).as_posix()
            objects[f"{prefix}/{rel}"] = path

    walk(DIARY_DIR, "diary")
    walk(MEDIA_DIR, "media")

    # Hidden app-level snapshot files are synced as regular keys.
    return objects


def _app_snapshot_key() -> str:
    return "records/app-backup.json"


# ---------------------------------------------------------------------------
# State store (cloud_sync_state singleton row)
# ---------------------------------------------------------------------------

def _state_row(con) -> Any:
    return con.execute("SELECT * FROM cloud_sync_state WHERE id = 1").fetchone()


def load_last_result(con) -> dict[str, Any] | None:
    row = _state_row(con)
    if row is None or not row["lastResultJson"]:
        return None
    try:
        parsed = json.loads(row["lastResultJson"])
        return parsed if isinstance(parsed, dict) else None
    except ValueError:
        return None


def save_last_result(con, result: dict[str, Any]) -> None:
    con.execute(
        "INSERT INTO cloud_sync_state(id, lastResultJson, undoSnapshotJson) VALUES(1, ?, NULL) "
        "ON CONFLICT(id) DO UPDATE SET lastResultJson = excluded.lastResultJson",
        (json.dumps(result, ensure_ascii=False),),
    )
    con.commit()


def load_undo_snapshot(con) -> dict[str, Any] | None:
    row = _state_row(con)
    if row is None or not row["undoSnapshotJson"]:
        return None
    try:
        parsed = json.loads(row["undoSnapshotJson"])
        return parsed if isinstance(parsed, dict) else None
    except ValueError:
        return None


def save_undo_snapshot(con, snapshot: dict[str, Any] | None) -> None:
    value = json.dumps(snapshot, ensure_ascii=False) if snapshot else None
    con.execute(
        "INSERT INTO cloud_sync_state(id, lastResultJson, undoSnapshotJson) VALUES(1, NULL, ?) "
        "ON CONFLICT(id) DO UPDATE SET undoSnapshotJson = excluded.undoSnapshotJson",
        (value,),
    )
    con.commit()


# ---------------------------------------------------------------------------
# Transport abstraction
# ---------------------------------------------------------------------------

class _Transport:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        self._client: Any = None
        self._kind = str(config.get("serviceType") or "WEBDAV")

    def _get_client(self):
        if self._client is None:
            if self._kind == "S3_COMPATIBLE":
                from .s3_client import S3Client

                self._client = S3Client(self.config)
            else:
                from .webdav_client import WebDavClient

                self._client = WebDavClient(self.config)
        return self._client

    def get(self, name: str, max_bytes: int = MAX_OBJECT_BYTES) -> bytes | None:
        client = self._get_client()
        if self._kind == "S3_COMPATIBLE":
            return client.get_object(name, max_bytes)
        return client.get(name, max_bytes)

    def put(self, name: str, data: bytes) -> None:
        client = self._get_client()
        if self._kind == "S3_COMPATIBLE":
            client.put_object(name, data)
        else:
            client.put(name, data)

    def ensure_collection(self) -> None:
        if self._kind != "WEBDAV":
            return  # S3 buckets need no MKCOL equivalent
        client = self._get_client()
        segments = [
            segment for segment in str(self.config.get("remotePath") or "DeskCubby").split("/")
            if segment
        ]
        if segments and not client.exists():
            client.mkcol(segments)


Transport = _Transport


# ---------------------------------------------------------------------------
# Manifest handling
# ---------------------------------------------------------------------------

def encode_manifest(entries: dict[str, dict[str, Any]]) -> bytes:
    return json.dumps(
        {"version": 1, "objects": entries},
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def decode_manifest(data: bytes) -> dict[str, dict[str, Any]]:
    parsed = json.loads(data.decode("utf-8"))
    objects = parsed.get("objects") if isinstance(parsed, dict) else None
    if not isinstance(objects, dict) or len(objects) > MAX_OBJECTS:
        raise ApiError(502, "manifest_invalid", "Remote sync manifest is invalid")
    clean: dict[str, dict[str, Any]] = {}
    for key, entry in objects.items():
        if not isinstance(key, str) or not isinstance(entry, dict):
            continue
        digest = str(entry.get("sha256") or "")
        if len(digest) != 64 or not all(c in "0123456789abcdef" for c in digest.lower()):
            continue
        clean[key] = {
            "sha256": digest.lower(),
            "size": int(entry.get("size") or 0),
            "lastModified": int(entry.get("lastModified") or 0),
        }
    return clean


# ---------------------------------------------------------------------------
# Undo snapshot
# ---------------------------------------------------------------------------

class _UndoRecorder:
    def __init__(self, con):
        self.con = con
        self.entries: list[dict[str, Any]] = []
        self.total_bytes = 0

    def capture_overwrite(self, key: str, path: Path, original: bytes) -> None:
        if not original or len(original) > MAX_UNDO_ENTRY_BYTES:
            return
        if self.total_bytes + len(original) > MAX_UNDO_TOTAL_BYTES:
            return
        self.total_bytes += len(original)
        self.entries.append({
            "key": key,
            "path": path.relative_to(DATA_DIR).as_posix(),
            "action": "overwrite",
            "contentBase64": base64.b64encode(original).decode("ascii"),
            "sha256": sha256_bytes(original),
        })

    def capture_create(self, key: str, path: Path) -> None:
        self.entries.append({
            "key": key,
            "path": path.relative_to(DATA_DIR).as_posix(),
            "action": "create",
        })

    def commit(self, config_id: str, finished_at_ms: int) -> None:
        # Latest run wins; a previous snapshot is discarded like CloudSyncUndoStore.beginRun.
        snapshot = {
            "configId": config_id,
            "finishedAtMs": finished_at_ms,
            "entries": self.entries,
        }
        with write_lock():
            save_undo_snapshot(self.con, snapshot)


# ---------------------------------------------------------------------------
# Engine
# ---------------------------------------------------------------------------

async def run_sync(app, config_id: str, mode: str = "now") -> dict[str, Any]:
    """Run one serialized sync pass; returns the aggregate result document."""
    if mode not in MODES:
        raise ApiError(400, "invalid_mode", "mode must be now, force_upload or force_download")
    if sync_lock.locked():
        raise ApiError(409, "sync_running", "Another sync is already running")
    async with sync_lock:
        con = getattr(app.state, "db", None)
        if con is None:
            raise ApiError(500, "not_ready", "Application is not ready")
        config = hydrate_config(con, config_id)
        return await asyncio.to_thread(_run_sync_blocking, con, config, mode)


def _run_sync_blocking(con, config: dict[str, Any], mode: str) -> dict[str, Any]:
    started_at_ms = int(time.time() * 1000)

    # --- build the local inventory ----------------------------------------
    local_files = _local_objects()

    from .backup_codec import export_backup

    snapshot_doc = export_backup(con, DATA_DIR)
    snapshot_bytes = json.dumps(snapshot_doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    snapshot_key = _app_snapshot_key()
    local_entries: dict[str, dict[str, Any]] = {}
    for key, path in local_files.items():
        try:
            data = path.read_bytes()
        except OSError:
            continue
        if len(data) > MAX_OBJECT_BYTES:
            continue
        stat = path.stat()
        local_entries[key] = {
            "sha256": sha256_bytes(data),
            "size": len(data),
            "lastModified": int(stat.st_mtime * 1000),
        }
    local_entries[snapshot_key] = {
        "sha256": sha256_bytes(snapshot_bytes),
        "size": len(snapshot_bytes),
        "lastModified": started_at_ms,
    }

    # --- fetch (or create) the remote manifest ----------------------------
    transport = _Transport(config)
    transport.ensure_collection()
    raw_manifest = transport.get(MANIFEST_STORAGE_NAME, MAX_MANIFEST_BYTES)
    created_manifest = False
    if raw_manifest is None:
        remote_entries: dict[str, dict[str, Any]] = {}
        created_manifest = True
    else:
        remote_entries = decode_manifest(raw_manifest)

    base_entries = dict(remote_entries)
    uploaded = downloaded = conflicts = 0
    undo = _UndoRecorder(con)

    def upload(key: str, data: bytes, entry: dict[str, Any]) -> None:
        storage_name = object_storage_name(key, entry["sha256"])
        if base_entries.get(key, {}).get("sha256") != entry["sha256"]:
            existing = transport.get(storage_name, max_bytes=MAX_OBJECT_BYTES)
            if existing is None or sha256_bytes(existing) != entry["sha256"]:
                transport.put(storage_name, data)

    def download(key: str, entry: dict[str, Any]) -> bytes | None:
        storage_name = object_storage_name(key, entry["sha256"])
        data = transport.get(storage_name, max_bytes=MAX_OBJECT_BYTES)
        if data is None or sha256_bytes(data) != entry["sha256"]:
            raise ApiError(502, "blob_invalid",
                           "Remote content failed verification; sync again")
        return data

    def target_path(key: str) -> Path:
        from ..core.fs import sanitize_rel_path

        return sanitize_rel_path(key, DATA_DIR / "workspace")

    all_keys = sorted(set(local_entries) | set(remote_entries))
    if len(all_keys) > MAX_OBJECTS:
        raise ApiError(413, "too_many_objects", "Too many objects for one sync run")

    new_remote = dict(remote_entries)
    for key in all_keys:
        local_entry = local_entries.get(key)
        remote_entry = remote_entries.get(key)
        base_entry = base_entries.get(key)

        if local_entry and remote_entry and local_entry["sha256"] == remote_entry["sha256"]:
            continue  # unchanged

        if remote_entry is None:
            # Remote missing -> upload unless force_download skips that direction.
            if mode == "force_download":
                continue
            data = snapshot_bytes if key == snapshot_key else local_files[key].read_bytes()
            upload(key, data, local_entry)
            new_remote[key] = dict(local_entry)
            uploaded += 1
            continue

        if local_entry is None:
            # Local missing -> download unless force_upload skips it. Never propagate deletions.
            if mode == "force_upload":
                continue
            data = download(key, remote_entry)
            assert data is not None
            path = target_path(key)
            path.parent.mkdir(parents=True, exist_ok=True)
            safe_write(path, data)
            undo.capture_create(key, path)
            downloaded += 1
            continue

        # Both sides exist but differ.
        if mode == "force_upload":
            data = snapshot_bytes if key == snapshot_key else local_files[key].read_bytes()
            upload(key, data, local_entry)
            new_remote[key] = dict(local_entry)
            uploaded += 1
            continue

        if mode == "force_download":
            outcome = apply_remote(
                key, download(key, remote_entry), remote_entry, target_path(key), undo,
                conflict_copy=False,
            )
            if outcome == "applied":
                downloaded += 1
            new_remote[key] = dict(remote_entry)
            continue

        # Three-way reconciliation against the manifest base hash.
        local_changed = base_entry is None or local_entry["sha256"] != base_entry["sha256"]
        remote_changed = base_entry is None or remote_entry["sha256"] != base_entry["sha256"]
        conflict = local_changed and remote_changed
        if not conflict and local_changed:  # only local moved -> push it
            data = snapshot_bytes if key == snapshot_key else local_files[key].read_bytes()
            upload(key, data, local_entry)
            new_remote[key] = dict(local_entry)
            uploaded += 1
            continue

        # Remote moved (or both sides moved): bring the remote copy down. When both
        # sides changed, preserve the local edit as a deterministic conflict copy first.
        data = download(key, remote_entry)
        outcome = apply_remote(
            key, data, remote_entry, target_path(key), undo,
            conflict_copy=conflict,
        )
        if conflict:
            conflicts += 1
        elif outcome == "applied":
            downloaded += 1
        new_remote[key] = dict(remote_entry)

    # --- publish the updated manifest --------------------------------------
    manifest_bytes = encode_manifest(new_remote)
    transport.put(MANIFEST_STORAGE_NAME, manifest_bytes)
    if created_manifest:
        uploaded += 0  # manifest creation itself is bookkeeping, not a file transfer

    finished_at_ms = int(time.time() * 1000)
    result = {
        "configId": config.get("id"),
        "startedAtMs": started_at_ms,
        "uploaded": uploaded,
        "downloaded": downloaded,
        "conflicts": conflicts,
        "finishedAtMs": finished_at_ms,
    }
    undo.commit(str(config.get("id")), finished_at_ms)
    with write_lock():
        save_last_result(con, result)
    return result


def apply_remote(
    key: str,
    data: bytes | None,
    entry: dict[str, Any],
    path: Path,
    undo: "_UndoRecorder",
    *,
    conflict_copy: bool,
) -> str:
    """Write remote bytes locally; on true conflicts keep a '<name>.conflict-<ts>' copy."""
    if data is None:
        return "skipped"
    existed = path.exists()
    if existed:
        current = path.read_bytes()
        if sha256_bytes(current) == entry["sha256"]:
            return "unchanged"
        if conflict_copy:
            stamp = time.strftime("%Y%m%d-%H%M%S")
            copy_path = path.with_name(f"{path.name}.conflict-{stamp}")
            seq = 1
            while copy_path.exists():
                copy_path = path.with_name(f"{path.name}.conflict-{stamp}-{seq}")
                seq += 1
            safe_write(copy_path, current)
            # The conflict copy is itself a file created this round; undo removes it
            # so "撤回一次" returns the workspace exactly to its pre-sync state.
            undo.capture_create(key, copy_path)
        undo.capture_overwrite(key, path, current)
    else:
        undo.capture_create(key, path)
    path.parent.mkdir(parents=True, exist_ok=True)
    safe_write(path, data)
    return "conflict-copy" if conflict_copy and existed else "applied"


def safe_write(path: Path, data: bytes) -> None:
    from ..core.fs import safe_write as fs_safe_write

    fs_safe_write(path, data)


# ---------------------------------------------------------------------------
# Undo ("撤回一次")
# ---------------------------------------------------------------------------

def undo_last_sync(app) -> int:
    """Restore overwritten files and delete created ones from the latest run."""
    con = getattr(app.state, "db", None)
    if con is None:
        raise ApiError(500, "not_ready", "Application is not ready")
    snapshot = load_undo_snapshot(con)
    if not snapshot:
        return 0
    restored = 0
    for entry in snapshot.get("entries", []):
        rel = str(entry.get("path") or "")
        action = entry.get("action")
        try:
            from ..core.fs import sanitize_rel_path

            path = sanitize_rel_path(rel, DATA_DIR)
        except ApiError:
            continue
        try:
            if action == "overwrite":
                payload = base64.b64decode(str(entry.get("contentBase64") or ""), validate=True)
                expected = entry.get("sha256")
                if payload and (not expected or sha256_bytes(payload) == expected):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    safe_write(path, payload)
                    restored += 1
            elif action == "create":
                if path.is_file():
                    path.unlink()
                    restored += 1
                    _prune_empty_dirs(path.parent, DATA_DIR / "workspace")
        except (OSError, ValueError):  # noqa: BLE001 - one bad entry must not stop undo
            continue
    with write_lock():
        save_undo_snapshot(con, None)
    return restored


def _prune_empty_dirs(directory: Path, stop: Path) -> None:
    current = directory
    while current.is_dir() and current != stop and stop in current.parents:
        try:
            next(current.iterdir())
            return
        except StopIteration:
            try:
                current.rmdir()
            except OSError:
                return
            current = current.parent
        except OSError:
            return
