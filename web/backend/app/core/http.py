"""Bounded outbound HTTP client (RSS / AI / poetry / WebDAV).

Defaults to HTTPS-only, bounded redirects, timeouts and response size caps.
Mirrors Android network rules: no cross-host redirects, no HTTPS downgrade,
cancel/timeout propagation, response bodies capped.
"""
from __future__ import annotations

from typing import Any

import httpx

from .errors import ApiError

DEFAULT_TIMEOUT = httpx.Timeout(15.0, connect=10.0)
MAX_RESPONSE_BYTES = 20 * 1024 * 1024


class BoundedHttpClient:
    def __init__(
        self,
        *,
        allow_http: bool = False,
        max_bytes: int = MAX_RESPONSE_BYTES,
        timeout: httpx.Timeout = DEFAULT_TIMEOUT,
        follow_redirects: bool = True,
        max_redirects: int = 3,
    ):
        self.allow_http = allow_http
        self.max_bytes = max_bytes
        self.timeout = timeout
        self.follow_redirects = follow_redirects
        self.max_redirects = max_redirects

    def _validate_url(self, url: str) -> None:
        if not (url.startswith("https://") or (self.allow_http and url.startswith("http://"))):
            raise ApiError(400, "insecure_url", "Only HTTPS URLs are allowed")

    def get(self, url: str, *, headers: dict[str, str] | None = None) -> httpx.Response:
        return self._send("GET", url, headers=headers)

    def post_json(self, url: str, payload: Any, *, headers: dict[str, str] | None = None) -> httpx.Response:
        return self._send(
            "POST",
            url,
            headers={"Content-Type": "application/json", **(headers or {})},
            content=_json_bytes(payload),
        )

    def request(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        return self._send(method, url, **kwargs)

    def _send(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        current = url
        for _ in range(self.max_redirects + 1):
            self._validate_url(current)
            try:
                with httpx.Client(timeout=self.timeout, follow_redirects=False) as client:
                    resp = client.request(method, current, **kwargs)
            except httpx.TimeoutException:
                raise ApiError(504, "network_timeout", "Network request timed out")
            except httpx.HTTPError:
                raise ApiError(502, "network_error", "Network request failed")
            if resp.is_redirect:
                loc = resp.headers.get("location", "")
                if not loc:
                    break
                nxt = str(httpx.URL(current).join(loc))
                if httpx.URL(nxt).host != httpx.URL(current).host:
                    raise ApiError(400, "redirect_cross_host", "Cross-host redirect blocked")
                if current.startswith("https://") and nxt.startswith("http://"):
                    raise ApiError(400, "redirect_downgrade", "HTTPS downgrade blocked")
                current = nxt
                continue
            return resp
        raise ApiError(400, "too_many_redirects", "Too many redirects")


def _json_bytes(payload: Any) -> bytes:
    import json

    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def read_capped(response: httpx.Response, max_bytes: int | None = None) -> bytes:
    cap = max_bytes or MAX_RESPONSE_BYTES
    data = b""
    for chunk in response.iter_bytes():
        data += chunk
        if len(data) > cap:
            raise ApiError(413, "response_too_large", "Response too large")
    return data
