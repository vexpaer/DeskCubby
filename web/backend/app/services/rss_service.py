"""RSS fetch/parse/cache service — port of android RssRepository.kt.

- Feed URLs come from `settings.rssSubscriptions` (`[{id,title,url,enabled}]`),
  which stays the single source of truth.
- HTTPS-only, bounded redirects/timeouts/response size (5 MiB), max 4 feeds in
  parallel, `rssMaxItemsPerFeed` cap (clamped to 1..200 like Android).
- RSS 2.0 and Atom are parsed with xml.etree; documents containing a DOCTYPE
  are rejected outright (XXE hardening, mirroring the Kotlin parser setup).
- Results are grouped by feed and cached under `data/private/rss-cache.json`.
"""
from __future__ import annotations

import hashlib
import json
import re
import threading
import time
import unicodedata
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlsplit, urlunsplit

from ..core.config import PRIVATE_DIR
from ..core.errors import ApiError
from ..core.http import BoundedHttpClient

CACHE_PATH = PRIVATE_DIR / "rss-cache.json"

MAX_RSS_BYTES = 5 * 1024 * 1024
MAX_PARALLEL_FEEDS = 4
MAX_FEEDS = 64
MAX_FEED_URL_CHARS = 8_192
MAX_ITEMS_PER_FEED_CAP = 200
DEFAULT_MAX_ITEMS_PER_FEED = 50
XML_NS = "{http://www.w3.org/XML/1998/namespace}base"

_accept_headers = {
    "Accept": "application/atom+xml, application/rss+xml, application/xml, text/xml;q=0.9",
    "Accept-Encoding": "identity",
    "User-Agent": "DeskCubby RSS/1.0",
}

_write_mutex = threading.Lock()


# ---------------------------------------------------------------------------
# URL normalization (RssRepository.normalizeFeedUrl)
# ---------------------------------------------------------------------------

def normalize_feed_url(raw: str) -> str:
    """Adds https:// when the scheme was omitted and rejects clear-text feeds."""
    trimmed = (raw or "").strip()
    if not trimmed:
        raise ApiError(400, "rss_empty_url", "RSS 地址不能为空。")
    if len(trimmed) > MAX_FEED_URL_CHARS or any(
        unicodedata.category(ch) == "Cc" for ch in trimmed
    ):
        raise ApiError(400, "rss_invalid_url", "RSS 地址过长或包含无效字符。")
    with_scheme = trimmed
    split = urlsplit(trimmed)
    if not split.scheme:
        with_scheme = f"https://{trimmed}"
    if len(with_scheme) > MAX_FEED_URL_CHARS:
        raise ApiError(400, "rss_invalid_url", "RSS 地址过长。")
    try:
        parts = urlsplit(with_scheme)
        port = parts.port  # may raise ValueError for out-of-range/invalid ports
    except ValueError:
        raise ApiError(400, "rss_invalid_url", "RSS 地址端口无效。")
    scheme = (parts.scheme or "").lower()
    if scheme == "http":
        raise ApiError(400, "rss_insecure_url", "RSS 地址仅支持 HTTPS，请将 http:// 改为 https://。")
    if scheme != "https":
        raise ApiError(400, "rss_insecure_url", "RSS 地址仅支持 HTTPS。")
    host = parts.hostname
    if not host:
        raise ApiError(400, "rss_invalid_url", "RSS 地址缺少有效的主机名。")
    if parts.username or parts.password:
        raise ApiError(400, "rss_invalid_url", "RSS 地址不能包含用户名或密码。")
    if port is not None and not (1 <= port <= 65_535):
        raise ApiError(400, "rss_invalid_url", "RSS 地址端口无效。")
    return urlunsplit(parts)


def looks_like_web_url(value: str) -> bool:
    try:
        return urlsplit(value.strip()).scheme.lower() in ("http", "https")
    except ValueError:
        return False


def resolve_web_url(base_url: str, raw: str) -> str:
    if not raw or not raw.strip():
        return ""
    try:
        joined = urljoin(base_url or "", raw.strip())
        parts = urlsplit(joined)
        if parts.scheme.lower() in ("http", "https"):
            return joined
    except ValueError:
        pass
    return ""


# ---------------------------------------------------------------------------
# XML parsing helpers
# ---------------------------------------------------------------------------

