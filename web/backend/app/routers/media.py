"""Media API: meal image upload (pattern naming + Pillow compression), bounded file
serving, and small JPEG thumbnails cached under private/thumb-cache."""
from __future__ import annotations

import base64
import hashlib
import io
import mimetypes
import threading
from datetime import date
from typing import Any

from fastapi import APIRouter, Depends, Query, UploadFile
from fastapi.responses import FileResponse, Response
from PIL import Image, ImageOps

from ..core.config import MEDIA_DIR, PRIVATE_DIR, MAX_UPLOAD_BYTES
from ..core.db import get_db
from ..core.errors import ApiError
from ..core.fs import safe_write, sanitize_rel_path
from ..services import diary_files, media_meta
from ..services.settings_store import load_settings

router = APIRouter(prefix="/api/media", tags=["media"])

THUMB_CACHE_DIR = PRIVATE_DIR / "thumb-cache"
THUMB_EDGE_PX = 240
MAX_IMAGE_BYTES = 50 * 1024 * 1024

_COMPRESSIBLE_MIMES = {
    "image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "image/webp", "image/avif",
}
_COMPRESSIBLE_EXTENSIONS = {"jpg", "jpeg", "png", "heic", "heif", "webp", "avif"}

upload_mutex = threading.RLock()


def media_file_path(rel: str) -> Path:
    target = sanitize_rel_path(rel, MEDIA_DIR)
    if not target.is_file():
        raise ApiError(404, "not_found", "Media file not found")
    return target


def _infer_extension(upload_name: str, mime: str) -> str:
    by_mime = mimetypes.guess_extension(mime or "", strict=False)
    if by_mime:
        candidate = by_mime.lstrip(".").lower()
        if candidate:
            return candidate
    extension = upload_name.rsplit(".", 1)[-1].lower() if "." in upload_name else ""
    return extension or "jpg"


def _compressible(mime: str, extension: str) -> bool:
    return mime.lower() in _COMPRESSIBLE_MIMES or extension.lower() in _COMPRESSIBLE_EXTENSIONS


def compress_meal_image(data: bytes, max_width: int, max_height: int, quality: int) -> bytes | None:
    """Pillow compression mirroring compressMealImageToCache; None keeps the original."""
    try:
        with Image.open(io.BytesIO(data)) as img:
            img.load()
            oriented = ImageOps.exif_transpose(img)
            scale = min(1.0, max_width / oriented.width, max_height / oriented.height)
            if scale < 1.0:
                oriented = oriented.resize(
                    (max(1, round(oriented.width * scale)), max(1, round(oriented.height * scale))),
                    Image.LANCZOS,
                )
            if oriented.mode in ("RGBA", "LA", "P"):
                flattened = Image.new("RGB", oriented.size, (255, 255, 255))
                rgba = oriented.convert("RGBA")
                flattened.paste(rgba, mask=rgba.split()[-1])
                oriented = flattened
            elif oriented.mode != "RGB":
                oriented = oriented.convert("RGB")
            buffer = io.BytesIO()
            oriented.save(buffer, format="JPEG", quality=max(30, min(int(quality), 95)))
    except Exception:
        return None
    compressed = buffer.getvalue()
    if not compressed or len(compressed) >= len(data):
        return None
    return compressed


