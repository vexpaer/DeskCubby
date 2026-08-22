"""DeskCubby Web — FastAPI application.

Routers are discovered from app/routers/*.py automatically so feature modules can
land independently; each module defines `router = APIRouter(...)`.
"""
from __future__ import annotations

import importlib
import json
import logging
import pkgutil
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .core import security
from .core.config import APP_VERSION, DATA_DIR, FRONTEND_DIST, ensure_dirs
from .core.db import connect, init_db
from .core.errors import ApiError

log = logging.getLogger("deskcubby.web")


@asynccontextmanager
async def lifespan(app: FastAPI):
    ensure_dirs()
    init_db()
    con = connect()
    app.state.db = con
    app.state.background_tasks = []
    try:
        from .services.background import start_background_tasks

        app.state.background_tasks = start_background_tasks(app)
    except ImportError:
        pass
    yield
    for task in getattr(app.state, "background_tasks", []):
        task.cancel()
    con.close()


app = FastAPI(title="DeskCubby Web", version=APP_VERSION, lifespan=lifespan)


@app.middleware("http")
async def auth_middleware(request: Request, call_next):
    path = request.url.path
    if not security.is_public_path(path) and not security.is_authenticated(request):
        return JSONResponse(
            status_code=401,
            content={"error": {"code": "unauthorized", "message": "Authentication required"}},
        )
    return await call_next(request)


@app.exception_handler(ApiError)
async def api_error_handler(_request: Request, exc: ApiError):
    return JSONResponse(status_code=exc.status, content={"error": {"code": exc.code, "message": exc.message}})


def _include_discovered_routers() -> None:
    import app.routers as routers_pkg

    for mod_info in sorted(pkgutil.iter_modules(routers_pkg.__path__), key=lambda m: m.name):
        if mod_info.name.startswith("_"):
            continue
        try:
            module = importlib.import_module(f"app.routers.{mod_info.name}")
            router = getattr(module, "router", None)
            if router is not None:
                app.include_router(router)
                log.info("Included router module %s", mod_info.name)
        except Exception:  # noqa: BLE001 - a broken optional module must not kill startup
            log.exception("Failed to load router module %s", mod_info.name)


_include_discovered_routers()


# ---------------------------------------------------------------------------
# System endpoints (always present)
# ---------------------------------------------------------------------------

def _detect_deployment(request: Request) -> dict:
    fwd_proto = request.headers.get("x-forwarded-proto")
    scheme = fwd_proto or request.url.scheme
    forwarded_for = request.headers.get("x-forwarded-for") or request.headers.get("x-real-ip")
    remote = request.client.host if request.client else ""
    private_prefixes = ("127.", "10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.",
                        "172.2", "172.30.", "172.31.", "::1", "localhost")
    is_remote_public = bool(forwarded_for) and not str(forwarded_for).split(",")[0].strip().startswith(private_prefixes)
    return {
        "scheme": scheme,
        "behindProxy": bool(fwd_proto or forwarded_for),
        "publicDeployment": (scheme == "https" and is_remote_public) or (scheme != "https" and is_remote_public),
        "suggestPassword": True,
        "suggestHttps": scheme != "https",
    }


@app.get("/api/system/info", tags=["system"])
def system_info(request: Request):
    from .services.data_usage import data_usage_summary

    con = request.app.state.db
    try:
        usage = data_usage_summary()
    except Exception:
        usage = {}
    return {
        "version": APP_VERSION,
        "platform": "web",
        "deployment": _detect_deployment(request),
        "dataUsage": usage,
    }


@app.get("/api/healthz", tags=["system"], include_in_schema=False)
def healthz():
    return {"ok": True}


# ---------------------------------------------------------------------------
# Optional password auth
# ---------------------------------------------------------------------------

from fastapi import Depends  # noqa: E402
from pydantic import BaseModel  # noqa: E402


