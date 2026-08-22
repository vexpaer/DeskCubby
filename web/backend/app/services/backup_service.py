"""Automatic backups: timestamped v34 exports under ``data/backups/<dirName>/``.

Configuration lives in the private settings kv as ``autoBackupV1`` =
``{enabled, dirName, keepCount}`` (the web counterpart of Android's SAF backup
folder + keep count). Every run writes one crash-safe v34 JSON document via
``fs.safe_write`` (temp file → read-back verify → commit) and prunes older
exports so only the newest ``keepCount`` files remain. Exports reuse
``backup_codec.export_backup``, which already strips AI keys, tree URIs and
cloud credentials.
"""
from __future__ import annotations

import json
import re
import time
from typing import Any

from ..core.config import BACKUPS_DIR, DATA_DIR
from ..core.errors import ApiError
from ..core.fs import safe_write_text
from .backup_codec import export_backup

KV_KEY = "autoBackupV1"

DEFAULT_CONFIG = {"enabled": False, "dirName": "auto", "keepCount": 7}

MIN_KEEP_COUNT = 1
MAX_KEEP_COUNT = 100

_DIR_NAME_RE = re.compile(r"[^A-Za-z0-9._ \-\u4e00-\u9fff]")


def load_auto_backup_config(con) -> dict[str, Any]:
    row = con.execute("SELECT value FROM app_settings_kv WHERE key = ?", (KV_KEY,)).fetchone()
    if row is None:
        return dict(DEFAULT_CONFIG)
    try:
        stored = json.loads(row["value"])
    except (ValueError, TypeError):
        return dict(DEFAULT_CONFIG)
    if not isinstance(stored, dict):
        return dict(DEFAULT_CONFIG)
    config = dict(DEFAULT_CONFIG)
    config.update({k: stored[k] for k in DEFAULT_CONFIG if k in stored})
    return sanitize_config(config)


def save_auto_backup_config(con, config: dict[str, Any]) -> dict[str, Any]:
    clean = sanitize_config(config)
    con.execute(
        "INSERT INTO app_settings_kv(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (KV_KEY, json.dumps(clean, ensure_ascii=False)),
    )
    con.commit()
    return clean


def sanitize_config(config: dict[str, Any]) -> dict[str, Any]:
    enabled = bool(config.get("enabled"))
    dir_name = str(config.get("dirName") or DEFAULT_CONFIG["dirName"]).strip().strip("/")
    dir_name = _DIR_NAME_RE.sub("", dir_name)
    if not dir_name or dir_name in (".", "..") or dir_name.startswith("."):
        dir_name = str(DEFAULT_CONFIG["dirName"])
    dir_name = dir_name[:120]
    try:
        keep_count = int(config.get("keepCount", DEFAULT_CONFIG["keepCount"]))
    except (TypeError, ValueError):
        keep_count = DEFAULT_CONFIG["keepCount"]
    keep_count = max(MIN_KEEP_COUNT, min(MAX_KEEP_COUNT, keep_count))
    return {"enabled": enabled, "dirName": dir_name, "keepCount": keep_count}


def _backup_dir(dir_name: str):
    from ..core.fs import sanitize_rel_path

    base = BACKUPS_DIR
    base.mkdir(parents=True, exist_ok=True)
    return sanitize_rel_path(dir_name, base)


def run_auto_backup(con, config: dict[str, Any] | None = None) -> dict[str, Any]:
    """Write one timestamped v34 export and enforce the retention window."""
    cfg = sanitize_config(config or load_auto_backup_config(con))
    target_dir = _backup_dir(cfg["dirName"])
    document = export_backup(con, DATA_DIR)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    name = f"deskcubby-backup-v{document['version']}-{stamp}.json"
    payload = json.dumps(document, ensure_ascii=False, indent=2)
    path = target_dir / name
    seq = 1
    while path.exists():
        path = target_dir / f"deskcubby-backup-v{document['version']}-{stamp}-{seq}.json"
        seq += 1
        if seq > 10_000:  # pragma: no cover - pathological clock collisions
            raise ApiError(500, "backup_write_failed", "无法创建不重名的备份文件 / Too many same-named files")
    safe_write_text(path, payload)

    kept, removed = _enforce_retention(target_dir, cfg["keepCount"], keep=name)
    return {
        "file": f"{cfg['dirName']}/{path.name}",
        "writtenAt": int(time.time() * 1000),
        "kept": kept,
        "removed": removed,
    }


def _enforce_retention(target_dir, keep_count: int, *, keep: str | None = None) -> tuple[int, int]:
    files = sorted(
        (p for p in target_dir.glob("deskcubby-backup-*.json") if p.is_file()),
        key=lambda p: p.name,
        reverse=True,
    )
    removed = 0
    for old in files[keep_count:]:
        if keep is not None and old.name == keep:
            continue
        try:
            old.unlink()
            removed += 1
        except OSError:  # pragma: no cover - best effort pruning
            continue
    return len(files[:keep_count]), removed


def run_auto_backup_if_enabled(app) -> dict[str, Any] | None:
    """Background-hook entry point; silently skips when disabled."""
    con = getattr(app.state, "db", None)
    if con is None:
        return None
    config = load_auto_backup_config(con)
    if not config["enabled"]:
        return None
    try:
        return run_auto_backup(con, config)
    except Exception:  # noqa: BLE001 - background task must never crash the app
        return None
