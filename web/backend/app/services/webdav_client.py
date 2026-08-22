"""Bounded WebDAV client (PROPFIND / GET / PUT / MKCOL).

Python counterpart of android `data/sync/BoundedHttpClient.kt` +
`BoundedPropFindHttpClient.kt` + the WebDAV half of `CloudBlobTransports.kt`:

- HTTPS by default; plain HTTP only when the config explicitly allows it
  (`allowInsecureHttp`), mirroring CloudSyncValidation.kt.
- Bounded connect/read timeouts, bounded redirect count with no cross-host or
  HTTPS-downgrade hops, bounded response bodies.
- All traffic goes through `core.http.BoundedHttpClient`; credentials are only
  ever placed in the Authorization header and never logged or returned.

Returns parsed href lists for PROPFIND so callers can enumerate collections.
"""
from __future__ import annotations

import base64
import re
import xml.etree.ElementTree as ET
from typing import Any
from urllib.parse import quote, urljoin, urlparse

from ..core.errors import ApiError
from ..core.http import BoundedHttpClient

DEFAULT_USER_AGENT = "DeskCubby-Sync/1"
MAX_RESPONSE_BYTES = 64 * 1024 * 1024          # mirrors CloudSyncLimits.maxObjectBytes
MAX_PROPFIND_BYTES = 4 * 1024 * 1024           # bounded directory listings
DAV_TIMEOUT = 30.0                             # readTimeoutMillis in CloudSyncLimits

_STATUS_OK = {200, 201, 204}
_PROPSTAT_OK = re.compile(r"(?:^|\s)200(?:\s|$)")


