"""Usage API — Android usage/v1 + backup v20–v28 `usageDevices` import.

- POST /api/usage/import: multipart `file` (JSON) accepting either a single
  usage/v1 device object (`{deviceId, deviceName, history:{days}}`, flattened
  `{deviceId, deviceName, days}` also accepted) or a v20–v28 projection
  (`{"usageDevices":[...]}`). Upserts into usage_devices + usage_events_daily.
- GET  /api/usage/devices: [{deviceId, deviceName}].
- GET  /api/usage/overview?days=7&deviceId=: per-day totals + top apps.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query, UploadFile

from ..core.db import get_db
from ..core.errors import ApiError
from ..services import usage_service

router = APIRouter(prefix="/api/usage", tags=["usage"])


@router.post("/import")
async def import_usage(file: UploadFile, con=Depends(get_db)):
    raw = await file.read()
    if not raw.strip():
        raise ApiError(400, "invalid_json", "使用时间 JSON 为空")
    records = usage_service.parse_usage_document(raw)
    result = usage_service.import_usage(con, records)
    return {"ok": True, **result}


@router.get("/devices")
def list_devices(con=Depends(get_db)):
    return usage_service.list_devices(con)


@router.get("/overview")
def overview(
    days: int = Query(default=usage_service.DEFAULT_OVERVIEW_DAYS),
    deviceId: str | None = Query(default=None),
    con=Depends(get_db),
):
    return usage_service.overview(con, days=days, device_id=deviceId)
