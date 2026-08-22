"""每日诗词 Daily poetry rotation — port of android PoetryRepository.kt.

Rotation across three bounded HTTPS providers (今日诗词 → Hitokoto 诗词分类 →
古诗词·一言), deduplicated per local day by poem fingerprint, with the bundled
preset library as offline fallback. The last result is cached under
``data/private/poetry-cache.json``.

All limits mirror PoetryRepository.kt: 64 KiB responses, 6s/8s timeouts,
sentence ≤ 160 code points, title ≤ 200, author ≤ 100, recent list of 12,
per-day dedup memory of 512 fingerprints.
"""
from __future__ import annotations

import json
import re
import threading
import time
from datetime import date, datetime
from pathlib import Path
from typing import Any

from ..core.config import PRIVATE_DIR
from ..core.errors import ApiError
from ..core.http import BoundedHttpClient

CACHE_PATH = PRIVATE_DIR / "poetry-cache.json"
PRESET_ASSET = Path(__file__).resolve().parents[1] / "assets" / "poetry_presets.json"

TOKEN_URL = "https://v2.jinrishici.com/token"
JINRISHICI_SENTENCE_URL = "https://v2.jinrishici.com/sentence"
HITOKOTO_URL = "https://v1.hitokoto.cn/?c=i&encode=json&max_length=64"
GUSHI_CI_URL = "https://api.gushi.ci/all.json"

PROVIDERS = ("jinrishici", "hitokoto", "gushici")

MAX_RESPONSE_BYTES = 64 * 1024
MANUAL_ATTEMPTS_PER_PROVIDER = 2
MAX_SENTENCE_CODE_POINTS = 160
MAX_TITLE_CODE_POINTS = 200
MAX_AUTHOR_CODE_POINTS = 100
MAX_RECENT_POEMS = 12
MAX_DAILY_POEMS = 512

# PoetryPresetCatalog.kt asset validation limits.
ASSET_VERSION = 1
MAX_ASSET_BYTES = 1024 * 1024
MAX_CATEGORIES = 32
MAX_ITEMS_PER_CATEGORY = 128
MAX_TOTAL_ITEMS = 512
MAX_CATEGORY_NAME_CHARS = 100
MAX_TITLE_CHARS = 200
MAX_AUTHOR_CHARS = 100
MAX_POEM_CONTENT_CHARS = 4_000  # PoetryBookRepository.MAX_CONTENT_CHARS
MAX_POEM_SOURCE_CHARS = 512  # PoetryBookRepository.MAX_SOURCE_CHARS
PRESET_ID_RE = re.compile(r"[a-z0-9-]{1,64}")
POEM_WHITESPACE = re.compile(r"\s+")

FALLBACK: dict[str, Any] = {
    "content": "山中何事？松花酿酒，春水煎茶。",
    "source": "— 张可久《人月圆·山中书事》",
    "fullContent": (
        "兴亡千古繁华梦，诗眼倦天涯。\n孔林乔木，吴宫蔓草，楚庙寒鸦。\n"
        "数间茅舍，藏书万卷，投老村家。\n山中何事？松花酿酒，春水煎茶。"
    ),
    "dynasty": "元",
    "title": "人月圆·山中书事",
}

_lock = threading.RLock()
_preset_cache: list[dict[str, Any]] | None = None


def now_ms() -> int:
    return int(time.time() * 1000)


def epoch_day(d: date) -> int:
    return d.toordinal() - date(1970, 1, 1).toordinal()