def contains_doctype(data: bytes) -> bool:
    """Removing NUL also catches the common UTF-16 encodings of "<!DOCTYPE"."""
    ascii_lower = []
    for byte in data[:MAX_RSS_BYTES]:
        value = byte & 0xFF
        if value != 0 and value < 128:
            ascii_lower.append(chr(value).lower())
    return "<!doctype" in "".join(ascii_lower)


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def _element_text(el: ET.Element | None) -> str:
    if el is None:
        return ""
    return "".join(el.itertext())


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:  # noqa: D102
        self.parts.append(data)


def plain_text(value: str) -> str:
    """Strip HTML markup like Android's Html.fromHtml(...).toString()."""
    if not value:
        return ""
    extractor = _TextExtractor()
    try:
        extractor.feed(value)
        extractor.close()
        text = "".join(extractor.parts)
    except Exception:  # noqa: BLE001 - malformed markup falls back to the raw string
        text = value
    text = re.sub(r"[ \t\x0b\f\r ]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def parse_published_at(value: str) -> int | None:
    if not value or not value.strip():
        return None
    candidate = value.strip()
    try:
        return int(datetime.fromisoformat(candidate.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        pass
    try:
        parsed = parsedate_to_datetime(candidate)
        if parsed is not None:
            return int(parsed.timestamp() * 1000)
    except (TypeError, ValueError, OverflowError):
        pass
    return None


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _children_by_name(el: ET.Element) -> dict[str, list[ET.Element]]:
    grouped: dict[str, list[ET.Element]] = {}
    for child in el:
        grouped.setdefault(_local(child.tag), []).append(child)
    return grouped


def _last_text(elements: list[ET.Element]) -> str:
    return _element_text(elements[-1]).strip() if elements else ""


class _Draft:
    __slots__ = ("source_id", "title", "link", "summary", "published_raw")

    def __init__(self, source_id: str, title: str, link: str, summary: str, published_raw: str):
        self.source_id = source_id
        self.title = title
        self.link = link
        self.summary = summary
        self.published_raw = published_raw

    def to_article(self, feed_id: str, feed_title: str, base_url: str) -> dict[str, Any]:
        resolved_url = resolve_web_url(base_url, self.link)
        stable_source = self.source_id or resolved_url or f"{self.title}|{self.published_raw}"
        return {
            "id": f"{feed_id}:{sha256_hex(stable_source)}",
            "feedId": feed_id,
            "feedTitle": feed_title,
            # Keep an absent title empty so the UI can provide a localized fallback.
            "title": self.title,
            "link": resolved_url,
            "summary": self.summary,
            "publishedAt": parse_published_at(self.published_raw),
        }


def _resolve_base(parent_url: str, el: ET.Element) -> str:
    raw_base = el.attrib.get(XML_NS)
    if not raw_base:
        return parent_url
    return resolve_web_url(parent_url, raw_base) or parent_url


def _parse_rss_item(item: ET.Element, base_url: str) -> _Draft:
    item_base = _resolve_base(base_url, item)
    grouped = _children_by_name(item)

    def first(*names: str) -> list[ET.Element]:
        for name in names:
            if name in grouped:
                return grouped[name]
        return []

    link = ""
    for link_el in first("link"):
        candidate = _element_text(link_el).strip()
        if candidate:
            link = candidate
            break
    guid = _last_text(first("guid", "id"))
    title = plain_text(_last_text(first("title")))
    summary_raw = _last_text(first("description", "summary"))
    content_raw = _last_text(first("encoded", "content"))
    published_raw = _last_text(first("pubdate", "published", "updated", "date"))
    fallback_link = guid if looks_like_web_url(guid) else ""
    return _Draft(
        source_id=guid,
        title=title,
        link=link or fallback_link,
        summary=plain_text(summary_raw or content_raw),
        published_raw=published_raw,
    )


def _parse_atom_entry(entry: ET.Element, feed_base_url: str) -> _Draft:
    entry_base = _resolve_base(feed_base_url, entry)
    grouped = _children_by_name(entry)

    def first(*names: str) -> list[ET.Element]:
        for name in names:
            if name in grouped:
                return grouped[name]
        return []

    alternate_link = ""
    fallback_link = ""
    for link_el in first("link"):
        href = (link_el.attrib.get("href") or "").strip()
        rel = (link_el.attrib.get("rel") or "").strip().lower()
        resolved = resolve_web_url(_resolve_base(entry_base, link_el), href)
        if resolved:
            if not rel or rel == "alternate":
                alternate_link = alternate_link or resolved
            fallback_link = fallback_link or resolved
    entry_id = _last_text(first("id"))
    title = plain_text(_last_text(first("title")))
    summary_raw = _last_text(first("summary"))
    content_raw = _last_text(first("content"))
    published_raw = _last_text(first("published")) or _last_text(first("updated"))
    fallback_id = entry_id if looks_like_web_url(entry_id) else ""
    return _Draft(
        source_id=entry_id,
        title=title,
        link=alternate_link or fallback_link or fallback_id,
        summary=plain_text(summary_raw or content_raw),
        published_raw=published_raw,
    )


def parse_feed_bytes(
    data: bytes,
    feed_id: str,
    subscription_title: str,
    source_url: str,
    max_items: int,
) -> list[dict[str, Any]]:
    if contains_doctype(data):
        raise ApiError(400, "rss_doctype", "为保证安全，不支持包含 DOCTYPE 的 RSS。")
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        raise ApiError(400, "rss_parse_error", "无法解析 RSS/Atom 内容。")

    root_base = _resolve_base(source_url, root)
    root_tag = _local(root.tag)
    drafts: list[_Draft] = []
    feed_title = (subscription_title or "").strip()

    if root_tag == "rss":
        channels = [child for child in root if _local(child.tag) == "channel"]
        if not channels:
            raise ApiError(400, "rss_parse_error", "该地址不是受支持的 RSS 2.0 或 Atom 订阅源。")
        channel = channels[0]
        grouped = _children_by_name(channel)
        if not feed_title:
            feed_title = plain_text(_last_text(grouped.get("title", [])))
        for child in channel:
            if _local(child.tag) == "item":
                drafts.append(_parse_rss_item(child, root_base))
                if len(drafts) >= max_items:
                    break
    elif root_tag == "feed":
        grouped = _children_by_name(root)
        if not feed_title:
            feed_title = plain_text(_last_text(grouped.get("title", [])))
        for child in root:
            if _local(child.tag) == "entry":
                drafts.append(_parse_atom_entry(child, root_base))
                if len(drafts) >= max_items:
                    break
    else:
        raise ApiError(400, "rss_unsupported", "该地址不是受支持的 RSS 2.0 或 Atom 订阅源。")

    display_title = feed_title or _host_label(source_url)
    return [draft.to_article(feed_id, display_title, root_base) for draft in drafts]


def _host_label(url: str) -> str:
    try:
        return urlsplit(url).hostname or "RSS"
    except ValueError:
        return "RSS"


# ---------------------------------------------------------------------------
# Fetch + refresh
# ---------------------------------------------------------------------------

def readable_message(error: BaseException) -> str:
    if isinstance(error, ApiError):
        mapping = {
            "network_timeout": "RSS 请求超时。",
            "network_error": "RSS 网络请求失败。",
            "insecure_url": "RSS 地址仅支持 HTTPS，请将 http:// 改为 https://。",
            "response_too_large": "RSS 内容超过上限。",
            "too_many_redirects": "RSS 地址重定向次数过多。",
            "redirect_cross_host": "RSS 重定向被拒绝。",
            "redirect_downgrade": "RSS 重定向被拒绝。",
        }
        return mapping.get(error.code, error.message)
    message = str(error).strip()
    return message or "RSS 加载失败。"


def fetch_feed(subscription: dict[str, Any], max_items: int) -> list[dict[str, Any]]:
    feed_url = normalize_feed_url(str(subscription.get("url") or ""))
    client = BoundedHttpClient(max_bytes=MAX_RSS_BYTES)
    resp = client.get(feed_url, headers=_accept_headers)
    declared = resp.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_RSS_BYTES:
        raise ApiError(413, "response_too_large", "RSS 内容超过 5 MiB 上限。")
    data = resp.content
    if len(data) > MAX_RSS_BYTES:
        raise ApiError(413, "response_too_large", "RSS 内容超过 5 MiB 上限。")
    if not (200 <= resp.status_code < 300):
        raise ApiError(502, "rss_http_error", f"RSS 请求失败（HTTP {resp.status_code}）。")
    capped_max = max(1, min(int(max_items), MAX_ITEMS_PER_FEED_CAP))
    return parse_feed_bytes(
        data=data,
        feed_id=str(subscription.get("id") or ""),
        subscription_title=str(subscription.get("title") or ""),
        source_url=feed_url,
        max_items=capped_max,
    )


def subscriptions_from_settings(settings: dict[str, Any]) -> list[dict[str, Any]]:
    raw = settings.get("rssSubscriptions")
    if not isinstance(raw, list):
        return []
    result = []
    for entry in raw[:MAX_FEEDS]:
        if not isinstance(entry, dict):
            continue
        result.append(
            {
                "id": str(entry.get("id") or ""),
                "title": str(entry.get("title") or ""),
                "url": str(entry.get("url") or ""),
                "enabled": bool(entry.get("enabled", True)),
            }
        )
    return result


def _sort_articles(articles: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(
        articles,
        key=lambda a: (
            -(a.get("publishedAt") or 0),
            a.get("feedTitle") or "",
            a.get("title") or "",
        ),
    )


def refresh_all_sync(con, settings: dict[str, Any] | None = None) -> dict[str, Any]:
    """Fetch every enabled feed, group items per feed and update the disk cache."""
    from .settings_store import load_settings

    if settings is None:
        settings = load_settings(con)
    subscriptions = subscriptions_from_settings(settings)
    enabled = [s for s in subscriptions if s["enabled"]]
    try:
        max_items = int(settings.get("rssMaxItemsPerFeed") or DEFAULT_MAX_ITEMS_PER_FEED)
    except (TypeError, ValueError):
        max_items = DEFAULT_MAX_ITEMS_PER_FEED
    max_items = max(1, min(max_items, MAX_ITEMS_PER_FEED_CAP))

    outcomes: dict[str, tuple[list[dict[str, Any]] | None, str | None]] = {}
    if enabled:
        workers = min(MAX_PARALLEL_FEEDS, len(enabled))
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="dc-rss") as pool:
            futures = {pool.submit(fetch_feed, sub, max_items): sub for sub in enabled}
            for future, sub in futures.items():
                try:
                    outcomes[sub["id"]] = (future.result(), None)
                except Exception as error:  # noqa: BLE001 - per-feed failure is isolated
                    outcomes[sub["id"]] = (None, readable_message(error))

    feeds_payload: list[dict[str, Any]] = []
    errors: dict[str, str] = {}
    flat: list[dict[str, Any]] = []
    for sub in subscriptions:
        feed_entry: dict[str, Any] = {
            "feedId": sub["id"],
            "title": (sub["title"] or "").strip(),
            "url": sub["url"],
            "enabled": sub["enabled"],
            "itemCount": 0,
            "error": None,
            "items": [],
        }
        if sub["id"] in outcomes:
            items, error = outcomes[sub["id"]]
            if error is not None:
                feed_entry["error"] = error
                errors[sub["id"]] = error
            elif items is not None:
                ordered = _sort_articles(items)
                feed_entry["items"] = ordered
                feed_entry["itemCount"] = len(ordered)
                flat.extend(ordered)
                try:
                    feed_entry["title"] = feed_entry["title"] or (items[0]["feedTitle"] if items else "")
                except (IndexError, KeyError):
                    pass
        else:
            # Disabled feeds are listed but not fetched; no error is reported.
            feed_entry["error"] = None
        feeds_payload.append(feed_entry)

    payload = {
        "version": 1,
        "refreshedAt": now_ms(),
        "maxItemsPerFeed": max_items,
        "feeds": feeds_payload,
        "items": _sort_articles(flat),
        "errors": errors,
    }
    save_cache(payload)
    return payload


def now_ms() -> int:
    return int(time.time() * 1000)


def save_cache(payload: dict[str, Any]) -> None:
    from ..core.fs import safe_write_text

    with _write_mutex:
        CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
        safe_write_text(CACHE_PATH, json.dumps(payload, ensure_ascii=False))


def load_cache() -> dict[str, Any] | None:
    try:
        stored = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return stored if isinstance(stored, dict) else None


def cached_items(feed_id: str | None = None) -> dict[str, Any]:
    cache = load_cache()
    if cache is None:
        return {"refreshedAt": 0, "feeds": [], "items": [], "errors": {}}
    if feed_id is None:
        return cache
    feeds = []
    flat: list[dict[str, Any]] = []
    for feed in cache.get("feeds", []):
        if not isinstance(feed, dict) or str(feed.get("feedId")) != feed_id:
            continue
        items = feed.get("items") or []
        filtered = dict(feed)
        filtered["items"] = items
        filtered["itemCount"] = len(items)
        feeds.append(filtered)
        flat.extend(items)
    return {
        "refreshedAt": cache.get("refreshedAt") or 0,
        "feeds": feeds,
        "items": _sort_articles(flat),
        "errors": cache.get("errors") or {},
    }
