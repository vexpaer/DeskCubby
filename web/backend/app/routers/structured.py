"""Structured records API: `.deskcubby` workspace config, records, statistics, reindex."""
from __future__ import annotations

from datetime import date, timedelta
from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel

from ..core.db import get_db
from ..core.errors import ApiError
from ..services import structured_records
from ..services.settings_store import load_settings

router = APIRouter(prefix="/api/structured", tags=["structured"])


@router.get("/config")
def get_config():
    return structured_records.get_config()


class DayBoundaryBody(BaseModel):
    hours: int | float | str


@router.put("/day-boundary")
def put_day_boundary(body: DayBoundaryBody):
    return structured_records.set_day_boundary(body.hours)


class FieldsBody(BaseModel):
    fields: list[dict[str, Any]]


@router.put("/fields")
def put_fields(body: FieldsBody):
    return {"fields": structured_records.put_fields(body.fields)}


@router.get("/records")
def get_records(
    fromDay: str | None = Query(default=None),
    toDay: str | None = Query(default=None),
    con=Depends(get_db),
):
    settings = load_settings(con)
    structured_records.refresh_incremental(con, settings)
    occurrences = structured_records.list_occurrences(con, fromDay, toDay)
    fields_by_id = {f["id"]: f for f in structured_records.load_fields()}
    for occurrence in occurrences:
        field = fields_by_id.get(occurrence["fieldId"])
        if field:
            occurrence["fieldName"] = field["name"]
            occurrence["fieldType"] = field["type"]
            occurrence["unit"] = field.get("unit")
        else:
            occurrence["fieldName"] = None
            occurrence["fieldType"] = None
            occurrence["unit"] = None
    return occurrences


class RecordBody(BaseModel):
    journalDay: str | None = None
    fieldId: str
    rawValue: str


@router.post("/records")
def post_record(body: RecordBody, con=Depends(get_db)):
    settings = load_settings(con)
    return structured_records.upsert_record(
        con, settings, body.fieldId, body.rawValue, body.journalDay
    )


def _parse_day(value: str | None, fallback: date) -> date:
    if not value:
        return fallback
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        raise ApiError(400, "invalid_date", "Invalid day (YYYY-MM-DD)")


@router.get("/statistics")
def get_statistics(
    fromDay: str | None = Query(default=None),
    toDay: str | None = Query(default=None),
    fieldId: str | None = Query(default=None),
    selector: str = Query(default="last"),
    con=Depends(get_db),
):
    settings = load_settings(con)
    structured_records.refresh_incremental(con, settings)
    end = _parse_day(toDay, date.today())
    start = _parse_day(fromDay, end - timedelta(days=29))
    if start > end:
        raise ApiError(400, "invalid_date", "fromDay 不能晚于 toDay")
    return structured_records.statistics(con, start.isoformat(), end.isoformat(), fieldId, selector)


@router.post("/reindex")
def reindex(con=Depends(get_db)):
    settings = load_settings(con)
    return structured_records.reindex(con, settings)
