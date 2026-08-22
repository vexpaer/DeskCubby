"""Meals API: photo wall grouped by journal/calendar date + dc-media.json photo meta."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query

from ..core.db import get_db
from ..core.errors import ApiError
from ..services import diary_files, media_meta
from ..services.settings_store import load_settings

router = APIRouter(prefix="/api/meals", tags=["meals"])


@router.get("/calendar")
def meal_calendar(
    From: str | None = Query(default=None, alias="from"),
    to: str | None = Query(default=None),
    categories: str = "",
    con=Depends(get_db),
):
    settings = load_settings(con)
    days = diary_files.scan_meal_calendar(settings, media_meta.get_decoded())
    from_day = From
    to_day = to
    if from_day or to_day:
        days = [d for d in days if (not from_day or d["dateIso"] >= from_day)
                and (not to_day or d["dateIso"] <= to_day)]
    by_key = {c["key"]: c for c in diary_files.MEAL_CATEGORIES}
    selected = [k.strip() for k in categories.split(",") if k.strip()]
    if selected:
        unknown = [k for k in selected if k not in by_key]
        if unknown:
            raise ApiError(400, "invalid_value", f"未知餐别：{unknown[0]}")
        allowed = set(selected)
        days = [
            {
                **day,
                "photos": [p for p in day["photos"] if p["category"] in allowed],
                "totalEnergyKj": diary_files.calculated_energy(
                    [p["energyKj"] for p in day["photos"] if p["category"] in allowed]
                ),
            }
            for day in days
        ]
        days = [day for day in days if day["photos"]]
    return days


@router.get("/photo-meta")
def get_photo_meta(file: str = Query(...)):
    key = media_meta.normalize_media_key(file)
    doc = media_meta.get_decoded()
    return {"fileName": key, "entry": doc["entries"].get(key) or {}, "mealDays": doc["mealDays"]}


@router.put("/photo-meta")
def put_photo_meta(body: dict[str, Any]):
    if not isinstance(body, dict):
        raise ApiError(400, "invalid_value", "body must be a JSON object")
    return media_meta.put_photo_meta(body)
