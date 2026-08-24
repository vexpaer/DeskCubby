"""手机使用时间 usage service — Android usage import + daily projection.

Mirrors the JSON shapes of `UsageDeviceJsonCodec` / `UsageStatisticsJsonCodec`
(android `data/statistics/`) and stores device/history metadata in
`usage_devices` + `usage_days`, with app durations in `usage_events_daily`.

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

Upserts are idempotent and use Android's exact record merge: device metadata
is last-write-wins by `updatedAtEpochMillis`; histories merge per date, where
`FINAL` beats `OPEN` and a newer collection timestamp wins within one state.
"""
from __future__ import annotations

import json
import re
import time
import uuid
from datetime import date, timedelta
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from ..core.db import write_lock
from ..core.errors import ApiError

# Limits mirrored from StatisticsJsonCodecs.kt / UsageDeviceRepository.kt.
MAX_STATISTICS_JSON_BYTES = 10 * 1024 * 1024
MAX_USAGE_DEVICE_JSON_BYTES = MAX_STATISTICS_JSON_BYTES + 64 * 1024
MAX_USAGE_DEVICES = 64
MAX_STATISTICS_DAYS = 36_600
MAX_APPS_PER_DAY = 4_096
MAX_PACKAGE_NAME_CHARS = 255
MAX_DEVICE_NAME_CODE_POINTS = 80
MAX_FOREGROUND_MILLIS_PER_APP_DAY = 26 * 60 * 60 * 1000
MAX_ZONE_ID_CHARS = 128

_PLATFORM_RE = re.compile(r"[a-z][a-z0-9_-]{0,31}")
_FIXED_ZONE_RE = re.compile(
    r"(?:Z|(?:UTC|GMT|UT)(?:[+-](?:(?:0\d|1[0-7]):[0-5]\d|18:00))?|"
    r"[+-](?:(?:0\d|1[0-7]):[0-5]\d|18:00))"
)

TOP_APPS_PER_DAY = 5
DEFAULT_OVERVIEW_DAYS = 7
MAX_OVERVIEW_DAYS = 366


def now_ms() -> int:
    return int(time.time() * 1000)


def parse_usage_document(raw: bytes | str) -> list[dict[str, Any]]:
    """Decode any accepted upload shape into validated device records."""
    if isinstance(raw, bytes):
        if len(raw) > MAX_USAGE_DEVICE_JSON_BYTES:
            raise ApiError(413, "usage_too_large", "使用时间 JSON 超过设备记录大小上限")
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


