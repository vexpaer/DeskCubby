"""Settings API: full AppSettings read/update; secrets never leave the server."""
from __future__ import annotations

import html
from typing import Any

from fastapi import APIRouter, Depends, Request, UploadFile
from fastapi.responses import FileResponse

from ..core.config import DATA_DIR, PRIVATE_DIR
from ..core.db import get_db
from ..core.errors import ApiError
from ..core.fs import safe_write
from ..services.data_usage import data_usage_summary
from ..services.settings_store import load_settings, public_settings, update_settings

router = APIRouter(prefix="/api/settings", tags=["settings"])


@router.get("")
def get_settings(con=Depends(get_db)):
    return public_settings(con)


@router.put("")
def put_settings(request: Request, body: dict[str, Any], con=Depends(get_db)):
    _ = request
    update_settings(con, body)
    # Re-read through the redacting projection so stored secrets (aiConfigs
    # apiKey, cloud credentials) are never echoed back, even to the writer.
    return public_settings(con)


@router.post("/background-image")
async def upload_background_image(file: UploadFile, con=Depends(get_db)):
    data = await file.read()
    if len(data) > 30 * 1024 * 1024:
        raise ApiError(413, "file_too_large", "Image too large")
    suffix = ".png"
    if file.content_type == "image/jpeg":
        suffix = ".jpg"
    elif file.content_type == "image/webp":
        suffix = ".webp"
    name = f"background{suffix}"
    dest = PRIVATE_DIR / "background" / name
    safe_write(dest, data)
    s = load_settings(con)
    s["backgroundImageUri"] = f"private://background/{name}"
    update_settings(con, {"backgroundImageUri": s["backgroundImageUri"]})
    return {"uri": s["backgroundImageUri"]}


@router.delete("/background-image")
def delete_background_image(con=Depends(get_db)):
    update_settings(con, {"backgroundImageUri": None})
    return {"ok": True}


@router.get("/background-image")
def get_background_image():
    for candidate in ("background.png", "background.jpg", "background.webp"):
        p = PRIVATE_DIR / "background" / candidate
        if p.exists():
            return FileResponse(p)
    raise ApiError(404, "not_found", "No background image")


@router.get("/data-usage")
def data_usage():
    return data_usage_summary()


_ = DATA_DIR
_ = html
