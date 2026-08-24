"""User-selected diary/media roots for loopback desktop installations.

Paths are device-local state: they are deliberately outside AppSettings and
Android backup JSON.  Selection never migrates or deletes files from the old
root.  Environment-provided roots remain administrator-owned and read-only.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Any

from ..core import config
from ..core.errors import ApiError
from ..core.fs import safe_write_text

root_io_lock = threading.RLock()
_KINDS = {"diary", "media"}


def _kind(kind: str) -> str:
    if kind not in _KINDS:
        raise ApiError(400, "invalid_storage_kind", "Unknown storage folder kind")
    return kind


def picker_available() -> bool:
    if not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        return False
    if shutil.which("zenity") or shutil.which("kdialog"):
        return True
    try:
        import tkinter  # noqa: F401

        return True
    except ImportError:
        return False


def validate_directory(raw_path: str) -> Path:
    if not isinstance(raw_path, str) or not raw_path.strip() or "\x00" in raw_path:
        raise ApiError(400, "invalid_storage_path", "Choose an absolute folder path")
    if len(raw_path) > 4096:
        raise ApiError(400, "invalid_storage_path", "Folder path is too long")
    candidate = Path(raw_path.strip()).expanduser()
    if not candidate.is_absolute():
        raise ApiError(400, "invalid_storage_path", "Choose an absolute folder path")
    try:
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError):
        raise ApiError(400, "storage_folder_missing", "The selected folder does not exist")
    if not resolved.is_dir():
        raise ApiError(400, "storage_not_folder", "The selected path is not a folder")
    if resolved == Path(resolved.anchor):
        raise ApiError(400, "storage_root_too_broad", "The filesystem root cannot be used as an app folder")

    probe: Path | None = None
    try:
        fd, probe_name = tempfile.mkstemp(prefix=".deskcubby-write-test-", dir=resolved)
        probe = Path(probe_name)
        with os.fdopen(fd, "wb") as handle:
            handle.write(b"DeskCubby")
            handle.flush()
            os.fsync(handle.fileno())
    except OSError:
        raise ApiError(400, "storage_not_writable", "The selected folder is not writable")
    finally:
        if probe is not None:
            try:
                probe.unlink(missing_ok=True)
            except OSError:
                pass
    return resolved


def _config_document() -> dict[str, Any]:
    try:
        if not config.STORAGE_ROOTS_FILE.is_file() or config.STORAGE_ROOTS_FILE.stat().st_size > 32 * 1024:
            return {"version": 1, "roots": {}}
        parsed = json.loads(config.STORAGE_ROOTS_FILE.read_text(encoding="utf-8"))
        if not isinstance(parsed, dict):
            return {"version": 1, "roots": {}}
        roots = parsed.get("roots")
        parsed["roots"] = roots if isinstance(roots, dict) else {}
        parsed["version"] = 1
        return parsed
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {"version": 1, "roots": {}}


def _persist(kind: str, path: Path | None) -> None:
    doc = _config_document()
    roots = dict(doc["roots"])
    if path is None:
        roots.pop(kind, None)
    else:
        roots[kind] = str(path)
    doc["roots"] = roots
    encoded = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
    config.STORAGE_ROOTS_FILE.parent.mkdir(parents=True, exist_ok=True)
    safe_write_text(config.STORAGE_ROOTS_FILE, encoded)


def _ensure_managed_child(root: Path, name: str) -> None:
    child = root / name
    if child.is_symlink():
        raise ApiError(400, "storage_unsafe_symlink", "A DeskCubby-managed subfolder cannot be a symbolic link")
    try:
        child.mkdir(parents=True, exist_ok=True)
        resolved = child.resolve(strict=True)
    except (OSError, RuntimeError):
        raise ApiError(400, "storage_not_writable", "The selected folder cannot be prepared")
    if resolved.parent != root.resolve():
        raise ApiError(400, "storage_unsafe_symlink", "A DeskCubby-managed subfolder escapes the selected folder")


def set_storage_root(kind: str, raw_path: str | None) -> dict[str, Any]:
    kind = _kind(kind)
    if config.storage_root_locked(kind):
        raise ApiError(409, "storage_root_locked", "This folder is managed by a server environment variable")

    selected = None if raw_path is None else validate_directory(raw_path)
    target = selected or config.default_storage_root(kind)
    with root_io_lock:
        try:
            target.mkdir(parents=True, exist_ok=True)
        except OSError:
            raise ApiError(400, "storage_not_writable", "The selected folder cannot be prepared")
        _ensure_managed_child(target, ".trash")
        if kind == "diary" and selected is not None:
            _ensure_managed_child(target, ".deskcubby")
        _persist(kind, selected)
        config.apply_storage_root_override(kind, selected)
    return root_info(kind, reveal_path=True)


def root_info(kind: str, *, reveal_path: bool) -> dict[str, Any]:
    kind = _kind(kind)
    path = config.storage_root(kind)
    override = config.storage_root_override(kind)
    return {
        "kind": kind,
        "configured": override is not None,
        "isDefault": override is None,
        "locked": config.storage_root_locked(kind),
        "path": str(path) if reveal_path else "",
        "displayName": path.name or ("Diary" if kind == "diary" else "Media"),
    }


def pick_directory(kind: str) -> dict[str, Any]:
    kind = _kind(kind)
    if not picker_available():
        raise ApiError(501, "folder_picker_unavailable", "No graphical folder picker is available; enter the absolute path instead")
    title = "选择日记文件夹" if kind == "diary" else "选择媒体文件夹"
    initial = str(config.storage_root(kind))
    command: list[str]
    if shutil.which("zenity"):
        command = ["zenity", "--file-selection", "--directory", f"--title={title}", f"--filename={initial}/"]
    elif shutil.which("kdialog"):
        command = ["kdialog", "--getexistingdirectory", initial, "--title", title]
    else:
        script = (
            "import sys,tkinter as tk;from tkinter import filedialog;"
            "r=tk.Tk();r.withdraw();r.attributes('-topmost',True);"
            "print(filedialog.askdirectory(title=sys.argv[1],initialdir=sys.argv[2]));r.destroy()"
        )
        command = [sys.executable, "-c", script, title, initial]
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=300, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ApiError(500, "folder_picker_failed", "The system folder picker could not be opened")
    if completed.returncode != 0 or not completed.stdout.strip():
        return {"cancelled": True}
    selected = validate_directory(completed.stdout.strip().splitlines()[-1])
    return {"cancelled": False, "path": str(selected), "displayName": selected.name}