def merge_android_usage_devices(con, records: list[dict[str, Any]]) -> None:
    """Merge canonical ``UsageDeviceJsonCodec`` records like Android.

    Device metadata is LWW, while histories merge independently by date:
    FINAL beats OPEN and then the newer collection timestamp wins. Legacy Web
    rows without `usage_days` metadata get one deterministic UTC projection.
    """
    if not records:
        return
    today_iso = date.today().isoformat()
    with write_lock(), con:
        for record in records:
            device_id = record["deviceId"]
            row = con.execute(
                "SELECT deviceName,platform,updatedAt,trackingStartedOn,"
                "backfillCompletedThrough FROM usage_devices WHERE deviceId=?",
                (device_id,),
            ).fetchone()
            incoming_updated = int(record.get("updatedAtEpochMillis") or 0)
            current_updated = int(row["updatedAt"] or 0) if row is not None else -1
            incoming_name = record.get("deviceName") or device_id
            incoming_platform = record.get("platform") or "android"
            history = record.get("history") or {}
            if row is None:
                con.execute(
                    "INSERT INTO usage_devices(deviceId,deviceName,isLocal,updatedAt,platform,"
                    "trackingStartedOn,backfillCompletedThrough) VALUES(?,?,0,?,?,?,?)",
                    (
                        device_id, incoming_name, incoming_updated, incoming_platform,
                        history.get("trackingStartedOn"),
                        history.get("backfillCompletedThrough"),
                    ),
                )
            else:
                con.execute(
                    "UPDATE usage_devices SET deviceName=?,platform=?,updatedAt=? WHERE deviceId=?",
                    (
                        incoming_name if incoming_updated >= current_updated else row["deviceName"],
                        incoming_platform if incoming_updated >= current_updated else row["platform"],
                        max(current_updated, incoming_updated),
                        device_id,
                    ),
                )

            for day in history.get("days") or []:
                day_iso = day["date"]
                incoming_collected = int(day.get("collectedAtEpochMillis") or 0)
                existing = con.execute(
                    "SELECT zoneId,state,collectedAt FROM usage_days "
                    "WHERE deviceId=? AND dayIso=?",
                    (device_id, day_iso),
                ).fetchone()
                if existing is None:
                    legacy = con.execute(
                        "SELECT MAX(lastSeen) AS collectedAt FROM usage_events_daily "
                        "WHERE deviceId=? AND dayIso=?",
                        (device_id, day_iso),
                    ).fetchone()
                    if legacy is not None and legacy["collectedAt"] is not None:
                        existing = {
                            "zoneId": "UTC",
                            "state": "OPEN" if day_iso == today_iso else "FINAL",
                            "collectedAt": int(legacy["collectedAt"]),
                        }
                        con.execute(
                            "INSERT OR IGNORE INTO usage_days(deviceId,dayIso,zoneId,state,collectedAt) "
                            "VALUES(?,?,?,?,?)",
                            (
                                device_id, day_iso, existing["zoneId"], existing["state"],
                                existing["collectedAt"],
                            ),
                        )
                if existing is not None:
                    current_rank = (
                        1 if existing["state"] == "FINAL" else 0,
                        int(existing["collectedAt"]),
                    )
                    incoming_rank = (
                        1 if day.get("state") == "FINAL" else 0,
                        incoming_collected,
                    )
                    # Kotlin maxWithOrNull selects the incoming (last) value on
                    # a fully equal comparison, so only a strictly lower rank loses.
                    if incoming_rank < current_rank:
                        continue

                con.execute(
                    "INSERT INTO usage_days(deviceId,dayIso,zoneId,state,collectedAt) "
                    "VALUES(?,?,?,?,?) ON CONFLICT(deviceId,dayIso) DO UPDATE SET "
                    "zoneId=excluded.zoneId,state=excluded.state,collectedAt=excluded.collectedAt",
                    (
                        device_id, day_iso, day.get("zoneId") or "UTC",
                        day.get("state") or ("OPEN" if day_iso == today_iso else "FINAL"),
                        incoming_collected,
                    ),
                )
                con.execute(
                    "DELETE FROM usage_events_daily WHERE deviceId = ? AND dayIso = ?",
                    (device_id, day_iso),
                )
                for app in day.get("apps") or []:
                    con.execute(
                        "INSERT OR REPLACE INTO usage_events_daily(deviceId, dayIso, packageName, appName, "
                        "firstSeen, lastSeen, totalTimeMs) VALUES(?,?,?,?,?,?,?)",
                        (
                            device_id,
                            day_iso,
                            app["packageName"],
                            app.get("appName") or app["packageName"],
                            incoming_collected,
                            incoming_collected,
                            int(app.get("foregroundMillis") or 0),
                        ),
                    )

            first_day = con.execute(
                "SELECT MIN(dayIso) FROM ("
                "SELECT dayIso FROM usage_days WHERE deviceId=? UNION ALL "
                "SELECT dayIso FROM usage_events_daily WHERE deviceId=?"
                ")",
                (device_id, device_id),
            ).fetchone()[0]
            stored = con.execute(
                "SELECT trackingStartedOn,backfillCompletedThrough FROM usage_devices "
                "WHERE deviceId=?",
                (device_id,),
            ).fetchone()
            tracking_candidates = [
                value for value in (
                    stored["trackingStartedOn"], history.get("trackingStartedOn"), first_day,
                ) if value
            ]
            backfill_candidates = [
                value for value in (
                    stored["backfillCompletedThrough"], history.get("backfillCompletedThrough"),
                ) if value
            ]
            newest_day = con.execute(
                "SELECT MAX(collectedAt) FROM usage_days WHERE deviceId=?", (device_id,)
            ).fetchone()[0]
            con.execute(
                "UPDATE usage_devices SET trackingStartedOn=?,backfillCompletedThrough=?,"
                "updatedAt=MAX(updatedAt,?) WHERE deviceId=?",
                (
                    min(tracking_candidates) if tracking_candidates else None,
                    max(backfill_candidates) if backfill_candidates else None,
                    int(newest_day or 0),
                    device_id,
                ),
            )


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
    if platform and not _PLATFORM_RE.fullmatch(platform):
        raise ApiError(400, "invalid_value", "设备平台无效")
    updated_at = _non_negative_int(item.get("updatedAtEpochMillis"), "updatedAtEpochMillis") or 0
    history = item.get("history") if isinstance(item.get("history"), dict) else item
    days_raw = history.get("days") if isinstance(history, dict) else None
    if not isinstance(days_raw, list):
        raise ApiError(400, "invalid_json", "使用时间设备缺少 days 数组")
    parsed_days = [_parse_day(day) for day in days_raw]
    days_by_date: dict[str, dict[str, Any]] = {}
    for day in parsed_days:
        current = days_by_date.get(day["date"])
        incoming_rank = (1 if day["state"] == "FINAL" else 0, day["collectedAtEpochMillis"])
        current_rank = (
            (1 if current["state"] == "FINAL" else 0, current["collectedAtEpochMillis"])
            if current is not None else None
        )
        if current_rank is None or incoming_rank >= current_rank:
            days_by_date[day["date"]] = day
    days = [days_by_date[key] for key in sorted(days_by_date)]
    for day in days:
        # updatedAt must not precede the newest history row (validateUsageDeviceRecord).
        updated_at = max(updated_at, day["collectedAtEpochMillis"])
    tracking = _optional_date(history.get("trackingStartedOn"), "trackingStartedOn")
    first_day = days[0]["date"] if days else None
    if first_day is not None and (tracking is None or first_day < tracking):
        tracking = first_day
    backfill = _optional_date(
        history.get("backfillCompletedThrough"), "backfillCompletedThrough"
    )
    return {
        "schemaVersion": 1,
        "deviceId": device_id,
        "deviceName": device_name,
        "platform": platform or "android",
        "updatedAtEpochMillis": updated_at,
        "history": {
            "schemaVersion": 4,
            "trackingStartedOn": tracking,
            "backfillCompletedThrough": backfill,
            "days": days,
        },
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
            ch.isspace() or ord(ch) < 0x20 or 0x7F <= ord(ch) <= 0x9F for ch in package
        ):
            raise ApiError(400, "invalid_json", "packageName 无效")
        millis = _first_int(
            entry,
            ("foregroundMillis", "totalTimeInMillis", "totalTimeMs", "timeInMillis"),
        )
        if millis is None or not (0 <= millis <= MAX_FOREGROUND_MILLIS_PER_APP_DAY):
            raise ApiError(400, "invalid_value", "前台时长超出允许范围")
        app_name = entry.get("appName")
        name = (
            "".join(ch for ch in app_name.strip() if not (ord(ch) < 0x20 or 0x7F <= ord(ch) <= 0x9F))[:200]
            if isinstance(app_name, str) else ""
        )
        existing = apps.get(package)
        if existing is None or millis > existing["totalTimeMs"]:
            apps[package] = {"packageName": package, "appName": name, "totalTimeMs": millis}
    collected = _non_negative_int(day.get("collectedAtEpochMillis"), "collectedAtEpochMillis") or 0
    raw_zone = day.get("zoneId")
    zone_id = raw_zone.strip() if isinstance(raw_zone, str) and raw_zone.strip() else "UTC"
    if len(zone_id) > MAX_ZONE_ID_CHARS:
        raise ApiError(400, "invalid_value", "使用时间时区无效")
    try:
        ZoneInfo(zone_id)
    except (ZoneInfoNotFoundError, ValueError):
        if not _FIXED_ZONE_RE.fullmatch(zone_id):
            raise ApiError(400, "invalid_value", "使用时间时区无效")
    raw_state = day.get("state")
    state = raw_state.strip().upper() if isinstance(raw_state, str) else ""
    if not state:
        state = "OPEN" if date_iso == date.today().isoformat() else "FINAL"
    if state not in {"OPEN", "FINAL"}:
        raise ApiError(400, "invalid_value", "使用时间日期状态无效")
    return {
        "date": date_iso,
        "zoneId": zone_id,
        "state": state,
        "collectedAtEpochMillis": collected,
        "apps": [
            {
                "packageName": app["packageName"],
                "foregroundMillis": app["totalTimeMs"],
                "appName": app["appName"],
            }
            for app in sorted(apps.values(), key=lambda a: a["packageName"])
        ],
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


def _optional_date(value: Any, field: str) -> str | None:
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    try:
        return _clean_date(value)
    except ApiError as exc:
        raise ApiError(400, "invalid_date", f"{field} 不是 ISO yyyy-MM-dd 日期") from exc


def _clean_device_id(value: Any) -> str:
    text = value.strip() if isinstance(value, str) else ""
    try:
        normalized = str(uuid.UUID(text))
    except (ValueError, AttributeError):
        raise ApiError(400, "invalid_value", "设备 ID 无效")
    return normalized


def _clean_device_name(value: Any) -> str:
    text = value.strip() if isinstance(value, str) else ""
    if not text:
        raise ApiError(400, "invalid_value", "设备名称不能为空")
    filtered = "".join(
        ch for ch in text if not (ord(ch) < 0x20 or 0x7F <= ord(ch) <= 0x9F)
    )
    if len(filtered) > MAX_DEVICE_NAME_CODE_POINTS:
        raise ApiError(400, "invalid_value", "设备名称过长")
    return filtered


# ---------------------------------------------------------------------------
# Persistence
# ---------------------------------------------------------------------------

def import_usage(con, records: list[dict[str, Any]]) -> dict[str, int]:
    """Merge validated upload records with Android's device/history semantics."""
    day_rows = sum(len((record.get("history") or {}).get("days") or []) for record in records)
    entry_rows = sum(
        len(day.get("apps") or [])
        for record in records
        for day in ((record.get("history") or {}).get("days") or [])
    )
    merge_android_usage_devices(con, records)
    return {"devices": len(records), "days": day_rows, "entries": entry_rows}


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
