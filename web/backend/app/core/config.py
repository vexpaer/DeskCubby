"""DeskCubby Web backend configuration.

The browser never receives arbitrary filesystem access.  A local desktop
installation may, however, let its loopback-only backend bind the diary and
media stores to folders explicitly selected by the user.  ``RuntimePath``
keeps the long-standing module constants usable while resolving their target
at operation time, so changing a root does not require restarting Uvicorn.
"""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path
from typing import Callable

APP_VERSION = "0.23.5"  # current Android parity target
BACKUP_FORMAT_VERSION = 34

_DEFAULT_DATA = Path(__file__).resolve().parents[2] / "data"
DATA_DIR = Path(os.environ.get("DESKCUBBY_DATA_DIR") or _DEFAULT_DATA).resolve()

DB_PATH = DATA_DIR / "deskcubby.db"
WORKSPACE_DIR = DATA_DIR / "workspace"
NOTES_DIR = WORKSPACE_DIR / "notes"
BOOKS_DIR = WORKSPACE_DIR / "books"
BACKUPS_DIR = DATA_DIR / "backups"
PRIVATE_DIR = DATA_DIR / "private"
UPLOADS_DIR = DATA_DIR / "uploads"
STORAGE_ROOTS_FILE = PRIVATE_DIR / "storage-roots.json"
FRONTEND_DIST = Path(os.environ.get("DESKCUBBY_FRONTEND_DIST") or Path(__file__).resolve().parents[3] / "frontend" / "dist")

LOCAL_DESKTOP_MODE = os.environ.get("DESKCUBBY_LOCAL_MODE", "").strip().lower() in {
    "1", "true", "yes", "on",
}

_ROOT_ENV = {
    "diary": "DESKCUBBY_DIARY_DIR",
    "media": "DESKCUBBY_MEDIA_DIR",
}
_DEFAULT_ROOTS = {
    "diary": WORKSPACE_DIR / "diary",
    "media": WORKSPACE_DIR / "media",
}
_root_lock = threading.RLock()


def _load_storage_roots() -> dict[str, Path]:
    try:
        if not STORAGE_ROOTS_FILE.is_file() or STORAGE_ROOTS_FILE.stat().st_size > 32 * 1024:
            return {}
        raw = json.loads(STORAGE_ROOTS_FILE.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}
    roots = raw.get("roots") if isinstance(raw, dict) else None
    if not isinstance(roots, dict):
        return {}
    loaded: dict[str, Path] = {}
    for kind in _DEFAULT_ROOTS:
        value = roots.get(kind)
        if isinstance(value, str) and value and "\x00" not in value and len(value) <= 4096:
            try:
                candidate = Path(value).expanduser()
                if candidate.is_absolute():
                    loaded[kind] = candidate.resolve()
            except (OSError, RuntimeError):
                continue
    return loaded


_stored_roots = _load_storage_roots()


def default_storage_root(kind: str) -> Path:
    if kind not in _DEFAULT_ROOTS:
        raise ValueError("unknown storage root")
    return _DEFAULT_ROOTS[kind]


def storage_root_locked(kind: str) -> bool:
    env_name = _ROOT_ENV.get(kind)
    return bool(env_name and os.environ.get(env_name, "").strip())


def storage_root_override(kind: str) -> Path | None:
    if kind not in _DEFAULT_ROOTS:
        raise ValueError("unknown storage root")
    env_name = _ROOT_ENV[kind]
    env_value = os.environ.get(env_name, "").strip()
    if env_value:
        return Path(env_value).expanduser().resolve()
    with _root_lock:
        return _stored_roots.get(kind)


def storage_root(kind: str) -> Path:
    return storage_root_override(kind) or default_storage_root(kind)


def apply_storage_root_override(kind: str, path: Path | None) -> None:
    """Update the in-process root after its durable file has been committed."""
    if kind not in _DEFAULT_ROOTS:
        raise ValueError("unknown storage root")
    with _root_lock:
        if path is None:
            _stored_roots.pop(kind, None)
        else:
            _stored_roots[kind] = path.resolve()


class RuntimePath(os.PathLike[str]):
    """A small pathlib-compatible facade whose target may change at runtime."""

    def __init__(self, resolver: Callable[[], Path]):
        self._resolver = resolver

    def current(self) -> Path:
        return self._resolver()

    def __fspath__(self) -> str:
        return os.fspath(self.current())

    def __str__(self) -> str:
        return str(self.current())

    def __repr__(self) -> str:
        return f"RuntimePath({self.current()!r})"

    def __truediv__(self, child: object) -> Path:
        return self.current() / child  # type: ignore[arg-type]

    def __getattr__(self, name: str):
        return getattr(self.current(), name)

    def __eq__(self, other: object) -> bool:
        try:
            return self.current() == Path(other)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            return False

    def __hash__(self) -> int:
        return hash(self.current())


DIARY_DIR = RuntimePath(lambda: storage_root("diary"))
DIARY_TRASH_DIR = RuntimePath(lambda: storage_root("diary") / ".trash")
MEDIA_DIR = RuntimePath(lambda: storage_root("media"))
MEDIA_TRASH_DIR = RuntimePath(lambda: storage_root("media") / ".trash")


def _structured_root() -> Path:
    # Preserve the existing internal layout for current Web users.  An
    # explicitly selected diary folder follows Android and owns .deskcubby.
    override = storage_root_override("diary")
    return (override / ".deskcubby") if override is not None else (WORKSPACE_DIR / ".deskcubby")


STRUCTURED_DIR = RuntimePath(_structured_root)

MAX_JSON_INPUT_BYTES = 64 * 1024 * 1024  # matches Android import limit
MAX_UPLOAD_BYTES = 200 * 1024 * 1024
SESSION_COOKIE = "dc_web_session"
SESSION_TTL_SECONDS = 30 * 24 * 3600


def ensure_dirs() -> None:
    for d in (
        DATA_DIR,
        WORKSPACE_DIR,
        NOTES_DIR,
        BOOKS_DIR,
        BACKUPS_DIR,
        PRIVATE_DIR,
        UPLOADS_DIR,
        PRIVATE_DIR / "reading",
        PRIVATE_DIR / "rss-cache",
    ):
        d.mkdir(parents=True, exist_ok=True)

    for kind in ("diary", "media"):
        root = storage_root(kind)
        # A folder selected through the UI existed and was write-tested at
        # selection time.  If a removable mount later disappears, fail closed
        # instead of silently recreating its mount point on another disk.
        if storage_root_override(kind) is not None and not storage_root_locked(kind) and not root.is_dir():
            raise RuntimeError(f"Configured {kind} storage folder is unavailable")
        root.mkdir(parents=True, exist_ok=True)
        trash = root / ".trash"
        if trash.is_symlink():
            raise RuntimeError(f"Configured {kind} trash folder is an unsafe symbolic link")
        trash.mkdir(parents=True, exist_ok=True)
        if trash.resolve().parent != root.resolve():
            raise RuntimeError(f"Configured {kind} trash folder escapes its root")
    structured = STRUCTURED_DIR.current()
    if structured.is_symlink():
        raise RuntimeError("Configured structured-data folder is an unsafe symbolic link")
    structured.mkdir(parents=True, exist_ok=True)
    if structured.resolve().parent != storage_root("diary").resolve() and storage_root_override("diary") is not None:
        raise RuntimeError("Configured structured-data folder escapes the diary root")
