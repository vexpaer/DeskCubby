"""统计中心 Statshub overview — aggregates every module into one payload.

Mirrors the sections of android `StatisticsHubModels.kt` where a web equivalent
exists. Every section is optional and degrades to zeros when its data is
missing, so a fresh install still renders the page:

- diary: entryCount / totalWords / currentStreakDays / longestStreakDays /
  monthEntries, derived from the rebuildable diary_index table only.
- thoughts / dateRecords (with upcoming) / poetry counts.
- reading engagement minutes from data/private/reading/engagement.json when present.
- games: high scores per game id from game_states.
- agent: SUM aggregate over agent_runs token columns (AgentTokenStatisticsRepository).
- usage / health: last-7-day totals from usage_events_daily / health_days.
"""
from __future__ import annotations

import json
from datetime import date, timedelta
from typing import Any

from fastapi import APIRouter, Depends

from ..core.config import PRIVATE_DIR
from ..core.db import get_db

router = APIRouter(prefix="/api/statshub", tags=["statshub"])

GAME_IDS = ["2048", "2048_5", "2048_6", "snake", "tetris", "minesweeper", "spider", "go"]

ENGAGEMENT_PATH = PRIVATE_DIR / "reading" / "engagement.json"


def _parse_iso(value: str) -> date | None:
    try:
        return date.fromisoformat(str(value)[:10])
    except (TypeError, ValueError):
        return None


def current_streak(dates: set[date], today: date) -> int:
    cursor = today if today in dates else today - timedelta(days=1)
    result = 0
    while cursor in dates and result < 100_000:
        result += 1
        cursor -= timedelta(days=1)
    return result


def longest_streak(dates: set[date]) -> int:
    longest = 0
    current = 0
    previous: date | None = None
    for day in sorted(dates):
        current = current + 1 if (previous is not None and previous + timedelta(days=1) == day) else 1
        longest = max(longest, current)
        previous = day
    return longest


def _diary_section(con, today: date) -> dict[str, Any]:
    rows = con.execute("SELECT dateIso, wordCount FROM diary_index").fetchall()
    dated: list[tuple[date, int]] = []
    total_words = 0
    for row in rows:
        parsed = _parse_iso(row["dateIso"])
        words = max(0, int(row["wordCount"] or 0))
        total_words += words
        if parsed is not None and parsed <= today:
            dated.append((parsed, words))
    dates = {d for d, _ in dated}
    month_prefix = today.strftime("%Y-%m")
    month_entries = sum(1 for d, _ in dated if d.strftime("%Y-%m") == month_prefix)
    return {
        "entryCount": len(rows),
        "totalWords": total_words,
        "currentStreakDays": current_streak(dates, today),
        "longestStreakDays": longest_streak(dates),
        "monthEntries": month_entries,
    }


def _thoughts_section(con) -> dict[str, Any]:
    active = con.execute("SELECT COUNT(*) FROM flash_thoughts WHERE deletedAt IS NULL").fetchone()[0]
    trashed = con.execute(
        "SELECT COUNT(*) FROM flash_thoughts WHERE deletedAt IS NOT NULL"
    ).fetchone()[0]
    categories = con.execute("SELECT COUNT(*) FROM thought_categories").fetchone()[0]
    highlighted = con.execute(
        "SELECT COUNT(*) FROM flash_thoughts WHERE deletedAt IS NULL AND highlighted = 1"
    ).fetchone()[0]
    pinned = con.execute(
        "SELECT COUNT(*) FROM flash_thoughts WHERE deletedAt IS NULL AND pinned = 1"
    ).fetchone()[0]
    return {
        "activeCount": int(active),
        "trashCount": int(trashed),
        "categoryCount": int(categories),
        "highlightedCount": int(highlighted),
        "pinnedCount": int(pinned),
    }


def _date_records_section(con, today: date) -> dict[str, Any]:
    count = con.execute("SELECT COUNT(*) FROM date_records").fetchone()[0]
    rows = con.execute(
        "SELECT id, name, icon, dateIso FROM date_records WHERE dateIso >= ? "
        "ORDER BY dateIso ASC, createdAt ASC, id ASC LIMIT 5",
        (today.isoformat(),),
    ).fetchall()
    upcoming = [
        {"id": r["id"], "name": r["name"], "icon": r["icon"], "dateIso": r["dateIso"]}
        for r in rows
    ]
    return {"count": int(count), "upcoming": upcoming}


def _poetry_section(con) -> dict[str, Any]:
    poems = con.execute("SELECT COUNT(*) FROM saved_poems").fetchone()[0]
    categories = con.execute("SELECT COUNT(*) FROM poetry_categories").fetchone()[0]
    return {"poemCount": int(poems), "categoryCount": int(categories)}


