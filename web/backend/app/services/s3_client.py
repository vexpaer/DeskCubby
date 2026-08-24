"""Minimal S3-compatible client with AWS Signature Version 4.

Python port of the essentials of android `data/sync/S3SigV4.kt`:

- SigV4 canonical request (sorted signed headers, URI-encoded canonical path
  and query, payload SHA-256 binding), credential scope and derived signing
  key. The secret access key never appears in returned values, errors or logs.
- `put_object` / `get_object` / `delete_object` / `list_objects` against a
  user-configured endpoint; path-style addressing by default, virtual-host
  style otherwise — mirroring `buildS3CollectionUri`.
- All requests go through `core.http.BoundedHttpClient` so HTTPS is enforced
  unless the config explicitly allows HTTP (`allowInsecureHttp`) and response
  bodies stay bounded.
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import hmac
import re
from collections.abc import Callable
from typing import Any
from urllib.parse import urlsplit

from ..core.errors import ApiError
from ..core.http import BoundedHttpClient

ALGORITHM = "AWS4-HMAC-SHA256"
TERMINATOR = "aws4_request"
UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
MAX_RESPONSE_BYTES = 64 * 1024 * 1024   # CloudSyncLimits.maxObjectBytes
S3_TIMEOUT = 30.0                       # readTimeoutMillis in CloudSyncLimits

_REGION_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
_BUCKET_VHOST_RE = re.compile(r"[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")
_UNRESERVED = frozenset(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
)
_HEX = "0123456789ABCDEF"


def _require_s3_version(raw: str | None) -> str:
    value = (raw or "").strip()
    if value.lower().startswith("w/") or any(ch in value for ch in ("\r", "\n", ",")):
        raise ApiError(502, "s3_remote_validation", "S3 returned an invalid object version")
    if value and not value.startswith('"') and '"' not in value:
        value = f'"{value}"'
    if len(value) < 2 or not (value.startswith('"') and value.endswith('"')):
        raise ApiError(502, "s3_remote_validation", "S3 returned an invalid object version")
    opaque = value[1:-1]
    if any(ord(ch) < 0x21 or ord(ch) > 0x7E or ch == '"' for ch in opaque):
        raise ApiError(502, "s3_remote_validation", "S3 returned an invalid object version")
    return value


def _s3_version(headers: dict[str, str], data: bytes) -> str:
    raw = headers.get("etag")
    if raw:
        return _require_s3_version(raw)
    # Standard non-multipart PUT objects use the quoted payload MD5 as ETag.
    # This is only an opaque conditional version; SHA-256 remains the integrity check.
    try:
        digest = hashlib.md5(data, usedforsecurity=False).hexdigest()
    except TypeError:  # pragma: no cover - older Python without usedforsecurity
        digest = hashlib.md5(data).hexdigest()
    return f'"{digest}"'


def _uri_encode(value: bytes) -> str:
    out: list[str] = []
    for byte in value:
        ch = chr(byte)
        if ch in _UNRESERVED:
            out.append(ch)
        else:
            out.append("%" + _HEX[byte >> 4] + _HEX[byte & 0x0F])
    return "".join(out)


def _percent_decode(value: str) -> bytes:
    out = bytearray()
    i = 0
    while i < len(value):
        if value[i] == "%" and i + 2 < len(value):
            try:
                out.append(int(value[i + 1:i + 3], 16))
                i += 3
                continue
            except ValueError:
                pass
        out.extend(value[i].encode("utf-8"))
        i += 1
    return bytes(out)


def canonical_uri(path: str) -> str:
    """SigV4 canonical URI: re-encode each raw path segment."""
    if not path:
        return "/"
    segments = path.split("/")
    encoded = "/".join(_uri_encode(_percent_decode(segment)) for segment in segments)
    return encoded or "/"


def canonical_query(query: str) -> str:
    if not query:
        return ""
    pairs: list[tuple[str, str]] = []
    for parameter in query.split("&"):
        if parameter == "":
            continue
        name, _, raw_value = parameter.partition("=")
        pairs.append((_uri_encode(_percent_decode(name)), _uri_encode(_percent_decode(raw_value))))
    pairs.sort()
    return "&".join(f"{name}={value}" for name, value in pairs)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _hmac(key: bytes, value: str) -> bytes:
    return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()


class S3SigV4Signer:
    """Signs single requests; keeps the secret key strictly internal."""

    def __init__(
        self,
        access_key_id: str,
        secret_access_key: str,
        region: str,
        service: str = "s3",
        session_token: str | None = None,
        clock: Callable[[], _dt.datetime] | None = None,
    ):
        access_key_id = (access_key_id or "").strip()
        if not access_key_id or "/" in access_key_id or "," in access_key_id:
            raise ApiError(400, "invalid_credentials", "S3 Access Key is invalid")
        if not (secret_access_key or "").encode("utf-8"):
            raise ApiError(400, "invalid_credentials", "S3 Secret Key is required")
        region = (region or "").strip().lower()
        if not region or "/" in region or not _REGION_RE.fullmatch(region):
            raise ApiError(400, "invalid_region", "S3 Region is invalid")
        self.access_key_id = access_key_id
        self._secret = secret_access_key.encode("utf-8")
        self.region = region
        self.service = service.strip().lower() or "s3"
        self.session_token = (session_token or "").strip() or None
        self._clock = clock or _dt.datetime.now

    def sign(
        self,
        method: str,
        url: str,
        headers: dict[str, str] | None = None,
        payload: bytes = b"",
        payload_sha256: str | None = None,
    ) -> dict[str, str]:
        """Return headers (including Authorization) for one signed request."""
        parts = urlsplit(url)
        if parts.scheme not in ("https", "http") or not parts.hostname:
            raise ApiError(400, "invalid_endpoint", "S3 endpoint URL is invalid")
        method = method.strip().upper()
        now = self._clock().astimezone(_dt.timezone.utc)
        amz_date = now.strftime("%Y%m%dT%H%M%SZ")
        date_stamp = amz_date[:8]
        if payload_sha256 == UNSIGNED_PAYLOAD:
            payload_hash = payload_sha256
        elif payload_sha256 is not None:
            if not re.fullmatch(r"[0-9A-Fa-f]{64}", payload_sha256):
                raise ApiError(400, "invalid_hash", "Payload hash must be hex SHA-256")
            payload_hash = payload_sha256.lower()
        else:
            payload_hash = sha256_hex(payload)

        normalized: dict[str, str] = {}
        for name, value in (headers or {}).items():
            name = name.strip().lower()
            if not name or name == "authorization":
                continue
            normalized[name] = " ".join(str(value).split())
        host = parts.hostname.lower()
        if parts.port:
            default = {"https": 443, "http": 80}[parts.scheme]
            if parts.port != default:
                host = f"{host}:{parts.port}"
        normalized["host"] = host
        normalized["x-amz-date"] = amz_date
        normalized["x-amz-content-sha256"] = payload_hash
        if self.session_token:
            normalized["x-amz-security-token"] = self.session_token

        signed_names = sorted(normalized)
        signed_headers = ";".join(signed_names)
        canonical_headers = "".join(f"{name}:{normalized[name]}\n" for name in signed_names)
        canonical_request = "\n".join([
            method,
            canonical_uri(parts.path),
            canonical_query(parts.query),
            canonical_headers,
            signed_headers,
            payload_hash,
        ])
        scope = f"{date_stamp}/{self.region}/{self.service}/{TERMINATOR}"
        string_to_sign = "\n".join([
            ALGORITHM,
            amz_date,
            scope,
            sha256_hex(canonical_request.encode("utf-8")),
        ])
        signing_key = _hmac(_hmac(_hmac(_hmac(
            b"AWS4" + self._secret, date_stamp,
        ), self.region), self.service), TERMINATOR)
        signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
        authorization = (
            f"{ALGORITHM} Credential={self.access_key_id}/{scope},"
            f"SignedHeaders={signed_headers},Signature={signature}"
        )
        return {**{name.upper(): value for name, value in normalized.items()}, "Authorization": authorization}


def build_collection_url(config: dict[str, Any]) -> str:
    """Endpoint + bucket (+ remotePath) with path-style or virtual-host style."""
    endpoint = str(config.get("endpointUrl") or "").strip().rstrip("/")
    bucket = str(config.get("s3Bucket") or "")
    remote_value = config.get("remotePath", "DeskCubby")
    if remote_value is None:
        remote_value = "DeskCubby"
    remote_path = "/".join(
        segment for segment in str(remote_value).split("/") if segment
    )
    if not bucket or "/" in bucket or "\\" in bucket or len(bucket) > 255:
        raise ApiError(400, "invalid_bucket", "S3 Bucket name is invalid")
    parts = urlsplit(endpoint)
    if parts.scheme not in ("https", "http") or not parts.hostname:
        raise ApiError(400, "invalid_endpoint", "S3 endpoint URL is invalid")
    if bool(config.get("s3PathStyle", True)):
        prefix = f"{endpoint}/{bucket}"
    else:
        if not _BUCKET_VHOST_RE.fullmatch(bucket) or ":" in parts.hostname:
            raise ApiError(400, "invalid_bucket",
                           "Bucket must be a lowercase hostname-safe name without path-style")
        host = bucket + "." + parts.hostname
        authority = host if parts.port is None else f"{host}:{parts.port}"
        endpoint = f"{parts.scheme}://{authority}{parts.path or '/'}".rstrip("/")
        prefix = endpoint
    encoded_remote = "/".join(_uri_encode_segment(s) for s in remote_path.split("/")) if remote_path else ""
    # This is a collection URI. appendStorageName on Android always receives a
    # trailing slash; without it Web produced `.../DeskCubby.manifest` instead
    # of `.../DeskCubby/.manifest` for every non-empty remotePath.
    return f"{prefix}/{encoded_remote}/" if encoded_remote else f"{prefix}/"


def _uri_encode_segment(segment: str) -> str:
    return _uri_encode(segment.encode("utf-8"))


class S3Client:
    """Object operations over the bounded HTTP client."""

    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.base_url = build_collection_url(config)
        self.signer = S3SigV4Signer(
            access_key_id=str(config.get("s3AccessKey") or ""),
            secret_access_key=str(config.get("s3SecretKey") or ""),
            region=str(config.get("s3Region") or "us-east-1"),
            session_token=str(config.get("s3SessionToken") or "") or None,
        )
        self.http = BoundedHttpClient(
            allow_http=bool(config.get("allowInsecureHttp")),
            max_bytes=MAX_RESPONSE_BYTES,
            timeout=S3_TIMEOUT,
            max_redirects=3,
        )

    def object_url(self, name: str) -> str:
        if not re.fullmatch(r"[.A-Za-z0-9_-]{1,200}", name):
            raise ApiError(400, "invalid_name", "Remote object name is invalid")
        return self.base_url + name

    def _request(self, method: str, url: str, *, payload: bytes = b"",
                 headers: dict[str, str] | None = None) -> tuple[int, bytes]:
        status, data, _response_headers = self._request_full(
            method, url, payload=payload, headers=headers,
        )
        return status, data

    def _request_full(self, method: str, url: str, *, payload: bytes = b"",
                      headers: dict[str, str] | None = None) -> tuple[int, bytes, dict[str, str]]:
        signed = self.signer.sign(method, url, headers=headers, payload=payload)
        resp = self.http.request(method, url, headers=signed, content=payload or None)
        declared = resp.headers.get("content-length")
        cap = MAX_RESPONSE_BYTES
        if declared and declared.isdigit() and int(declared) > cap:
            raise ApiError(413, "response_too_large", "S3 response exceeds the size limit")
        data = resp.content
        if len(data) > cap:
            raise ApiError(413, "response_too_large", "S3 response exceeds the size limit")
        return resp.status_code, data, {str(k).lower(): str(v) for k, v in resp.headers.items()}

    def get_object(self, name: str, max_bytes: int = MAX_RESPONSE_BYTES) -> bytes | None:
        blob = self.get_blob(name, max_bytes=max_bytes)
        return blob[0] if blob is not None else None

    def get_blob(
        self,
        name: str,
        max_bytes: int = MAX_RESPONSE_BYTES,
        *,
        expected_version: str | None = None,
    ) -> tuple[bytes, str] | None:
        request_headers = {"If-Match": _require_s3_version(expected_version)} if expected_version else None
        status, data, response_headers = self._request_full(
            "GET", self.object_url(name), headers=request_headers,
        )
        if status == 404:
            return None
        if status in (409, 412):
            raise ApiError(409, "s3_conflict", "Remote S3 object changed during sync")
        if status != 200:
            raise self._status_error("S3 GET", status, data)
        if len(data) > max_bytes:
            raise ApiError(413, "response_too_large", "S3 object exceeds the size limit")
        # S3-compatible gateways sometimes omit or normalize ETag. Android uses
        # the requested version for a conditional read, otherwise falls back to
        # the single-part object MD5 used by ordinary PUT uploads.
        version = _require_s3_version(expected_version) if expected_version else _s3_version(response_headers, data)
        return data, version

    def put_object(self, name: str, data: bytes, *,
                   content_type: str = "application/octet-stream") -> None:
        self.put_blob(name, data, content_type=content_type)

    def put_blob(
        self,
        name: str,
        data: bytes,
        *,
        content_type: str = "application/octet-stream",
        expected_version: str | None = None,
        must_not_exist: bool = False,
    ) -> str:
        if expected_version is not None and must_not_exist:
            raise ValueError("expected_version and must_not_exist are mutually exclusive")
        request_headers = {
            "Content-Type": content_type,
            "x-amz-meta-deskcubby-sha256": hashlib.sha256(data).hexdigest(),
        }
        if expected_version is not None:
            request_headers["If-Match"] = _require_s3_version(expected_version)
        elif must_not_exist:
            request_headers["If-None-Match"] = "*"
        status, body, response_headers = self._request_full(
            "PUT", self.object_url(name),
            payload=data,
            headers=request_headers,
        )
        if status in (409, 412):
            raise ApiError(409, "s3_conflict", "Remote S3 object changed during sync")
        if status not in (200, 201, 204):
            raise self._status_error("S3 PUT", status, body)
        raw = response_headers.get("etag")
        if raw:
            return _require_s3_version(raw)
        verified = self.get_blob(name, max_bytes=max(1, len(data)))
        if verified is None or verified[0] != data:
            raise ApiError(409, "s3_conflict", "S3 write could not be verified")
        return verified[1]

    def delete_object(self, name: str) -> None:
        status, body = self._request("DELETE", self.object_url(name))
        if status not in (200, 201, 204, 404):
            raise self._status_error("S3 DELETE", status, body)

    def list_objects(self, prefix: str = "", *, max_keys: int = 1000) -> list[dict[str, Any]]:
        """List keys under `prefix` via GET on the collection (ListObjects V2 shape)."""
        results: list[dict[str, Any]] = []
        token: str | None = None
        base = self.base_url
        query_prefix = _uri_encode(prefix.encode("utf-8")) if prefix else ""
        for _ in range(100):  # bounded pagination
            params = [f"list-type=2", f"max-keys={int(max_keys)}"]
            if query_prefix:
                params.append(f"prefix={query_prefix}")
            if token:
                params.append(f"continuation-token={_uri_encode(token.encode('utf-8'))}")
            url = base + "?" + "&".join(params)
            status, data = self._request("GET", url)
            if status != 200:
                raise self._status_error("S3 LIST", status, data)
            page, token = parse_list_objects(data)
            results.extend(page)
            if not token or len(results) >= 100_000:
                break
        return results[:100_000]

    @staticmethod
    def _status_error(action: str, status: int, body: bytes) -> ApiError:
        code = extract_error_code(body)
        suffix = f" ({code})" if code else ""
        return ApiError(502, f"s3_{status}", f"{action} failed (HTTP {status}){suffix}")


def extract_error_code(body: bytes) -> str | None:
    """Safe provider error code from an S3 XML error document."""
    match = re.search(
        r"<(?:[A-Za-z0-9_-]+:)?Code>\s*([A-Za-z0-9._-]{1,128})\s*</(?:[A-Za-z0-9_-]+:)?Code>",
        body.decode("utf-8", errors="ignore"),
    )
    return match.group(1) if match else None


def parse_list_objects(data: bytes) -> tuple[list[dict[str, Any]], str | None]:
    """Decode a minimal ListObjectsV2 XML page into {key,size,lastModified} dicts."""
    import xml.etree.ElementTree as ET

    text_head = data[:4096].decode("utf-8", errors="ignore").lower()
    if "<!doctype" in text_head or "<!entity" in text_head:
        raise ApiError(400, "s3_invalid_xml", "S3 list response is invalid")
    try:
        root = ET.fromstring(data)
    except ET.ParseError as exc:
        raise ApiError(400, "s3_invalid_xml", "S3 list response is invalid") from exc

    def local(tag: str) -> str:
        return tag.rsplit("}", 1)[-1].lower()

    objects: list[dict[str, Any]] = []
    token: str | None = None
    for node in root.iter():
        name = local(node.tag)
        if name == "contents":
            entry: dict[str, Any] = {}
            for child in node:
                child_name = local(child.tag)
                if child_name in ("key", "etag") and child.text:
                    entry[child_name] = child.text.strip()
                elif child_name in ("size", "lastmodified") and child.text:
                    entry[child_name] = child.text.strip()
            if entry.get("key"):
                objects.append(entry)
        elif name == "nextcontinuationtoken" and node.text:
            token = node.text.strip()
    return objects, token
