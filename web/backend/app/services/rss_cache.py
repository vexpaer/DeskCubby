"""Async RSS cache facade used by services/background.py's refresh loop.

The heavy lifting lives in rss_service.refresh_all_sync; this wrapper keeps the
event loop responsive by running it in a worker thread.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from ..core.db import connect

log = logging.getLogger("deskcubby.web.rss")


async def refresh_all_feeds(app) -> dict[str, Any]:
    """Refresh every enabled feed from settings.rssSubscriptions off-thread."""
    from . import rss_service

    def _run() -> dict[str, Any]:
        con = getattr(app.state, "db", None)
        owned = False
        if con is None:  # pragma: no cover - startup always installs it
            con = connect()
            owned = True
        try:
            return rss_service.refresh_all_sync(con)
        finally:
            if owned:
                con.close()

    try:
        return await asyncio.to_thread(_run)
    except Exception:  # noqa: BLE001 - background loop must never crash the app
        log.exception("rss refresh failed")
        raise


async def cached_payload(feed_id: str | None = None) -> dict[str, Any]:
    from . import rss_service

    return await asyncio.to_thread(rss_service.cached_items, feed_id)
