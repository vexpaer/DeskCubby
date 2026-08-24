"""SQLite access. Table/column names mirror Room entities (android Entities.kt) exactly.

Room v16 is the reference schema; web adds app-private tables (settings kv, auth,
reader registry). Migrations are explicit and additive; destructive resets are forbidden.
"""
from __future__ import annotations

import datetime as _dt
import re
import sqlite3
import threading
import uuid
from collections.abc import Iterator

from fastapi import Request

from .config import DB_PATH, ensure_dirs

_write_lock = threading.RLock()

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS flash_thoughts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  content TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  pinned INTEGER NOT NULL DEFAULT 0,
  deletedAt INTEGER,
  sortOrder INTEGER NOT NULL DEFAULT 0,
  categoryId INTEGER,
  highlighted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_flash_thoughts_category ON flash_thoughts(categoryId);
CREATE TABLE IF NOT EXISTS thought_categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL COLLATE NOCASE,
  colorArgb INTEGER NOT NULL,
  sortOrder INTEGER NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_thought_categories_name ON thought_categories(name);
CREATE TABLE IF NOT EXISTS browser_records (
  url TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  lastVisitedAt INTEGER NOT NULL,
  visitCount INTEGER NOT NULL DEFAULT 1,
  favorite INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS diary_index (
  uri TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  title TEXT NOT NULL,
  dateIso TEXT NOT NULL,
  monthKey TEXT NOT NULL,
  lastModified INTEGER NOT NULL,
  size INTEGER NOT NULL,
  wordCount INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  indexedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS date_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  icon TEXT NOT NULL,
  dateIso TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS poetry_categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL COLLATE NOCASE,
  colorArgb INTEGER NOT NULL,
  sortOrder INTEGER NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_poetry_categories_name ON poetry_categories(name);
CREATE TABLE IF NOT EXISTS saved_poems (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  content TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT '',
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  sortOrder INTEGER NOT NULL DEFAULT 0,
  categoryId INTEGER,
  FOREIGN KEY(categoryId) REFERENCES poetry_categories(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_saved_poems_category ON saved_poems(categoryId);
CREATE TABLE IF NOT EXISTS ai_conversations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  modelConfigId TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  syncId TEXT UNIQUE,
  deletedAt INTEGER
);
CREATE INDEX IF NOT EXISTS idx_ai_conversations_updated ON ai_conversations(updatedAt);
CREATE TABLE IF NOT EXISTS ai_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversationId INTEGER NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  reasoning TEXT NOT NULL,
  imageUri TEXT,
  imageMimeType TEXT,
  imagePermissionOwned INTEGER NOT NULL,
  createdAt INTEGER NOT NULL,
  syncId TEXT UNIQUE
);
CREATE INDEX IF NOT EXISTS idx_ai_messages_conv ON ai_messages(conversationId);
CREATE TABLE IF NOT EXISTS ai_attachments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  messageId INTEGER NOT NULL REFERENCES ai_messages(id) ON DELETE CASCADE,
  uri TEXT NOT NULL,
  mimeType TEXT NOT NULL,
  displayName TEXT NOT NULL,
  sizeBytes INTEGER NOT NULL,
  kind TEXT NOT NULL,
  extractedText TEXT,
  permissionOwned INTEGER NOT NULL,
  syncId TEXT UNIQUE
);
CREATE INDEX IF NOT EXISTS idx_ai_attachments_message ON ai_attachments(messageId);
-- Room v16 index_ai_attachments_uri (additive migration; the flash_thoughts
-- categoryId FOREIGN KEY ... ON DELETE SET NULL would need a table rebuild and is
-- intentionally NOT recreated here).
CREATE INDEX IF NOT EXISTS idx_ai_attachments_uri ON ai_attachments(uri);
CREATE TABLE IF NOT EXISTS agent_runs (
  runId TEXT PRIMARY KEY,
  conversationId INTEGER,
  conversationTitle TEXT NOT NULL,
  userRequestSummary TEXT NOT NULL,
  modelConfigId TEXT NOT NULL,
  permissionMode TEXT NOT NULL,
  enabledSourcesJson TEXT NOT NULL,
  status TEXT NOT NULL,
  modelCallCount INTEGER NOT NULL DEFAULT 0,
  usageReportedCallCount INTEGER NOT NULL DEFAULT 0,
  inputTokens INTEGER,
  outputTokens INTEGER,
  totalTokens INTEGER,
  cachedInputTokens INTEGER,
  cacheRateInputTokens INTEGER,
  reasoningTokens INTEGER,
  startedAt INTEGER NOT NULL,
  completedAt INTEGER
);
CREATE INDEX IF NOT EXISTS idx_agent_runs_conv ON agent_runs(conversationId);
-- Room v16 index_agent_runs_startedAt.
CREATE INDEX IF NOT EXISTS idx_agent_runs_started ON agent_runs(startedAt);
CREATE TABLE IF NOT EXISTS agent_tool_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  runId TEXT NOT NULL REFERENCES agent_runs(runId) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  toolCallId TEXT NOT NULL,
  toolName TEXT NOT NULL,
  classification TEXT NOT NULL,
  status TEXT NOT NULL,
  target TEXT NOT NULL,
  summary TEXT NOT NULL,
  argumentsSummary TEXT NOT NULL,
  resultSummary TEXT NOT NULL,
  errorCode TEXT,
  startedAt INTEGER NOT NULL,
  completedAt INTEGER,
  UNIQUE(runId, sequence)
);
CREATE TABLE IF NOT EXISTS agent_mutations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  runId TEXT NOT NULL REFERENCES agent_runs(runId) ON DELETE CASCADE,
  toolEventId INTEGER NOT NULL UNIQUE REFERENCES agent_tool_events(id) ON DELETE CASCADE,
  toolName TEXT NOT NULL,
  target TEXT NOT NULL,
  operation TEXT NOT NULL,
  summary TEXT NOT NULL,
  beforeContent TEXT NOT NULL,
  afterContent TEXT NOT NULL,
  undoPayload TEXT NOT NULL,
  status TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  undoneAt INTEGER
);
CREATE TABLE IF NOT EXISTS vault_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cipherText TEXT NOT NULL,
  iv TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  sortOrder INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS game_states (
  gameId TEXT PRIMARY KEY,
  highScore INTEGER NOT NULL DEFAULT 0,
  saveJson TEXT,
  updatedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS game_statistics (
  gameId TEXT NOT NULL,
  metricKey TEXT NOT NULL,
  value INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL,
  PRIMARY KEY (gameId, metricKey)
);
CREATE TABLE IF NOT EXISTS structured_record_files (
  sourceFile TEXT PRIMARY KEY,
  modifiedAt INTEGER NOT NULL,
  fileSize INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  parsedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS structured_record_occurrences (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  journalDay TEXT NOT NULL,
  sourceFile TEXT NOT NULL,
  sourceFileModifiedAt INTEGER NOT NULL,
  fieldId TEXT NOT NULL,
  rawValue TEXT NOT NULL,
  normalizedValue TEXT NOT NULL,
  valueType TEXT NOT NULL,
  orderInFile INTEGER NOT NULL,
  parsedAt INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sro_field_day ON structured_record_occurrences(fieldId, journalDay);
CREATE INDEX IF NOT EXISTS idx_sro_source ON structured_record_occurrences(sourceFile);
-- Room v16 index_structured_record_occurrences_journalDay.
CREATE INDEX IF NOT EXISTS idx_sro_journal_day ON structured_record_occurrences(journalDay);

-- Web app-private tables -------------------------------------------------
CREATE TABLE IF NOT EXISTS app_settings_kv (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_password (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  salt_hex TEXT NOT NULL,
  hash_hex TEXT NOT NULL,
  iterations INTEGER NOT NULL,
  createdAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_sessions (
  tokenHash TEXT PRIMARY KEY,
  createdAt INTEGER NOT NULL,
  expiresAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS reader_books (
  id TEXT PRIMARY KEY,
  fileName TEXT NOT NULL,
  bookType TEXT NOT NULL,
  title TEXT NOT NULL,
  sizeBytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  addedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS usage_devices (
  deviceId TEXT PRIMARY KEY,
  deviceName TEXT NOT NULL,
  isLocal INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL,
  platform TEXT NOT NULL DEFAULT 'web',
  trackingStartedOn TEXT,
  backfillCompletedThrough TEXT
);
CREATE TABLE IF NOT EXISTS usage_days (
  deviceId TEXT NOT NULL,
  dayIso TEXT NOT NULL,
  zoneId TEXT NOT NULL DEFAULT 'UTC',
  state TEXT NOT NULL DEFAULT 'OPEN',
  collectedAt INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (deviceId, dayIso),
  FOREIGN KEY (deviceId) REFERENCES usage_devices(deviceId) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS usage_events_daily (
  deviceId TEXT NOT NULL,
  dayIso TEXT NOT NULL,
  packageName TEXT NOT NULL,
  appName TEXT NOT NULL DEFAULT '',
  firstSeen INTEGER NOT NULL,
  lastSeen INTEGER NOT NULL,
  totalTimeMs INTEGER NOT NULL,
  PRIMARY KEY (deviceId, dayIso, packageName)
);
CREATE TABLE IF NOT EXISTS health_days (
  dayIso TEXT PRIMARY KEY,
  steps INTEGER NOT NULL DEFAULT 0,
  distanceMeters REAL NOT NULL DEFAULT 0,
  activeCaloriesKcal REAL NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS cloud_sync_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  lastResultJson TEXT,
  undoSnapshotJson TEXT
);
"""


def connect() -> sqlite3.Connection:
    ensure_dirs()
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH, timeout=30, check_same_thread=False)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("PRAGMA busy_timeout=30000")
    return con


_init_done = False

_LEGACY_USAGE_DEVICE_NAMESPACE = uuid.UUID("42f4a6dd-55df-4cd2-8fcf-b346347c9b73")
_USAGE_PLATFORM_RE = re.compile(r"[a-z][a-z0-9_-]{0,31}")


def _canonical_usage_device_id(raw: str) -> str:
    value = str(raw).strip()
    try:
        return str(uuid.UUID(value))
    except (ValueError, AttributeError):
        # Old Web imports accepted arbitrary non-whitespace IDs. Map them to a
        # stable UUID so Android UsageDeviceJsonCodec can consume the record
        # without losing or repeatedly duplicating its history.
        return str(uuid.uuid5(_LEGACY_USAGE_DEVICE_NAMESPACE, value))


def _canonical_usage_device_name(raw: str, fallback: str) -> str:
    value = "".join(
        ch for ch in str(raw).strip()
        if not (ord(ch) < 32 or 127 <= ord(ch) <= 159)
    )[:80]
    return value or fallback[:80] or "Web device"


def _table_exists(con: sqlite3.Connection, table_name: str) -> bool:
    return con.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table_name,)
    ).fetchone() is not None


def _canonical_usage_date(raw: object) -> str | None:
    value = str(raw).strip() if raw is not None else ""
    try:
        parsed = _dt.date.fromisoformat(value)
    except ValueError:
        return None
    return value if parsed.isoformat() == value else None


def _ensure_usage_schema(con: sqlite3.Connection) -> None:
    """Add Android UsageDevice metadata without destructively rebuilding tables."""
    columns = {str(row["name"]) for row in con.execute("PRAGMA table_info(usage_devices)")}
    if "platform" not in columns:
        con.execute("ALTER TABLE usage_devices ADD COLUMN platform TEXT NOT NULL DEFAULT 'web'")
    if "trackingStartedOn" not in columns:
        con.execute("ALTER TABLE usage_devices ADD COLUMN trackingStartedOn TEXT")
    if "backfillCompletedThrough" not in columns:
        con.execute("ALTER TABLE usage_devices ADD COLUMN backfillCompletedThrough TEXT")

    today_iso = _dt.date.today().isoformat()
    con.execute(
        "INSERT OR IGNORE INTO usage_days(deviceId,dayIso,zoneId,state,collectedAt) "
        "SELECT deviceId,dayIso,'UTC',CASE WHEN dayIso=? THEN 'OPEN' ELSE 'FINAL' END,MAX(lastSeen) "
        "FROM usage_events_daily GROUP BY deviceId,dayIso",
        (today_iso,),
    )
    for row in con.execute(
        "SELECT deviceId,deviceName,platform,trackingStartedOn,backfillCompletedThrough "
        "FROM usage_devices"
    ).fetchall():
        device_id = str(row["deviceId"])
        name = _canonical_usage_device_name(str(row["deviceName"]), device_id)
        platform = str(row["platform"] or "web").strip().lower()
        if not _USAGE_PLATFORM_RE.fullmatch(platform):
            platform = "web"
        first_day = con.execute(
            "SELECT MIN(dayIso) FROM usage_days WHERE deviceId=?", (device_id,)
        ).fetchone()[0]
        tracking = _canonical_usage_date(row["trackingStartedOn"])
        if first_day is not None and (tracking is None or str(first_day) < tracking):
            tracking = str(first_day)
        backfill = _canonical_usage_date(row["backfillCompletedThrough"])
        con.execute(
            "UPDATE usage_devices SET deviceName=?,platform=?,trackingStartedOn=?,"
            "backfillCompletedThrough=? WHERE deviceId=?",
            (name, platform, tracking, backfill, device_id),
        )


def _migrate_legacy_usage_device_ids(con: sqlite3.Connection) -> None:
    """Transactionally canonicalize old Web-only usage IDs and child rows."""
    device_columns = {
        str(row["name"]) for row in con.execute("PRAGMA table_info(usage_devices)").fetchall()
    }
    has_usage_days = _table_exists(con, "usage_days")
    select_columns = ["deviceId", "deviceName", "isLocal", "updatedAt"]
    select_columns.extend(
        column for column in ("platform", "trackingStartedOn", "backfillCompletedThrough")
        if column in device_columns
    )
    devices = con.execute(
        f"SELECT {','.join(select_columns)} FROM usage_devices ORDER BY deviceId"
    ).fetchall()
    for device in devices:
        old_id = str(device["deviceId"])
        new_id = _canonical_usage_device_id(old_id)
        if new_id == old_id:
            continue

        old_events = con.execute(
            "SELECT dayIso,packageName,appName,firstSeen,lastSeen,totalTimeMs "
            "FROM usage_events_daily WHERE deviceId = ?",
            (old_id,),
        ).fetchall()
        old_days = (
            con.execute(
                "SELECT dayIso,zoneId,state,collectedAt FROM usage_days WHERE deviceId=?",
                (old_id,),
            ).fetchall()
            if has_usage_days else []
        )

        current = con.execute(
            f"SELECT {','.join(select_columns[1:])} FROM usage_devices WHERE deviceId = ?",
            (new_id,),
        ).fetchone()
        incoming_platform = str(device["platform"] or "web") if "platform" in device_columns else "web"
        incoming_tracking = (
            _canonical_usage_date(device["trackingStartedOn"])
            if "trackingStartedOn" in device_columns else None
        )
        incoming_backfill = (
            _canonical_usage_date(device["backfillCompletedThrough"])
            if "backfillCompletedThrough" in device_columns else None
        )
        if current is None:
            insert_columns = select_columns
            values: list[object] = [
                new_id, device["deviceName"], device["isLocal"], device["updatedAt"]
            ]
            if "platform" in device_columns:
                values.append(incoming_platform)
            if "trackingStartedOn" in device_columns:
                values.append(incoming_tracking)
            if "backfillCompletedThrough" in device_columns:
                values.append(incoming_backfill)
            placeholders = ",".join("?" for _ in insert_columns)
            con.execute(
                f"INSERT INTO usage_devices({','.join(insert_columns)}) VALUES({placeholders})",
                values,
            )
        else:
            incoming_is_newer = int(device["updatedAt"]) >= int(current["updatedAt"])
            assignments = ["deviceName=?", "isLocal=?", "updatedAt=?"]
            values = [
                device["deviceName"] if incoming_is_newer else current["deviceName"],
                max(int(current["isLocal"]), int(device["isLocal"])),
                max(int(current["updatedAt"]), int(device["updatedAt"])),
            ]
            if "platform" in device_columns:
                assignments.append("platform=?")
                values.append(incoming_platform if incoming_is_newer else current["platform"])
            if "trackingStartedOn" in device_columns:
                assignments.append("trackingStartedOn=?")
                values.append(min(filter(None, [
                    _canonical_usage_date(current["trackingStartedOn"]), incoming_tracking,
                ]), default=None))
            if "backfillCompletedThrough" in device_columns:
                assignments.append("backfillCompletedThrough=?")
                values.append(max(filter(None, [
                    _canonical_usage_date(current["backfillCompletedThrough"]), incoming_backfill,
                ]), default=None))
            values.append(new_id)
            con.execute(
                f"UPDATE usage_devices SET {','.join(assignments)} WHERE deviceId=?", values
            )

        if has_usage_days:
            old_events_by_day: dict[str, list[sqlite3.Row]] = {}
            for event in old_events:
                old_events_by_day.setdefault(str(event["dayIso"]), []).append(event)
            for day in old_days:
                day_iso = str(day["dayIso"])
                existing_day = con.execute(
                    "SELECT zoneId,state,collectedAt FROM usage_days "
                    "WHERE deviceId=? AND dayIso=?",
                    (new_id, day_iso),
                ).fetchone()
                incoming_rank = (1 if day["state"] == "FINAL" else 0, int(day["collectedAt"]))
                current_rank = (
                    (1 if existing_day["state"] == "FINAL" else 0, int(existing_day["collectedAt"]))
                    if existing_day is not None else None
                )
                if current_rank is None or incoming_rank >= current_rank:
                    con.execute(
                        "INSERT INTO usage_days(deviceId,dayIso,zoneId,state,collectedAt) "
                        "VALUES(?,?,?,?,?) ON CONFLICT(deviceId,dayIso) DO UPDATE SET "
                        "zoneId=excluded.zoneId,state=excluded.state,collectedAt=excluded.collectedAt",
                        (new_id, day_iso, day["zoneId"], day["state"], day["collectedAt"]),
                    )
                    con.execute(
                        "DELETE FROM usage_events_daily WHERE deviceId=? AND dayIso=?",
                        (new_id, day_iso),
                    )
                    for event in old_events_by_day.pop(day_iso, []):
                        con.execute(
                            "INSERT INTO usage_events_daily(deviceId,dayIso,packageName,appName,"
                            "firstSeen,lastSeen,totalTimeMs) VALUES(?,?,?,?,?,?,?)",
                            (
                                new_id, event["dayIso"], event["packageName"], event["appName"],
                                event["firstSeen"], event["lastSeen"], event["totalTimeMs"],
                            ),
                        )
                else:
                    old_events_by_day.pop(day_iso, None)
            # A partially upgraded database can contain event rows whose day
            # metadata was never written. Preserve them with the legacy merge.
            old_events = [event for events in old_events_by_day.values() for event in events]

        for event in old_events:
            existing = con.execute(
                "SELECT appName,firstSeen,lastSeen,totalTimeMs FROM usage_events_daily "
                "WHERE deviceId=? AND dayIso=? AND packageName=?",
                (new_id, event["dayIso"], event["packageName"]),
            ).fetchone()
            if existing is None:
                con.execute(
                    "INSERT INTO usage_events_daily(deviceId,dayIso,packageName,appName,"
                    "firstSeen,lastSeen,totalTimeMs) VALUES(?,?,?,?,?,?,?)",
                    (
                        new_id, event["dayIso"], event["packageName"], event["appName"],
                        event["firstSeen"], event["lastSeen"], event["totalTimeMs"],
                    ),
                )
            else:
                con.execute(
                    "UPDATE usage_events_daily SET appName=?,firstSeen=?,lastSeen=?,totalTimeMs=? "
                    "WHERE deviceId=? AND dayIso=? AND packageName=?",
                    (
                        existing["appName"] or event["appName"],
                        min(int(existing["firstSeen"]), int(event["firstSeen"])),
                        max(int(existing["lastSeen"]), int(event["lastSeen"])),
                        max(int(existing["totalTimeMs"]), int(event["totalTimeMs"])),
                        new_id, event["dayIso"], event["packageName"],
                    ),
                )

        con.execute("DELETE FROM usage_events_daily WHERE deviceId = ?", (old_id,))
        if has_usage_days:
            con.execute("DELETE FROM usage_days WHERE deviceId = ?", (old_id,))
        con.execute("DELETE FROM usage_devices WHERE deviceId = ?", (old_id,))


def init_db() -> None:
    global _init_done
    with _write_lock:
        con = connect()
        try:
            con.executescript(SCHEMA_SQL)
            _ensure_usage_schema(con)
            _migrate_legacy_usage_device_ids(con)
            con.commit()
        finally:
            con.close()
    _init_done = True


def get_db(request: Request) -> Iterator[sqlite3.Connection]:
    con = getattr(request.app.state, "db", None)
    if con is None:  # pragma: no cover - startup always installs it
        con = connect()
    yield con


def write_lock():
    """Serialize multi-step writes across threads."""
    return _write_lock
