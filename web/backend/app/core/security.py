"""Optional access-password auth: PBKDF2-HMAC-SHA256 hash server-side,
opaque session tokens stored hashed, HttpOnly Secure-capable cookie.
When disabled, localhost/LAN access is direct. Sensitive server config is never
returned to the browser regardless of auth state.
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import threading
import time

from fastapi import Request, Response
from fastapi.responses import JSONResponse

from .config import SESSION_COOKIE, SESSION_TTL_SECONDS
from .db import connect

PBKDF2_ITERATIONS = 240_000

# Failed-login throttling: after FAILED_LOGIN_LIMIT failures a remote host is locked
# out for LOGIN_LOCKOUT_SECONDS. In-memory only ({host: (count, lockout_until)});
# counters reset on a successful login or process restart.
FAILED_LOGIN_LIMIT = 8
LOGIN_LOCKOUT_SECONDS = 15 * 60
_failed_logins: dict[str, tuple[int, float]] = {}
_failed_login_lock = threading.Lock()


def _now_ms() -> int:
    return int(time.time() * 1000)


def hash_password(password: str, salt: bytes, iterations: int = PBKDF2_ITERATIONS) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)


def get_password_row(con):
    return con.execute("SELECT * FROM auth_password WHERE id = 1").fetchone()


def password_enabled(con) -> bool:
    return get_password_row(con) is not None


def set_password(con, password: str) -> None:
    if not password or len(password.encode("utf-8")) > 1024:
        from .errors import ApiError

        raise ApiError(400, "invalid_password", "Invalid password")
    salt = secrets.token_bytes(16)
    digest = hash_password(password, salt)
    con.execute(
        "INSERT INTO auth_password(id, salt_hex, hash_hex, iterations, createdAt) VALUES(1,?,?,?,?) "
        "ON CONFLICT(id) DO UPDATE SET salt_hex=excluded.salt_hex, hash_hex=excluded.hash_hex,"
        " iterations=excluded.iterations, createdAt=excluded.createdAt",
        (salt.hex(), digest.hex(), PBKDF2_ITERATIONS, _now_ms()),
    )
    con.commit()


def verify_password(con, password: str) -> bool:
    row = get_password_row(con)
    if row is None:
        return True
    digest = hash_password(password, bytes.fromhex(row["salt_hex"]), row["iterations"])
    return hmac.compare_digest(digest.hex(), row["hash_hex"])


def disable_password(con) -> None:
    con.execute("DELETE FROM auth_password WHERE id = 1")
    con.execute("DELETE FROM auth_sessions")
    con.commit()


def create_session(con) -> str:
    token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(token.encode()).hexdigest()
    now = _now_ms()
    con.execute(
        "INSERT INTO auth_sessions(tokenHash, createdAt, expiresAt) VALUES(?,?,?)",
        (token_hash, now, now + SESSION_TTL_SECONDS * 1000),
    )
    con.execute("DELETE FROM auth_sessions WHERE expiresAt < ?", (now,))
    con.commit()
    return token


def destroy_session(con, token: str) -> None:
    con.execute("DELETE FROM auth_sessions WHERE tokenHash = ?", (hashlib.sha256(token.encode()).hexdigest(),))
    con.commit()


def session_valid(con, token: str | None) -> bool:
    if not token:
        return False
    row = con.execute(
        "SELECT expiresAt FROM auth_sessions WHERE tokenHash = ?",
        (hashlib.sha256(token.encode()).hexdigest(),),
    ).fetchone()
    return row is not None and row["expiresAt"] > _now_ms()


def apply_session_cookie(response: Response, token: str, secure: bool) -> None:
    response.set_cookie(
        SESSION_COOKIE,
        token,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        samesite="lax",
        secure=secure,
        path="/",
    )


def effective_request_secure(request: Request) -> bool:
    """True when the request reached the browser over https, honoring a reverse
    proxy's X-Forwarded-Proto so the session cookie Secure flag is set correctly
    behind TLS termination."""
    forwarded = request.headers.get("x-forwarded-proto")
    if forwarded:
        return forwarded.split(",")[0].strip().lower() == "https"
    return request.url.scheme == "https"


def _host_key(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def login_locked_remaining_seconds(host: str) -> int:
    """Seconds left in the host's lockout; 0 when logins are allowed."""
    with _failed_login_lock:
        entry = _failed_logins.get(host)
        if not entry:
            return 0
        _count, lockout_until = entry
        remaining = lockout_until - time.time()
        return max(1, int(remaining)) if remaining > 0 else 0


def register_login_failure(host: str) -> bool:
    """Record one failed login. Returns True when this failure locked the host."""
    with _failed_login_lock:
        count, lockout_until = _failed_logins.get(host, (0, 0.0))
        now = time.time()
        if lockout_until > now:
            # Already locked; extend nothing, just report the active lockout.
            return True
        count += 1
        if count >= FAILED_LOGIN_LIMIT:
            _failed_logins[host] = (FAILED_LOGIN_LIMIT, now + LOGIN_LOCKOUT_SECONDS)
            return True
        _failed_logins[host] = (count, 0.0)
        return False


def clear_login_failures(host: str) -> None:
    """Successful login: forget the host's failure counter and any expired state."""
    with _failed_login_lock:
        _failed_logins.pop(host, None)


def clear_session_cookie(response: Response) -> None:
    response.delete_cookie(SESSION_COOKIE, path="/")


def token_from_request(request: Request) -> str | None:
    return request.cookies.get(SESSION_COOKIE)


def is_authenticated(request: Request) -> bool:
    con = getattr(request.app.state, "db", None)
    if con is None:
        return True
    if not password_enabled(con):
        return True
    return session_valid(con, token_from_request(request))


PUBLIC_PATH_PREFIXES = ("/api/auth", "/api/healthz", "/dc-web-sw.js", "/manifest.webmanifest", "/icons/")


def is_public_path(path: str) -> bool:
    if path.startswith(PUBLIC_PATH_PREFIXES):
        return True
    # SPA shell must load so the login page can render
    return path in ("/", "/index.html") or not path.startswith("/api")
