"""手机使用时间 usage service — Android usage import + daily projection.

Mirrors the JSON shapes of `UsageDeviceJsonCodec` / `UsageStatisticsJsonCodec`
(android `data/statistics/`) and stores them in the Room-mirrored tables
`usage_devices` + `usage_events_daily`.

Accepted upload shapes (tolerant, like Windows 0.4.0's importer):

- a single usage/v1 device object:
  `{deviceId, deviceName, platform?, updatedAtEpochMillis?, history:{days:[...]}}`
  with the flattened shorthand `{deviceId, deviceName, days:[...]}` also accepted;
- a backup v20–v28 projection: `{"usageDevices": [device, ...]}` where every
  entry uses the same device object shape.

Canonical day keys are `date`/`zoneId`/`state`/`collectedAtEpochMillis`/`apps`
with app entries `packageName`/`foregroundMillis`; the tolerant spellings
`dayIso`, `totalTimeInMillis` / `totalTimeMs` / `timeInMillis` and an optional
`appName` are accepted as well.

Upserts are idempotent: `usage_devices` keeps the newest name/timestamp and
`usage_events_daily` keeps the greatest per-day duration seen per package
(the table carries no collectedAt/state columns, so Android's
"FINAL beats OPEN, then newest collectedAt" per-day snapshot merge is
approximated monotonically — re-imports never shrink stored totals).
"""
from __future__ import annotations

import json
import time
from datetime import date, timedelta
from typing import Any

from ..core.db import write_lock
from ..core.errors import ApiError

# Limits mirrored from StatisticsJsonCodecs.kt / UsageDeviceRepository.kt.
MAX_STATISTICS_JSON_BYTES = 10 * 1024 * 1024
MAX_USAGE_DEVICES = 64
MAX_STATISTICS_DAYS = 36_600
MAX_APPS_PER_DAY = 4_096
MAX_PACKAGE_NAME_CHARS = 255
MAX_DEVICE_NAME_CODE_POINTS = 80
MAX_FOREGROUND_MILLIS_PER_APP_DAY = 26 * 60 * 60 * 1000

TOP_APPS_PER_DAY = 5
DEFAULT_OVERVIEW_DAYS = 7
MAX_OVERVIEW_DAYS = 366


def now_ms() -> int:
    return int(time.time() * 1000)


def parse_usage_document(raw: bytes | str) -> list[dict[str, Any]]:
    """Decode any accepted upload shape into validated device records."""
    if isinstance(raw, bytes):
        if len(raw) > MAX_STATISTICS_JSON_BYTES:
            raise ApiError(413, "usage_too_large", "使用时间 JSON 超过 10 MiB 上限")
        raw = raw.decode("utf-8", "replace")
    try:
        root = json.loads(raw)
    except ValueError:
        raise ApiError(400, "invalid_json", "无法解析使用时间 JSON")
    return parse_usage_root(root)


def parse_usage_root(root: Any) -> list[dict[str, Any]]:
    if not isinstance(root, dict):
        raise ApiError(400, "invalid_json", "使用时间 JSON 根节点必须是对象")
    raw_devices = root.get("usageDevices")
    if isinstance(raw_devices, list):
        devices = raw_devices
    elif _looks_like_device(root):
        devices = [root]
    else:
        raise ApiError(400, "invalid_json", "未找到 usageDevices 或 usage/v1 设备对象")
    if len(devices) > MAX_USAGE_DEVICES:
        raise ApiError(400, "too_many_devices", "使用时间设备数量超出上限")
    records: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for item in devices:
        record = _parse_device(item)
        if record["deviceId"] in seen_ids:
            raise ApiError(400, "duplicate_device", "使用时间设备 ID 重复")
        seen_ids.add(record["deviceId"])
        records.append(record)
    return records


def _looks_like_device(node: dict[str, Any]) -> bool:
    return isinstance(node.get("deviceId"), str) and (
        isinstance(node.get("days"), list) or isinstance(node.get("history"), dict)
    )


def _parse_device(item: Any) -> dict[str, Any]:
    if not isinstance(item, dict):
        raise ApiError(400, "invalid_json", "使用时间设备条目必须是对象")
    device_id = _clean_device_id(item.get("deviceId"))
    device_name = _clean_device_name(item.get("deviceName"))
    platform_raw = item.get("platform")
    platform = platform_raw.strip().lower() if isinstance(platform_raw, str) else ""
    updated_at = _non_negative_int(item.get("updatedAtEpochMillis"), "updatedAtEpochMillis") or 0
    history = item.get("history") if isinstance(item.get("history"), dict) else item
    days_raw = history.get("days") if isinstance(history, dict) else None
    if not isinstance(days_raw, list):
        raise ApiError(400, "invalid_json", "使用时间设备缺少 days 数组")
    days = [_parse_day(day) for day in days_raw]
    for day in days:
        # updatedAt must not precede the newest history row (validateUsageDeviceRecord).
        updated_at = max(updated_at, day["collectedAt"])
    return {
        "deviceId": device_id,
        "deviceName": device_name,
        "platform": platform or "android",
        "updatedAt": updated_at,
        "days": days,
    }


