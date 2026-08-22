"""健康每日统计 health service — Android step-statistics import + daily rows.

Mirrors `StepStatisticsJsonCodec` (android `data/statistics/`) and stores the
decoded days in the Room-mirrored `health_days` table
(dayIso PK, steps / distanceMeters / activeCaloriesKcal).

Accepted upload shapes (tolerant):

- a full step-statistics document `{schemaVersion, trackingStartedOn,
  deviceSensorBaseline?, days:[...]}` (v1–v3; unknown siblings ignored);
- a bare `{days:[...]}` object;
- a bare JSON array of day entries.

Canonical day keys are `date`/`zoneId`/`state`/`collectedAtEpochMillis`/`steps`
/`distanceMeters`/`activeCaloriesKilocalories`; the tolerant spellings
`dayIso` and `activeCaloriesKcal` / `calories` are accepted as well. Value
bounds mirror Android: steps 0..1,000,000 per day, distance 0..1,000,000 m,
active calories 0..100,000 kcal, at most 36,600 days and no duplicate dates.
"""
from __future__ import annotations

import json
import math
import time
from datetime import date, timedelta
from typing import Any

from ..core.db import write_lock
from ..core.errors import ApiError

MAX_STATISTICS_JSON_BYTES = 10 * 1024 * 1024
MAX_STATISTICS_DAYS = 36_600
MAX_STEPS_PER_DAY = 1_000_000
MAX_DISTANCE_METERS_PER_DAY = 1_000_000.0
MAX_ACTIVE_CALORIES_KCAL_PER_DAY = 100_000.0

DEFAULT_OVERVIEW_DAYS = 30
MAX_OVERVIEW_DAYS = 366


def now_ms() -> int:
    return int(time.time() * 1000)


def parse_health_document(raw: bytes | str) -> list[dict[str, Any]]:
    """Decode any accepted upload shape into validated day rows."""
    if isinstance(raw, bytes):
        if len(raw) > MAX_STATISTICS_JSON_BYTES:
            raise ApiError(413, "health_too_large", "健康统计 JSON 超过 10 MiB 上限")
        raw = raw.decode("utf-8", "replace")
    try:
        root = json.loads(raw)
    except ValueError:
        raise ApiError(400, "invalid_json", "无法解析健康统计 JSON")
    return parse_days_root(root)


def parse_days_root(root: Any) -> list[dict[str, Any]]:
    if isinstance(root, list):
        days_raw = root
    elif isinstance(root, dict) and isinstance(root.get("days"), list):
        days_raw = root["days"]
    else:
        raise ApiError(400, "invalid_json", "未找到健康统计 days 数组")
    if len(days_raw) > MAX_STATISTICS_DAYS:
        raise ApiError(400, "too_many_days", "健康统计数据天数超出上限")
    days: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in days_raw:
        day = _parse_day(item)
        if day["dayIso"] in seen:
            raise ApiError(400, "duplicate_date", "健康统计数据日期重复")
        seen.add(day["dayIso"])
        days.append(day)
    return days


def _parse_day(day: Any) -> dict[str, Any]:
    if not isinstance(day, dict):
        raise ApiError(400, "invalid_json", "健康统计天条目必须是对象")
    raw_date = day.get("date") or day.get("dayIso")
    text = raw_date.strip() if isinstance(raw_date, str) else ""
    try:
        parsed = date.fromisoformat(text[:10])
    except ValueError:
        parsed = None
    if parsed is None or parsed.isoformat() != text.strip():
        raise ApiError(400, "invalid_date", "健康统计日期不是 ISO yyyy-MM-dd 格式")

    steps = _bounded_number(day.get("steps"), MAX_STEPS_PER_DAY)
    distance = _bounded_number(
        first_present(day, ("distanceMeters", "distance")), MAX_DISTANCE_METERS_PER_DAY
    )
    calories = _bounded_number(
        first_present(day, ("activeCaloriesKilocalories", "activeCaloriesKcal", "calories")),
        MAX_ACTIVE_CALORIES_KCAL_PER_DAY,
    )
    return {
        "dayIso": text.strip(),
        "steps": int(steps) if steps is not None else 0,
        "distanceMeters": float(distance) if distance is not None else 0.0,
        "activeCaloriesKcal": float(calories) if calories is not None else 0.0,
    }


