"""DeskCubby Web backend configuration."""
from __future__ import annotations

import os
from pathlib import Path

APP_VERSION = "0.20.1"  # mirrors Android version at replication time
BACKUP_FORMAT_VERSION = 34

_DEFAULT_DATA = Path(__file__).resolve().parents[2] / "data"
DATA_DIR = Path(os.environ.get("DESKCUBBY_DATA_DIR") or _DEFAULT_DATA).resolve()

DB_PATH = DATA_DIR / "deskcubby.db"
WORKSPACE_DIR = DATA_DIR / "workspace"
DIARY_DIR = WORKSPACE_DIR / "diary"
DIARY_TRASH_DIR = DIARY_DIR / ".trash"
MEDIA_DIR = WORKSPACE_DIR / "media"
MEDIA_TRASH_DIR = MEDIA_DIR / ".trash"
NOTES_DIR = WORKSPACE_DIR / "notes"
BOOKS_DIR = WORKSPACE_DIR / "books"
STRUCTURED_DIR = WORKSPACE_DIR / ".deskcubby"
BACKUPS_DIR = DATA_DIR / "backups"
PRIVATE_DIR = DATA_DIR / "private"
UPLOADS_DIR = DATA_DIR / "uploads"
FRONTEND_DIST = Path(os.environ.get("DESKCUBBY_FRONTEND_DIST") or Path(__file__).resolve().parents[3] / "frontend" / "dist")

MAX_JSON_INPUT_BYTES = 64 * 1024 * 1024  # matches Android import limit
MAX_UPLOAD_BYTES = 200 * 1024 * 1024
SESSION_COOKIE = "dc_web_session"
SESSION_TTL_SECONDS = 30 * 24 * 3600


def ensure_dirs() -> None:
    for d in (
        DATA_DIR,
        WORKSPACE_DIR,
        DIARY_DIR,
        DIARY_TRASH_DIR,
        MEDIA_DIR,
        MEDIA_TRASH_DIR,
        NOTES_DIR,
        BOOKS_DIR,
        STRUCTURED_DIR,
        BACKUPS_DIR,
        PRIVATE_DIR,
        UPLOADS_DIR,
        PRIVATE_DIR / "reading",
        PRIVATE_DIR / "rss-cache",
    ):
        d.mkdir(parents=True, exist_ok=True)