def _games_section(con) -> list[dict[str, Any]]:
    scores = {
        str(row["gameId"]): int(row["highScore"] or 0)
        for row in con.execute("SELECT gameId, highScore FROM game_states")
    }
    return [
        {"gameId": game_id, "highScore": max(0, scores.get(game_id, 0))} for game_id in GAME_IDS
    ]


def _agent_section(con) -> dict[str, Any]:
    row = con.execute(
        "SELECT COUNT(*) AS runCount,"
        " COALESCE(SUM(modelCallCount), 0) AS modelCallCount,"
        " COALESCE(SUM(usageReportedCallCount), 0) AS reportedCallCount,"
        " SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens,"
        " SUM(totalTokens) AS totalTokens, SUM(cachedInputTokens) AS cachedInputTokens,"
        " SUM(cacheRateInputTokens) AS cacheRateInputTokens,"
        " SUM(reasoningTokens) AS reasoningTokens"
        " FROM agent_runs"
    ).fetchone()

    def nonneg(value: Any) -> int | None:
        if value is None:
            return None
        return max(0, int(value))

    cache_rate_input = nonneg(row["cacheRateInputTokens"])
    cached_input = nonneg(row["cachedInputTokens"])
    cache_rate: float | None = None
    if cache_rate_input is not None and cache_rate_input > 0 and cached_input is not None:
        cache_rate = cached_input / cache_rate_input
    return {
        "runCount": max(0, int(row["runCount"] or 0)),
        "modelCallCount": max(0, int(row["modelCallCount"] or 0)),
        "reportedCallCount": max(0, int(row["reportedCallCount"] or 0)),
        "unreportedCallCount": max(0, int(row["modelCallCount"] or 0) - int(row["reportedCallCount"] or 0)),
        "inputTokens": nonneg(row["inputTokens"]),
        "outputTokens": nonneg(row["outputTokens"]),
        "totalTokens": nonneg(row["totalTokens"]),
        "cachedInputTokens": cached_input,
        "cacheRateInputTokens": cache_rate_input,
        "reasoningTokens": nonneg(row["reasoningTokens"]),
        "cacheRate": cache_rate,
    }


def _coerce_seconds(entry: Any) -> float | None:
    """Accept seconds / totalSeconds / millis / minutes spellings defensively."""
    if isinstance(entry, (int, float)):
        return float(entry)
    if isinstance(entry, dict):
        for key in ("totalSeconds", "seconds", "engagementSeconds"):
            if isinstance(entry.get(key), (int, float)):
                return float(entry[key])
        for key in ("totalMillis", "milliseconds"):
            if isinstance(entry.get(key), (int, float)):
                return float(entry[key]) / 1000.0
        if isinstance(entry.get("minutes"), (int, float)):
            return float(entry["minutes"]) * 60.0
    return None


def _reading_section() -> dict[str, Any]:
    try:
        raw = json.loads(ENGAGEMENT_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"available": False, "totalMinutes": 0.0, "books": []}
    books: dict[str, float] = {}
    titles: dict[str, str] = {}
    try:
        if isinstance(raw, dict):
            entries = raw.get("books") if isinstance(raw.get("books"), (dict, list)) else raw.get("totals")
            if isinstance(entries, dict):
                for book_id, entry in entries.items():
                    seconds = _coerce_seconds(entry)
                    if seconds is not None:
                        books[str(book_id)] = max(0.0, seconds)
                        if isinstance(entry, dict) and isinstance(entry.get("title"), str):
                            titles[str(book_id)] = entry["title"]
            elif isinstance(entries, list):
                for entry in entries:
                    if not isinstance(entry, dict):
                        continue
                    book_id = str(entry.get("id") or entry.get("bookId") or "")
                    seconds = _coerce_seconds(entry)
                    if book_id and seconds is not None:
                        books[book_id] = max(0.0, seconds)
                        if isinstance(entry.get("title"), str):
                            titles[book_id] = entry["title"]
    except Exception:  # noqa: BLE001 - tolerate any unexpected private-file shape
        books = {}
    items = sorted(
        (
            {"bookId": book_id, "title": titles.get(book_id), "minutes": round(seconds / 60.0, 1)}
            for book_id, seconds in books.items()
            if seconds > 0
        ),
        key=lambda item: (-item["minutes"], item["bookId"]),
    )
    total_minutes = round(sum(item["minutes"] for item in items), 1)
    return {"available": True, "totalMinutes": total_minutes, "books": items}


def _last_seven_days(today: date) -> list[str]:
    return [(today - timedelta(days=offset)).isoformat() for offset in range(6, -1, -1)]