class PasswordBody(BaseModel):
    password: str
    newPassword: str | None = None


@app.get("/api/auth/status", tags=["auth"])
def auth_status(request: Request):
    con = request.app.state.db
    enabled = security.password_enabled(con)
    return {
        "enabled": enabled,
        "authenticated": (not enabled) or security.session_valid(con, security.token_from_request(request)),
        "deployment": _detect_deployment(request),
    }


@app.post("/api/auth/set-password", tags=["auth"])
def auth_set_password(request: Request, body: PasswordBody):
    con = request.app.state.db
    if security.password_enabled(con):
        raise ApiError(400, "already_enabled", "Password already set")
    if not body.password or len(body.password) < 4:
        raise ApiError(400, "invalid_password", "Password too short")
    security.set_password(con, body.password)
    token = security.create_session(con)
    resp = JSONResponse({"ok": True})
    security.apply_session_cookie(resp, token, secure=security.effective_request_secure(request))
    return resp


@app.post("/api/auth/login", tags=["auth"])
def auth_login(request: Request, body: PasswordBody):
    con = request.app.state.db
    host = security._host_key(request)
    remaining = security.login_locked_remaining_seconds(host)
    if remaining > 0:
        raise ApiError(429, "rate_limited", f"Too many failed logins; retry in {remaining} seconds")
    if not security.verify_password(con, body.password):
        locked_now = security.register_login_failure(host)
        if locked_now:
            raise ApiError(429, "rate_limited", "Too many failed logins; host locked for 15 minutes")
        raise ApiError(401, "wrong_password", "Wrong password")
    security.clear_login_failures(host)
    token = security.create_session(con)
    resp = JSONResponse({"ok": True})
    security.apply_session_cookie(resp, token, secure=security.effective_request_secure(request))
    return resp


@app.post("/api/auth/logout", tags=["auth"])
def auth_logout(request: Request):
    con = request.app.state.db
    token = security.token_from_request(request)
    if token:
        security.destroy_session(con, token)
    resp = JSONResponse({"ok": True})
    security.clear_session_cookie(resp)
    return resp


@app.post("/api/auth/change-password", tags=["auth"])
def auth_change_password(request: Request, body: PasswordBody):
    con = request.app.state.db
    if not security.password_enabled(con):
        raise ApiError(400, "not_enabled", "Password not enabled")
    if not security.verify_password(con, body.password):
        raise ApiError(401, "wrong_password", "Wrong password")
    if not body.newPassword or len(body.newPassword) < 4:
        raise ApiError(400, "invalid_password", "Password too short")
    security.set_password(con, body.newPassword)
    token = security.create_session(con)
    resp = JSONResponse({"ok": True})
    security.apply_session_cookie(resp, token, secure=security.effective_request_secure(request))
    return resp


@app.post("/api/auth/disable", tags=["auth"])
def auth_disable(request: Request, body: PasswordBody):
    con = request.app.state.db
    if security.password_enabled(con) and not security.verify_password(con, body.password):
        raise ApiError(401, "wrong_password", "Wrong password")
    security.disable_password(con)
    resp = JSONResponse({"ok": True})
    security.clear_session_cookie(resp)
    return resp


# ---------------------------------------------------------------------------
# Frontend static hosting (production build) + PWA assets
# ---------------------------------------------------------------------------

if FRONTEND_DIST.exists():
    app.mount("/assets", StaticFiles(directory=FRONTEND_DIST / "assets"), name="assets")

    @app.get("/{full_path:path}", include_in_schema=False)
    def spa_fallback(full_path: str):
        candidate = (FRONTEND_DIST / full_path).resolve()
        try:
            candidate.relative_to(FRONTEND_DIST.resolve())
        except ValueError:
            raise ApiError(404, "not_found", "Not found")
        if full_path and candidate.is_file():
            return FileResponse(candidate)
        return FileResponse(FRONTEND_DIST / "index.html")