def first_present(item: dict[str, Any], keys: tuple[str, ...]) -> Any:
    for key in keys:
        value = item.get(key)
        if value is not None:
            return value
    return None


def _bounded_number(value: Any, maximum: float) -> float | None:
    """Android requiredNullableDouble semantics: null stays null, junk is rejected."""
    if value is None or isinstance(value, bool):
        if isinstance(value, bool):
            raise ApiError(400, "invalid_value", "健康统计数据数值无效")
        return None
    if isinstance(value, int):
        number = float(value)
    elif isinstance(value, float):
        number = value
    elif isinstance(value, str):
        try:
            number = float(value)
        except ValueError:
            raise ApiError(400, "invalid_value", "健康统计数据数值无效")
    else:
        raise ApiError(400, "invalid_value", "健康统计数据数值无效")
    if not math.isfinite(number) or number < 0.0 or number > maximum:
        raise ApiError(400, "invalid_value", "健康统计数据数值超出允许范围")
    return number


# ---------------------------------------------------------------------------
# Persistence + overview
# ---------------------------------------------------------------------------

def import_health(con, days: list[dict[str, Any]]) -> dict[str, int]:
    """Upsert day rows inside one transaction; returns counters."""
    imported = 0
    stamp = now_ms()
    with write_lock(), con:
        for day in days:
            con.execute(
                "INSERT INTO health_days(dayIso, steps, distanceMeters, activeCaloriesKcal, updatedAt)"
                " VALUES(?,?,?,?,?)"
                " ON CONFLICT(dayIso) DO UPDATE SET"
                " steps=excluded.steps, distanceMeters=excluded.distanceMeters,"
                " activeCaloriesKcal=excluded.activeCaloriesKcal, updatedAt=excluded.updatedAt",
                (
                    day["dayIso"],
                    day["steps"],
                    day["distanceMeters"],
                    day["activeCaloriesKcal"],
                    stamp,
                ),
            )
            imported += 1
        con.commit()
    return {"days": imported}


def overview(con, days: int = DEFAULT_OVERVIEW_DAYS) -> dict[str, Any]:
    """{days:[{dayIso,steps,distanceMeters,activeCaloriesKcal}], totals:{...}}."""
    window = max(1, min(MAX_OVERVIEW_DAYS, int(days or DEFAULT_OVERVIEW_DAYS)))
    today = date.today()
    since = (today - timedelta(days=window - 1)).isoformat()
    rows = con.execute(
        "SELECT dayIso, steps, distanceMeters, activeCaloriesKcal FROM health_days WHERE dayIso >= ?"
        " ORDER BY dayIso ASC",
        (since,),
    ).fetchall()
    by_day = {str(r["dayIso"])[:10]: r for r in rows}
    out_days: list[dict[str, Any]] = []
    totals = {"steps": 0, "distanceMeters": 0.0, "activeCaloriesKcal": 0.0}
    for offset in range(window - 1, -1, -1):
        day_iso = (today - timedelta(days=offset)).isoformat()
        row = by_day.get(day_iso)
        steps = int(row["steps"]) if row is not None else 0
        distance = float(row["distanceMeters"]) if row is not None else 0.0
        kcal = float(row["activeCaloriesKcal"]) if row is not None else 0.0
        totals["steps"] += steps
        totals["distanceMeters"] += distance
        totals["activeCaloriesKcal"] += kcal
        out_days.append(
            {
                "dayIso": day_iso,
                "steps": steps,
                "distanceMeters": round(distance, 2),
                "activeCaloriesKcal": round(kcal, 2),
            }
        )
    return {
        "days": out_days,
        "totals": {
            "steps": totals["steps"],
            "distanceMeters": round(totals["distanceMeters"], 2),
            "activeCaloriesKcal": round(totals["activeCaloriesKcal"], 2),
        },
    }
