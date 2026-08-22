"""Diary API: documents over workspace/diary Markdown + rebuildable diary_index."""
from __future__ import annotations

from datetime import date
from typing import Any

from fastapi import APIRouter, Depends, Query, Response
from fastapi.responses import JSONResponse

from ..core.db import get_db
from ..core.errors import ApiError
from ..services import diary_files, media_meta
from ..services.meal_export import ExportTooLargeError, render_meal_calendar_png
from ..services.settings_store import load_settings

router = APIRouter(prefix="/api/diary", tags=["diary"])


def _conflict_response(disk_document: dict[str, Any]) -> JSONResponse:
    return JSONResponse(
        status_code=409,
        content={
            "error": {
                "code": "external_file_conflict",
                "message": "日记已被其他应用修改，请选择重新加载、覆盖或另存副本",
            },
            "currentSha256": disk_document["sha256"],
            "content": disk_document["content"],
            "lastModified": disk_document["lastModified"],
            "document": diary_files._editor_view(disk_document),
        },
    )


@router.get("/documents")
def list_documents(
    query: str = "",
    month: str = "",
    con=Depends(get_db),
):
    return diary_files.query_documents(con, query or None, month or None)


@router.get("/document")
def get_document(name: str = Query(...)):
    doc = diary_files.load_document(name)
    return diary_files._editor_view(doc)


@router.post("/documents")
def create_document(body: dict[str, Any], con=Depends(get_db)):
    settings = load_settings(con)
    editor = diary_files.create_document(
        con,
        settings,
        name=body.get("name"),
        title=body.get("title"),
        date_iso=body.get("dateIso"),
        template=bool(body.get("template", True)),
    )
    return editor


@router.put("/document")
def put_document(body: dict[str, Any], con=Depends(get_db)):
    name = body.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ApiError(400, "invalid_name", "name is required")
    content = body.get("content")
    if not isinstance(content, str):
        raise ApiError(400, "invalid_value", "content must be a string")
    previous = body.get("previousSha256")
    if previous is not None and not isinstance(previous, str):
        raise ApiError(400, "invalid_value", "previousSha256 must be a string")
    force = bool(body.get("force", False))
    settings = load_settings(con)
    try:
        return diary_files.save_document(con, settings, name, content, previous, force=force)
    except diary_files.ExternalFileConflict as conflict:
        return _conflict_response(conflict.document)


@router.post("/rename")
def rename_document(body: dict[str, Any], con=Depends(get_db)):
    name = body.get("name")
    new_name = body.get("newName")
    if not isinstance(name, str) or not name.strip():
        raise ApiError(400, "invalid_name", "name is required")
    if not isinstance(new_name, str) or not new_name.strip():
        raise ApiError(400, "invalid_name", "newName is required")
    return diary_files.rename_document(con, name.strip(), new_name)


@router.delete("/document")
def delete_document(name: str = Query(...), con=Depends(get_db)):
    diary_files.trash_delete(con, name)
    return {"ok": True}


@router.get("/trash")
def list_trash():
    return diary_files.trash_list()


@router.post("/trash/restore")
def restore_trash_item(body: dict[str, Any], con=Depends(get_db)):
    stored_name = body.get("name") if isinstance(body, dict) else None
    if not isinstance(stored_name, str) or not stored_name:
        raise ApiError(400, "invalid_name", "name is required")
    editor = diary_files.trash_restore(con, stored_name)
    return {"ok": True, "document": editor}


@router.delete("/trash/item")
def delete_trash_item(name: str = Query(...)):
    diary_files.trash_permanent_delete(name)
    return {"ok": True}


@router.get("/recent")
def recent(limit: int = 10, con=Depends(get_db)):
    return diary_files.recent_documents(con, limit)


@router.get("/random")
def random_doc(con=Depends(get_db)):
    document = diary_files.random_document(con)
    if document is None:
        raise ApiError(404, "not_found", "No diary documents")
    return document


@router.get("/stats")
def stats(con=Depends(get_db)):
    return diary_files.diary_stats(con)


def _parse_iso(value: str | None, field: str) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        raise ApiError(400, "invalid_date", f"Invalid {field}")


@router.get("/export/meal-calendar.png")
def export_meal_calendar_png(
    start: str = Query(...),
    end: str = Query(...),
    categories: str = "",
    con=Depends(get_db),
):
    start_date = _parse_iso(start, "start")
    end_date = _parse_iso(end, "end")
    if start_date is None or end_date is None:
        raise ApiError(400, "invalid_date", "start and end are required (YYYY-MM-DD)")
    if start_date > end_date:
        raise ApiError(400, "invalid_date", "开始日期不能晚于结束日期")
    by_key = {c["key"]: c for c in diary_files.MEAL_CATEGORIES}
    selected_keys = [k.strip() for k in categories.split(",") if k.strip()]
    unknown = [k for k in selected_keys if k not in by_key]
    if unknown:
        raise ApiError(400, "invalid_value", f"未知餐别：{unknown[0]}")
    selected_categories = [by_key[k] for k in selected_keys] if selected_keys else list(by_key.values())

    settings = load_settings(con)
    calendar = diary_files.scan_meal_calendar(settings, media_meta.get_decoded())
    all_categories = len(selected_categories) == len(by_key)
    days = []
    for day in calendar:
        day_date = _parse_iso(day["dateIso"], "dateIso")
        if day_date is None or day_date < start_date or day_date > end_date:
            continue
        photos = [p for p in day["photos"] if p["category"] in {c["key"] for c in selected_categories}]
        if not photos:
            continue
        days.append({
            "dateIso": day["dateIso"],
            "photos": photos,
            # A manual override is scoped to the complete date; a category-only export
            # keeps showing the selected-photo subtotal instead.
            "totalEnergyKj": day.get("totalEnergyKj") if all_categories else diary_files.calculated_energy(
                [p["energyKj"] for p in photos]
            ),
        })
    if not days:
        raise ApiError(400, "nothing_to_export", "所选日期和餐别下没有可导出的饮食照片")

    resolver_cache: dict[str, bytes] = {}

    def resolve_photo(photo: dict[str, Any], target_width: int, target_height: int):
        from PIL import Image
        import io as _io

        from .media import load_media_image_resized

        actual = photo.get("fileNameActual") or photo.get("fileName")
        key = f"{actual}|{target_width}x{target_height}"
        cached = resolver_cache.get(key)
        if cached is not None:
            return Image.open(_io.BytesIO(cached))
        try:
            data = load_media_image_resized(actual, target_width, target_height)
        except ApiError:
            return None
        if data is None:
            return None
        if len(resolver_cache) < 64:
            resolver_cache[key] = data
        return Image.open(_io.BytesIO(data))

    try:
        png = render_meal_calendar_png(days, settings, start_date, end_date, selected_categories, resolve_photo)
    except ExportTooLargeError:
        raise ApiError(413, "export_too_large", "所选范围生成的长图过高，请缩短日期范围")
    # Sanity: decode the produced PNG before committing it to the response.
    from PIL import Image
    import io as _io

    with Image.open(_io.BytesIO(png)) as check_img:
        if check_img.size[0] != 720 or check_img.format != "PNG":
            raise ApiError(500, "export_verify_failed", "生成的 PNG 校验失败")
    return Response(content=png, media_type="image/png")