class WebDavClient:
    """Minimal class-style WebDAV client honouring `allowInsecureHttp`."""

    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.allow_http = bool(config.get("allowInsecureHttp"))
        self.base_url = self._require_base_url(str(config.get("endpointUrl") or ""))
        self.remote_path = "/".join(
            segment for segment in str(config.get("remotePath") or "DeskCubby").split("/")
            if segment
        )
        self.user_agent = str(config.get("userAgent") or DEFAULT_USER_AGENT)[:512]
        username = str(config.get("webDavUsername") or "")
        password = str(config.get("webDavPassword") or "")
        if username or password:
            raw = f"{username}:{password}".encode("utf-8")
            self._authorization = "Basic " + base64.b64encode(raw).decode("ascii")
        else:
            self._authorization = None
        self.http = BoundedHttpClient(
            allow_http=self.allow_http,
            max_bytes=MAX_RESPONSE_BYTES,
            timeout=DAV_TIMEOUT,
            max_redirects=3,
        )

    # -- URL helpers -------------------------------------------------------

    @staticmethod
    def _require_base_url(endpoint: str) -> str:
        parsed = urlparse(endpoint.strip())
        scheme = (parsed.scheme or "").lower()
        if scheme not in ("https", "http") or not parsed.hostname:
            raise ApiError(400, "invalid_endpoint", "WebDAV endpoint URL is invalid")
        return endpoint.strip().rstrip("/")

    def collection_url(self) -> str:
        if not self.remote_path:
            return self.base_url + "/"
        return self.base_url + "/" + "/".join(quote(segment) for segment in self.remote_path.split("/")) + "/"

    def object_url(self, name: str) -> str:
        if not re.fullmatch(r"[.A-Za-z0-9_-]{1,200}", name):
            raise ApiError(400, "invalid_name", "Remote object name is invalid")
        return self.collection_url() + name

    def _headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        headers = {"User-Agent": self.user_agent}
        if self._authorization:
            headers["Authorization"] = self._authorization
        if extra:
            headers.update(extra)
        return headers

    def _check_size(self, response: Any, cap: int) -> bytes:
        declared = response.headers.get("content-length")
        if declared and declared.isdigit() and int(declared) > cap:
            raise ApiError(413, "response_too_large", "WebDAV response exceeds the size limit")
        data = response.content
        if len(data) > cap:
            raise ApiError(413, "response_too_large", "WebDAV response exceeds the size limit")
        return data

    def _status_error(self, action: str, status: int) -> ApiError:
        detail = {
            401: "authentication failed", 403: "access denied",
            404: "remote collection missing; create the WebDAV folder first",
            409: "conflict with the remote state", 412: "condition failed",
        }.get(status)
        message = detail or f"WebDAV request failed (HTTP {status})"
        return ApiError(502, f"webdav_{status}", f"{action}: {message}")

    # -- Operations --------------------------------------------------------

    def mkcol(self, path_segments: list[str]) -> None:
        """Create each missing collection under the endpoint root (best effort)."""
        current = self.base_url
        for segment in path_segments:
            current = current.rstrip("/") + "/" + quote(segment)
            resp = self.http.request(
                "MKCOL", current + "/", headers=self._headers(),
            )
            if resp.status_code not in _STATUS_OK and resp.status_code != 405:
                raise self._status_error("WebDAV MKCOL", resp.status_code)

    def propfind(self, url: str | None = None, depth: str = "1") -> list[str]:
        """Return decoded href lists (one entry per <D:response> href)."""
        target = url or self.collection_url()
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/><D:getcontentlength/>'
            "<D:getlastmodified/></D:prop></D:propfind>"
        ).encode("utf-8")
        resp = self.http.request(
            "PROPFIND",
            target,
            headers=self._headers({
                "Depth": depth,
                "Content-Type": "application/xml; charset=utf-8",
            }),
            content=body,
        )
        if resp.status_code == 404:
            raise ApiError(404, "webdav_missing", "Remote collection does not exist")
        if resp.status_code not in (200, 207):
            raise self._status_error("WebDAV PROPFIND", resp.status_code)
        data = self._check_size(resp, MAX_PROPFIND_BYTES)
        return parse_propfind_hrefs(data)

    def get(self, name: str, max_bytes: int = MAX_RESPONSE_BYTES) -> bytes | None:
        """GET one object; returns None on 404."""
        resp = self.http.request("GET", self.object_url(name), headers=self._headers())
        if resp.status_code == 404:
            return None
        if resp.status_code != 200:
            raise self._status_error("WebDAV GET", resp.status_code)
        return self._check_size(resp, max_bytes)

    def put(self, name: str, data: bytes, *, content_type: str = "application/octet-stream") -> None:
        resp = self.http.request(
            "PUT",
            self.object_url(name),
            headers=self._headers({"Content-Type": content_type}),
            content=data,
        )
        if resp.status_code not in _STATUS_OK:
            raise self._status_error("WebDAV PUT", resp.status_code)

    def exists(self) -> bool:
        try:
            self.propfind(depth="0")
            return True
        except ApiError as error:
            if error.status == 404:
                return False
            raise


def parse_propfind_hrefs(data: bytes) -> list[str]:
    """Decode the multistatus href list; XML entities/DTD are rejected outright."""
    text_head = data[:4096].decode("utf-8", errors="ignore").lower()
    if "<!doctype" in text_head or "<!entity" in text_head:
        raise ApiError(400, "webdav_invalid_xml", "WebDAV property response is invalid")
    try:
        root = ET.fromstring(data)
    except ET.ParseError as exc:
        raise ApiError(400, "webdav_invalid_xml", "WebDAV property response is invalid") from exc

    def local(tag: str) -> str:
        return tag.rsplit("}", 1)[-1].lower()

    if local(root.tag) != "multistatus":
        raise ApiError(400, "webdav_invalid_xml", "WebDAV property response is invalid")
    hrefs: list[str] = []
    for response_node in root.iter():
        if local(response_node.tag) != "response":
            continue
        for child in response_node:
            if local(child.tag) == "href" and child.text:
                href = child.text.strip()
                if href:
                    hrefs.append(href)
    return hrefs


def resolve_href(base_url: str, href: str) -> str:
    """Resolve a (possibly relative) multistatus href against the request URL."""
    return urljoin(base_url, href)
