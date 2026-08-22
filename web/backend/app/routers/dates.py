"""日期记录 Date records API — mirrors DateRecordRepository + DateRecordDao.

- list: ORDER BY dateIso ASC, createdAt ASC, id ASC
- name: non-blank, <= 256 chars; icon: non-blank emoji, <= 64 chars
- dateIso: exactly yyyy-MM-dd and a valid calendar date
- timestamps are epoch millis
"""
from __future__ import annotations

import time
from datetime import date
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..core.db import get_db, write_lock
from ..core.errors import ApiError

router = APIRouter(prefix="/api/date-records", tags=["dates"])

MAX_NAME_CHARS = 256
MAX_ICON_CHARS = 64
ISO_DATE_LENGTH = 10

ORDER = "dateIso ASC, createdAt ASC, id ASC"


def now_ms() -> int:
    return int(time.time() * 1000)


def _validate_name(value: str) -> str:
    trimmed = value.strip()
    if not trimmed:
        raise ApiError(400, "invalid_name", "Date record name must not be blank")
    if len(trimmed) > MAX_NAME_CHARS:
        raise ApiError(400, "name_too_long", "Date record name is too long")
    return trimmed


def _validate_icon(value: str) -> str:
    trimmed = value.strip()
    if not trimmed:
        raise ApiError(400, "invalid_icon", "Date record icon must not be blank")
    if len(trimmed) > MAX_ICON_CHARS:
        raise ApiError(400, "icon_too_long", "Date record icon is too long")
    return trimmed


def _validate_date_iso(value: str) -> str:
    trimmed = value.strip()
    if len(trimmed) != ISO_DATE_LENGTH:
        raise ApiError(400, "invalid_date", "Date record date must use yyyy-MM-dd")
    try:
        date.fromisoformat(trimmed)
    except ValueError:
        raise ApiError(400, "invalid_date", "Date record date must be a valid yyyy-MM-dd date")
    return trimmed


def row_to_record(row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "name": row["name"],
        "icon": row["icon"],
        "dateIso": row["dateIso"],
        "createdAt": row["createdAt"],
        "updatedAt": row["updatedAt"],
    }


class DateRecordBody(BaseModel):
    name: str
    icon: str
    dateIso: str


def _checked(body: DateRecordBody) -> tuple[str, str, str]:
    return _validate_name(body.name), _validate_icon(body.icon), _validate_date_iso(body.dateIso)


@router.get("")
def list_records(con=Depends(get_db)):
    rows = con.execute(f"SELECT * FROM date_records ORDER BY {ORDER}").fetchall()
    return [row_to_record(r) for r in rows]


@router.post("", status_code=201)
def create_record(body: DateRecordBody, con=Depends(get_db)):
    name, icon, date_iso = _checked(body)
    now = now_ms()
    with write_lock(), con:
        cur = con.execute(
            "INSERT INTO date_records(name, icon, dateIso, createdAt, updatedAt) VALUES(?,?,?,?,?)",
            (name, icon, date_iso, now, now),
        )
        record_id = int(cur.lastrowid)
    row = con.execute("SELECT * FROM date_records WHERE id = ?", (record_id,)).fetchone()
    assert row is not None
    return row_to_record(row)


@router.put("/{record_id}")
def update_record(record_id: int, body: DateRecordBody, con=Depends(get_db)):
    _require(con, record_id)
    name, icon, date_iso = _checked(body)
    with write_lock(), con:
        cur = con.execute(
            "UPDATE date_records SET name = ?, icon = ?, dateIso = ?, updatedAt = ? WHERE id = ?",
            (name, icon, date_iso, now_ms(), record_id),
        )
        if cur.rowcount == 0:
            raise ApiError(404, "date_record_not_found", "Date record not found")
    row = con.execute("SELECT * FROM date_records WHERE id = ?", (record_id,)).fetchone()
    assert row is not None
    return row_to_record(row)


@router.delete("/{record_id}")
def delete_record(record_id: int, con=Depends(get_db)):
    _require(con, record_id)
    with write_lock(), con:
        cur = con.execute("DELETE FROM date_records WHERE id = ?", (record_id,))
        if cur.rowcount == 0:
            raise ApiError(404, "date_record_not_found", "Date record not found")
    return {"ok": True}


def _require(con, record_id: int) -> None:
    row = con.execute("SELECT id FROM date_records WHERE id = ? LIMIT 1", (record_id,)).fetchone()
    if row is None:
        raise ApiError(404, "date_record_not_found", "Date record not found")