def floor_mod(value: int, modulus: int) -> int:
    return value - modulus * (value // modulus) if modulus else 0


def take_code_points(value: str, limit: int) -> str:
    # Python string indices already count Unicode code points.
    return value[:limit]


# ---------------------------------------------------------------------------
# Preset catalog (PoetryPresetCatalog.kt)
# ---------------------------------------------------------------------------

def load_preset_categories() -> list[dict[str, Any]]:
    """Decode and validate app/assets/poetry_presets.json exactly like Kotlin."""
    global _preset_cache
    with _lock:
        if _preset_cache is not None:
            return _preset_cache
    raw = PRESET_ASSET.read_bytes()
    if not (1 <= len(raw) <= MAX_ASSET_BYTES):
        raise ValueError("Poetry preset asset is invalid")
    root = json.loads(raw.decode("utf-8"))
    if root.get("version") != ASSET_VERSION:
        raise ValueError("Poetry preset asset version mismatch")
    values = root.get("categories")
    if not isinstance(values, list) or not (1 <= len(values) <= MAX_CATEGORIES):
        raise ValueError("Poetry preset asset has invalid category count")
    result: list[dict[str, Any]] = []
    total_items = 0
    for category in values:
        preset_id = str(category["id"])
        name_zh = str(category["nameZh"]).strip()
        name_en = str(category["nameEn"]).strip()
        if not PRESET_ID_RE.fullmatch(preset_id):
            raise ValueError("Invalid preset id")
        if not name_zh or len(name_zh) > MAX_CATEGORY_NAME_CHARS:
            raise ValueError("Invalid preset category name")
        if not name_en or len(name_en) > MAX_CATEGORY_NAME_CHARS:
            raise ValueError("Invalid preset category name")
        items = category["items"]
        if not isinstance(items, list) or not (1 <= len(items) <= MAX_ITEMS_PER_CATEGORY):
            raise ValueError("Invalid preset item count")
        total_items += len(items)
        if total_items > MAX_TOTAL_ITEMS:
            raise ValueError("Too many preset poems")
        poems: list[dict[str, str]] = []
        for item in items:
            title = str(item["title"]).strip()
            author = str(item.get("author", "")).strip()
            content = str(item["content"]).strip()
            if not title or len(title) > MAX_TITLE_CHARS:
                raise ValueError("Invalid preset poem title")
            if len(author) > MAX_AUTHOR_CHARS:
                raise ValueError("Invalid preset poem author")
            if not content or len(content) > MAX_POEM_CONTENT_CHARS:
                raise ValueError("Invalid preset poem content")
            source = f"《{title}》" if not author else f"{author}《{title}》"
            if len(source) > MAX_POEM_SOURCE_CHARS:
                raise ValueError("Invalid preset poem source")
            poems.append({"content": content, "source": source})
        color = ((int(category["colorArgb"]) | 0xFF000000) & 0xFFFFFFFF) ^ 0x80000000
        color -= 0x80000000
        result.append(
            {
                "id": preset_id,
                "nameZh": name_zh,
                "nameEn": name_en,
                "colorArgb": color,
                "itemCount": len(poems),
                "poems": poems,
            }
        )
    if len({c["id"] for c in result}) != len(result):
        raise ValueError("Duplicate preset ids")
    with _lock:
        _preset_cache = result
    return result


def all_preset_poems() -> list[dict[str, str]]:
    return [poem for category in load_preset_categories() for poem in category["poems"]]


def preset_summaries() -> list[dict[str, Any]]:
    return [
        {k: category[k] for k in ("id", "nameZh", "nameEn", "colorArgb", "itemCount")}
        for category in load_preset_categories()
    ]


# ---------------------------------------------------------------------------
# Fingerprints and formatting
# ---------------------------------------------------------------------------

def poem_fingerprint(poem: dict[str, Any]) -> str:
    return "\u0001".join(
        POEM_WHITESPACE.sub("", str(poem.get(key, "")).strip()) for key in ("content", "source", "title")
    )


def format_source(title: str, author: str) -> str:
    if author and title:
        return f"— {author}《{title}》"
    if author:
        return f"— {author}"
    if title:
        return f"— 《{title}》"
    return "— 今日诗词"


def title_from_formatted_source(source: str) -> str:
    start = source.find("《")
    end = source.find("》", max(start + 1, 0))
    if start >= 0 and end > start + 1:
        return source[start + 1 : end].strip()
    return ""


# ---------------------------------------------------------------------------
# Bounded network fetches
# ---------------------------------------------------------------------------

def _http_get(url: str, token: str | None = None) -> str:
    """HTTPS-only GET through the shared bounded client; response capped at 64 KiB."""
    client = BoundedHttpClient(max_bytes=MAX_RESPONSE_BYTES)
    headers = {"Accept": "application/json", "User-Agent": "DeskCubby Web"}
    if token:
        headers["X-User-Token"] = token
    resp = client.get(url, headers=headers)
    declared = resp.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_RESPONSE_BYTES:
        raise ApiError(413, "response_too_large", "Poetry response is too large")
    body = resp.content
    if len(body) > MAX_RESPONSE_BYTES:
        raise ApiError(413, "response_too_large", "Poetry response is too large")
    if not (200 <= resp.status_code < 300):
        raise ApiError(resp.status_code, "poetry_http_error", "Poetry service returned an error")
    return body.decode("utf-8", errors="replace")


def _fetch_token() -> str:
    payload = json.loads(_http_get(TOKEN_URL))
    if payload.get("status") != "success":
        raise ApiError(502, "poetry_token_failed", str(payload.get("errMessage") or "Token request failed"))
    token = str(payload.get("data") or "")
    if not token:
        raise ApiError(502, "poetry_token_failed", "Token request failed")
    return token


def _fetch_jinrishici(cached_token: str | None) -> tuple[dict[str, Any], str | None]:
    active_token = (cached_token or "").strip() or None
    last_error: Exception | None = None
    for attempt in range(2):
        try:
            if active_token is None:
                active_token = _fetch_token()
            raw = _http_get(JINRISHICI_SENTENCE_URL, active_token)
            response_token = str(json.loads(raw).get("token") or "").strip() or active_token
            return parse_sentence(raw), response_token
        except Exception as error:  # noqa: BLE001 - provider failures rotate to the next one
            last_error = error
            if attempt == 0:
                active_token = None
    assert last_error is not None
    raise last_error


def parse_sentence(raw: str) -> dict[str, Any]:
    root = json.loads(raw)
    if root.get("status") != "success":
        raise ApiError(502, "poetry_bad_payload", str(root.get("errMessage") or "Poetry request failed"))
    data = root.get("data") or {}
    origin = data.get("origin") or {}
    title = str(origin.get("title") or "")
    author = str(origin.get("author") or "")
    lines = origin.get("content")
    full_content = ""
    if isinstance(lines, list):
        full_content = "\n".join(str(line) for line in lines if str(line).strip())
    return {
        "content": str(data.get("content")),
        "source": format_source(title, author),
        "fullContent": full_content,
        "dynasty": str(origin.get("dynasty") or ""),
        "title": title,
    }


def parse_hitokoto(raw: str) -> dict[str, Any]:
    root = json.loads(raw)
    content = str(root.get("hitokoto") or "").strip()
    if not content or len(content) > MAX_SENTENCE_CODE_POINTS:
        raise ApiError(502, "poetry_bad_payload", "Hitokoto sentence out of bounds")
    title = take_code_points(str(root.get("from") or "").strip(), MAX_TITLE_CODE_POINTS)
    author = take_code_points(str(root.get("from_who") or "").strip(), MAX_AUTHOR_CODE_POINTS)
    source = "— 一言·诗词" if not title.strip() and not author.strip() else format_source(title, author)
    return {"content": content, "source": source, "fullContent": "", "dynasty": "", "title": title.strip()}


def parse_gushi_ci(raw: str) -> dict[str, Any]:
    root = json.loads(raw)
    content = str(root.get("content") or "").strip()
    if not content or len(content) > MAX_SENTENCE_CODE_POINTS:
        raise ApiError(502, "poetry_bad_payload", "Gushi.ci sentence out of bounds")
    title = take_code_points(str(root.get("origin") or "").strip(), MAX_TITLE_CODE_POINTS)
    author = take_code_points(str(root.get("author") or "").strip(), MAX_AUTHOR_CODE_POINTS)
    source = "— 古诗词·一言" if not title.strip() and not author.strip() else format_source(title, author)
    return {"content": content, "source": source, "fullContent": "", "dynasty": "", "title": title.strip()}


# ---------------------------------------------------------------------------
# Offline fallback (PoetryRepository.chooseOfflinePoem)
# ---------------------------------------------------------------------------

def daily_poem_from_preset(preset: dict[str, str]) -> dict[str, Any]:
    normalized_body = preset["content"].strip()
    first_line = next((line.strip() for line in normalized_body.splitlines() if line.strip()), "")
    excerpt = take_code_points(first_line, MAX_SENTENCE_CODE_POINTS)
    source = preset["source"].strip()
    return {
        "content": excerpt if excerpt.strip() else take_code_points(normalized_body, MAX_SENTENCE_CODE_POINTS),
        "source": source if source.startswith("—") else f"— {source}",
        "fullContent": normalized_body,
        "dynasty": "",
        "title": title_from_formatted_source(source),
    }


def choose_offline_poem(
    presets: list[dict[str, str]],
    current: dict[str, Any] | None,
    blocked_fingerprints: set[str],
    seed: int,
) -> dict[str, Any] | None:
    if not presets:
        return None
    candidates = [daily_poem_from_preset(preset) for preset in presets]
    blocked = set(blocked_fingerprints)
    if current:
        blocked.add(poem_fingerprint(current))
    start = floor_mod(seed, len(candidates))
    for offset in range(len(candidates)):
        candidate = candidates[(start + offset) % len(candidates)]
        if poem_fingerprint(candidate) not in blocked:
            return candidate
    return candidates[start]


# ---------------------------------------------------------------------------
# Cache handling
# ---------------------------------------------------------------------------

def default_cache() -> dict[str, Any]:
    return {
        "version": 1,
        "token": "",
        "content": "",
        "source": "",
        "fullContent": "",
        "dynasty": "",
        "title": "",
        "updatedAt": 0,
        "recentFingerprints": [],
        "dailyFingerprintDate": "",
        "dailyFingerprints": [],
    }


def load_cache() -> dict[str, Any]:
    cache = default_cache()
    try:
        stored = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
        if isinstance(stored, dict):
            for key, value in stored.items():
                if key in cache:
                    cache[key] = value
    except (OSError, ValueError):
        pass
    return cache


def save_cache(cache: dict[str, Any]) -> None:
    from ..core.fs import safe_write_text

    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    safe_write_text(CACHE_PATH, json.dumps(cache, ensure_ascii=False))


def decode_fingerprints(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(v) for v in value if str(v).strip()]


def current_daily_seen(cache: dict[str, Any], today_iso: str, cached_today: bool) -> list[str]:
    if cache.get("dailyFingerprintDate") == today_iso:
        return decode_fingerprints(cache.get("dailyFingerprints"))
    if cached_today:
        # 0.4.1 upgrade path in Kotlin: conservatively treat the rolling recent
        # list as today's list so a shown poem does not reappear later that day.
        return decode_fingerprints(cache.get("recentFingerprints"))
    return []


def daily_poem_view(cache: dict[str, Any]) -> dict[str, Any]:
    """Current cached poem; FALLBACK when nothing was ever fetched."""
    if not cache.get("content"):
        poem = dict(FALLBACK)
        poem["updatedAt"] = 0
    else:
        poem = {
            "content": cache["content"],
            "source": cache.get("source") or "",
            "fullContent": cache.get("fullContent") or "",
            "dynasty": cache.get("dynasty") or "",
            "title": cache.get("title") or title_from_formatted_source(cache.get("source") or ""),
            "updatedAt": cache.get("updatedAt") or 0,
        }
    return poem


def rotated_providers(start_index: int) -> list[str]:
    n = len(PROVIDERS)
    normalized = floor_mod(start_index, n)
    return [PROVIDERS[(normalized + offset) % n] for offset in range(n)]


def _fetch_provider(provider: str, token: str | None) -> tuple[dict[str, Any], str | None]:
    if provider == "jinrishici":
        return _fetch_jinrishici(token)
    if provider == "hitokoto":
        return parse_hitokoto(_http_get(HITOKOTO_URL)), None
    return parse_gushi_ci(_http_get(GUSHI_CI_URL)), None


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def refresh_daily(force: bool = False) -> dict[str, Any]:
    """Rotate to a fresh poem unless today's poem was already fetched.

    Returns the DailyPoem view plus ``result`` (UPDATED / ALREADY_CURRENT_FOR_DAY)
    and ``provider`` (which source produced it).
    """
    with _lock:
        cache = load_cache()
        today = date.today()
        today_iso = today.isoformat()
        updated_at = int(cache.get("updatedAt") or 0)
        cached_today = updated_at > 0 and _date_of(updated_at) == today
        if not force and cached_today:
            poem = daily_poem_view(cache)
            poem["result"] = "ALREADY_CURRENT_FOR_DAY"
            poem["provider"] = "cache"
            return poem

        recent = decode_fingerprints(cache.get("recentFingerprints"))
        current: dict[str, Any] | None = (
            {
                "content": cache["content"],
                "source": cache.get("source") or "",
                "fullContent": cache.get("fullContent") or "",
                "dynasty": cache.get("dynasty") or "",
                "title": cache.get("title") or "",
            }
            if cache.get("content")
            else None
        )
        daily_seen = current_daily_seen(cache, today_iso, cached_today)
        blocked = {
            fp
            for fp in (daily_seen + ([poem_fingerprint(current)] if current else []))
            if fp.strip()
        }

        start_index = epoch_day(today) + len(daily_seen)
        attempts_per_provider = MANUAL_ATTEMPTS_PER_PROVIDER if force else 1
        token: str | None = (cache.get("token") or "").strip() or None
        selected: dict[str, Any] | None = None
        used_provider = "offline-fallback"
        for provider in rotated_providers(start_index):
            for _ in range(attempts_per_provider):
                try:
                    candidate, new_token = _fetch_provider(provider, token)
                    if new_token:
                        token = new_token
                except Exception:  # noqa: BLE001 - fall through to next provider/attempt
                    continue
                if candidate and poem_fingerprint(candidate) not in blocked:
                    selected = candidate
                    used_provider = provider
                    break
            if selected:
                break

        if selected is None:
            try:
                selected = choose_offline_poem(
                    presets=all_preset_poems(),
                    current=current,
                    blocked_fingerprints=blocked,
                    seed=start_index,
                )
                used_provider = "offline-fallback"
            except Exception:  # noqa: BLE001 - broken asset must not crash the endpoint
                selected = None
        if selected is None:
            # Last resort, mirrors Kotlin's IllegalStateException but keeps the API alive.
            selected = dict(FALLBACK)
            used_provider = "initial-fallback"

        merged = [
            fp
            for fp in (
                daily_seen
                + ([poem_fingerprint(current)] if current else [])
                + [poem_fingerprint(selected)]
            )
            if fp.strip()
        ]
        distinct: list[str] = []
        seen: set[str] = set()
        for fp in merged:
            if fp not in seen:
                seen.add(fp)
                distinct.append(fp)
        daily_after = distinct[-MAX_DAILY_POEMS:]

        recent_merged = [poem_fingerprint(selected)] + recent + (
            [poem_fingerprint(current)] if current else []
        )
        distinct_recent: list[str] = []
        seen_recent: set[str] = set()
        for fp in recent_merged:
            if fp.strip() and fp not in seen_recent:
                seen_recent.add(fp)
                distinct_recent.append(fp)
        recent_after = distinct_recent[:MAX_RECENT_POEMS]

        now = now_ms()
        cache.update(
            {
                "token": token or "",
                "content": selected["content"],
                "source": selected["source"],
                "fullContent": selected.get("fullContent") or "",
                "dynasty": selected.get("dynasty") or "",
                "title": selected.get("title") or "",
                "recentFingerprints": recent_after,
                "dailyFingerprintDate": today_iso,
                "dailyFingerprints": daily_after,
                "updatedAt": now,
            }
        )
        save_cache(cache)
        poem = daily_poem_view(cache)
        poem["result"] = "UPDATED"
        poem["provider"] = used_provider
        return poem


def _date_of(epoch_millis: int) -> date:
    return datetime.fromtimestamp(epoch_millis / 1000).date()