def _parse_day(day: Any) -> dict[str, Any]:
    if not isinstance(day, dict):
        raise ApiError(400, "invalid_json", "使用时间天条目必须是对象")
    raw_date = day.get("date") or day.get("dayIso")
    date_iso = _clean_date(raw_date)
    apps_raw = day.get("apps")
    if apps_raw is None:
        apps_raw = []
    if not isinstance(apps_raw, list):
        raise ApiError(400, "invalid_json", "使用时间 apps 必须是数组")
    if len(apps_raw) > MAX_APPS_PER_DAY:
        raise ApiError(400, "too_many_apps", "单日应用数量超出上限")
    apps: dict[str, dict[str, Any]] = {}
    for entry in apps_raw:
        if not isinstance(entry, dict):
            raise ApiError(400, "invalid_json", "应用条目必须是对象")
        package = entry.get("packageName")
        if not isinstance(package, str) or not package.strip():
            raise ApiError(400, "invalid_json", "packageName 无效")
        package = package.strip()
        if len(package) > MAX_PACKAGE_NAME_CHARS or any(
            ch.isspace() or ord(ch) < 0x20 for ch in package
        ):
            raise ApiError(400, "invalid_json", "packageName 无效")
        millis = _first_int(
            entry,
            ("foregroundMillis", "totalTimeInMillis", "totalTimeMs", "timeInMillis"),
        )
        if millis is None or not (0 <= millis <= MAX_FOREGROUND_MILLIS_PER_APP_DAY):
            raise ApiError(400, "invalid_value", "前台时长超出允许范围")
        app_name = entry.get("appName")
        name = app_name.strip()[:200] if isinstance(app_name, str) else ""
        existing = apps.get(package)
        if existing is None or millis > existing["totalTimeMs"]:
            apps[package] = {"packageName": package, "appName": name, "totalTimeMs": millis}
    collected = _non_negative_int(day.get("collectedAtEpochMillis"), "collectedAtEpochMillis") or 0
    return {
        "dayIso": date_iso,
        "state": str(day.get("state") or ""),
        "collectedAt": collected,
        "apps": sorted(apps.values(), key=lambda a: a["packageName"]),
    }


def _first_int(item: dict[str, Any], keys: tuple[str, ...]) -> int | None:
    for key in keys:
        value = item.get(key)
        if isinstance(value, bool) or value is None:
            continue
        if isinstance(value, int):
            return value
        if isinstance(value, float) and value.is_integer():
            return int(value)
    return None


def _non_negative_int(value: Any, field: str) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int) and value >= 0:
        return value
    if isinstance(value, float) and value.is_integer() and value >= 0:
        return int(value)
    if field:
        raise ApiError(400, "invalid_value", f"{field} 必须是非负整数")
    return None


def _clean_date(value: Any) -> str:
    text = value.strip() if isinstance(value, str) else ""
    try:
        parsed = date.fromisoformat(text[:10])
    except ValueError:
        raise ApiError(400, "invalid_date", "使用时间日期不是 ISO yyyy-MM-dd 格式")
    if parsed.isoformat() != text.strip():
        raise ApiError(400, "invalid_date", "使用时间日期不是 ISO yyyy-MM-dd 格式")
    return text.strip()


def _clean_device_id(value: Any) -> str:
    text = value.strip() if isinstance(value, str) else ""
    if not text or len(text) > 128 or any(ch.isspace() or ord(ch) < 0x20 for ch in text):
        raise ApiError(400, "invalid_value", "设备 ID 无效")
    return text


def _clean_device_name(value: Any) -> str:
    text = value.strip() if isinstance(value, str) else ""
    if not text:
        raise ApiError(400, "invalid_value", "设备名称不能为空")
    filtered = "".join(ch for ch in text if ord(ch) >= 0x20)
    if len(filtered) > MAX_DEVICE_NAME_CODE_POINTS:
        raise ApiError(400, "invalid_value", "设备名称过长")
    return filtered


# ---------------------------------------------------------------------------
# Persistence
# ---------------------------------------------------------------------------

