"""App data usage summary (mirrors AppDataUsageRepository semantics loosely)."""
from __future__ import annotations

from ..core.config import BACKUPS_DIR, BOOKS_DIR, DIARY_DIR, MEDIA_DIR, NOTES_DIR, PRIVATE_DIR
from ..core.fs import dir_size


def data_usage_summary() -> dict:
    def mb(p) -> float:
        try:
            return round(dir_size(p) / (1024 * 1024), 2)
        except Exception:
            return 0.0

    return {
        "diaryMB": mb(DIARY_DIR),
        "mediaMB": mb(MEDIA_DIR),
        "notesMB": mb(NOTES_DIR),
        "booksMB": mb(BOOKS_DIR),
        "backupsMB": mb(BACKUPS_DIR),
        "privateMB": mb(PRIVATE_DIR),
    }