def _usage_section(con, today: date) -> dict[str, Any]:
    days = _last_seven_days(today)
    since = days[0]
    rows = con.execute(
        "SELECT dayIso, SUM(totalTimeMs) AS totalMs FROM usage_events_daily"
        " WHERE dayIso >= ? GROUP BY dayIso",
        (since,),
    ).fetchall()
    by_day = {str(row["dayIso"])[:10]: int(row["totalMs"] or 0) for row in rows}
    recorded_days = con.execute(
        "SELECT COUNT(DISTINCT dayIso) FROM usage_events_daily"
    ).fetchone()[0]
    points = [{"date": day, "totalMinutes": round(by_day.get(day, 0) / 60000.0, 1)} for day in days]
    return {
        "recordedDays": int(recorded_days),
        "lastSevenTotalMs": sum(by_day.values()),
        "lastSevenTotalMinutes": round(sum(by_day.values()) / 60000.0, 1),
        "days": points,
    }


def _health_section(con, today: date) -> dict[str, Any]:
    days = _last_seven_days(today)
    since = days[0]
    placeholders = ",".join("?" * len(days))
    row = con.execute(
        f"SELECT COALESCE(SUM(steps), 0) AS steps, COALESCE(SUM(distanceMeters), 0) AS distance,"
        f" COALESCE(SUM(activeCaloriesKcal), 0) AS calories FROM health_days WHERE dayIso IN ({placeholders})",
        days,
    ).fetchone()
    daily_rows = con.execute(
        f"SELECT dayIso, steps, distanceMeters, activeCaloriesKcal FROM health_days WHERE dayIso >= ?",
        (since,),
    ).fetchall()
    by_day = {str(r["dayIso"])[:10]: r for r in daily_rows}
    recorded_days = con.execute("SELECT COUNT(*) FROM health_days").fetchone()[0]
    points = [
        {
            "date": day,
            "steps": int(by_day[day]["steps"]) if day in by_day else 0,
            "distanceMeters": float(by_day[day]["distanceMeters"]) if day in by_day else 0.0,
            "activeCaloriesKcal": float(by_day[day]["activeCaloriesKcal"]) if day in by_day else 0.0,
        }
        for day in days
    ]
    return {
        "recordedDays": int(recorded_days),
        "lastSevenSteps": int(row["steps"] or 0),
        "lastSevenDistanceMeters": float(row["distance"] or 0.0),
        "lastSevenActiveCaloriesKcal": float(row["calories"] or 0.0),
        "days": points,
    }


@router.get("/overview")
def overview(con=Depends(get_db)):
    today = date.today()
    payload: dict[str, Any] = {}
    sections = (
        ("diary", lambda: _diary_section(con, today)),
        ("thoughts", lambda: _thoughts_section(con)),
        ("dateRecords", lambda: _date_records_section(con, today)),
        ("poetry", lambda: _poetry_section(con)),
        ("games", lambda: _games_section(con)),
        ("agent", lambda: _agent_section(con)),
        ("reading", _reading_section),
        ("usage", lambda: _usage_section(con, today)),
        ("health", lambda: _health_section(con, today)),
    )
    for name, builder in sections:
        try:
            payload[name] = builder()
        except Exception:  # noqa: BLE001 - each section stays optional/zero on failure
            payload[name] = _empty_section(name)
    return payload


def _empty_section(name: str) -> Any:
    fallbacks: dict[str, Any] = {
        "diary": {
            "entryCount": 0,
            "totalWords": 0,
            "currentStreakDays": 0,
            "longestStreakDays": 0,
            "monthEntries": 0,
        },
        "thoughts": {
            "activeCount": 0,
            "trashCount": 0,
            "categoryCount": 0,
            "highlightedCount": 0,
            "pinnedCount": 0,
        },
        "dateRecords": {"count": 0, "upcoming": []},
        "poetry": {"poemCount": 0, "categoryCount": 0},
        "games": [{"gameId": game_id, "highScore": 0} for game_id in GAME_IDS],
        "agent": {
            "runCount": 0,
            "modelCallCount": 0,
            "reportedCallCount": 0,
            "unreportedCallCount": 0,
            "inputTokens": None,
            "outputTokens": None,
            "totalTokens": None,
            "cachedInputTokens": None,
            "cacheRateInputTokens": None,
            "reasoningTokens": None,
            "cacheRate": None,
        },
        "reading": {"available": False, "totalMinutes": 0.0, "books": []},
        "usage": {
            "recordedDays": 0,
            "lastSevenTotalMs": 0,
            "lastSevenTotalMinutes": 0.0,
            "days": [],
        },
        "health": {
            "recordedDays": 0,
            "lastSevenSteps": 0,
            "lastSevenDistanceMeters": 0.0,
            "lastSevenActiveCaloriesKcal": 0.0,
            "days": [],
        },
    }
    return fallbacks.get(name, {})
