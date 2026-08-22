"""Notes API: Obsidian-compatible vault over workspace/notes."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query, UploadFile
from fastapi.responses import JSONResponse

from ..core.db import get_db
from ..core.errors import ApiError
from ..services import notes_repo

router = APIRouter(prefix="/api/notes", tags=["notes"])


def _conflict_response(disk_document: dict[str, Any]) -> JSONResponse:
    return JSONResponse(
        status_code=409,
        content={
            "error": {
                "code": "external_file_conflict",
                "message": "笔记已被其他应用修改，请选择重新加载、覆盖或另存副本",
            },
            "currentSha256": disk_document["version"]["sha256"],
            "content": disk_document["content"],
            "lastModified": disk_document["version"]["lastModified"],
            "document": disk_document,
        },
    )


@router.get("/tree")
def notes_tree():
    return notes_repo.scan_tree()


@router.get("/file")
def get_note_file(path: str = Query(...)):
    return notes_repo.load_note(path)


@router.put("/file")
def put_note_file(body: dict[str, Any]):
    path = body.get("path")
    if not isinstance(path, str) or not path.strip():
        raise ApiError(400, "invalid_path", "path is required")
    content = body.get("content")
    if not isinstance(content, str):
        raise ApiError(400, "invalid_value", "content must be a string")
    previous = body.get("previousSha256")
    if previous is not None and not isinstance(previous, str):
        raise ApiError(400, "invalid_value", "previousSha256 must be a string")
    force = bool(body.get("force", False))
    try:
        return notes_repo.save_note(path, content, previous, force=force)
    except notes_repo.NoteExternalConflict as conflict:
        return _conflict_response(conflict.document)


@router.post("/folder")
def create_folder(body: dict[str, Any]):
    parent = body.get("parent") or ""
    name = body.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ApiError(400, "invalid_name", "name is required")
    return notes_repo.create_folder(str(parent), name)


@router.post("/file-create")
def create_note_file(body: dict[str, Any], con=Depends(get_db)):
    _ = con  # symmetric with other routers; notes live purely on the FS
    parent = body.get("parent") or ""
    name = body.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ApiError(400, "invalid_name", "name is required")
    return notes_repo.create_note(None, str(parent), name)


@router.post("/rename")
def rename_node(body: dict[str, Any]):
    path = body.get("path")
    new_name = body.get("newName")
    if not isinstance(path, str) or not path.strip():
        raise ApiError(400, "invalid_path", "path is required")
    if not isinstance(new_name, str) or not new_name.strip():
        raise ApiError(400, "invalid_name", "newName is required")
    entry = notes_repo.rename_node(path, new_name)
    return {"ok": True, "entry": entry}


@router.delete("/node")
def delete_node(path: str = Query(...)):
    notes_repo.delete_node(path)
    return {"ok": True}


@router.get("/search")
def search_notes(q: str = Query(default="")):
    return notes_repo.search_notes(q)


@router.get("/resolve")
def resolve_wiki(name: str = Query(...)):
    """`![[name]]` resolution helper: bounded BFS for a matching Markdown file."""
    result = notes_repo.resolve_wiki_link(name)
    if result is None:
        raise ApiError(404, "not_found", "未找到同名笔记")
    return result


@router.post("/media-upload")
async def media_upload(file: UploadFile, targetFolder: str = Query(default="")):
    data = await file.read(notes_repo.MAX_NOTE_MEDIA_BYTES + 1)
    if len(data) > notes_repo.MAX_NOTE_MEDIA_BYTES:
        raise ApiError(413, "file_too_large", "媒体超过 64 MiB 上限")
    mime = (file.content_type or "").split(";")[0].strip()
    source_name = file.filename or "image"
    return notes_repo.import_media(data, source_name, mime, targetFolder or "")
