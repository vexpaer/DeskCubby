"""Loopback-only local folder selection; server deployments stay sandboxed."""
from __future__ import annotations

import ipaddress

from fastapi import APIRouter, Depends, Request

from ..core import config
from ..core.db import get_db
from ..core.errors import ApiError
from ..services import diary_files, media_meta, storage_roots, structured_records

router = APIRouter(prefix="/api/storage", tags=["storage"])


def _direct_loopback(request: Request) -> bool:
    if any(request.headers.get(name) for name in (
        "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip",
    )):
        return False
    host = request.client.host if request.client else ""
    try:
        client_loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        client_loopback = host == "localhost"
    request_host = request.url.hostname or ""
    try:
        header_loopback = ipaddress.ip_address(request_host).is_loopback
    except ValueError:
        header_loopback = request_host == "localhost"
    # Checking both the peer and Host closes the DNS-rebinding route to this
    # local-only filesystem capability.
    return client_loopback and header_loopback


def _can_configure(request: Request) -> bool:
    return config.LOCAL_DESKTOP_MODE and _direct_loopback(request)


def _require_local(request: Request) -> None:
    if not _can_configure(request):
        raise ApiError(403, "local_storage_only", "Folder selection is available only in the local desktop installation")


@router.get("/roots")
def get_roots(request: Request):
    can_configure = _can_configure(request)
    return {
        "localDesktopMode": config.LOCAL_DESKTOP_MODE,
        "canConfigure": can_configure,
        "pickerAvailable": can_configure and storage_roots.picker_available(),
        "roots": {
            kind: storage_roots.root_info(kind, reveal_path=can_configure)
            for kind in ("diary", "media")
        },
    }


@router.post("/pick")
def pick_root(request: Request, body: dict):
    _require_local(request)
    kind = str(body.get("kind") or "")
    return storage_roots.pick_directory(kind)


@router.put("/root")
def put_root(request: Request, body: dict, con=Depends(get_db)):
    _require_local(request)
    kind = str(body.get("kind") or "")
    raw_path = body.get("path")
    if raw_path is not None and not isinstance(raw_path, str):
        raise ApiError(400, "invalid_storage_path", "Folder path must be a string or null")
    if kind == "diary":
        # Keep the runtime root stable across both ordinary Markdown writes and
        # .deskcubby field/index writes. Otherwise a concurrent structured-record
        # save could evaluate the dynamic root once before and once after the
        # switch and split one operation across two folders.
        with storage_roots.root_io_lock, structured_records.structured_mutex, diary_files.write_mutex:
            result = storage_roots.set_storage_root(kind, raw_path)
            diary_files.scan_documents(con)
            return result
    if kind == "media":
        from .media import upload_mutex

        with storage_roots.root_io_lock, upload_mutex, media_meta.media_mutex:
            return storage_roots.set_storage_root(kind, raw_path)
    raise ApiError(400, "invalid_storage_kind", "Unknown storage folder kind")
