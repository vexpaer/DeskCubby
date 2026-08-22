"""Calorie API — day-scoped background estimation + status ledger.

- POST /api/calorie/estimate {dateIso}: starts an asyncio.Task stored in
  `app.state.calorie_tasks` keyed by dateIso; double runs are refused (409).
- GET  /api/calorie/status?dateIso=: reads data/private/calorie-status.json
  ({dateIso: {running, progress, error?, updatedAt}}).
"""
from __future__ import annotations

from datetime import date as date_type
from typing import Any

from fastapi import APIRouter, Query, Request
from pydantic import BaseModel

from ..core.errors import ApiError
from ..services import calorie_service

router = APIRouter(prefix="/api/calorie", tags=["calorie"])


class EstimateBody(BaseModel):
    dateIso: str


def _require_date_iso(value: str) -> str:
    text = (value or "").strip()
    try:
        parsed = date_type.fromisoformat(text[:10])
    except ValueError:
        raise ApiError(400, "invalid_date", "Invalid dateIso")
    if parsed.isoformat() != text:
        raise ApiError(400, "invalid_date", "Invalid dateIso")
    return text


@router.post("/estimate")
async def start_estimate(body: EstimateBody, request: Request):
    date_iso = _require_date_iso(body.dateIso)
    return calorie_service.start_estimate(request.app, date_iso)


@router.get("/status")
def estimate_status(dateIso: str | None = Query(default=None)):
    status: dict[str, Any] = calorie_service.read_status(dateIso)
    if dateIso is None:
        return {"statuses": status}
    return {"dateIso": dateIso, **status}
