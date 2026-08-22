"""Background asyncio tasks: auto-backup scheduler, RSS refresh.

Tasks are started by main.lifespan and cancelled on shutdown.
"""
from __future__ import annotations

import asyncio
import logging

log = logging.getLogger("deskcubby.web.background")

AUTO_BACKUP_INTERVAL_S = 12 * 3600
RSS_REFRESH_INTERVAL_S = 6 * 3600


async def _auto_backup_loop(app):
    while True:
        try:
            await asyncio.sleep(AUTO_BACKUP_INTERVAL_S)
            from .backup_service import run_auto_backup_if_enabled

            run_auto_backup_if_enabled(app)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("auto backup failed")


async def _rss_refresh_loop(app):
    while True:
        try:
            await asyncio.sleep(RSS_REFRESH_INTERVAL_S)
            from .rss_cache import refresh_all_feeds

            await refresh_all_feeds(app)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("rss refresh failed")


def start_background_tasks(app) -> list:
    return [
        asyncio.create_task(_auto_backup_loop(app)),
        asyncio.create_task(_rss_refresh_loop(app)),
    ]
