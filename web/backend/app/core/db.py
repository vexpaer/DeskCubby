"""SQLite access. Table/column names mirror Room entities (android Entities.kt) exactly.

Room v16 is the reference schema; web adds app-private tables (settings kv, auth,
reader registry). Migrations are explicit and additive; destructive resets are forbidden.
"""
from __future__ import annotations

import sqlite3
import threading
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
  updatedAt INTEGER NOT NULL
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


def init_db() -> None:
    global _init_done
    with _write_lock:
        con = connect()
        try:
            con.executescript(SCHEMA_SQL)
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
