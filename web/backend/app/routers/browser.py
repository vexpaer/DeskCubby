"""内置浏览器记录 Browser records API — mirrors BrowserRepository + BrowserRecordDao.

- history   : ORDER BY lastVisitedAt DESC LIMIT 200
- favorites : favorite = 1 ORDER BY title COLLATE NOCASE
- recordVisit: upsert with visitCount+1 and lastVisitedAt=now; blank URLs and
  "about:blank" are ignored; blank title falls back to the URL
- setFavorite: upsert preserving lastVisitedAt/visitCount, creating on demand
"""
from __future__ import annotations

import time
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..core.db import get_db, write_lock
from ..core.errors import ApiError

router = APIRouter(prefix="/api/browser", tags=["browser"])

HISTORY_LIMIT = 200


def now_ms() -> int:
    return int(time.time() * 1000)


def row_to_record(row) -> dict[str, Any]:
    return {
        "url": row["url"],
        "title": row["title"],
        "lastVisitedAt": row["lastVisitedAt"],
        "visitCount": row["visitCount"],
        "favorite": bool(row["favorite"]),
    }


def _get(con, url: str):
    return con.execute("SELECT * FROM browser_records WHERE url = ? LIMIT 1", (url,)).fetchone()


class VisitBody(BaseModel):
    url: str
    title: str = ""


class FavoriteBody(BaseModel):
    url: str
    title: str = ""
    favorite: bool


@router.get("/records")
def list_records(favorites: int = 0, limit: int = HISTORY_LIMIT, con=Depends(get_db)):
    if favorites:
        rows = con.execute(
            "SELECT * FROM browser_records WHERE favorite = 1 ORDER BY title COLLATE NOCASE"
        ).fetchall()
    else:
        bounded_limit = max(1, min(int(limit), 500))
        rows = con.execute(
            "SELECT * FROM browser_records ORDER BY lastVisitedAt DESC LIMIT ?", (bounded_limit,)
        ).fetchall()
    return [row_to_record(r) for r in rows]


@router.post("/records")
def record_visit(body: VisitBody, con=Depends(get_db)):
    """BrowserRepository.recordVisit: no-op for blank urls / about:blank."""
    url = body.url.strip()
    if not url or url == "about:blank":
        return {"ok": True, "recorded": False}
    if len(url) > 8192 or any(ord(ch) < 32 for ch in url):
        raise ApiError(400, "invalid_url", "URL is too long or contains invalid characters")
    title = body.title.strip() or url
    now = now_ms()
    with write_lock(), con:
        existing = _get(con, url)
        con.execute(
            "INSERT INTO browser_records(url, title, lastVisitedAt, visitCount, favorite)"
            " VALUES(?,?,?,?,0)"
            " ON CONFLICT(url) DO UPDATE SET title = excluded.title,"
            " lastVisitedAt = excluded.lastVisitedAt, visitCount = excluded.visitCount",
            (
                url,
                title,
                now,
                (int(existing["visitCount"]) + 1) if existing else 1,
            ),
        )
    row = _get(con, url)
    assert row is not None
    return {"ok": True, "recorded": True, "record": row_to_record(row)}


@router.post("/records/favorite")
def set_favorite(body: FavoriteBody, con=Depends(get_db)):
    url = body.url.strip()
    if not url:
        raise ApiError(400, "invalid_url", "URL must not be blank")
    with write_lock(), con:
        existing = _get(con, url)
        title = body.title.strip() or (existing["title"] if existing else "") or url
        last_visited = int(existing["lastVisitedAt"]) if existing else now_ms()
        visit_count = int(existing["visitCount"]) if existing else 1
        con.execute(
            "INSERT INTO browser_records(url, title, lastVisitedAt, visitCount, favorite)"
            " VALUES(?,?,?,?,?)"
            " ON CONFLICT(url) DO UPDATE SET title = excluded.title, favorite = excluded.favorite",
            (url, title, last_visited, visit_count, 1 if body.favorite else 0),
        )
    row = _get(con, url)
    assert row is not None
    return row_to_record(row)


@router.delete("/records")
def delete_record(url: str | None = None, con=Depends(get_db)):
    """DELETE ?url=<encoded> removes one entry. Without a url the call clears the
    history while keeping favorites (BrowserRepository.clearHistory)."""
    if url:
        with write_lock(), con:
            cur = con.execute("DELETE FROM browser_records WHERE url = ?", (url,))
            if cur.rowcount == 0:
                raise ApiError(404, "record_not_found", "Record not found")
        return {"ok": True, "cleared": False}
    with write_lock(), con:
        cur = con.execute("DELETE FROM browser_records WHERE favorite = 0")
    return {"ok": True, "cleared": True, "removed": cur.rowcount}