def load_media_image_resized(file_name: str, target_width: int, target_height: int) -> bytes | None:
    """Center-crop resize used by the meal-calendar export renderer."""
    path = MEDIA_DIR / file_name
    if not path.is_file():
        return None
    try:
        with Image.open(path) as img:
            img = ImageOps.exif_transpose(img)
            img.load()
    except Exception:
        return None
    source_aspect = img.width / img.height
    dest_aspect = target_width / target_height
    if source_aspect > dest_aspect:
        crop_width = max(1, round(img.height * dest_aspect))
        left = max(0, (img.width - crop_width) // 2)
        box = (left, 0, min(left + crop_width, img.width), img.height)
    else:
        crop_height = max(1, round(img.width / dest_aspect))
        top = max(0, (img.height - crop_height) // 2)
        box = (0, top, img.width, min(top + crop_height, img.height))
    cropped = img.crop(box).resize((max(1, target_width), max(1, target_height)), Image.LANCZOS)
    buffer = io.BytesIO()
    cropped.convert("RGB").save(buffer, format="JPEG", quality=88)
    return buffer.getvalue()


@router.post("/upload")
async def upload_media(
    request_category: str = Query(default="", alias="category"),
    file: UploadFile = ...,
    con=Depends(get_db),
):
    data = await file.read(MAX_UPLOAD_BYTES + 1)
    if not data:
        raise ApiError(400, "empty_file", "所选图片为空")
    if len(data) > min(MAX_UPLOAD_BYTES, MAX_IMAGE_BYTES):
        raise ApiError(413, "file_too_large", "Image too large")
    settings = load_settings(con)
    category_text = (request_category or "").strip() or "图片"
    mime = (file.content_type or "").split(";")[0].strip() or "image/jpeg"
    source_extension = _infer_extension(file.filename or "", mime)
    should_compress = bool(settings.get("mealImageCompressionEnabled")) and bool(request_category.strip())
    compressed = None
    if should_compress and _compressible(mime, source_extension):
        max_w = _bounded_dim(settings.get("imageMaxWidthDp"), 720)
        max_h = _bounded_dim(settings.get("imageMaxHeightDp"), 640)
        quality = settings.get("mealImageCompressionQuality")
        quality = quality if isinstance(quality, int) and 30 <= quality <= 95 else 80
        compressed = compress_meal_image(data, max_w, max_h, quality)

    final_data = compressed if compressed is not None else data
    final_mime = "image/jpeg" if compressed is not None else mime
    final_extension = "jpg" if compressed is not None else source_extension.lower()

    with upload_mutex:
        existing = diary_files.media_files_by_lower_name()
        today_text = date.today().isoformat()
        sequence = 1
        while True:
            base = (
                (settings.get("imageNamePattern") or "{date}_{category}_{seq}")
                .replace("{date}", today_text)
                .replace("{category}", category_text)
                .replace("{seq}", f"{sequence:02d}")
            )
            candidate = diary_files.sanitize_file_name(base) + "." + final_extension
            if candidate.lower() not in existing:
                break
            sequence += 1
        safe_write(MEDIA_DIR / candidate, final_data)

    markdown = f"![{category_text}](<{candidate.replace('>', '%3E')}>)"
    return {
        "documentUri": f"workspace://media/{candidate}",
        "fileName": candidate,
        "markdown": markdown,
        "compressed": compressed is not None,
    }


def _bounded_dim(value: Any, default: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(16, min(number, 4096))


@router.get("/file")
def get_media_file(path: str = Query(...), size: str = "full"):
    target = media_file_path(path)
    if size == "thumb":
        data = _thumbnail_bytes(target)
        return Response(content=data, media_type="image/jpeg")
    mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
    return FileResponse(target, media_type=mime)


def _thumbnail_bytes(source: Path) -> bytes:
    THUMB_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    st = source.stat()
    key = hashlib.sha256(
        f"{source.name}|{st.st_mtime_ns}|{st.st_size}|{THUMB_EDGE_PX}".encode()
    ).hexdigest()[:32]
    cache_path = THUMB_CACHE_DIR / f"{key}.jpg"
    if cache_path.is_file():
        return cache_path.read_bytes()
    try:
        with Image.open(source) as img:
            oriented = ImageOps.exif_transpose(img)
            oriented.thumbnail((THUMB_EDGE_PX, THUMB_EDGE_PX), Image.LANCZOS)
            if oriented.mode not in ("RGB", "L"):
                oriented = oriented.convert("RGB")
            buffer = io.BytesIO()
            oriented.save(buffer, format="JPEG", quality=75)
    except Exception:
        raise ApiError(500, "thumbnail_failed", "无法生成缩略图")
    data = buffer.getvalue()
    try:
        safe_write(cache_path, data)
    except ApiError:
        pass
    return data


@router.get("/thumbs")
def get_thumbs(paths: str = Query(...)):
    requested = [p.strip() for p in paths.split(",") if p.strip()][:100]
    thumbs: dict[str, str | None] = {}
    for rel in requested:
        try:
            target = media_file_path(rel)
            data = _thumbnail_bytes(target)
            thumbs[rel] = "data:image/jpeg;base64," + base64.b64encode(data).decode("ascii")
        except ApiError:
            thumbs[rel] = None
    return {"thumbs": thumbs}
