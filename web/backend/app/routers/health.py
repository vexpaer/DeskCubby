"""Health API — Android step-statistics import + daily overview.

- POST /api/health/import: multipart `file` (JSON) accepting a full
  StepStatisticsJsonCodec document, a bare `{days:[...]}` object or a bare day
  array; tolerant to `dayIso`/`date` and `activeCaloriesKcal` spellings.
  Upserts into health_days.
- GET  /api/health/overview?days=30: per-day rows + window totals.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query, UploadFile

from ..core.db import get_db
from ..core.errors import ApiError
from ..services import health_service

router = APIRouter(prefix="/api/health", tags=["health"])


@router.post("/import")
async def import_health(file: UploadFile, con=Depends(get_db)):
    raw = await file.read()
    if not raw.strip():
        raise ApiError(400, "invalid_json", "健康统计 JSON 为空")
    days = health_service.parse_health_document(raw)
    result = health_service.import_health(con, days)
    return {"ok": True, **result}


@router.get("/overview")
def overview(
    days: int = Query(default=health_service.DEFAULT_OVERVIEW_DAYS),
    con=Depends(get_db),
):
    return health_service.overview(con, days=days)