def import_usage(con, records: list[dict[str, Any]]) -> dict[str, int]:
    """Upsert devices + per-day rows inside one transaction; returns counters.

    Monotonic/idempotent: re-importing the same file is a no-op and stored
    totals never shrink (see module docstring for the snapshot-merge note).
    """
    device_rows = 0
    day_rows = 0
    entry_rows = 0
    stamp = now_ms()
    with write_lock(), con:
        for record in records:
            con.execute(
                "INSERT INTO usage_devices(deviceId, deviceName, isLocal, updatedAt)"
                " VALUES(?,?,0,?)"
                " ON CONFLICT(deviceId) DO UPDATE SET"
                " deviceName=CASE WHEN excluded.updatedAt >= usage_devices.updatedAt"
                "   THEN excluded.deviceName ELSE usage_devices.deviceName END,"
                " updatedAt=MAX(usage_devices.updatedAt, excluded.updatedAt)",
                (record["deviceId"], record["deviceName"], max(record["updatedAt"], stamp)),
            )
            device_rows += 1
            for day in record["days"]:
                for app in day["apps"]:
                    cursor = con.execute(
                        "INSERT INTO usage_events_daily(deviceId, dayIso, packageName, appName,"
                        " firstSeen, lastSeen, totalTimeMs) VALUES(?,?,?,?,?,?,?)"
                        " ON CONFLICT(deviceId, dayIso, packageName) DO UPDATE SET"
                        " appName=CASE WHEN excluded.appName != ''"
                        "   THEN excluded.appName ELSE usage_events_daily.appName END,"
                        " firstSeen=MIN(usage_events_daily.firstSeen, excluded.firstSeen),"
                        " lastSeen=MAX(usage_events_daily.lastSeen, excluded.lastSeen),"
                        " totalTimeMs=MAX(usage_events_daily.totalTimeMs, excluded.totalTimeMs)",
                        (
                            record["deviceId"],
                            day["dayIso"],
                            app["packageName"],
                            app["appName"],
                            day["collectedAt"],
                            day["collectedAt"],
                            app["totalTimeMs"],
                        ),
                    )
                    entry_rows += max(cursor.rowcount, 0)
                day_rows += 1
        con.commit()
    return {"devices": device_rows, "days": day_rows, "entries": entry_rows}


def list_devices(con) -> list[dict[str, Any]]:
    rows = con.execute(
        "SELECT deviceId, deviceName FROM usage_devices ORDER BY deviceName COLLATE NOCASE ASC, deviceId ASC"
    ).fetchall()
    return [{"deviceId": str(r["deviceId"]), "deviceName": str(r["deviceName"])} for r in rows]


def overview(con, days: int = DEFAULT_OVERVIEW_DAYS, device_id: str | None = None) -> dict[str, Any]:
    """{days:[{dayIso,totalMs,topApps:[...]}], totalMs} over the last N days."""
    window = max(1, min(MAX_OVERVIEW_DAYS, int(days or DEFAULT_OVERVIEW_DAYS)))
    today = date.today()
    since = (today - timedelta(days=window - 1)).isoformat()
    filters = "WHERE dayIso >= ?"
    params: list[Any] = [since]
    if device_id:
        filters += " AND deviceId = ?"
        params.append(device_id)
    totals = {
        str(row["dayIso"])[:10]: int(row["totalMs"] or 0)
        for row in con.execute(
            f"SELECT dayIso, SUM(totalTimeMs) AS totalMs FROM usage_events_daily {filters}"
            " GROUP BY dayIso",
            params,
        ).fetchall()
    }
    out_days: list[dict[str, Any]] = []
    for offset in range(window - 1, -1, -1):
        day_iso = (today - timedelta(days=offset)).isoformat()
        top_params: list[Any] = [day_iso]
        top_filter = "WHERE dayIso = ?"
        if device_id:
            top_filter += " AND deviceId = ?"
            top_params.append(device_id)
        top_rows = con.execute(
            f"SELECT packageName, appName, SUM(totalTimeMs) AS totalMs FROM usage_events_daily"
            f" {top_filter} GROUP BY packageName ORDER BY totalMs DESC, packageName ASC"
            f" LIMIT {TOP_APPS_PER_DAY}",
            top_params,
        ).fetchall()
        out_days.append(
            {
                "dayIso": day_iso,
                "totalMs": totals.get(day_iso, 0),
                "topApps": [
                    {
                        "packageName": str(r["packageName"]),
                        "appName": str(r["appName"] or ""),
                        "totalTimeMs": int(r["totalMs"] or 0),
                    }
                    for r in top_rows
                ],
            }
        )
    return {"days": out_days, "totalMs": sum(totals.values())}
