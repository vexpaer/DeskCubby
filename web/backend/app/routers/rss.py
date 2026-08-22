"""RSS API. settings.rssSubscriptions is the source of truth for feeds."""
from __future__ import annotations

from fastapi import APIRouter, Depends

from ..core.db import get_db
from ..services import rss_service
from ..services.settings_store import load_settings

router = APIRouter(prefix="/api/rss", tags=["rss"])


@router.get("/feeds")
def list_feeds(con=Depends(get_db)):
    """The configured subscriptions, exactly as stored in AppSettings."""
    settings = load_settings(con)
    return rss_service.subscriptions_from_settings(settings)


@router.post("/refresh")
def refresh(con=Depends(get_db)):
    """Server-side fetch of every enabled feed; results grouped by feed and cached."""
    settings = load_settings(con)
    return rss_service.refresh_all_sync(con, settings)


@router.get("/items")
def items(feedId: str | None = None):
    return rss_service.cached_items(feedId)
