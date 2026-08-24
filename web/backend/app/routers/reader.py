"""阅读 Reader API — books upload/rename/delete, ranged content streaming,
reading/v1/progress.json ledger and engagement aggregation.

Mirrors the Android 阅读页面 contract (README_for_ai.md「阅读页面（Android）」):
TXT ≤ 32 MiB, PDF/TXT only, title derived from file name, URI-free fingerprint
progress records, per-day engagement seconds.
"""
from __future__ import annotations

from fastapi import APIRouter, Body, Depends, Query, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from ..core.config import MAX_UPLOAD_BYTES
from ..core.db import get_db
from ..core.errors import ApiError
from ..services import reader_service

router = APIRouter(prefix="/api/reader", tags=["reader"])

CHUNK_BYTES = 1 << 20


class RenameBody(BaseModel):
    title: str


class EngagementBody(BaseModel):
    bookId: str
    seconds: float


@router.get("/books")
def list_books(con=Depends(get_db)):
    return reader_service.list_books(con)


@router.post("/books", status_code=201)
async def upload_book(file: UploadFile = ..., con=Depends(get_db)):
    upload_name = (file.filename or "").strip() or "Untitled"
    book_type = reader_service.detect_book_type(upload_name, file.content_type or "")
    if book_type is None:
        raise ApiError(400, "unsupported_type", "请选择 TXT 或 PDF 文件")
    cap = reader_service.MAX_TEXT_BYTES if book_type == "TXT" else min(MAX_UPLOAD_BYTES, 512 * 1024 * 1024)
    data = await file.read(cap + 1)
    if not data:
        raise ApiError(400, "empty_file", "所选文件为空")
    if len(data) > cap:
        raise ApiError(413, "file_too_large", "TXT 文件超过 32 MiB 上限" if book_type == "TXT" else "书籍文件过大")
    count = con.execute("SELECT COUNT(*) FROM reader_books").fetchone()[0]
    if int(count) >= reader_service.MAX_BOOKS:
        raise ApiError(413, "too_many_books", "书架数量已达上限")
    return reader_service.create_book(con, file_name=upload_name[:255], book_type=book_type, data=data)


@router.put("/books/{book_id}")
def rename_book(book_id: str, body: RenameBody, con=Depends(get_db)):
    return reader_service.rename_book(con, book_id, body.title)


@router.delete("/books/{book_id}")
def delete_book(book_id: str, con=Depends(get_db)):
    reader_service.delete_book(con, book_id)
    return {"ok": True}


@router.get("/books/{book_id}/content")
def get_content(book_id: str, request: Request, con=Depends(get_db)):
    """Raw bytes with single-range support (206 Partial Content)."""
    row = reader_service.get_book_row(con, book_id)
    path = reader_service.book_file_path(row)
    total = path.stat().st_size
    mime = "application/pdf" if row["bookType"] == "PDF" else "text/plain; charset=utf-8"
    base_headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": mime,
    }
    parsed = reader_service.parse_range_header(request.headers.get("range"), total)
    if parsed == "invalid":
        return JSONResponse(
            status_code=416,
            headers={"Content-Range": f"bytes */{total}"},
            content={"error": {"code": "invalid_range", "message": "Requested range not satisfiable"}},
        )
    if parsed is None:
        handle = path.open("rb")
        return _stream(
            reader_service.iter_file_range(handle, 0, total - 1),
            mime,
            {**base_headers, "Content-Length": str(total)},
        )
    start, end = parsed
    handle = path.open("rb")
    return _stream(
        reader_service.iter_file_range(handle, start, end),
        mime,
        {
            **base_headers,
            "Content-Range": f"bytes {start}-{end}/{total}",
            "Content-Length": str(end - start + 1),
        },
        status_code=206,
    )


def _stream(iterator, mime: str, headers: dict, *, status_code: int = 200):
    from fastapi.responses import StreamingResponse

    return StreamingResponse(iterator, status_code=status_code, media_type=mime, headers=headers)


@router.get("/books/{book_id}/text")
def get_decoded_text(book_id: str, con=Depends(get_db)):
    """Decoded TXT content (UTF-8/UTF-16 BOM detection + GB18030 fallback)."""
    row = reader_service.get_book_row(con, book_id)
    if row["bookType"] != "TXT":
        raise ApiError(400, "not_text", "仅 TXT 支持文本解码读取")
    path = reader_service.book_file_path(row)
    if path.stat().st_size > reader_service.MAX_TEXT_BYTES:
        raise ApiError(413, "file_too_large", "TXT 文件超过 32 MiB 上限")
    text = reader_service.normalize_reader_text_line_breaks(
        reader_service.decode_reader_text(path.read_bytes())
    )
    return {"id": row["id"], "title": row["title"], "text": text}


# ---------------------------------------------------------------------------
# Progress ledger (reading/v1/progress.json)
# ---------------------------------------------------------------------------

@router.get("/progress")
def get_progress():
    records = reader_service.read_ledger_records()
    canonical = sorted(records, key=lambda r: (r["fingerprint"], r["type"]))
    return {"version": reader_service.PROGRESS_FORMAT_VERSION, "records": canonical}


@router.put("/progress")
def put_progress(payload: dict | list = Body(...)):
    return reader_service.upsert_progress(payload)


# ---------------------------------------------------------------------------
# Reader preferences (record-syncable; device-only reader fields stay local)
# ---------------------------------------------------------------------------

@router.get("/preferences")
def get_preferences():
    return {
        **reader_service.read_reader_preferences(),
        "stored": reader_service.PREFERENCES_PATH.is_file(),
    }


@router.put("/preferences")
def put_preferences(payload: dict = Body(...)):
    return {**reader_service.write_reader_preferences(payload), "stored": True}


# ---------------------------------------------------------------------------
# Engagement
# ---------------------------------------------------------------------------

@router.post("/engagement")
def post_engagement(body: EngagementBody, con=Depends(get_db)):
    seconds = float(body.seconds)
    if seconds != seconds or seconds <= 0:  # NaN or non-positive
        raise ApiError(400, "invalid_seconds", "阅读时长无效")
    if seconds > reader_service.MAX_ENGAGEMENT_SECONDS_PER_POST:
        raise ApiError(413, "seconds_too_large", "单次上报时长超出限制")
    return reader_service.append_engagement(con, body.bookId, seconds)


@router.get("/engagement")
def get_engagement(days: int = Query(default=30, ge=1, le=36_600)):
    return reader_service.engagement_summary(days=days)
