use std::{
    collections::{BTreeMap, HashSet},
    fs,
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use chrono::NaiveDate;
use rusqlite::{Connection, OptionalExtension, Row, Transaction, params};
use thiserror::Error;
use uuid::Uuid;

use crate::games::{self, GameBackupState, GameBackupStatistic};
use crate::models::{
    CompatibilityShadow, CoreSnapshot, DateRecord, DateRecordDraft, LocalPaths, ManagedSettings,
    PoetryCategory, PoetryCategoryDraft, SavedPoem, SavedPoemDraft, Thought, ThoughtCategory,
    ThoughtCategoryDraft, ThoughtDraft,
};

const SCHEMA_VERSION: i32 = 6;
const RECOVERY_SNAPSHOT_VERSION: i32 = 1;
const BUSY_TIMEOUT: Duration = Duration::from_secs(5);
// Android v27 accepts backup documents up to 64 MiB. DPAPI ciphertext adds a
// small envelope, so keep the database boundary slightly above that wire
// limit while remaining bounded.
const MAX_SHADOW_BYTES: usize = 65 * 1024 * 1024;
const MAX_VAULT_CIPHERTEXT_BYTES: usize = 1024 * 1024;
const MAX_CLOUD_SECRET_BYTES: usize = 64 * 1024;
const MAX_CLOUD_BASE_ENTRIES: usize = 10_000;
const MAX_CLOUD_BASE_JSON_BYTES: usize = 4 * 1024 * 1024;

#[derive(Debug, Error)]
pub enum DataError {
    #[error("DATABASE_ERROR")]
    Sqlite(#[source] rusqlite::Error),
    #[error("DATABASE_IO_ERROR")]
    Io(#[source] std::io::Error),
    #[error("DATABASE_JSON_ERROR")]
    Json(#[source] serde_json::Error),
    #[error("VALIDATION_ERROR: {0}")]
    Validation(String),
    #[error("NOT_FOUND")]
    NotFound,
    #[error("UNSUPPORTED_DATABASE_VERSION")]
    UnsupportedVersion,
}

impl From<rusqlite::Error> for DataError {
    fn from(value: rusqlite::Error) -> Self {
        Self::Sqlite(value)
    }
}

impl From<std::io::Error> for DataError {
    fn from(value: std::io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<serde_json::Error> for DataError {
    fn from(value: serde_json::Error) -> Self {
        Self::Json(value)
    }
}

#[derive(Debug, Clone)]
pub struct Database {
    path: PathBuf,
}

/// Password-derived vault metadata. The generation ID binds every encrypted
/// item to exactly one password/key generation and is never reused.
#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct VaultMetadataRecord {
    pub crypto_version: i64,
    pub generation_id: String,
    pub revision: i64,
    pub salt: Vec<u8>,
    pub kdf_iterations: i64,
    pub verifier_ciphertext: Vec<u8>,
    pub verifier_nonce: Vec<u8>,
    pub created_at: i64,
    pub updated_at: i64,
}

/// An encrypted vault row. Plaintext content and notes never enter SQLite.
#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct VaultItemRecord {
    pub id: i64,
    pub generation_id: String,
    pub ciphertext: Vec<u8>,
    pub nonce: Vec<u8>,
    pub created_at: i64,
    pub updated_at: i64,
    pub sort_order: i64,
}

#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct CloudSyncSettingsRecord {
    pub automatic_sync_enabled: bool,
    pub interval_minutes: i64,
    /// False until Windows explicitly creates, edits, copies, or deletes a
    /// cloud configuration. While false, v27 export preserves Android's
    /// compatibility-shadow cloud metadata verbatim.
    pub configs_managed: bool,
    pub updated_at: i64,
}

/// Windows-only sync configuration. This is deliberately separate from
/// ManagedSettings so Android v27 cloud metadata remains shadow-preserved.
#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct CloudSyncConfigRecord {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub service_type: String,
    pub endpoint_url: String,
    pub remote_path: String,
    pub user_agent: String,
    pub webdav_username: String,
    pub s3_bucket: String,
    pub s3_region: String,
    pub s3_path_style: bool,
    pub allow_insecure_http: bool,
    pub selected_contents_json: String,
    pub direction: String,
    pub sort_order: i64,
    pub updated_at: i64,
}

/// Opaque DPAPI output plus a SHA-256 binding supplied by the cloud service.
/// No plaintext password, access key, secret key, or session token is stored.
#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct CloudSyncSecretRecord {
    pub config_id: String,
    pub dpapi_ciphertext: Vec<u8>,
    pub binding_sha256: String,
    pub updated_at: i64,
}

#[allow(dead_code)]
pub(crate) enum CloudSyncSecretMutation {
    Preserve,
    Replace(CloudSyncSecretRecord),
    Clear,
}

#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct CloudSyncBaseStateRecord {
    pub config_id: String,
    pub scope_fingerprint: String,
    pub hashes_by_key: BTreeMap<String, String>,
    pub updated_at: i64,
}

/// Rebuildable progress/status only. It intentionally contains no endpoint,
/// local path, remote object key, or credential material.
#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct CloudSyncStatusRecord {
    pub config_id: String,
    pub state: String,
    pub run_token: Option<String>,
    pub last_started_at: Option<i64>,
    pub last_completed_at: Option<i64>,
    pub last_success_at: Option<i64>,
    pub last_error_code: Option<String>,
    pub uploaded_count: i64,
    pub downloaded_count: i64,
    pub conflict_count: i64,
    pub transferred_bytes: i64,
    pub updated_at: i64,
}

#[allow(dead_code)]
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct UpdateSettingsRecord {
    pub automatic_checks_enabled: bool,
    pub last_attempted_at: Option<i64>,
    pub updated_at: i64,
}

impl Database {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, DataError> {
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let database = Self { path };
        let mut connection = database.connect()?;
        database.migrate(&mut connection)?;
        database.ensure_defaults(&connection)?;
        Ok(database)
    }

    pub(crate) fn connect(&self) -> Result<Connection, DataError> {
        let connection = Connection::open(&self.path)?;
        connection.busy_timeout(BUSY_TIMEOUT)?;
        connection.pragma_update(None, "foreign_keys", true)?;
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.pragma_update(None, "synchronous", "NORMAL")?;
        Ok(connection)
    }

    fn migrate(&self, connection: &mut Connection) -> Result<(), DataError> {
        let mut version: i32 =
            connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
        if version > SCHEMA_VERSION {
            return Err(DataError::UnsupportedVersion);
        }
        while version < SCHEMA_VERSION {
            match version {
                0 => Self::migrate_0_to_1(connection)?,
                1 => Self::migrate_1_to_2(connection)?,
                2 => Self::migrate_2_to_3(connection)?,
                3 => Self::migrate_3_to_4(connection)?,
                4 => Self::migrate_4_to_5(connection)?,
                5 => Self::migrate_5_to_6(connection)?,
                _ => return Err(DataError::UnsupportedVersion),
            }
            version = connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
        }
        Ok(())
    }

    /// Adds Android v27 poetry categories and deterministic ordering without
    /// disturbing any poem created by an earlier Windows release. Every step,
    /// including the version bump, is committed as one transaction.
    fn migrate_5_to_6(connection: &mut Connection) -> Result<(), DataError> {
        let transaction = connection.transaction()?;
        transaction.execute_batch(
            r#"
            CREATE TABLE poetry_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE
                    CHECK (length(trim(name)) BETWEEN 1 AND 100),
                color_argb INTEGER NOT NULL,
                sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
                created_at INTEGER NOT NULL CHECK (created_at >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= created_at)
            );
            CREATE INDEX poetry_categories_order_idx
                ON poetry_categories(sort_order, created_at, id);

            ALTER TABLE saved_poems
                ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0);
            ALTER TABLE saved_poems
                ADD COLUMN category_id INTEGER DEFAULT NULL
                    REFERENCES poetry_categories(id) ON DELETE SET NULL;

            UPDATE saved_poems AS poem
            SET sort_order = (
                SELECT COUNT(*)
                FROM saved_poems AS preceding
                WHERE preceding.updated_at > poem.updated_at
                   OR (preceding.updated_at = poem.updated_at AND preceding.id > poem.id)
            );
            CREATE INDEX saved_poems_category_idx ON saved_poems(category_id);
            CREATE INDEX saved_poems_order_idx
                ON saved_poems(sort_order, created_at DESC, id DESC);
            "#,
        )?;
        transaction.pragma_update(None, "user_version", 6)?;
        transaction.commit()?;
        Ok(())
    }

    fn migrate_0_to_1(connection: &mut Connection) -> Result<(), DataError> {
        let transaction = connection.transaction()?;
        transaction.execute_batch(
            r#"
                CREATE TABLE app_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    managed_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
                );

                CREATE TABLE local_paths (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    diary_path TEXT,
                    media_path TEXT,
                    backup_path TEXT
                );

                CREATE TABLE thought_categories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    color_argb INTEGER NOT NULL,
                    sort_order INTEGER NOT NULL,
                    created_at INTEGER NOT NULL CHECK (created_at >= 0),
                    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at)
                );

                CREATE TABLE thoughts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL CHECK (created_at >= 0),
                    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
                    pinned INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
                    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    category_id INTEGER,
                    highlighted INTEGER NOT NULL DEFAULT 0 CHECK (highlighted IN (0, 1)),
                    FOREIGN KEY (category_id) REFERENCES thought_categories(id)
                        ON DELETE SET NULL
                );
                CREATE INDEX thoughts_category_id_idx ON thoughts(category_id);
                CREATE INDEX thoughts_active_order_idx
                    ON thoughts(deleted_at, pinned DESC, sort_order, updated_at DESC);

                CREATE TABLE date_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    icon TEXT NOT NULL,
                    date_iso TEXT NOT NULL,
                    created_at INTEGER NOT NULL CHECK (created_at >= 0),
                    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at)
                );
                CREATE INDEX date_records_date_idx ON date_records(date_iso);

                CREATE TABLE saved_poems (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    content TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL CHECK (created_at >= 0),
                    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at)
                );
                CREATE INDEX saved_poems_updated_idx ON saved_poems(updated_at DESC);

                CREATE TABLE compatibility_shadow (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    ciphertext BLOB NOT NULL,
                    source_sha256 TEXT NOT NULL,
                    imported_at INTEGER NOT NULL CHECK (imported_at >= 0)
                );
                "#,
        )?;
        transaction.pragma_update(None, "user_version", 1)?;
        transaction.commit()?;
        Ok(())
    }

    /// Schema v2 is intentionally one transaction. A failure at any statement
    /// leaves both the v1 data and `user_version = 1` unchanged.
    fn migrate_1_to_2(connection: &mut Connection) -> Result<(), DataError> {
        let transaction = connection.transaction()?;
        transaction.execute_batch(
            r#"
            CREATE TABLE vault_metadata (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                crypto_version INTEGER NOT NULL CHECK (crypto_version >= 1),
                generation_id TEXT NOT NULL UNIQUE
                    CHECK (length(generation_id) BETWEEN 1 AND 80),
                revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
                salt BLOB NOT NULL
                    CHECK (typeof(salt) = 'blob' AND length(salt) BETWEEN 16 AND 64),
                kdf_iterations INTEGER NOT NULL
                    CHECK (kdf_iterations BETWEEN 10000 AND 10000000),
                verifier_ciphertext BLOB NOT NULL
                    CHECK (
                        typeof(verifier_ciphertext) = 'blob'
                        AND length(verifier_ciphertext) BETWEEN 1 AND 4096
                    ),
                verifier_nonce BLOB NOT NULL
                    CHECK (
                        typeof(verifier_nonce) = 'blob'
                        AND length(verifier_nonce) BETWEEN 12 AND 32
                    ),
                created_at INTEGER NOT NULL CHECK (created_at >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= created_at)
            );

            CREATE TABLE vault_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                generation_id TEXT NOT NULL,
                ciphertext BLOB NOT NULL
                    CHECK (
                        typeof(ciphertext) = 'blob'
                        AND length(ciphertext) BETWEEN 1 AND 1048576
                    ),
                nonce BLOB NOT NULL
                    CHECK (
                        typeof(nonce) = 'blob'
                        AND length(nonce) BETWEEN 12 AND 32
                    ),
                created_at INTEGER NOT NULL CHECK (created_at >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
                sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
                FOREIGN KEY (generation_id) REFERENCES vault_metadata(generation_id)
                    ON DELETE CASCADE
            );
            CREATE INDEX vault_items_order_idx
                ON vault_items(sort_order, updated_at DESC, id DESC);

            CREATE TABLE cloud_sync_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                automatic_sync_enabled INTEGER NOT NULL DEFAULT 0
                    CHECK (automatic_sync_enabled IN (0, 1)),
                interval_minutes INTEGER NOT NULL DEFAULT 360
                    CHECK (interval_minutes BETWEEN 15 AND 10080),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
            );

            CREATE TABLE cloud_sync_configs (
                id TEXT PRIMARY KEY CHECK (length(id) BETWEEN 1 AND 80),
                name TEXT NOT NULL CHECK (
                    length(trim(name)) BETWEEN 1 AND 200
                ),
                enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
                service_type TEXT NOT NULL CHECK (
                    service_type IN ('WEBDAV', 'S3_COMPATIBLE')
                ),
                endpoint_url TEXT NOT NULL CHECK (
                    length(endpoint_url) BETWEEN 1 AND 8192
                ),
                remote_path TEXT NOT NULL CHECK (length(remote_path) <= 1024),
                webdav_username TEXT NOT NULL CHECK (length(webdav_username) <= 1024),
                s3_bucket TEXT NOT NULL CHECK (length(s3_bucket) <= 255),
                s3_region TEXT NOT NULL CHECK (length(s3_region) <= 128),
                allow_insecure_http INTEGER NOT NULL
                    CHECK (allow_insecure_http IN (0, 1)),
                selected_contents_json TEXT NOT NULL CHECK (
                    length(selected_contents_json) BETWEEN 2 AND 2048
                ),
                direction TEXT NOT NULL CHECK (
                    direction IN ('UPLOAD_ONLY', 'TWO_WAY')
                ),
                sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
            );
            CREATE INDEX cloud_sync_configs_order_idx
                ON cloud_sync_configs(sort_order, id);

            CREATE TABLE cloud_sync_secrets (
                config_id TEXT PRIMARY KEY,
                dpapi_ciphertext BLOB NOT NULL CHECK (
                    typeof(dpapi_ciphertext) = 'blob'
                    AND length(dpapi_ciphertext) BETWEEN 1 AND 65536
                ),
                binding_sha256 TEXT NOT NULL CHECK (length(binding_sha256) = 64),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
                FOREIGN KEY (config_id) REFERENCES cloud_sync_configs(id)
                    ON DELETE CASCADE
            );

            CREATE TABLE cloud_sync_base (
                config_id TEXT PRIMARY KEY,
                scope_fingerprint TEXT NOT NULL CHECK (length(scope_fingerprint) = 64),
                hashes_json TEXT NOT NULL CHECK (
                    length(hashes_json) BETWEEN 2 AND 4194304
                ),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
                FOREIGN KEY (config_id) REFERENCES cloud_sync_configs(id)
                    ON DELETE CASCADE
            );

            CREATE TABLE cloud_sync_status (
                config_id TEXT PRIMARY KEY,
                state TEXT NOT NULL CHECK (
                    state IN ('IDLE', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                ),
                run_token TEXT CHECK (
                    run_token IS NULL OR length(run_token) BETWEEN 1 AND 80
                ),
                last_started_at INTEGER CHECK (
                    last_started_at IS NULL OR last_started_at >= 0
                ),
                last_completed_at INTEGER CHECK (
                    last_completed_at IS NULL OR last_completed_at >= 0
                ),
                last_success_at INTEGER CHECK (
                    last_success_at IS NULL OR last_success_at >= 0
                ),
                last_error_code TEXT CHECK (
                    last_error_code IS NULL OR length(last_error_code) BETWEEN 1 AND 80
                ),
                uploaded_count INTEGER NOT NULL DEFAULT 0 CHECK (uploaded_count >= 0),
                downloaded_count INTEGER NOT NULL DEFAULT 0 CHECK (downloaded_count >= 0),
                conflict_count INTEGER NOT NULL DEFAULT 0 CHECK (conflict_count >= 0),
                transferred_bytes INTEGER NOT NULL DEFAULT 0 CHECK (transferred_bytes >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
                FOREIGN KEY (config_id) REFERENCES cloud_sync_configs(id)
                    ON DELETE CASCADE
            );

            CREATE TABLE update_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                automatic_checks_enabled INTEGER NOT NULL DEFAULT 1
                    CHECK (automatic_checks_enabled IN (0, 1)),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
            );
            "#,
        )?;
        transaction.pragma_update(None, "user_version", 2)?;
        transaction.commit()?;
        Ok(())
    }

    /// Records whether Windows has taken ownership of the credential-free
    /// `cloudSyncConfigs` field in v27 backups. The default deliberately keeps
    /// imported Android metadata shadow-owned until the user edits cloud
    /// configuration on Windows.
    fn migrate_2_to_3(connection: &mut Connection) -> Result<(), DataError> {
        let transaction = connection.transaction()?;
        transaction.execute_batch(
            r#"
            ALTER TABLE cloud_sync_settings
                ADD COLUMN configs_managed INTEGER NOT NULL DEFAULT 0
                    CHECK (configs_managed IN (0, 1));
            "#,
        )?;
        transaction.pragma_update(None, "user_version", 3)?;
        transaction.commit()?;
        Ok(())
    }

    /// Persists the last automatic updater attempt separately from the
    /// user-visible opt-in preference. A nullable value means no network
    /// attempt has been made by this Windows profile yet.
    fn migrate_3_to_4(connection: &mut Connection) -> Result<(), DataError> {
        let transaction = connection.transaction()?;
        transaction.execute_batch(
            r#"
            ALTER TABLE update_settings
                ADD COLUMN last_attempted_at INTEGER
                    CHECK (last_attempted_at IS NULL OR last_attempted_at >= 0);
            "#,
        )?;
        transaction.pragma_update(None, "user_version", 4)?;
        transaction.commit()?;
        Ok(())
    }

    /// Adds Windows-private feature tables in one transaction. Additional v5
    /// fragments are kept here so any failure leaves the v4 database and its
    /// `user_version` untouched.
    fn migrate_4_to_5(connection: &mut Connection) -> Result<(), DataError> {
        let transaction = connection.transaction()?;
        transaction.execute_batch(
            "ALTER TABLE cloud_sync_configs
                 ADD COLUMN user_agent TEXT NOT NULL DEFAULT 'DeskCubby-Sync/1'
                     CHECK(length(user_agent) BETWEEN 1 AND 512);
             ALTER TABLE cloud_sync_configs
                 ADD COLUMN s3_path_style INTEGER NOT NULL DEFAULT 1
                     CHECK(s3_path_style IN (0, 1));",
        )?;
        crate::notes::migrate(&transaction)?;
        crate::ai::migrate(&transaction)?;
        crate::games::migrate(&transaction)?;
        transaction.pragma_update(None, "user_version", 5)?;
        transaction.commit()?;
        Ok(())
    }

    fn ensure_defaults(&self, connection: &Connection) -> Result<(), DataError> {
        let settings_json = serde_json::to_string(&ManagedSettings::default())?;
        connection.execute(
            "INSERT OR IGNORE INTO app_settings(id, managed_json, updated_at) VALUES(1, ?1, ?2)",
            params![settings_json, now_millis()],
        )?;
        connection.execute(
            "INSERT OR IGNORE INTO local_paths(id, diary_path, media_path, backup_path)
             VALUES(1, NULL, NULL, NULL)",
            [],
        )?;
        connection.execute(
            "INSERT OR IGNORE INTO cloud_sync_settings(
                id, automatic_sync_enabled, interval_minutes, configs_managed, updated_at
             ) VALUES(1, 0, 360, 0, ?1)",
            params![now_millis()],
        )?;
        connection.execute(
            "INSERT OR IGNORE INTO update_settings(
                id, automatic_checks_enabled, updated_at
             ) VALUES(1, 1, ?1)",
            params![now_millis()],
        )?;
        Ok(())
    }

    pub fn get_managed_settings(&self) -> Result<ManagedSettings, DataError> {
        let connection = self.connect()?;
        get_managed_settings_from(&connection)
    }

    pub fn put_managed_settings(
        &self,
        settings: &ManagedSettings,
        updated_at: i64,
    ) -> Result<(), DataError> {
        let mut settings = settings.clone();
        settings.normalize_android_compatible();
        settings.validate().map_err(DataError::Validation)?;
        require_nonnegative_timestamp(updated_at, "updatedAt")?;
        let json = serde_json::to_string(&settings)?;
        let connection = self.connect()?;
        connection.execute(
            "INSERT INTO app_settings(id, managed_json, updated_at) VALUES(1, ?1, ?2)
             ON CONFLICT(id) DO UPDATE SET managed_json = excluded.managed_json,
                 updated_at = excluded.updated_at",
            params![json, updated_at],
        )?;
        Ok(())
    }

    pub fn reset_managed_settings(&self, updated_at: i64) -> Result<ManagedSettings, DataError> {
        let settings = ManagedSettings::default();
        self.put_managed_settings(&settings, updated_at)?;
        Ok(settings)
    }

    pub fn get_local_paths(&self) -> Result<LocalPaths, DataError> {
        let connection = self.connect()?;
        get_local_paths_from(&connection)
    }

    pub fn get_notes_root_path(&self) -> Result<Option<String>, DataError> {
        let connection = self.connect()?;
        Ok(crate::notes::get_root_path(&connection)?)
    }

    pub fn set_notes_root_path(&self, root_path: Option<&str>) -> Result<(), DataError> {
        let connection = self.connect()?;
        crate::notes::set_root_path(&connection, root_path, now_millis())?;
        Ok(())
    }

    pub fn put_local_paths(&self, paths: &LocalPaths) -> Result<(), DataError> {
        validate_local_path_value("diaryPath", paths.diary_path.as_deref())?;
        validate_local_path_value("mediaPath", paths.media_path.as_deref())?;
        validate_local_path_value("backupPath", paths.backup_path.as_deref())?;
        let connection = self.connect()?;
        connection.execute(
            "INSERT INTO local_paths(id, diary_path, media_path, backup_path)
             VALUES(1, ?1, ?2, ?3)
             ON CONFLICT(id) DO UPDATE SET diary_path = excluded.diary_path,
                 media_path = excluded.media_path, backup_path = excluded.backup_path",
            params![paths.diary_path, paths.media_path, paths.backup_path],
        )?;
        Ok(())
    }

    /// Persist user-visible settings and Windows-only folder selections as one
    /// configuration change. Callers must use this instead of two independent
    /// writes when both values originate from the same settings form.
    pub fn put_windows_configuration(
        &self,
        settings: &ManagedSettings,
        paths: &LocalPaths,
        updated_at: i64,
    ) -> Result<(), DataError> {
        let mut settings = settings.clone();
        settings.normalize_android_compatible();
        settings.validate().map_err(DataError::Validation)?;
        validate_local_path_value("diaryPath", paths.diary_path.as_deref())?;
        validate_local_path_value("mediaPath", paths.media_path.as_deref())?;
        validate_local_path_value("backupPath", paths.backup_path.as_deref())?;
        require_nonnegative_timestamp(updated_at, "updatedAt")?;
        let settings_json = serde_json::to_string(&settings)?;

        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        transaction.execute(
            "INSERT INTO app_settings(id, managed_json, updated_at) VALUES(1, ?1, ?2)
             ON CONFLICT(id) DO UPDATE SET managed_json = excluded.managed_json,
                 updated_at = excluded.updated_at",
            params![settings_json, updated_at],
        )?;
        transaction.execute(
            "INSERT INTO local_paths(id, diary_path, media_path, backup_path)
             VALUES(1, ?1, ?2, ?3)
             ON CONFLICT(id) DO UPDATE SET diary_path = excluded.diary_path,
                 media_path = excluded.media_path, backup_path = excluded.backup_path",
            params![paths.diary_path, paths.media_path, paths.backup_path],
        )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn list_thoughts(&self, include_deleted: bool) -> Result<Vec<Thought>, DataError> {
        let connection = self.connect()?;
        list_thoughts_from(&connection, include_deleted)
    }

    pub fn save_thought(&self, draft: ThoughtDraft) -> Result<Thought, DataError> {
        validate_thought_draft(&draft)?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        if let Some(id) = draft.id {
            require_positive_id(id, "thought.id")?;
            let changed = transaction.execute(
                "UPDATE thoughts SET content = ?1, updated_at = MAX(updated_at, ?2), pinned = ?3,
                     category_id = ?4, highlighted = ?5 WHERE id = ?6",
                params![
                    draft.content,
                    now_millis(),
                    draft.pinned,
                    draft.category_id,
                    draft.highlighted,
                    id
                ],
            )?;
            if changed == 0 {
                return Err(DataError::NotFound);
            }
        } else {
            let timestamp = now_millis();
            let sort_order: i64 = transaction.query_row(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM thoughts",
                [],
                |row| row.get(0),
            )?;
            transaction.execute(
                "INSERT INTO thoughts(content, created_at, updated_at, pinned, deleted_at,
                     sort_order, category_id, highlighted)
                 VALUES(?1, ?2, ?2, ?3, NULL, ?4, ?5, ?6)",
                params![
                    draft.content,
                    timestamp,
                    draft.pinned,
                    sort_order,
                    draft.category_id,
                    draft.highlighted
                ],
            )?;
        }
        let id = draft.id.unwrap_or_else(|| transaction.last_insert_rowid());
        let thought = get_thought_from(&transaction, id)?;
        transaction.commit()?;
        Ok(thought)
    }

    pub fn soft_delete_thought(&self, id: i64, deleted_at: i64) -> Result<(), DataError> {
        require_positive_id(id, "thought.id")?;
        require_nonnegative_timestamp(deleted_at, "deletedAt")?;
        let connection = self.connect()?;
        let changed = connection.execute(
            "UPDATE thoughts SET deleted_at = MAX(created_at, ?1),
                 updated_at = MAX(updated_at, created_at, ?1)
             WHERE id = ?2",
            params![deleted_at, id],
        )?;
        require_changed(changed)
    }

    pub fn restore_thought(&self, id: i64, updated_at: i64) -> Result<(), DataError> {
        require_positive_id(id, "thought.id")?;
        require_nonnegative_timestamp(updated_at, "updatedAt")?;
        let connection = self.connect()?;
        let changed = connection.execute(
            "UPDATE thoughts SET deleted_at = NULL, updated_at = MAX(updated_at, ?1)
             WHERE id = ?2",
            params![updated_at, id],
        )?;
        require_changed(changed)
    }

    pub fn permanently_delete_thought(&self, id: i64) -> Result<(), DataError> {
        require_positive_id(id, "thought.id")?;
        let connection = self.connect()?;
        require_changed(connection.execute("DELETE FROM thoughts WHERE id = ?1", params![id])?)
    }

    pub fn reorder_thoughts(&self, ids: &[i64], updated_at: i64) -> Result<(), DataError> {
        require_nonnegative_timestamp(updated_at, "updatedAt")?;
        let unique = ids.iter().copied().collect::<HashSet<_>>();
        if unique.len() != ids.len() || ids.iter().any(|id| *id <= 0) {
            return Err(DataError::Validation(
                "Thought order contains invalid or duplicate IDs".to_owned(),
            ));
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        for (index, id) in ids.iter().enumerate() {
            let changed = transaction.execute(
                "UPDATE thoughts SET sort_order = ?1, updated_at = MAX(updated_at, ?2)
                 WHERE id = ?3 AND deleted_at IS NULL",
                params![index as i64, updated_at, id],
            )?;
            if changed != 1 {
                return Err(DataError::NotFound);
            }
        }
        transaction.commit()?;
        Ok(())
    }

    pub fn list_categories(&self) -> Result<Vec<ThoughtCategory>, DataError> {
        let connection = self.connect()?;
        list_categories_from(&connection)
    }

    pub fn save_category(&self, draft: ThoughtCategoryDraft) -> Result<ThoughtCategory, DataError> {
        validate_category_draft(&draft)?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        if let Some(id) = draft.id {
            require_positive_id(id, "category.id")?;
            let changed = transaction.execute(
                "UPDATE thought_categories SET name = ?1, color_argb = ?2,
                     updated_at = MAX(updated_at, ?3)
                 WHERE id = ?4",
                params![draft.name, draft.color_argb, now_millis(), id],
            )?;
            if changed == 0 {
                return Err(DataError::NotFound);
            }
        } else {
            let timestamp = now_millis();
            let sort_order: i64 = transaction.query_row(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM thought_categories",
                [],
                |row| row.get(0),
            )?;
            transaction.execute(
                "INSERT INTO thought_categories(
                    name, color_argb, sort_order, created_at, updated_at
                 ) VALUES(?1, ?2, ?3, ?4, ?4)",
                params![draft.name, draft.color_argb, sort_order, timestamp],
            )?;
        }
        let id = draft.id.unwrap_or_else(|| transaction.last_insert_rowid());
        let category = get_category_from(&transaction, id)?;
        transaction.commit()?;
        Ok(category)
    }

    pub fn delete_category(&self, id: i64) -> Result<(), DataError> {
        require_positive_id(id, "category.id")?;
        let connection = self.connect()?;
        require_changed(
            connection.execute("DELETE FROM thought_categories WHERE id = ?1", params![id])?,
        )
    }

    pub fn reorder_categories(&self, ids: &[i64], updated_at: i64) -> Result<(), DataError> {
        require_nonnegative_timestamp(updated_at, "updatedAt")?;
        let unique = ids.iter().copied().collect::<HashSet<_>>();
        if unique.len() != ids.len() || ids.iter().any(|id| *id <= 0) {
            return Err(DataError::Validation(
                "Category order contains invalid or duplicate IDs".to_owned(),
            ));
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        for (index, id) in ids.iter().enumerate() {
            let changed = transaction.execute(
                "UPDATE thought_categories SET sort_order = ?1,
                     updated_at = MAX(updated_at, ?2) WHERE id = ?3",
                params![index as i64, updated_at, id],
            )?;
            if changed != 1 {
                return Err(DataError::NotFound);
            }
        }
        transaction.commit()?;
        Ok(())
    }

    pub fn list_date_records(&self) -> Result<Vec<DateRecord>, DataError> {
        let connection = self.connect()?;
        list_date_records_from(&connection)
    }

    pub fn save_date_record(&self, draft: DateRecordDraft) -> Result<DateRecord, DataError> {
        validate_date_record_draft(&draft)?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        if let Some(id) = draft.id {
            require_positive_id(id, "dateRecord.id")?;
            let changed = transaction.execute(
                "UPDATE date_records SET name = ?1, icon = ?2, date_iso = ?3,
                     updated_at = MAX(updated_at, ?4)
                 WHERE id = ?5",
                params![draft.name, draft.icon, draft.date_iso, now_millis(), id],
            )?;
            if changed == 0 {
                return Err(DataError::NotFound);
            }
        } else {
            let timestamp = now_millis();
            transaction.execute(
                "INSERT INTO date_records(name, icon, date_iso, created_at, updated_at)
                 VALUES(?1, ?2, ?3, ?4, ?4)",
                params![draft.name, draft.icon, draft.date_iso, timestamp],
            )?;
        }
        let id = draft.id.unwrap_or_else(|| transaction.last_insert_rowid());
        let record = get_date_record_from(&transaction, id)?;
        transaction.commit()?;
        Ok(record)
    }

    pub fn delete_date_record(&self, id: i64) -> Result<(), DataError> {
        require_positive_id(id, "dateRecord.id")?;
        let connection = self.connect()?;
        require_changed(connection.execute("DELETE FROM date_records WHERE id = ?1", params![id])?)
    }

    pub fn list_poems(&self) -> Result<Vec<SavedPoem>, DataError> {
        let connection = self.connect()?;
        list_poems_from(&connection)
    }

    pub fn list_poetry_categories(&self) -> Result<Vec<PoetryCategory>, DataError> {
        let connection = self.connect()?;
        list_poetry_categories_from(&connection)
    }

    pub fn save_poetry_category(
        &self,
        mut draft: PoetryCategoryDraft,
    ) -> Result<PoetryCategory, DataError> {
        draft.name = normalize_poetry_category_name(&draft.name);
        validate_poetry_category_draft(&draft)?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        if let Some(id) = draft.id {
            require_positive_id(id, "poetryCategory.id")?;
            let changed = transaction.execute(
                "UPDATE poetry_categories
                 SET name = ?1, color_argb = ?2, updated_at = MAX(updated_at, ?3)
                 WHERE id = ?4",
                params![draft.name, draft.color_argb, now_millis(), id],
            )?;
            if changed == 0 {
                return Err(DataError::NotFound);
            }
        } else {
            let timestamp = now_millis();
            let sort_order: i64 = transaction.query_row(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM poetry_categories",
                [],
                |row| row.get(0),
            )?;
            transaction.execute(
                "INSERT INTO poetry_categories(
                    name, color_argb, sort_order, created_at, updated_at
                 ) VALUES(?1, ?2, ?3, ?4, ?4)",
                params![draft.name, draft.color_argb, sort_order, timestamp],
            )?;
        }
        let id = draft.id.unwrap_or_else(|| transaction.last_insert_rowid());
        let category = get_poetry_category_from(&transaction, id)?;
        transaction.commit()?;
        Ok(category)
    }

    pub fn delete_poetry_category(&self, id: i64, delete_poems: bool) -> Result<(), DataError> {
        require_positive_id(id, "poetryCategory.id")?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        if delete_poems {
            transaction.execute(
                "DELETE FROM saved_poems WHERE category_id = ?1",
                params![id],
            )?;
        } else {
            transaction.execute(
                "UPDATE saved_poems SET category_id = NULL WHERE category_id = ?1",
                params![id],
            )?;
        }
        require_changed(
            transaction.execute("DELETE FROM poetry_categories WHERE id = ?1", params![id])?,
        )?;
        normalize_poetry_category_order(&transaction)?;
        transaction.commit()?;
        Ok(())
    }

    pub fn move_poetry_category(&self, id: i64, target_index: usize) -> Result<(), DataError> {
        require_positive_id(id, "poetryCategory.id")?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let mut ids = list_poetry_category_ids(&transaction)?;
        move_id_to_index(&mut ids, id, target_index)?;
        replace_poetry_category_order(&transaction, &ids)?;
        transaction.commit()?;
        Ok(())
    }

    pub fn save_poem(&self, draft: SavedPoemDraft) -> Result<SavedPoem, DataError> {
        validate_poem_draft(&draft)?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        if let Some(id) = draft.id {
            require_positive_id(id, "poem.id")?;
            let changed = transaction.execute(
                "UPDATE saved_poems SET content = ?1, source = ?2, category_id = ?3,
                     updated_at = MAX(updated_at, ?4)
                 WHERE id = ?5",
                params![
                    draft.content,
                    draft.source,
                    draft.category_id,
                    now_millis(),
                    id
                ],
            )?;
            if changed == 0 {
                return Err(DataError::NotFound);
            }
        } else {
            let timestamp = now_millis();
            let sort_order: i64 = transaction.query_row(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM saved_poems",
                [],
                |row| row.get(0),
            )?;
            transaction.execute(
                "INSERT INTO saved_poems(
                    content, source, created_at, updated_at, sort_order, category_id
                 ) VALUES(?1, ?2, ?3, ?3, ?4, ?5)",
                params![
                    draft.content,
                    draft.source,
                    timestamp,
                    sort_order,
                    draft.category_id
                ],
            )?;
        }
        let id = draft.id.unwrap_or_else(|| transaction.last_insert_rowid());
        let poem = get_poem_from(&transaction, id)?;
        transaction.commit()?;
        Ok(poem)
    }

    pub fn delete_poem(&self, id: i64) -> Result<(), DataError> {
        require_positive_id(id, "poem.id")?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        require_changed(
            transaction.execute("DELETE FROM saved_poems WHERE id = ?1", params![id])?,
        )?;
        normalize_poem_order(&transaction)?;
        transaction.commit()?;
        Ok(())
    }

    pub fn set_poem_category(&self, id: i64, category_id: Option<i64>) -> Result<(), DataError> {
        require_positive_id(id, "poem.id")?;
        if let Some(category_id) = category_id {
            require_positive_id(category_id, "poem.categoryId")?;
        }
        let connection = self.connect()?;
        require_changed(connection.execute(
            "UPDATE saved_poems
             SET category_id = ?1, updated_at = MAX(updated_at, ?2)
             WHERE id = ?3",
            params![category_id, now_millis(), id],
        )?)
    }

    /// Reorders a poem globally, within uncategorized poems, or inside one
    /// category while preserving the global slots occupied by other poems.
    pub fn move_poem(
        &self,
        id: i64,
        target_index: usize,
        category_scope: Option<Option<i64>>,
    ) -> Result<(), DataError> {
        require_positive_id(id, "poem.id")?;
        if let Some(Some(category_id)) = category_scope {
            require_positive_id(category_id, "poem.categoryId")?;
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let all_ids = list_poem_ids(&transaction, None)?;
        let mut moving_ids = match category_scope {
            None => all_ids.clone(),
            Some(category_id) => list_poem_ids(&transaction, Some(category_id))?,
        };
        move_id_to_index(&mut moving_ids, id, target_index)?;
        let reordered = if category_scope.is_none() {
            moving_ids
        } else {
            replace_subset_order(&all_ids, &moving_ids)?
        };
        replace_poem_order(&transaction, &reordered)?;
        transaction.commit()?;
        Ok(())
    }

    pub fn import_poetry_preset(
        &self,
        name: &str,
        color_argb: i32,
        poems: &[(String, String)],
    ) -> Result<(i64, usize, usize), DataError> {
        let name = normalize_poetry_category_name(name);
        validate_poetry_category_draft(&PoetryCategoryDraft {
            id: None,
            name: name.clone(),
            color_argb,
        })?;
        if poems.is_empty() || poems.len() > 128 {
            return Err(DataError::Validation(
                "Poetry preset item count is invalid".to_owned(),
            ));
        }
        for (content, source) in poems {
            validate_poem_draft(&SavedPoemDraft {
                id: None,
                content: content.clone(),
                source: source.clone(),
                category_id: None,
            })?;
        }

        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let existing_id: Option<i64> = transaction
            .query_row(
                "SELECT id FROM poetry_categories WHERE name = ?1 COLLATE NOCASE LIMIT 1",
                params![name],
                |row| row.get(0),
            )
            .optional()?;
        let category_id = if let Some(id) = existing_id {
            id
        } else {
            let timestamp = now_millis();
            let sort_order: i64 = transaction.query_row(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM poetry_categories",
                [],
                |row| row.get(0),
            )?;
            transaction.execute(
                "INSERT INTO poetry_categories(
                    name, color_argb, sort_order, created_at, updated_at
                 ) VALUES(?1, ?2, ?3, ?4, ?4)",
                params![name, color_argb, sort_order, timestamp],
            )?;
            transaction.last_insert_rowid()
        };

        let timestamp = now_millis();
        let mut next_sort_order: i64 = transaction.query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM saved_poems",
            [],
            |row| row.get(0),
        )?;
        let mut added = 0_usize;
        for (index, (content, source)) in poems.iter().enumerate() {
            let duplicate: bool = transaction.query_row(
                "SELECT EXISTS(
                    SELECT 1 FROM saved_poems
                    WHERE category_id = ?1 AND content = ?2 AND source = ?3
                 )",
                params![category_id, content, source],
                |row| row.get(0),
            )?;
            if duplicate {
                continue;
            }
            let row_timestamp = timestamp.saturating_sub(index as i64);
            transaction.execute(
                "INSERT INTO saved_poems(
                    content, source, created_at, updated_at, sort_order, category_id
                 ) VALUES(?1, ?2, ?3, ?3, ?4, ?5)",
                params![content, source, row_timestamp, next_sort_order, category_id],
            )?;
            next_sort_order += 1;
            added += 1;
        }
        transaction.commit()?;
        Ok((category_id, added, poems.len() - added))
    }

    pub fn get_compatibility_shadow(&self) -> Result<Option<CompatibilityShadow>, DataError> {
        let connection = self.connect()?;
        get_compatibility_shadow_from(&connection)
    }

    pub fn put_compatibility_shadow(
        &self,
        ciphertext: &[u8],
        source_sha256: &str,
        imported_at: i64,
    ) -> Result<(), DataError> {
        validate_shadow(ciphertext, source_sha256, imported_at)?;
        let connection = self.connect()?;
        connection.execute(
            "INSERT INTO compatibility_shadow(
                id, ciphertext, source_sha256, imported_at
             ) VALUES(1, ?1, ?2, ?3)
             ON CONFLICT(id) DO UPDATE SET ciphertext = excluded.ciphertext,
                 source_sha256 = excluded.source_sha256, imported_at = excluded.imported_at",
            params![ciphertext, source_sha256, imported_at],
        )?;
        Ok(())
    }

    pub fn snapshot_core(&self) -> Result<CoreSnapshot, DataError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let shadow = get_compatibility_shadow_from(&transaction)?;
        let (game_states, game_statistics) = games::list_backup_rows(&transaction)?;
        let snapshot = CoreSnapshot {
            // This serialized field predates database v2. It is a recovery
            // payload format version, not SQLite's `user_version`.
            schema_version: RECOVERY_SNAPSHOT_VERSION,
            created_at: now_millis(),
            settings: get_managed_settings_from(&transaction)?,
            thoughts: list_thoughts_from(&transaction, true)?,
            categories: list_categories_from(&transaction)?,
            date_records: list_date_records_from(&transaction)?,
            poetry_categories: list_poetry_categories_from(&transaction)?,
            poems: list_poems_from(&transaction)?,
            game_states: Some(game_states),
            game_statistics: Some(game_statistics),
            local_paths: get_local_paths_from(&transaction)?,
            encrypted_compatibility_shadow: shadow.as_ref().map(|item| item.ciphertext.clone()),
            compatibility_source_sha256: shadow.map(|item| item.source_sha256),
        };
        transaction.commit()?;
        Ok(snapshot)
    }

    pub fn restore_core_snapshot(&self, snapshot: &CoreSnapshot) -> Result<(), DataError> {
        if snapshot.schema_version != RECOVERY_SNAPSHOT_VERSION {
            return Err(DataError::UnsupportedVersion);
        }
        require_nonnegative_timestamp(snapshot.created_at, "snapshot.createdAt")?;
        let mut settings = snapshot.settings.clone();
        settings.normalize_android_compatible();
        settings.validate().map_err(DataError::Validation)?;
        validate_imported_core(
            &snapshot.thoughts,
            &snapshot.categories,
            &snapshot.date_records,
            &snapshot.poetry_categories,
            &snapshot.poems,
        )?;
        validate_local_path_value("diaryPath", snapshot.local_paths.diary_path.as_deref())?;
        validate_local_path_value("mediaPath", snapshot.local_paths.media_path.as_deref())?;
        validate_local_path_value("backupPath", snapshot.local_paths.backup_path.as_deref())?;
        match (
            snapshot.encrypted_compatibility_shadow.as_deref(),
            snapshot.compatibility_source_sha256.as_deref(),
        ) {
            (Some(ciphertext), Some(source_sha256)) => {
                validate_shadow(ciphertext, source_sha256, snapshot.created_at)?;
            }
            (None, None) => {}
            _ => {
                return Err(DataError::Validation(
                    "Recovery compatibility shadow is incomplete".to_owned(),
                ));
            }
        }
        match (&snapshot.game_states, &snapshot.game_statistics) {
            (Some(_), Some(_)) | (None, None) => {}
            _ => {
                return Err(DataError::Validation(
                    "Recovery game collections are incomplete".to_owned(),
                ));
            }
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        replace_core_rows(
            &transaction,
            &settings,
            &snapshot.thoughts,
            &snapshot.categories,
            &snapshot.date_records,
            &snapshot.poetry_categories,
            &snapshot.poems,
        )?;
        if let (Some(states), Some(statistics)) = (&snapshot.game_states, &snapshot.game_statistics)
        {
            games::replace_backup_rows(&transaction, states, statistics)?;
        }
        transaction.execute(
            "UPDATE local_paths SET diary_path = ?1, media_path = ?2, backup_path = ?3
             WHERE id = 1",
            params![
                snapshot.local_paths.diary_path,
                snapshot.local_paths.media_path,
                snapshot.local_paths.backup_path
            ],
        )?;
        transaction.execute("DELETE FROM compatibility_shadow", [])?;
        if let (Some(ciphertext), Some(source_sha256)) = (
            snapshot.encrypted_compatibility_shadow.as_deref(),
            snapshot.compatibility_source_sha256.as_deref(),
        ) {
            transaction.execute(
                "INSERT INTO compatibility_shadow(
                    id, ciphertext, source_sha256, imported_at
                 ) VALUES(1, ?1, ?2, ?3)",
                params![ciphertext, source_sha256, snapshot.created_at],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn replace_imported_core(
        &self,
        settings: &ManagedSettings,
        thoughts: &[Thought],
        categories: &[ThoughtCategory],
        date_records: &[DateRecord],
        poetry_categories: &[PoetryCategory],
        poems: &[SavedPoem],
        game_states: &[GameBackupState],
        game_statistics: &[GameBackupStatistic],
        merge_game_states: bool,
        merge_game_statistics: bool,
        encrypted_shadow: Option<&[u8]>,
        source_sha256: &str,
        imported_at: i64,
    ) -> Result<(), DataError> {
        let mut settings = settings.clone();
        settings.normalize_android_compatible();
        settings.validate().map_err(DataError::Validation)?;
        validate_imported_core(thoughts, categories, date_records, poetry_categories, poems)?;
        require_nonnegative_timestamp(imported_at, "importedAt")?;
        if let Some(ciphertext) = encrypted_shadow {
            validate_shadow(ciphertext, source_sha256, imported_at)?;
        }

        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        replace_core_rows(
            &transaction,
            &settings,
            thoughts,
            categories,
            date_records,
            poetry_categories,
            poems,
        )?;
        games::merge_backup_rows(
            &transaction,
            game_states,
            game_statistics,
            merge_game_states,
            merge_game_statistics,
        )?;
        if let Some(ciphertext) = encrypted_shadow {
            transaction.execute(
                "INSERT INTO compatibility_shadow(
                    id, ciphertext, source_sha256, imported_at
                 ) VALUES(1, ?1, ?2, ?3)
                 ON CONFLICT(id) DO UPDATE SET ciphertext = excluded.ciphertext,
                     source_sha256 = excluded.source_sha256,
                     imported_at = excluded.imported_at",
                params![ciphertext, source_sha256, imported_at],
            )?;
        } else {
            // Replacing the managed core without a matching source document
            // must never leave a stale compatibility shadow attached to it.
            transaction.execute("DELETE FROM compatibility_shadow", [])?;
        }
        transaction.commit()?;
        Ok(())
    }

    pub(crate) fn list_game_backup_rows(
        &self,
    ) -> Result<(Vec<GameBackupState>, Vec<GameBackupStatistic>), DataError> {
        let connection = self.connect()?;
        games::list_backup_rows(&connection)
    }
}

#[allow(dead_code)]
impl Database {
    pub(crate) fn get_vault_metadata(&self) -> Result<Option<VaultMetadataRecord>, DataError> {
        let connection = self.connect()?;
        get_vault_metadata_from(&connection)
    }

    pub(crate) fn initialize_vault(&self, metadata: &VaultMetadataRecord) -> Result<(), DataError> {
        validate_vault_metadata(metadata)?;
        if metadata.revision != 0 {
            return Err(DataError::Validation(
                "A new vault must start at revision zero".to_owned(),
            ));
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let initialized: i64 =
            transaction.query_row("SELECT COUNT(*) FROM vault_metadata", [], |row| row.get(0))?;
        if initialized != 0 {
            return Err(DataError::Validation(
                "Vault is already initialized".to_owned(),
            ));
        }
        insert_vault_metadata(&transaction, metadata)?;
        transaction.commit()?;
        Ok(())
    }

    pub(crate) fn list_vault_items(&self) -> Result<Vec<VaultItemRecord>, DataError> {
        let connection = self.connect()?;
        list_vault_items_from(&connection)
    }

    pub(crate) fn insert_vault_item(
        &self,
        expected_generation: &str,
        ciphertext: &[u8],
        nonce: &[u8],
        created_at: i64,
    ) -> Result<VaultItemRecord, DataError> {
        validate_generation_id(expected_generation, "vault.expectedGeneration")?;
        validate_vault_ciphertext(ciphertext, nonce)?;
        require_nonnegative_timestamp(created_at, "vaultItem.createdAt")?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        require_vault_generation(&transaction, expected_generation)?;
        let sort_order: i64 = transaction.query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM vault_items",
            [],
            |row| row.get(0),
        )?;
        transaction.execute(
            "INSERT INTO vault_items(
                generation_id, ciphertext, nonce, created_at, updated_at, sort_order
             ) VALUES(?1, ?2, ?3, ?4, ?4, ?5)",
            params![
                expected_generation,
                ciphertext,
                nonce,
                created_at,
                sort_order
            ],
        )?;
        bump_vault_revision(&transaction, expected_generation, created_at)?;
        let item = get_vault_item_from(&transaction, transaction.last_insert_rowid())?;
        transaction.commit()?;
        Ok(item)
    }

    pub(crate) fn update_vault_item(
        &self,
        id: i64,
        expected_generation: &str,
        ciphertext: &[u8],
        nonce: &[u8],
        updated_at: i64,
    ) -> Result<VaultItemRecord, DataError> {
        require_positive_id(id, "vaultItem.id")?;
        validate_generation_id(expected_generation, "vault.expectedGeneration")?;
        validate_vault_ciphertext(ciphertext, nonce)?;
        require_nonnegative_timestamp(updated_at, "vaultItem.updatedAt")?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        require_vault_generation(&transaction, expected_generation)?;
        let changed = transaction.execute(
            "UPDATE vault_items
             SET ciphertext = ?1, nonce = ?2, updated_at = MAX(updated_at, created_at, ?3)
             WHERE id = ?4 AND generation_id = ?5",
            params![ciphertext, nonce, updated_at, id, expected_generation],
        )?;
        require_changed(changed)?;
        bump_vault_revision(&transaction, expected_generation, updated_at)?;
        let item = get_vault_item_from(&transaction, id)?;
        transaction.commit()?;
        Ok(item)
    }

    pub(crate) fn delete_vault_item(
        &self,
        id: i64,
        expected_generation: &str,
        updated_at: i64,
    ) -> Result<(), DataError> {
        require_positive_id(id, "vaultItem.id")?;
        validate_generation_id(expected_generation, "vault.expectedGeneration")?;
        require_nonnegative_timestamp(updated_at, "vault.updatedAt")?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        require_vault_generation(&transaction, expected_generation)?;
        require_changed(transaction.execute(
            "DELETE FROM vault_items WHERE id = ?1 AND generation_id = ?2",
            params![id, expected_generation],
        )?)?;
        bump_vault_revision(&transaction, expected_generation, updated_at)?;
        transaction.commit()?;
        Ok(())
    }

    pub(crate) fn reorder_vault_items(
        &self,
        ids: &[i64],
        expected_generation: &str,
        updated_at: i64,
    ) -> Result<(), DataError> {
        validate_generation_id(expected_generation, "vault.expectedGeneration")?;
        require_nonnegative_timestamp(updated_at, "vault.updatedAt")?;
        let unique = ids.iter().copied().collect::<HashSet<_>>();
        if unique.len() != ids.len() || ids.iter().any(|id| *id <= 0) {
            return Err(DataError::Validation(
                "Vault order contains invalid or duplicate IDs".to_owned(),
            ));
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        require_vault_generation(&transaction, expected_generation)?;
        let existing = vault_item_ids_from(&transaction, expected_generation)?;
        if existing != unique {
            return Err(DataError::Validation(
                "Vault order must include every item exactly once".to_owned(),
            ));
        }
        for (index, id) in ids.iter().enumerate() {
            transaction.execute(
                "UPDATE vault_items
                 SET sort_order = ?1, updated_at = MAX(updated_at, ?2)
                 WHERE id = ?3 AND generation_id = ?4",
                params![index as i64, updated_at, id, expected_generation],
            )?;
        }
        bump_vault_revision(&transaction, expected_generation, updated_at)?;
        transaction.commit()?;
        Ok(())
    }

    /// Atomically switches the password/key generation after the service has
    /// decrypted and re-encrypted every row. A stale session cannot replace a
    /// newer generation.
    pub(crate) fn replace_vault_generation(
        &self,
        expected_generation: &str,
        expected_revision: i64,
        metadata: &VaultMetadataRecord,
        items: &[VaultItemRecord],
    ) -> Result<bool, DataError> {
        validate_generation_id(expected_generation, "vault.expectedGeneration")?;
        if expected_revision < 0 {
            return Err(DataError::Validation(
                "Vault expected revision is invalid".to_owned(),
            ));
        }
        validate_vault_metadata(metadata)?;
        let next_revision = expected_revision.checked_add(1).ok_or_else(|| {
            DataError::Validation("Vault revision cannot be incremented".to_owned())
        })?;
        if metadata.generation_id == expected_generation {
            return Err(DataError::Validation(
                "A replacement generation must be new".to_owned(),
            ));
        }
        let mut supplied_ids = HashSet::with_capacity(items.len());
        for item in items {
            validate_vault_item(item)?;
            if item.generation_id != metadata.generation_id
                || !supplied_ids.insert(item.id)
                || item.id <= 0
            {
                return Err(DataError::Validation(
                    "Replacement vault rows are invalid".to_owned(),
                ));
            }
        }

        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let current = get_vault_metadata_from(&transaction)?;
        if current
            .as_ref()
            .map(|value| (value.generation_id.as_str(), value.revision))
            != Some((expected_generation, expected_revision))
        {
            return Ok(false);
        }
        if metadata.revision != next_revision {
            return Err(DataError::Validation(
                "Replacement vault revision must increment exactly once".to_owned(),
            ));
        }
        let existing_ids = vault_item_ids_from(&transaction, expected_generation)?;
        if existing_ids != supplied_ids {
            return Err(DataError::Validation(
                "Replacement must include every existing vault item".to_owned(),
            ));
        }

        transaction.execute("DELETE FROM vault_items", [])?;
        require_changed(transaction.execute(
            "UPDATE vault_metadata SET
                crypto_version = ?1, generation_id = ?2, revision = ?3, salt = ?4,
                kdf_iterations = ?5, verifier_ciphertext = ?6,
                verifier_nonce = ?7, updated_at = ?8
             WHERE id = 1 AND generation_id = ?9 AND revision = ?10",
            params![
                metadata.crypto_version,
                metadata.generation_id,
                metadata.revision,
                metadata.salt,
                metadata.kdf_iterations,
                metadata.verifier_ciphertext,
                metadata.verifier_nonce,
                metadata.updated_at,
                expected_generation,
                expected_revision
            ],
        )?)?;
        for item in items {
            insert_vault_item_record(&transaction, item)?;
        }
        transaction.commit()?;
        Ok(true)
    }

    pub(crate) fn clear_vault(
        &self,
        expected_generation: &str,
        expected_revision: i64,
    ) -> Result<bool, DataError> {
        validate_generation_id(expected_generation, "vault.expectedGeneration")?;
        if expected_revision < 0 {
            return Err(DataError::Validation(
                "Vault expected revision is invalid".to_owned(),
            ));
        }
        let connection = self.connect()?;
        Ok(connection.execute(
            "DELETE FROM vault_metadata
             WHERE id = 1 AND generation_id = ?1 AND revision = ?2",
            params![expected_generation, expected_revision],
        )? == 1)
    }

    pub(crate) fn get_cloud_sync_settings(&self) -> Result<CloudSyncSettingsRecord, DataError> {
        let connection = self.connect()?;
        connection
            .query_row(
                "SELECT automatic_sync_enabled, interval_minutes, configs_managed, updated_at
                 FROM cloud_sync_settings WHERE id = 1",
                [],
                |row| {
                    Ok(CloudSyncSettingsRecord {
                        automatic_sync_enabled: row.get(0)?,
                        interval_minutes: row.get(1)?,
                        configs_managed: row.get(2)?,
                        updated_at: row.get(3)?,
                    })
                },
            )
            .map_err(DataError::from)
    }

    pub(crate) fn put_cloud_sync_settings(
        &self,
        settings: &CloudSyncSettingsRecord,
    ) -> Result<(), DataError> {
        validate_cloud_sync_settings(settings)?;
        let connection = self.connect()?;
        connection.execute(
            "INSERT INTO cloud_sync_settings(
                id, automatic_sync_enabled, interval_minutes, configs_managed, updated_at
             ) VALUES(1, ?1, ?2, ?3, ?4)
             ON CONFLICT(id) DO UPDATE SET
                automatic_sync_enabled = excluded.automatic_sync_enabled,
                interval_minutes = excluded.interval_minutes,
                configs_managed = excluded.configs_managed,
                updated_at = excluded.updated_at",
            params![
                settings.automatic_sync_enabled,
                settings.interval_minutes,
                settings.configs_managed,
                settings.updated_at
            ],
        )?;
        Ok(())
    }

    pub(crate) fn get_update_settings(&self) -> Result<UpdateSettingsRecord, DataError> {
        let connection = self.connect()?;
        connection
            .query_row(
                "SELECT automatic_checks_enabled, last_attempted_at, updated_at
                 FROM update_settings WHERE id = 1",
                [],
                |row| {
                    Ok(UpdateSettingsRecord {
                        automatic_checks_enabled: row.get(0)?,
                        last_attempted_at: row.get(1)?,
                        updated_at: row.get(2)?,
                    })
                },
            )
            .map_err(DataError::from)
    }

    pub(crate) fn set_automatic_update_checks(
        &self,
        enabled: bool,
        updated_at: i64,
    ) -> Result<(), DataError> {
        require_nonnegative_timestamp(updated_at, "updateSettings.updatedAt")?;
        let connection = self.connect()?;
        connection.execute(
            "INSERT INTO update_settings(
                id, automatic_checks_enabled, updated_at
             ) VALUES(1, ?1, ?2)
             ON CONFLICT(id) DO UPDATE SET
                automatic_checks_enabled = excluded.automatic_checks_enabled,
                updated_at = excluded.updated_at",
            params![enabled, updated_at],
        )?;
        Ok(())
    }

    pub(crate) fn claim_automatic_update_attempt(
        &self,
        attempted_at: i64,
    ) -> Result<bool, DataError> {
        require_nonnegative_timestamp(attempted_at, "updateSettings.lastAttemptedAt")?;
        let connection = self.connect()?;
        let changed = connection.execute(
            "UPDATE update_settings SET last_attempted_at = ?1
             WHERE id = 1 AND automatic_checks_enabled = 1",
            params![attempted_at],
        )?;
        Ok(changed == 1)
    }

    pub(crate) fn list_cloud_sync_configs(&self) -> Result<Vec<CloudSyncConfigRecord>, DataError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "SELECT id, name, enabled, service_type, endpoint_url, remote_path,
                    user_agent, webdav_username, s3_bucket, s3_region, s3_path_style,
                    allow_insecure_http,
                    selected_contents_json, direction, sort_order, updated_at
             FROM cloud_sync_configs ORDER BY sort_order, id",
        )?;
        let rows = statement.query_map([], cloud_sync_config_from_row)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(DataError::from)
    }

    pub(crate) fn get_cloud_sync_config(
        &self,
        id: &str,
    ) -> Result<Option<CloudSyncConfigRecord>, DataError> {
        validate_cloud_config_id(id)?;
        let connection = self.connect()?;
        connection
            .query_row(
                "SELECT id, name, enabled, service_type, endpoint_url, remote_path,
                        user_agent, webdav_username, s3_bucket, s3_region, s3_path_style,
                        allow_insecure_http,
                        selected_contents_json, direction, sort_order, updated_at
                 FROM cloud_sync_configs WHERE id = ?1",
                params![id],
                cloud_sync_config_from_row,
            )
            .optional()
            .map_err(DataError::from)
    }

    /// Upserts non-secret metadata and applies the requested secret mutation in
    /// the same SQLite transaction. `Preserve` is explicit so callers cannot
    /// accidentally erase credentials while editing a display-only field.
    pub(crate) fn save_cloud_sync_config(
        &self,
        config: &CloudSyncConfigRecord,
        secret_mutation: CloudSyncSecretMutation,
    ) -> Result<(), DataError> {
        validate_cloud_sync_config(config)?;
        if let CloudSyncSecretMutation::Replace(secret) = &secret_mutation {
            validate_cloud_sync_secret(secret)?;
            if secret.config_id != config.id {
                return Err(DataError::Validation(
                    "Cloud secret belongs to a different configuration".to_owned(),
                ));
            }
        }

        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        transaction.execute(
            "INSERT INTO cloud_sync_configs(
                id, name, enabled, service_type, endpoint_url, remote_path,
                user_agent, webdav_username, s3_bucket, s3_region, s3_path_style,
                allow_insecure_http,
                selected_contents_json, direction, sort_order, updated_at
             ) VALUES(
                ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16
             )
             ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                enabled = excluded.enabled,
                service_type = excluded.service_type,
                endpoint_url = excluded.endpoint_url,
                remote_path = excluded.remote_path,
                user_agent = excluded.user_agent,
                webdav_username = excluded.webdav_username,
                s3_bucket = excluded.s3_bucket,
                s3_region = excluded.s3_region,
                s3_path_style = excluded.s3_path_style,
                allow_insecure_http = excluded.allow_insecure_http,
                selected_contents_json = excluded.selected_contents_json,
                direction = excluded.direction,
                sort_order = excluded.sort_order,
                updated_at = excluded.updated_at",
            params![
                config.id,
                config.name,
                config.enabled,
                config.service_type,
                config.endpoint_url,
                config.remote_path,
                config.user_agent,
                config.webdav_username,
                config.s3_bucket,
                config.s3_region,
                config.s3_path_style,
                config.allow_insecure_http,
                config.selected_contents_json,
                config.direction,
                config.sort_order,
                config.updated_at
            ],
        )?;
        match secret_mutation {
            CloudSyncSecretMutation::Preserve => {}
            CloudSyncSecretMutation::Replace(secret) => {
                transaction.execute(
                    "INSERT INTO cloud_sync_secrets(
                        config_id, dpapi_ciphertext, binding_sha256, updated_at
                     ) VALUES(?1, ?2, ?3, ?4)
                     ON CONFLICT(config_id) DO UPDATE SET
                        dpapi_ciphertext = excluded.dpapi_ciphertext,
                        binding_sha256 = excluded.binding_sha256,
                        updated_at = excluded.updated_at",
                    params![
                        secret.config_id,
                        secret.dpapi_ciphertext,
                        secret.binding_sha256,
                        secret.updated_at
                    ],
                )?;
            }
            CloudSyncSecretMutation::Clear => {
                transaction.execute(
                    "DELETE FROM cloud_sync_secrets WHERE config_id = ?1",
                    params![config.id],
                )?;
            }
        }
        transaction.execute(
            "INSERT OR IGNORE INTO cloud_sync_status(
                config_id, state, run_token, last_started_at, last_completed_at,
                last_success_at, last_error_code, uploaded_count, downloaded_count,
                conflict_count, transferred_bytes, updated_at
             ) VALUES(?1, 'IDLE', NULL, NULL, NULL, NULL, NULL, 0, 0, 0, 0, ?2)",
            params![config.id, config.updated_at],
        )?;
        transaction.execute(
            "UPDATE cloud_sync_settings
             SET configs_managed = 1, updated_at = MAX(updated_at, ?1)
             WHERE id = 1",
            params![config.updated_at],
        )?;
        transaction.commit()?;
        Ok(())
    }

    pub(crate) fn get_cloud_sync_secret(
        &self,
        config_id: &str,
    ) -> Result<Option<CloudSyncSecretRecord>, DataError> {
        validate_cloud_config_id(config_id)?;
        let connection = self.connect()?;
        connection
            .query_row(
                "SELECT config_id, dpapi_ciphertext, binding_sha256, updated_at
                 FROM cloud_sync_secrets WHERE config_id = ?1",
                params![config_id],
                |row| {
                    Ok(CloudSyncSecretRecord {
                        config_id: row.get(0)?,
                        dpapi_ciphertext: row.get(1)?,
                        binding_sha256: row.get(2)?,
                        updated_at: row.get(3)?,
                    })
                },
            )
            .optional()
            .map_err(DataError::from)
    }

    pub(crate) fn delete_cloud_sync_config(&self, id: &str) -> Result<(), DataError> {
        validate_cloud_config_id(id)?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        require_changed(
            transaction.execute("DELETE FROM cloud_sync_configs WHERE id = ?1", params![id])?,
        )?;
        transaction.execute(
            "UPDATE cloud_sync_settings
             SET configs_managed = 1, updated_at = MAX(updated_at, ?1)
             WHERE id = 1",
            params![now_millis()],
        )?;
        transaction.commit()?;
        Ok(())
    }

    pub(crate) fn reorder_cloud_sync_configs(
        &self,
        ids: &[String],
        updated_at: i64,
    ) -> Result<(), DataError> {
        require_nonnegative_timestamp(updated_at, "cloudSync.updatedAt")?;
        let unique = ids.iter().collect::<HashSet<_>>();
        if unique.len() != ids.len() || ids.iter().any(|id| validate_cloud_config_id(id).is_err()) {
            return Err(DataError::Validation(
                "Cloud configuration order is invalid".to_owned(),
            ));
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        let existing = cloud_config_ids_from(&transaction)?;
        let supplied = ids.iter().cloned().collect::<HashSet<_>>();
        if existing != supplied {
            return Err(DataError::Validation(
                "Cloud order must include every configuration exactly once".to_owned(),
            ));
        }
        for (index, id) in ids.iter().enumerate() {
            transaction.execute(
                "UPDATE cloud_sync_configs
                 SET sort_order = ?1, updated_at = MAX(updated_at, ?2)
                 WHERE id = ?3",
                params![index as i64, updated_at, id],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }
}

#[allow(dead_code)]
impl Database {
    pub(crate) fn get_cloud_sync_base(
        &self,
        config_id: &str,
    ) -> Result<Option<CloudSyncBaseStateRecord>, DataError> {
        validate_cloud_config_id(config_id)?;
        let connection = self.connect()?;
        connection
            .query_row(
                "SELECT config_id, scope_fingerprint, hashes_json, updated_at
                 FROM cloud_sync_base WHERE config_id = ?1",
                params![config_id],
                |row| {
                    let config_id = row.get(0)?;
                    let scope_fingerprint = row.get(1)?;
                    let hashes_json: String = row.get(2)?;
                    let updated_at = row.get(3)?;
                    Ok((config_id, scope_fingerprint, hashes_json, updated_at))
                },
            )
            .optional()?
            .map(|(config_id, scope_fingerprint, hashes_json, updated_at)| {
                let hashes_by_key = serde_json::from_str(&hashes_json)?;
                let record = CloudSyncBaseStateRecord {
                    config_id,
                    scope_fingerprint,
                    hashes_by_key,
                    updated_at,
                };
                validate_cloud_sync_base(&record)?;
                Ok(record)
            })
            .transpose()
    }

    /// Replaces the complete ancestry map in one statement. The state is
    /// rebuildable and never contains object bodies or credentials.
    pub(crate) fn put_cloud_sync_base(
        &self,
        state: &CloudSyncBaseStateRecord,
    ) -> Result<(), DataError> {
        validate_cloud_sync_base(state)?;
        let hashes_json = serde_json::to_string(&state.hashes_by_key)?;
        if hashes_json.len() > MAX_CLOUD_BASE_JSON_BYTES {
            return Err(DataError::Validation(
                "Cloud base state exceeds its size limit".to_owned(),
            ));
        }
        let connection = self.connect()?;
        connection.execute(
            "INSERT INTO cloud_sync_base(
                config_id, scope_fingerprint, hashes_json, updated_at
             ) VALUES(?1, ?2, ?3, ?4)
             ON CONFLICT(config_id) DO UPDATE SET
                scope_fingerprint = excluded.scope_fingerprint,
                hashes_json = excluded.hashes_json,
                updated_at = excluded.updated_at",
            params![
                state.config_id,
                state.scope_fingerprint,
                hashes_json,
                state.updated_at
            ],
        )?;
        Ok(())
    }

    pub(crate) fn clear_cloud_sync_base(&self, config_id: &str) -> Result<(), DataError> {
        validate_cloud_config_id(config_id)?;
        let connection = self.connect()?;
        connection.execute(
            "DELETE FROM cloud_sync_base WHERE config_id = ?1",
            params![config_id],
        )?;
        Ok(())
    }

    pub(crate) fn get_cloud_sync_status(
        &self,
        config_id: &str,
    ) -> Result<Option<CloudSyncStatusRecord>, DataError> {
        validate_cloud_config_id(config_id)?;
        let connection = self.connect()?;
        get_cloud_sync_status_from(&connection, config_id)
    }

    pub(crate) fn list_cloud_sync_statuses(&self) -> Result<Vec<CloudSyncStatusRecord>, DataError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "SELECT config_id, state, run_token, last_started_at, last_completed_at,
                    last_success_at, last_error_code, uploaded_count, downloaded_count,
                    conflict_count, transferred_bytes, updated_at
             FROM cloud_sync_status ORDER BY config_id",
        )?;
        let rows = statement.query_map([], cloud_sync_status_from_row)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(DataError::from)
    }

    pub(crate) fn begin_cloud_sync_run(
        &self,
        config_id: &str,
        run_token: &str,
        started_at: i64,
    ) -> Result<CloudSyncStatusRecord, DataError> {
        validate_cloud_config_id(config_id)?;
        validate_short_token(run_token, "cloudSync.runToken")?;
        require_nonnegative_timestamp(started_at, "cloudSync.startedAt")?;
        let connection = self.connect()?;
        let changed = connection.execute(
            "UPDATE cloud_sync_status SET
                state = 'RUNNING',
                run_token = ?1,
                last_started_at = ?2,
                last_completed_at = NULL,
                last_error_code = NULL,
                uploaded_count = 0,
                downloaded_count = 0,
                conflict_count = 0,
                transferred_bytes = 0,
                updated_at = ?2
             WHERE config_id = ?3 AND state != 'RUNNING'",
            params![run_token, started_at, config_id],
        )?;
        if changed != 1 {
            return Err(DataError::Validation(
                "Cloud synchronization is already running or missing".to_owned(),
            ));
        }
        get_cloud_sync_status_from(&connection, config_id)?.ok_or(DataError::NotFound)
    }

    /// Finishes only the run that still owns `expected_run_token`. A stale
    /// background task receives `false` and cannot overwrite newer status.
    pub(crate) fn finish_cloud_sync_run(
        &self,
        expected_run_token: &str,
        status: &CloudSyncStatusRecord,
    ) -> Result<bool, DataError> {
        validate_short_token(expected_run_token, "cloudSync.expectedRunToken")?;
        validate_cloud_sync_status(status, true)?;
        if status.run_token.is_some() || matches!(status.state.as_str(), "IDLE" | "RUNNING") {
            return Err(DataError::Validation(
                "Completed cloud status must be terminal".to_owned(),
            ));
        }
        let connection = self.connect()?;
        let changed = connection.execute(
            "UPDATE cloud_sync_status SET
                state = ?1,
                run_token = NULL,
                last_started_at = ?2,
                last_completed_at = ?3,
                last_success_at = ?4,
                last_error_code = ?5,
                uploaded_count = ?6,
                downloaded_count = ?7,
                conflict_count = ?8,
                transferred_bytes = ?9,
                updated_at = ?10
             WHERE config_id = ?11
               AND state = 'RUNNING'
               AND run_token = ?12",
            params![
                status.state,
                status.last_started_at,
                status.last_completed_at,
                status.last_success_at,
                status.last_error_code,
                status.uploaded_count,
                status.downloaded_count,
                status.conflict_count,
                status.transferred_bytes,
                status.updated_at,
                status.config_id,
                expected_run_token
            ],
        )?;
        Ok(changed == 1)
    }

    pub(crate) fn reset_cloud_sync_status(
        &self,
        config_id: &str,
        updated_at: i64,
    ) -> Result<(), DataError> {
        validate_cloud_config_id(config_id)?;
        require_nonnegative_timestamp(updated_at, "cloudSync.updatedAt")?;
        let connection = self.connect()?;
        require_changed(connection.execute(
            "UPDATE cloud_sync_status SET
                state = 'IDLE', run_token = NULL, last_error_code = NULL,
                uploaded_count = 0, downloaded_count = 0, conflict_count = 0,
                transferred_bytes = 0, updated_at = ?1
             WHERE config_id = ?2",
            params![updated_at, config_id],
        )?)
    }

    /// Converts process-crash leftovers into an explicit retryable terminal
    /// state. No new process can resume the abandoned HTTP/file operation, and
    /// leaving RUNNING in place would permanently block the configuration.
    pub(crate) fn recover_interrupted_cloud_sync_runs(
        &self,
        completed_at: i64,
    ) -> Result<usize, DataError> {
        require_nonnegative_timestamp(completed_at, "cloudSync.completedAt")?;
        let connection = self.connect()?;
        connection
            .execute(
                "UPDATE cloud_sync_status SET
                    state = 'FAILED',
                    run_token = NULL,
                    last_completed_at = MAX(COALESCE(last_started_at, 0), ?1),
                    last_error_code = 'interrupted',
                    updated_at = MAX(COALESCE(last_started_at, 0), ?1)
                 WHERE state = 'RUNNING'",
                params![completed_at],
            )
            .map_err(DataError::from)
    }
}

impl crate::updater::AutomaticUpdateStore for Database {
    type Error = DataError;

    fn automatic_update_state(&self) -> Result<crate::updater::AutomaticUpdateState, Self::Error> {
        let settings = self.get_update_settings()?;
        Ok(crate::updater::AutomaticUpdateState {
            enabled: settings.automatic_checks_enabled,
            last_attempted_at: settings.last_attempted_at,
        })
    }

    fn claim_automatic_update_attempt(&self, attempted_at: i64) -> Result<bool, Self::Error> {
        Database::claim_automatic_update_attempt(self, attempted_at)
    }
}

fn get_vault_metadata_from(
    connection: &Connection,
) -> Result<Option<VaultMetadataRecord>, DataError> {
    connection
        .query_row(
            "SELECT crypto_version, generation_id, revision, salt, kdf_iterations,
                    verifier_ciphertext, verifier_nonce, created_at, updated_at
             FROM vault_metadata WHERE id = 1",
            [],
            |row| {
                Ok(VaultMetadataRecord {
                    crypto_version: row.get(0)?,
                    generation_id: row.get(1)?,
                    revision: row.get(2)?,
                    salt: row.get(3)?,
                    kdf_iterations: row.get(4)?,
                    verifier_ciphertext: row.get(5)?,
                    verifier_nonce: row.get(6)?,
                    created_at: row.get(7)?,
                    updated_at: row.get(8)?,
                })
            },
        )
        .optional()
        .map_err(DataError::from)
}

fn insert_vault_metadata(
    transaction: &Transaction<'_>,
    metadata: &VaultMetadataRecord,
) -> Result<(), DataError> {
    transaction.execute(
        "INSERT INTO vault_metadata(
            id, crypto_version, generation_id, revision, salt, kdf_iterations,
            verifier_ciphertext, verifier_nonce, created_at, updated_at
         ) VALUES(1, ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            metadata.crypto_version,
            metadata.generation_id,
            metadata.revision,
            metadata.salt,
            metadata.kdf_iterations,
            metadata.verifier_ciphertext,
            metadata.verifier_nonce,
            metadata.created_at,
            metadata.updated_at
        ],
    )?;
    Ok(())
}

fn vault_item_from_row(row: &Row<'_>) -> rusqlite::Result<VaultItemRecord> {
    Ok(VaultItemRecord {
        id: row.get(0)?,
        generation_id: row.get(1)?,
        ciphertext: row.get(2)?,
        nonce: row.get(3)?,
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
        sort_order: row.get(6)?,
    })
}

fn list_vault_items_from(connection: &Connection) -> Result<Vec<VaultItemRecord>, DataError> {
    let mut statement = connection.prepare(
        "SELECT id, generation_id, ciphertext, nonce, created_at, updated_at, sort_order
         FROM vault_items ORDER BY sort_order, updated_at DESC, id DESC",
    )?;
    let rows = statement.query_map([], vault_item_from_row)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(DataError::from)
}

fn get_vault_item_from(connection: &Connection, id: i64) -> Result<VaultItemRecord, DataError> {
    connection
        .query_row(
            "SELECT id, generation_id, ciphertext, nonce, created_at, updated_at, sort_order
             FROM vault_items WHERE id = ?1",
            params![id],
            vault_item_from_row,
        )
        .optional()?
        .ok_or(DataError::NotFound)
}

fn insert_vault_item_record(
    transaction: &Transaction<'_>,
    item: &VaultItemRecord,
) -> Result<(), DataError> {
    transaction.execute(
        "INSERT INTO vault_items(
            id, generation_id, ciphertext, nonce, created_at, updated_at, sort_order
         ) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            item.id,
            item.generation_id,
            item.ciphertext,
            item.nonce,
            item.created_at,
            item.updated_at,
            item.sort_order
        ],
    )?;
    Ok(())
}

fn vault_item_ids_from(
    connection: &Connection,
    generation_id: &str,
) -> Result<HashSet<i64>, DataError> {
    let mut statement =
        connection.prepare("SELECT id FROM vault_items WHERE generation_id = ?1")?;
    let rows = statement.query_map(params![generation_id], |row| row.get(0))?;
    rows.collect::<Result<HashSet<_>, _>>()
        .map_err(DataError::from)
}

fn require_vault_generation(
    connection: &Connection,
    expected_generation: &str,
) -> Result<i64, DataError> {
    let current: Option<(String, i64)> = connection
        .query_row(
            "SELECT generation_id, revision FROM vault_metadata WHERE id = 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    current
        .filter(|(generation, _)| generation == expected_generation)
        .map(|(_, revision)| revision)
        .ok_or_else(|| DataError::Validation("Vault generation is stale or missing".to_owned()))
}

fn bump_vault_revision(
    transaction: &Transaction<'_>,
    expected_generation: &str,
    updated_at: i64,
) -> Result<i64, DataError> {
    let revision = require_vault_generation(transaction, expected_generation)?;
    let next_revision = revision
        .checked_add(1)
        .ok_or_else(|| DataError::Validation("Vault revision cannot be incremented".to_owned()))?;
    require_changed(transaction.execute(
        "UPDATE vault_metadata
         SET revision = ?1, updated_at = MAX(updated_at, ?2)
         WHERE id = 1 AND generation_id = ?3 AND revision = ?4",
        params![next_revision, updated_at, expected_generation, revision],
    )?)?;
    Ok(next_revision)
}

fn cloud_sync_config_from_row(row: &Row<'_>) -> rusqlite::Result<CloudSyncConfigRecord> {
    Ok(CloudSyncConfigRecord {
        id: row.get(0)?,
        name: row.get(1)?,
        enabled: row.get(2)?,
        service_type: row.get(3)?,
        endpoint_url: row.get(4)?,
        remote_path: row.get(5)?,
        user_agent: row.get(6)?,
        webdav_username: row.get(7)?,
        s3_bucket: row.get(8)?,
        s3_region: row.get(9)?,
        s3_path_style: row.get(10)?,
        allow_insecure_http: row.get(11)?,
        selected_contents_json: row.get(12)?,
        direction: row.get(13)?,
        sort_order: row.get(14)?,
        updated_at: row.get(15)?,
    })
}

fn cloud_config_ids_from(connection: &Connection) -> Result<HashSet<String>, DataError> {
    let mut statement = connection.prepare("SELECT id FROM cloud_sync_configs")?;
    let rows = statement.query_map([], |row| row.get(0))?;
    rows.collect::<Result<HashSet<_>, _>>()
        .map_err(DataError::from)
}

fn cloud_sync_status_from_row(row: &Row<'_>) -> rusqlite::Result<CloudSyncStatusRecord> {
    Ok(CloudSyncStatusRecord {
        config_id: row.get(0)?,
        state: row.get(1)?,
        run_token: row.get(2)?,
        last_started_at: row.get(3)?,
        last_completed_at: row.get(4)?,
        last_success_at: row.get(5)?,
        last_error_code: row.get(6)?,
        uploaded_count: row.get(7)?,
        downloaded_count: row.get(8)?,
        conflict_count: row.get(9)?,
        transferred_bytes: row.get(10)?,
        updated_at: row.get(11)?,
    })
}

fn get_cloud_sync_status_from(
    connection: &Connection,
    config_id: &str,
) -> Result<Option<CloudSyncStatusRecord>, DataError> {
    connection
        .query_row(
            "SELECT config_id, state, run_token, last_started_at, last_completed_at,
                    last_success_at, last_error_code, uploaded_count, downloaded_count,
                    conflict_count, transferred_bytes, updated_at
             FROM cloud_sync_status WHERE config_id = ?1",
            params![config_id],
            cloud_sync_status_from_row,
        )
        .optional()
        .map_err(DataError::from)
}

fn replace_core_rows(
    transaction: &Transaction<'_>,
    settings: &ManagedSettings,
    thoughts: &[Thought],
    categories: &[ThoughtCategory],
    date_records: &[DateRecord],
    poetry_categories: &[PoetryCategory],
    poems: &[SavedPoem],
) -> Result<(), DataError> {
    transaction.execute("DELETE FROM thoughts", [])?;
    transaction.execute("DELETE FROM thought_categories", [])?;
    transaction.execute("DELETE FROM date_records", [])?;
    transaction.execute("DELETE FROM saved_poems", [])?;
    transaction.execute("DELETE FROM poetry_categories", [])?;

    for category in categories {
        transaction.execute(
            "INSERT INTO thought_categories(
                id, name, color_argb, sort_order, created_at, updated_at
             ) VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                category.id,
                category.name,
                category.color_argb,
                category.sort_order,
                category.created_at,
                category.updated_at
            ],
        )?;
    }
    for thought in thoughts {
        transaction.execute(
            "INSERT INTO thoughts(
                id, content, created_at, updated_at, pinned, deleted_at, sort_order,
                category_id, highlighted
             ) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![
                thought.id,
                thought.content,
                thought.created_at,
                thought.updated_at,
                thought.pinned,
                thought.deleted_at,
                thought.sort_order,
                thought.category_id,
                thought.highlighted
            ],
        )?;
    }
    for record in date_records {
        transaction.execute(
            "INSERT INTO date_records(id, name, icon, date_iso, created_at, updated_at)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                record.id,
                record.name,
                record.icon,
                record.date_iso,
                record.created_at,
                record.updated_at
            ],
        )?;
    }
    for category in poetry_categories {
        transaction.execute(
            "INSERT INTO poetry_categories(
                id, name, color_argb, sort_order, created_at, updated_at
             ) VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                category.id,
                category.name,
                category.color_argb,
                category.sort_order,
                category.created_at,
                category.updated_at
            ],
        )?;
    }
    for poem in poems {
        transaction.execute(
            "INSERT INTO saved_poems(
                id, content, source, created_at, updated_at, sort_order, category_id
             ) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                poem.id,
                poem.content,
                poem.source,
                poem.created_at,
                poem.updated_at,
                poem.sort_order,
                poem.category_id
            ],
        )?;
    }
    let settings_json = serde_json::to_string(settings)?;
    transaction.execute(
        "UPDATE app_settings SET managed_json = ?1, updated_at = ?2 WHERE id = 1",
        params![settings_json, now_millis()],
    )?;
    Ok(())
}

fn validate_imported_core(
    thoughts: &[Thought],
    categories: &[ThoughtCategory],
    date_records: &[DateRecord],
    poetry_categories: &[PoetryCategory],
    poems: &[SavedPoem],
) -> Result<(), DataError> {
    if thoughts.len() > 50_000
        || categories.len() > 10_000
        || date_records.len() > 50_000
        || poetry_categories.len() > 10_000
        || poems.len() > 50_000
    {
        return Err(DataError::Validation(
            "Imported core collection exceeds its limit".to_owned(),
        ));
    }

    let mut category_ids = HashSet::new();
    let mut category_names = HashSet::new();
    for category in categories {
        require_positive_id(category.id, "category.id")?;
        if !category_ids.insert(category.id)
            || !category_names.insert(category.name.to_lowercase())
            || category.name.trim().is_empty()
            || utf16_len(&category.name) > 40
            || category.created_at < 0
            || category.updated_at < category.created_at
        {
            return Err(DataError::Validation(
                "Imported category is invalid or duplicated".to_owned(),
            ));
        }
    }

    let mut thought_ids = HashSet::new();
    for thought in thoughts {
        require_positive_id(thought.id, "thought.id")?;
        if !thought_ids.insert(thought.id)
            || utf16_len(&thought.content) > 1_000_000
            || thought.created_at < 0
            || thought.updated_at < thought.created_at
            || thought
                .deleted_at
                .is_some_and(|deleted_at| deleted_at < thought.created_at)
            || thought
                .category_id
                .is_some_and(|category_id| !category_ids.contains(&category_id))
        {
            return Err(DataError::Validation(
                "Imported thought is invalid or duplicated".to_owned(),
            ));
        }
    }

    let mut date_ids = HashSet::new();
    for record in date_records {
        require_positive_id(record.id, "dateRecord.id")?;
        if !date_ids.insert(record.id)
            || record.name.trim().is_empty()
            || utf16_len(&record.name) > 256
            || record.icon.trim().is_empty()
            || utf16_len(&record.icon) > 64
            || !valid_date_iso(&record.date_iso)
            || record.created_at < 0
            || record.updated_at < record.created_at
        {
            return Err(DataError::Validation(
                "Imported date record is invalid or duplicated".to_owned(),
            ));
        }
    }

    let mut poetry_category_ids = HashSet::new();
    let mut poetry_category_names = HashSet::new();
    for category in poetry_categories {
        require_positive_id(category.id, "poetryCategory.id")?;
        if !poetry_category_ids.insert(category.id)
            || !poetry_category_names.insert(category.name.to_lowercase())
            || category.name.trim().is_empty()
            || utf16_len(&category.name) > 100
            || category.sort_order < 0
            || category.created_at < 0
            || category.updated_at < category.created_at
        {
            return Err(DataError::Validation(
                "Imported poetry category is invalid or duplicated".to_owned(),
            ));
        }
    }

    let mut poem_ids = HashSet::new();
    for poem in poems {
        require_positive_id(poem.id, "poem.id")?;
        if !poem_ids.insert(poem.id)
            || poem.content.trim().is_empty()
            || utf16_len(&poem.content) > 100_000
            || utf16_len(&poem.source) > 4_096
            || poem.sort_order < 0
            || poem
                .category_id
                .is_some_and(|category_id| !poetry_category_ids.contains(&category_id))
            || poem.created_at < 0
            || poem.updated_at < poem.created_at
        {
            return Err(DataError::Validation(
                "Imported poem is invalid or duplicated".to_owned(),
            ));
        }
    }
    Ok(())
}

fn get_managed_settings_from(connection: &Connection) -> Result<ManagedSettings, DataError> {
    let json: String = connection.query_row(
        "SELECT managed_json FROM app_settings WHERE id = 1",
        [],
        |row| row.get(0),
    )?;
    let mut settings: ManagedSettings = serde_json::from_str(&json)?;
    settings.normalize_android_compatible();
    settings.validate().map_err(DataError::Validation)?;
    Ok(settings)
}

fn get_local_paths_from(connection: &Connection) -> Result<LocalPaths, DataError> {
    connection
        .query_row(
            "SELECT diary_path, media_path, backup_path FROM local_paths WHERE id = 1",
            [],
            |row| {
                Ok(LocalPaths {
                    diary_path: row.get(0)?,
                    media_path: row.get(1)?,
                    backup_path: row.get(2)?,
                })
            },
        )
        .map_err(DataError::from)
}

fn list_thoughts_from(
    connection: &Connection,
    include_deleted: bool,
) -> Result<Vec<Thought>, DataError> {
    let mut statement = connection.prepare(
        "SELECT id, content, created_at, updated_at, pinned, deleted_at, sort_order,
                category_id, highlighted
         FROM thoughts
         WHERE (?1 = 1 OR deleted_at IS NULL)
         ORDER BY CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END,
                  pinned DESC, sort_order ASC, updated_at DESC, id DESC",
    )?;
    statement
        .query_map(params![include_deleted], map_thought)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(DataError::from)
}

fn list_categories_from(connection: &Connection) -> Result<Vec<ThoughtCategory>, DataError> {
    let mut statement = connection.prepare(
        "SELECT id, name, color_argb, sort_order, created_at, updated_at
         FROM thought_categories ORDER BY sort_order, name COLLATE NOCASE, id",
    )?;
    statement
        .query_map([], map_category)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(DataError::from)
}

fn list_date_records_from(connection: &Connection) -> Result<Vec<DateRecord>, DataError> {
    let mut statement = connection.prepare(
        "SELECT id, name, icon, date_iso, created_at, updated_at
         FROM date_records ORDER BY date_iso, updated_at DESC, id",
    )?;
    statement
        .query_map([], map_date_record)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(DataError::from)
}

fn list_poems_from(connection: &Connection) -> Result<Vec<SavedPoem>, DataError> {
    let mut statement = connection.prepare(
        "SELECT id, content, source, created_at, updated_at, sort_order, category_id
         FROM saved_poems ORDER BY sort_order, created_at DESC, id DESC",
    )?;
    statement
        .query_map([], map_poem)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(DataError::from)
}

fn list_poetry_categories_from(connection: &Connection) -> Result<Vec<PoetryCategory>, DataError> {
    let mut statement = connection.prepare(
        "SELECT id, name, color_argb, sort_order, created_at, updated_at
         FROM poetry_categories ORDER BY sort_order, created_at, id",
    )?;
    statement
        .query_map([], map_poetry_category)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(DataError::from)
}

fn get_compatibility_shadow_from(
    connection: &Connection,
) -> Result<Option<CompatibilityShadow>, DataError> {
    connection
        .query_row(
            "SELECT ciphertext, source_sha256, imported_at
             FROM compatibility_shadow WHERE id = 1",
            [],
            |row| {
                Ok(CompatibilityShadow {
                    ciphertext: row.get(0)?,
                    source_sha256: row.get(1)?,
                    imported_at: row.get(2)?,
                })
            },
        )
        .optional()
        .map_err(DataError::from)
}

fn map_thought(row: &rusqlite::Row<'_>) -> rusqlite::Result<Thought> {
    Ok(Thought {
        id: row.get(0)?,
        content: row.get(1)?,
        created_at: row.get(2)?,
        updated_at: row.get(3)?,
        pinned: row.get(4)?,
        deleted_at: row.get(5)?,
        sort_order: row.get(6)?,
        category_id: row.get(7)?,
        highlighted: row.get(8)?,
    })
}

fn map_category(row: &rusqlite::Row<'_>) -> rusqlite::Result<ThoughtCategory> {
    Ok(ThoughtCategory {
        id: row.get(0)?,
        name: row.get(1)?,
        color_argb: row.get(2)?,
        sort_order: row.get(3)?,
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
    })
}

fn map_date_record(row: &rusqlite::Row<'_>) -> rusqlite::Result<DateRecord> {
    Ok(DateRecord {
        id: row.get(0)?,
        name: row.get(1)?,
        icon: row.get(2)?,
        date_iso: row.get(3)?,
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
    })
}

fn map_poem(row: &rusqlite::Row<'_>) -> rusqlite::Result<SavedPoem> {
    Ok(SavedPoem {
        id: row.get(0)?,
        content: row.get(1)?,
        source: row.get(2)?,
        created_at: row.get(3)?,
        updated_at: row.get(4)?,
        sort_order: row.get(5)?,
        category_id: row.get(6)?,
    })
}

fn map_poetry_category(row: &rusqlite::Row<'_>) -> rusqlite::Result<PoetryCategory> {
    Ok(PoetryCategory {
        id: row.get(0)?,
        name: row.get(1)?,
        color_argb: row.get(2)?,
        sort_order: row.get(3)?,
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
    })
}

fn get_thought_from(transaction: &Transaction<'_>, id: i64) -> Result<Thought, DataError> {
    transaction
        .query_row(
            "SELECT id, content, created_at, updated_at, pinned, deleted_at, sort_order,
                    category_id, highlighted
             FROM thoughts WHERE id = ?1",
            params![id],
            map_thought,
        )
        .optional()?
        .ok_or(DataError::NotFound)
}

fn get_category_from(transaction: &Transaction<'_>, id: i64) -> Result<ThoughtCategory, DataError> {
    transaction
        .query_row(
            "SELECT id, name, color_argb, sort_order, created_at, updated_at
             FROM thought_categories WHERE id = ?1",
            params![id],
            map_category,
        )
        .optional()?
        .ok_or(DataError::NotFound)
}

fn get_date_record_from(transaction: &Transaction<'_>, id: i64) -> Result<DateRecord, DataError> {
    transaction
        .query_row(
            "SELECT id, name, icon, date_iso, created_at, updated_at
             FROM date_records WHERE id = ?1",
            params![id],
            map_date_record,
        )
        .optional()?
        .ok_or(DataError::NotFound)
}

fn get_poem_from(transaction: &Transaction<'_>, id: i64) -> Result<SavedPoem, DataError> {
    transaction
        .query_row(
            "SELECT id, content, source, created_at, updated_at, sort_order, category_id
             FROM saved_poems WHERE id = ?1",
            params![id],
            map_poem,
        )
        .optional()?
        .ok_or(DataError::NotFound)
}

fn get_poetry_category_from(
    transaction: &Transaction<'_>,
    id: i64,
) -> Result<PoetryCategory, DataError> {
    transaction
        .query_row(
            "SELECT id, name, color_argb, sort_order, created_at, updated_at
             FROM poetry_categories WHERE id = ?1",
            params![id],
            map_poetry_category,
        )
        .optional()?
        .ok_or(DataError::NotFound)
}

fn validate_generation_id(value: &str, field: &str) -> Result<(), DataError> {
    let parsed = Uuid::parse_str(value)
        .map_err(|_| DataError::Validation(format!("{field} must be a UUID")))?;
    if parsed.is_nil() || parsed.hyphenated().to_string() != value.to_ascii_lowercase() {
        return Err(DataError::Validation(format!(
            "{field} must be a canonical non-nil UUID"
        )));
    }
    Ok(())
}

fn validate_vault_metadata(metadata: &VaultMetadataRecord) -> Result<(), DataError> {
    if metadata.crypto_version < 1 {
        return Err(DataError::Validation(
            "Vault crypto version is invalid".to_owned(),
        ));
    }
    validate_generation_id(&metadata.generation_id, "vault.generationId")?;
    if metadata.revision < 0
        || !(16..=64).contains(&metadata.salt.len())
        || !(10_000..=10_000_000).contains(&metadata.kdf_iterations)
        || metadata.verifier_ciphertext.is_empty()
        || metadata.verifier_ciphertext.len() > 4_096
        || !(12..=32).contains(&metadata.verifier_nonce.len())
    {
        return Err(DataError::Validation(
            "Vault metadata exceeds its safety limits".to_owned(),
        ));
    }
    require_nonnegative_timestamp(metadata.created_at, "vault.createdAt")?;
    if metadata.updated_at < metadata.created_at {
        return Err(DataError::Validation(
            "Vault metadata timestamps are invalid".to_owned(),
        ));
    }
    Ok(())
}

fn validate_vault_ciphertext(ciphertext: &[u8], nonce: &[u8]) -> Result<(), DataError> {
    if ciphertext.is_empty()
        || ciphertext.len() > MAX_VAULT_CIPHERTEXT_BYTES
        || !(12..=32).contains(&nonce.len())
    {
        return Err(DataError::Validation(
            "Vault ciphertext exceeds its safety limits".to_owned(),
        ));
    }
    Ok(())
}

fn validate_vault_item(item: &VaultItemRecord) -> Result<(), DataError> {
    require_positive_id(item.id, "vaultItem.id")?;
    validate_generation_id(&item.generation_id, "vaultItem.generationId")?;
    validate_vault_ciphertext(&item.ciphertext, &item.nonce)?;
    require_nonnegative_timestamp(item.created_at, "vaultItem.createdAt")?;
    if item.updated_at < item.created_at || item.sort_order < 0 {
        return Err(DataError::Validation(
            "Vault item timestamps or order are invalid".to_owned(),
        ));
    }
    Ok(())
}

fn validate_cloud_sync_settings(settings: &CloudSyncSettingsRecord) -> Result<(), DataError> {
    if !(15..=10_080).contains(&settings.interval_minutes) {
        return Err(DataError::Validation(
            "Cloud interval is outside the allowed range".to_owned(),
        ));
    }
    require_nonnegative_timestamp(settings.updated_at, "cloudSync.updatedAt")
}

fn validate_cloud_config_id(value: &str) -> Result<(), DataError> {
    if value.is_empty()
        || value.len() > 80
        || value != value.trim()
        || value.chars().any(char::is_control)
    {
        return Err(DataError::Validation(
            "Cloud configuration ID is invalid".to_owned(),
        ));
    }
    Ok(())
}

fn validate_cloud_sync_config(config: &CloudSyncConfigRecord) -> Result<(), DataError> {
    validate_cloud_config_id(&config.id)?;
    if config.name.trim().is_empty()
        || config.name.chars().count() > 200
        || config.name.chars().any(char::is_control)
        || config.endpoint_url.encode_utf16().count() > 4_096
        || config.remote_path.len() > 1_024
        || config.user_agent.trim().is_empty()
        || config.user_agent.encode_utf16().count() > 512
        || config.user_agent != config.user_agent.trim()
        || config.webdav_username.encode_utf16().count() > 512
        || config.s3_bucket.len() > 255
        || config.s3_region.len() > 128
        || config.sort_order < 0
    {
        return Err(DataError::Validation(
            "Cloud configuration fields are invalid".to_owned(),
        ));
    }
    for value in [
        &config.endpoint_url,
        &config.remote_path,
        &config.user_agent,
        &config.webdav_username,
        &config.s3_bucket,
        &config.s3_region,
    ] {
        if value.chars().any(char::is_control) {
            return Err(DataError::Validation(
                "Cloud configuration contains control characters".to_owned(),
            ));
        }
    }
    if !matches!(config.service_type.as_str(), "WEBDAV" | "S3_COMPATIBLE")
        || !matches!(config.direction.as_str(), "UPLOAD_ONLY" | "TWO_WAY")
    {
        return Err(DataError::Validation(
            "Cloud service type or direction is invalid".to_owned(),
        ));
    }
    if config.service_type == "S3_COMPATIBLE"
        && (config.s3_bucket.trim().is_empty()
            || config.s3_bucket != config.s3_bucket.trim()
            || matches!(config.s3_bucket.as_str(), "." | "..")
            || config.s3_bucket.contains(['/', '\\'])
            || !valid_cloud_region(&config.s3_region))
    {
        return Err(DataError::Validation(
            "S3 bucket and region are required".to_owned(),
        ));
    }
    if config.remote_path.contains('\\')
        || config
            .remote_path
            .trim()
            .trim_matches('/')
            .split('/')
            .any(|segment| matches!(segment, "." | ".."))
    {
        return Err(DataError::Validation(
            "Cloud remote path is invalid".to_owned(),
        ));
    }
    let endpoint = reqwest::Url::parse(&config.endpoint_url)
        .map_err(|_| DataError::Validation("Cloud endpoint is invalid".to_owned()))?;
    let allowed_scheme =
        endpoint.scheme() == "https" || config.allow_insecure_http && endpoint.scheme() == "http";
    if !allowed_scheme
        || endpoint.host_str().is_none()
        || !endpoint.username().is_empty()
        || endpoint.password().is_some()
        || endpoint.query().is_some()
        || endpoint.fragment().is_some()
    {
        return Err(DataError::Validation(
            "Cloud endpoint is outside the allowed URL boundary".to_owned(),
        ));
    }

    let selected: Vec<String> = serde_json::from_str(&config.selected_contents_json)?;
    let mut unique = HashSet::with_capacity(selected.len());
    if selected.is_empty()
        || selected.len() > 4
        || selected.iter().any(|value| {
            !matches!(
                value.as_str(),
                "DIARIES" | "MEDIA" | "JSON_BACKUP" | "USAGE_STATISTICS"
            ) || !unique.insert(value)
        })
    {
        return Err(DataError::Validation(
            "Cloud selected contents are invalid".to_owned(),
        ));
    }
    require_nonnegative_timestamp(config.updated_at, "cloudSync.updatedAt")
}

fn valid_cloud_region(value: &str) -> bool {
    let bytes = value.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 128
        && bytes[0].is_ascii_alphanumeric()
        && bytes
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(byte))
}

fn validate_lower_sha256(value: &str, field: &str) -> Result<(), DataError> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        return Err(DataError::Validation(format!("{field} is not a SHA-256")));
    }
    Ok(())
}

fn validate_cloud_sync_secret(secret: &CloudSyncSecretRecord) -> Result<(), DataError> {
    validate_cloud_config_id(&secret.config_id)?;
    if secret.dpapi_ciphertext.is_empty() || secret.dpapi_ciphertext.len() > MAX_CLOUD_SECRET_BYTES
    {
        return Err(DataError::Validation(
            "Cloud credential ciphertext exceeds its safety limit".to_owned(),
        ));
    }
    validate_lower_sha256(&secret.binding_sha256, "cloudSecret.bindingSha256")?;
    require_nonnegative_timestamp(secret.updated_at, "cloudSecret.updatedAt")
}

fn validate_cloud_sync_base(state: &CloudSyncBaseStateRecord) -> Result<(), DataError> {
    validate_cloud_config_id(&state.config_id)?;
    validate_lower_sha256(&state.scope_fingerprint, "cloudSyncBase.scopeFingerprint")?;
    if state.hashes_by_key.len() > MAX_CLOUD_BASE_ENTRIES {
        return Err(DataError::Validation(
            "Cloud base state contains too many objects".to_owned(),
        ));
    }
    for (key, hash) in &state.hashes_by_key {
        if key.is_empty()
            || key.len() > 2_048
            || key.starts_with(['/', '\\'])
            || key.contains('\\')
            || key.chars().any(char::is_control)
            || key
                .split('/')
                .any(|component| component.is_empty() || matches!(component, "." | ".."))
        {
            return Err(DataError::Validation(
                "Cloud base state contains an invalid object key".to_owned(),
            ));
        }
        validate_lower_sha256(hash, "cloudSyncBase.sha256")?;
    }
    require_nonnegative_timestamp(state.updated_at, "cloudSyncBase.updatedAt")
}

fn validate_short_token(value: &str, field: &str) -> Result<(), DataError> {
    validate_generation_id(value, field)
}

fn validate_optional_timestamp(value: Option<i64>, field: &str) -> Result<(), DataError> {
    if let Some(value) = value {
        require_nonnegative_timestamp(value, field)?;
    }
    Ok(())
}

fn validate_cloud_sync_status(
    status: &CloudSyncStatusRecord,
    require_terminal: bool,
) -> Result<(), DataError> {
    validate_cloud_config_id(&status.config_id)?;
    if !matches!(
        status.state.as_str(),
        "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED"
    ) || require_terminal && matches!(status.state.as_str(), "IDLE" | "RUNNING")
    {
        return Err(DataError::Validation(
            "Cloud synchronization status is invalid".to_owned(),
        ));
    }
    if let Some(run_token) = status.run_token.as_deref() {
        validate_short_token(run_token, "cloudSync.runToken")?;
    }
    validate_optional_timestamp(status.last_started_at, "cloudSync.lastStartedAt")?;
    validate_optional_timestamp(status.last_completed_at, "cloudSync.lastCompletedAt")?;
    validate_optional_timestamp(status.last_success_at, "cloudSync.lastSuccessAt")?;
    require_nonnegative_timestamp(status.updated_at, "cloudSync.updatedAt")?;
    if [
        status.uploaded_count,
        status.downloaded_count,
        status.conflict_count,
        status.transferred_bytes,
    ]
    .into_iter()
    .any(|value| value < 0)
    {
        return Err(DataError::Validation(
            "Cloud synchronization counters are invalid".to_owned(),
        ));
    }
    if let Some(error_code) = status.last_error_code.as_deref()
        && (error_code.is_empty()
            || error_code.len() > 80
            || !error_code
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_'))
    {
        return Err(DataError::Validation(
            "Cloud error code is invalid".to_owned(),
        ));
    }
    if status.state == "SUCCEEDED"
        && (status.last_completed_at.is_none()
            || status.last_success_at != status.last_completed_at
            || status.last_error_code.is_some())
    {
        return Err(DataError::Validation(
            "Successful cloud status is inconsistent".to_owned(),
        ));
    }
    if status.state == "FAILED"
        && (status.last_completed_at.is_none() || status.last_error_code.is_none())
    {
        return Err(DataError::Validation(
            "Failed cloud status is incomplete".to_owned(),
        ));
    }
    if let (Some(started), Some(completed)) = (status.last_started_at, status.last_completed_at)
        && completed < started
    {
        return Err(DataError::Validation(
            "Cloud synchronization timestamps are invalid".to_owned(),
        ));
    }
    Ok(())
}

fn validate_thought_draft(draft: &ThoughtDraft) -> Result<(), DataError> {
    if utf16_len(&draft.content) > 1_000_000 {
        return Err(DataError::Validation(
            "Thought content exceeds 1,000,000 characters".to_owned(),
        ));
    }
    if let Some(category_id) = draft.category_id {
        require_positive_id(category_id, "thought.categoryId")?;
    }
    Ok(())
}

fn validate_category_draft(draft: &ThoughtCategoryDraft) -> Result<(), DataError> {
    if draft.name.trim().is_empty() || utf16_len(&draft.name) > 40 {
        return Err(DataError::Validation(
            "Category name must be 1 to 40 characters".to_owned(),
        ));
    }
    Ok(())
}

fn validate_date_record_draft(draft: &DateRecordDraft) -> Result<(), DataError> {
    if draft.name.trim().is_empty() || utf16_len(&draft.name) > 256 {
        return Err(DataError::Validation(
            "Date record name must be 1 to 256 characters".to_owned(),
        ));
    }
    if draft.icon.trim().is_empty() || utf16_len(&draft.icon) > 64 {
        return Err(DataError::Validation(
            "Date record icon must be 1 to 64 characters".to_owned(),
        ));
    }
    if !valid_date_iso(&draft.date_iso) {
        return Err(DataError::Validation(
            "dateIso must be a valid yyyy-MM-dd date".to_owned(),
        ));
    }
    Ok(())
}

fn validate_poem_draft(draft: &SavedPoemDraft) -> Result<(), DataError> {
    if draft.content.trim().is_empty() || utf16_len(&draft.content) > 100_000 {
        return Err(DataError::Validation(
            "Poem content must be 1 to 100,000 characters".to_owned(),
        ));
    }
    if utf16_len(&draft.source) > 4_096 {
        return Err(DataError::Validation(
            "Poem source exceeds 4,096 characters".to_owned(),
        ));
    }
    if let Some(category_id) = draft.category_id {
        require_positive_id(category_id, "poem.categoryId")?;
    }
    Ok(())
}

fn normalize_poetry_category_name(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn validate_poetry_category_draft(draft: &PoetryCategoryDraft) -> Result<(), DataError> {
    if let Some(id) = draft.id {
        require_positive_id(id, "poetryCategory.id")?;
    }
    if draft.name.trim().is_empty() || utf16_len(&draft.name) > 100 {
        return Err(DataError::Validation(
            "Poetry category name must be 1 to 100 characters".to_owned(),
        ));
    }
    Ok(())
}

fn list_poetry_category_ids(connection: &Connection) -> Result<Vec<i64>, DataError> {
    let mut statement = connection
        .prepare("SELECT id FROM poetry_categories ORDER BY sort_order, created_at, id")?;
    statement
        .query_map([], |row| row.get(0))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(DataError::from)
}

fn replace_poetry_category_order(
    transaction: &Transaction<'_>,
    ids: &[i64],
) -> Result<(), DataError> {
    for (index, id) in ids.iter().copied().enumerate() {
        require_changed(transaction.execute(
            "UPDATE poetry_categories SET sort_order = ?1 WHERE id = ?2",
            params![index as i64, id],
        )?)?;
    }
    Ok(())
}

fn normalize_poetry_category_order(transaction: &Transaction<'_>) -> Result<(), DataError> {
    let ids = list_poetry_category_ids(transaction)?;
    replace_poetry_category_order(transaction, &ids)
}

fn list_poem_ids(
    connection: &Connection,
    category_scope: Option<Option<i64>>,
) -> Result<Vec<i64>, DataError> {
    let sql = match category_scope {
        None => {
            "SELECT id FROM saved_poems
             ORDER BY sort_order, created_at DESC, id DESC"
        }
        Some(Some(_)) => {
            "SELECT id FROM saved_poems WHERE category_id = ?1
             ORDER BY sort_order, created_at DESC, id DESC"
        }
        Some(None) => {
            "SELECT id FROM saved_poems WHERE category_id IS NULL
             ORDER BY sort_order, created_at DESC, id DESC"
        }
    };
    let mut statement = connection.prepare(sql)?;
    match category_scope {
        Some(Some(category_id)) => statement
            .query_map(params![category_id], |row| row.get(0))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(DataError::from),
        _ => statement
            .query_map([], |row| row.get(0))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(DataError::from),
    }
}

fn replace_poem_order(transaction: &Transaction<'_>, ids: &[i64]) -> Result<(), DataError> {
    for (index, id) in ids.iter().copied().enumerate() {
        require_changed(transaction.execute(
            "UPDATE saved_poems SET sort_order = ?1 WHERE id = ?2",
            params![index as i64, id],
        )?)?;
    }
    Ok(())
}

fn normalize_poem_order(transaction: &Transaction<'_>) -> Result<(), DataError> {
    let ids = list_poem_ids(transaction, None)?;
    replace_poem_order(transaction, &ids)
}

fn move_id_to_index(ids: &mut Vec<i64>, id: i64, target_index: usize) -> Result<(), DataError> {
    let current = ids
        .iter()
        .position(|candidate| *candidate == id)
        .ok_or(DataError::NotFound)?;
    let value = ids.remove(current);
    let target = target_index.min(ids.len());
    ids.insert(target, value);
    Ok(())
}

fn replace_subset_order(all_ids: &[i64], subset_ids: &[i64]) -> Result<Vec<i64>, DataError> {
    let subset = subset_ids.iter().copied().collect::<HashSet<_>>();
    if subset.len() != subset_ids.len()
        || all_ids.iter().filter(|id| subset.contains(id)).count() != subset_ids.len()
    {
        return Err(DataError::Validation(
            "Poem subset order is inconsistent".to_owned(),
        ));
    }
    let mut replacements = subset_ids.iter().copied();
    Ok(all_ids
        .iter()
        .copied()
        .map(|id| {
            if subset.contains(&id) {
                replacements.next().unwrap_or(id)
            } else {
                id
            }
        })
        .collect())
}

fn validate_shadow(
    ciphertext: &[u8],
    source_sha256: &str,
    imported_at: i64,
) -> Result<(), DataError> {
    if ciphertext.is_empty() || ciphertext.len() > MAX_SHADOW_BYTES {
        return Err(DataError::Validation(
            "Compatibility shadow has an invalid size".to_owned(),
        ));
    }
    if source_sha256.len() != 64
        || !source_sha256
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        return Err(DataError::Validation(
            "Compatibility shadow hash is invalid".to_owned(),
        ));
    }
    require_nonnegative_timestamp(imported_at, "importedAt")
}

fn validate_local_path_value(field: &str, value: Option<&str>) -> Result<(), DataError> {
    if value.is_some_and(|path| path.is_empty() || path.len() > 32_768 || path.contains('\0')) {
        Err(DataError::Validation(format!("{field} is invalid")))
    } else {
        Ok(())
    }
}

fn require_positive_id(value: i64, field: &str) -> Result<(), DataError> {
    if value > 0 {
        Ok(())
    } else {
        Err(DataError::Validation(format!("{field} must be positive")))
    }
}

fn require_nonnegative_timestamp(value: i64, field: &str) -> Result<(), DataError> {
    if value >= 0 {
        Ok(())
    } else {
        Err(DataError::Validation(format!(
            "{field} must not be negative"
        )))
    }
}

fn require_changed(changed: usize) -> Result<(), DataError> {
    if changed == 1 {
        Ok(())
    } else {
        Err(DataError::NotFound)
    }
}

fn valid_date_iso(value: &str) -> bool {
    value.len() == 10 && NaiveDate::parse_from_str(value, "%Y-%m-%d").is_ok()
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

pub fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

#[cfg(test)]
mod tests {
    use tempfile::TempDir;

    use super::*;

    fn database() -> (TempDir, Database) {
        let directory = tempfile::tempdir().expect("create temp directory");
        let database =
            Database::open(directory.path().join("deskcubby.db")).expect("create test database");
        (directory, database)
    }

    fn create_v1_fixture(path: &Path) {
        let mut connection = Connection::open(path).expect("open v1 fixture");
        connection
            .pragma_update(None, "foreign_keys", true)
            .expect("foreign keys");
        Database::migrate_0_to_1(&mut connection).expect("create v1 schema");
        let settings_json =
            serde_json::to_string(&ManagedSettings::default()).expect("settings JSON");
        connection
            .execute(
                "INSERT INTO app_settings(id, managed_json, updated_at) VALUES(1, ?1, 10)",
                params![settings_json],
            )
            .expect("v1 settings");
        connection
            .execute(
                "INSERT INTO local_paths(id, diary_path, media_path, backup_path)
                 VALUES(1, 'C:\\diary', 'C:\\media', 'C:\\backup')",
                [],
            )
            .expect("v1 paths");
        connection
            .execute(
                "INSERT INTO thought_categories(
                    id, name, color_argb, sort_order, created_at, updated_at
                 ) VALUES(5, 'v1 category', -1, 0, 1, 2)",
                [],
            )
            .expect("v1 category");
        connection
            .execute(
                "INSERT INTO thoughts(
                    id, content, created_at, updated_at, pinned, deleted_at,
                    sort_order, category_id, highlighted
                 ) VALUES(7, 'v1 thought', 1, 2, 1, NULL, 0, 5, 1)",
                [],
            )
            .expect("v1 thought");
        connection
            .execute(
                "INSERT INTO date_records(
                    id, name, icon, date_iso, created_at, updated_at
                 ) VALUES(9, 'v1 date', 'event', '2026-07-29', 1, 2)",
                [],
            )
            .expect("v1 date");
        connection
            .execute(
                "INSERT INTO saved_poems(
                    id, content, source, created_at, updated_at
                 ) VALUES(11, 'v1 poem', 'v1 source', 1, 2)",
                [],
            )
            .expect("v1 poem");
        connection
            .execute(
                "INSERT INTO compatibility_shadow(
                    id, ciphertext, source_sha256, imported_at
                 ) VALUES(1, X'010203', ?1, 3)",
                params!["0".repeat(64)],
            )
            .expect("v1 shadow");
    }

    fn create_v5_fixture(path: &Path) {
        create_v1_fixture(path);
        let mut connection = Connection::open(path).expect("open v1 fixture");
        connection
            .pragma_update(None, "foreign_keys", true)
            .expect("foreign keys");
        Database::migrate_1_to_2(&mut connection).expect("create v2 schema");
        Database::migrate_2_to_3(&mut connection).expect("create v3 schema");
        Database::migrate_3_to_4(&mut connection).expect("create v4 schema");
        Database::migrate_4_to_5(&mut connection).expect("create v5 schema");
        connection
            .execute_batch(
                "INSERT INTO saved_poems(
                    id, content, source, created_at, updated_at
                 ) VALUES
                    (12, 'newer poem', 'source two', 3, 5),
                    (13, 'newest tie', 'source three', 4, 5);",
            )
            .expect("additional v5 poems");
    }

    fn table_exists(connection: &Connection, name: &str) -> bool {
        connection
            .query_row(
                "SELECT EXISTS(
                    SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?1
                 )",
                params![name],
                |row| row.get(0),
            )
            .expect("table lookup")
    }

    fn vault_metadata(generation_id: &str, revision: i64, updated_at: i64) -> VaultMetadataRecord {
        VaultMetadataRecord {
            crypto_version: 1,
            generation_id: generation_id.to_owned(),
            revision,
            salt: vec![1; 16],
            kdf_iterations: 120_000,
            verifier_ciphertext: vec![2; 32],
            verifier_nonce: vec![3; 12],
            created_at: 1,
            updated_at,
        }
    }

    fn cloud_config(id: &str, updated_at: i64) -> CloudSyncConfigRecord {
        CloudSyncConfigRecord {
            id: id.to_owned(),
            name: "Personal WebDAV".to_owned(),
            enabled: true,
            service_type: "WEBDAV".to_owned(),
            endpoint_url: "https://cloud.example.com/dav".to_owned(),
            remote_path: "DeskCubby".to_owned(),
            user_agent: "DeskCubby-Sync/1".to_owned(),
            webdav_username: "user".to_owned(),
            s3_bucket: String::new(),
            s3_region: "us-east-1".to_owned(),
            s3_path_style: true,
            allow_insecure_http: false,
            selected_contents_json: r#"["DIARIES","MEDIA","JSON_BACKUP"]"#.to_owned(),
            direction: "TWO_WAY".to_owned(),
            sort_order: 0,
            updated_at,
        }
    }

    #[test]
    fn enables_wal_foreign_keys_and_busy_timeout() {
        let (_directory, database) = database();
        let connection = database.connect().expect("open connection");
        let journal_mode: String = connection
            .pragma_query_value(None, "journal_mode", |row| row.get(0))
            .expect("journal mode");
        let foreign_keys: i32 = connection
            .pragma_query_value(None, "foreign_keys", |row| row.get(0))
            .expect("foreign keys");
        let busy_timeout: i64 = connection
            .pragma_query_value(None, "busy_timeout", |row| row.get(0))
            .expect("busy timeout");
        assert_eq!(journal_mode.to_ascii_lowercase(), "wal");
        assert_eq!(foreign_keys, 1);
        assert_eq!(busy_timeout, 5_000);
    }

    #[test]
    fn fresh_database_uses_latest_schema_and_has_no_update_attempt() {
        let (_directory, database) = database();
        let version: i32 = database
            .connect()
            .expect("open connection")
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("user version");
        let update_settings = database.get_update_settings().expect("update defaults");

        assert_eq!(version, SCHEMA_VERSION);
        assert!(update_settings.automatic_checks_enabled);
        assert_eq!(update_settings.last_attempted_at, None);
    }

    #[test]
    fn thought_crud_preserves_soft_deleted_items() {
        let (_directory, database) = database();
        let category = database
            .save_category(ThoughtCategoryDraft {
                id: None,
                name: "Work".to_owned(),
                color_argb: -1,
            })
            .expect("save category");
        let thought = database
            .save_thought(ThoughtDraft {
                id: None,
                content: "hello".to_owned(),
                pinned: true,
                category_id: Some(category.id),
                highlighted: true,
            })
            .expect("save thought");
        database
            .soft_delete_thought(thought.id, now_millis())
            .expect("soft delete");
        assert!(
            database
                .list_thoughts(false)
                .expect("list active")
                .is_empty()
        );
        assert_eq!(database.list_thoughts(true).expect("list all").len(), 1);
        database
            .restore_thought(thought.id, now_millis())
            .expect("restore");
        assert_eq!(database.list_thoughts(false).expect("list active").len(), 1);
    }

    #[test]
    fn deleting_category_sets_thought_reference_to_null() {
        let (_directory, database) = database();
        let category = database
            .save_category(ThoughtCategoryDraft {
                id: None,
                name: "Temporary".to_owned(),
                color_argb: 123,
            })
            .expect("save category");
        database
            .save_thought(ThoughtDraft {
                id: None,
                content: "linked".to_owned(),
                pinned: false,
                category_id: Some(category.id),
                highlighted: false,
            })
            .expect("save thought");
        database
            .delete_category(category.id)
            .expect("delete category");
        assert_eq!(
            database.list_thoughts(false).expect("list")[0].category_id,
            None
        );
    }

    #[test]
    fn invalid_snapshot_rolls_back_before_mutation() {
        let (_directory, database) = database();
        let original = database
            .save_thought(ThoughtDraft {
                id: None,
                content: "keep me".to_owned(),
                pinned: false,
                category_id: None,
                highlighted: false,
            })
            .expect("save thought");
        let mut snapshot = database.snapshot_core().expect("snapshot");
        snapshot.thoughts = vec![Thought {
            id: 20,
            content: "invalid relation".to_owned(),
            created_at: 1,
            updated_at: 1,
            pinned: false,
            deleted_at: None,
            sort_order: 0,
            category_id: Some(999),
            highlighted: false,
        }];
        assert!(database.restore_core_snapshot(&snapshot).is_err());
        assert_eq!(
            database.list_thoughts(false).expect("list")[0].id,
            original.id
        );
    }

    #[test]
    fn android_utf16_lengths_are_used_for_core_text() {
        let (_directory, database) = database();
        let accepted = "类".repeat(40);
        database
            .save_category(ThoughtCategoryDraft {
                id: None,
                name: accepted,
                color_argb: -1,
            })
            .expect("40 UTF-16 code units are valid");
        assert!(
            database
                .save_category(ThoughtCategoryDraft {
                    id: None,
                    name: "类".repeat(41),
                    color_argb: -1,
                })
                .is_err()
        );
    }

    #[test]
    fn incomplete_recovery_shadow_is_rejected_without_mutation() {
        let (_directory, database) = database();
        let original = database
            .save_thought(ThoughtDraft {
                id: None,
                content: "keep me".to_owned(),
                pinned: false,
                category_id: None,
                highlighted: false,
            })
            .expect("save thought");
        let mut snapshot = database.snapshot_core().expect("snapshot");
        snapshot.encrypted_compatibility_shadow = Some(vec![1, 2, 3]);
        snapshot.compatibility_source_sha256 = None;
        assert!(matches!(
            database.restore_core_snapshot(&snapshot),
            Err(DataError::Validation(message)) if message.contains("incomplete")
        ));
        assert_eq!(
            database.list_thoughts(false).expect("list")[0].id,
            original.id
        );
    }

    #[test]
    fn import_without_shadow_clears_stale_shadow_transactionally() {
        let (_directory, database) = database();
        let source_sha256 = "0".repeat(64);
        database
            .put_compatibility_shadow(b"encrypted", &source_sha256, 1)
            .expect("seed shadow");
        database
            .replace_imported_core(
                &ManagedSettings::default(),
                &[],
                &[],
                &[],
                &[],
                &[],
                &[],
                &[],
                false,
                false,
                None,
                &source_sha256,
                2,
            )
            .expect("replace without shadow");
        assert!(
            database
                .get_compatibility_shadow()
                .expect("read shadow")
                .is_none()
        );
    }

    #[test]
    fn settings_are_canonicalized_before_persistence() {
        let (_directory, database) = database();
        let settings = ManagedSettings {
            theme_color_argb: 0x0012_3456,
            theme_secondary_colors_argb: vec![0x0012_3456, 0xFF12_3456u32 as i32],
            image_max_width_dp: -1,
            thought_highlight_color_argb: 0x0001_0203,
            meal_calendar_image_max_height_dp: 9_999,
            ..ManagedSettings::default()
        };

        database
            .put_managed_settings(&settings, 1)
            .expect("persist normalized settings");
        let stored = database.get_managed_settings().expect("read settings");

        assert_eq!(stored.theme_color_argb, 0xFF12_3456u32 as i32);
        assert_eq!(
            stored.theme_secondary_colors_argb,
            vec![0xFF12_3456u32 as i32, 0xFFC9_6F4Au32 as i32]
        );
        assert_eq!(stored.image_max_width_dp, 120);
        assert_eq!(stored.thought_highlight_color_argb, 0xFF01_0203u32 as i32);
        assert_eq!(stored.meal_calendar_image_max_height_dp, 320);
    }

    #[test]
    fn windows_configuration_rolls_back_both_rows_when_second_write_fails() {
        let (_directory, database) = database();
        let original_settings = ManagedSettings::default();
        let original_paths = LocalPaths {
            diary_path: Some(r"C:\old-diary".to_owned()),
            media_path: Some(r"C:\old-media".to_owned()),
            backup_path: Some(r"C:\old-backup".to_owned()),
        };
        database
            .put_windows_configuration(&original_settings, &original_paths, 1)
            .expect("seed configuration");
        let persisted_original_settings = database
            .get_managed_settings()
            .expect("read seeded settings");
        database
            .connect()
            .expect("connection")
            .execute_batch(
                "CREATE TRIGGER reject_local_paths_update
                 BEFORE UPDATE ON local_paths
                 BEGIN
                     SELECT RAISE(ABORT, 'simulated local path failure');
                 END;",
            )
            .expect("failure trigger");

        let mut replacement_settings = original_settings.clone();
        replacement_settings.visual_style = "LIQUID_GLASS".to_owned();
        let replacement_paths = LocalPaths {
            diary_path: Some(r"C:\new-diary".to_owned()),
            media_path: Some(r"C:\new-media".to_owned()),
            backup_path: Some(r"C:\new-backup".to_owned()),
        };
        assert!(matches!(
            database.put_windows_configuration(&replacement_settings, &replacement_paths, 2),
            Err(DataError::Sqlite(_))
        ));

        assert_eq!(
            database
                .get_managed_settings()
                .expect("settings after rollback"),
            persisted_original_settings
        );
        assert_eq!(
            database.get_local_paths().expect("paths after rollback"),
            original_paths
        );
    }

    #[test]
    fn migrates_v1_fixture_to_latest_without_losing_core_data() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v1_fixture(&path);

        let database = Database::open(&path).expect("migrate v1 to latest");
        let connection = database.connect().expect("open migrated database");
        let version: i32 = connection
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("user version");
        assert_eq!(version, SCHEMA_VERSION);
        for table in [
            "vault_metadata",
            "vault_items",
            "cloud_sync_settings",
            "cloud_sync_configs",
            "cloud_sync_secrets",
            "cloud_sync_base",
            "cloud_sync_status",
            "update_settings",
            "windows_notes_settings",
            "ai_conversations",
            "ai_messages",
            "game_states",
            "game_statistics",
            "game_engagement_times",
            "poetry_categories",
        ] {
            assert!(table_exists(&connection, table), "missing table {table}");
        }

        assert_eq!(
            database.list_thoughts(false).expect("thoughts")[0].content,
            "v1 thought"
        );
        assert_eq!(
            database.list_categories().expect("categories")[0].name,
            "v1 category"
        );
        assert_eq!(
            database.list_date_records().expect("dates")[0].name,
            "v1 date"
        );
        assert_eq!(database.list_poems().expect("poems")[0].content, "v1 poem");
        assert_eq!(
            database
                .get_compatibility_shadow()
                .expect("shadow")
                .expect("existing shadow")
                .ciphertext,
            vec![1, 2, 3]
        );
        let cloud_defaults = database.get_cloud_sync_settings().expect("cloud defaults");
        assert_eq!(cloud_defaults.interval_minutes, 360);
        assert!(!cloud_defaults.configs_managed);
        let update_settings = database.get_update_settings().expect("update defaults");
        assert!(update_settings.automatic_checks_enabled);
        assert_eq!(update_settings.last_attempted_at, None);
        assert_eq!(
            database.snapshot_core().expect("snapshot").schema_version,
            RECOVERY_SNAPSHOT_VERSION
        );
    }

    #[test]
    fn failed_v1_to_v2_migration_rolls_back_every_new_table_and_version() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v1_fixture(&path);
        {
            let connection = Connection::open(&path).expect("open v1 fixture");
            // This deliberately collides after the migration has already
            // created the vault and cloud-settings tables.
            connection
                .execute_batch("CREATE TABLE cloud_sync_configs(conflict INTEGER);")
                .expect("create migration collision");
        }

        assert!(matches!(Database::open(&path), Err(DataError::Sqlite(_))));
        let connection = Connection::open(&path).expect("inspect rolled-back fixture");
        let version: i32 = connection
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("user version");
        assert_eq!(version, 1);
        assert!(!table_exists(&connection, "vault_metadata"));
        assert!(!table_exists(&connection, "vault_items"));
        assert!(!table_exists(&connection, "cloud_sync_settings"));
        assert!(!table_exists(&connection, "cloud_sync_secrets"));
        assert!(!table_exists(&connection, "cloud_sync_base"));
        assert!(!table_exists(&connection, "cloud_sync_status"));
        assert!(!table_exists(&connection, "update_settings"));
        let thought: String = connection
            .query_row("SELECT content FROM thoughts WHERE id = 7", [], |row| {
                row.get(0)
            })
            .expect("preserved v1 row");
        assert_eq!(thought, "v1 thought");
    }

    #[test]
    fn migrates_v2_fixture_to_latest_preserving_update_preference() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v1_fixture(&path);
        {
            let mut connection = Connection::open(&path).expect("open v1 fixture");
            Database::migrate_1_to_2(&mut connection).expect("create v2 schema");
            connection
                .execute(
                    "INSERT INTO cloud_sync_settings(
                        id, automatic_sync_enabled, interval_minutes, updated_at
                     ) VALUES(1, 1, 120, 9)",
                    [],
                )
                .expect("v2 cloud settings");
            connection
                .execute(
                    "INSERT INTO update_settings(
                        id, automatic_checks_enabled, updated_at
                     ) VALUES(1, 0, 17)",
                    [],
                )
                .expect("v2 update settings");
        }

        let database = Database::open(&path).expect("migrate v2 to latest");
        let settings = database
            .get_cloud_sync_settings()
            .expect("migrated cloud settings");
        assert!(settings.automatic_sync_enabled);
        assert_eq!(settings.interval_minutes, 120);
        assert!(!settings.configs_managed);
        let update_settings = database
            .get_update_settings()
            .expect("migrated update settings");
        assert!(!update_settings.automatic_checks_enabled);
        assert_eq!(update_settings.last_attempted_at, None);
        assert_eq!(update_settings.updated_at, 17);
        let version: i32 = database
            .connect()
            .expect("connection")
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("version");
        assert_eq!(version, SCHEMA_VERSION);
    }

    #[test]
    fn failed_v2_to_v3_migration_keeps_v2_version_and_values() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v1_fixture(&path);
        {
            let mut connection = Connection::open(&path).expect("open v1 fixture");
            Database::migrate_1_to_2(&mut connection).expect("create v2 schema");
            connection
                .execute(
                    "INSERT INTO cloud_sync_settings(
                        id, automatic_sync_enabled, interval_minutes, updated_at
                     ) VALUES(1, 1, 75, 8)",
                    [],
                )
                .expect("v2 cloud settings");
            // Force the v3 ALTER TABLE to fail before it can advance the
            // version. Existing v2 values must remain readable and unchanged.
            connection
                .execute_batch(
                    "ALTER TABLE cloud_sync_settings
                     ADD COLUMN configs_managed INTEGER NOT NULL DEFAULT 0;",
                )
                .expect("create migration collision");
        }

        assert!(matches!(Database::open(&path), Err(DataError::Sqlite(_))));
        let connection = Connection::open(&path).expect("inspect v2 database");
        let version: i32 = connection
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("version");
        assert_eq!(version, 2);
        let values: (bool, i64) = connection
            .query_row(
                "SELECT automatic_sync_enabled, interval_minutes
                 FROM cloud_sync_settings WHERE id = 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("preserved settings");
        assert_eq!(values, (true, 75));
    }

    #[test]
    fn migrates_v3_fixture_to_latest_preserving_update_preference() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v1_fixture(&path);
        {
            let mut connection = Connection::open(&path).expect("open v1 fixture");
            Database::migrate_1_to_2(&mut connection).expect("create v2 schema");
            Database::migrate_2_to_3(&mut connection).expect("create v3 schema");
            connection
                .execute(
                    "INSERT INTO update_settings(
                        id, automatic_checks_enabled, updated_at
                     ) VALUES(1, 0, 23)",
                    [],
                )
                .expect("v3 update settings");
        }

        let database = Database::open(&path).expect("migrate v3 to latest");
        let settings = database
            .get_update_settings()
            .expect("migrated update settings");
        let version: i32 = database
            .connect()
            .expect("connection")
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("version");

        assert_eq!(version, SCHEMA_VERSION);
        assert!(!settings.automatic_checks_enabled);
        assert_eq!(settings.last_attempted_at, None);
        assert_eq!(settings.updated_at, 23);
    }

    #[test]
    fn failed_v3_to_v4_migration_keeps_v3_version_and_values() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v1_fixture(&path);
        {
            let mut connection = Connection::open(&path).expect("open v1 fixture");
            Database::migrate_1_to_2(&mut connection).expect("create v2 schema");
            Database::migrate_2_to_3(&mut connection).expect("create v3 schema");
            connection
                .execute(
                    "INSERT INTO update_settings(
                        id, automatic_checks_enabled, updated_at
                     ) VALUES(1, 0, 29)",
                    [],
                )
                .expect("v3 update settings");
            // Force the v4 ALTER TABLE to fail. The transaction must not
            // advance user_version or rewrite the existing preference row.
            connection
                .execute_batch(
                    "ALTER TABLE update_settings
                     ADD COLUMN last_attempted_at INTEGER;",
                )
                .expect("create migration collision");
        }

        assert!(matches!(Database::open(&path), Err(DataError::Sqlite(_))));
        let connection = Connection::open(&path).expect("inspect v3 database");
        let version: i32 = connection
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("version");
        let values: (bool, i64) = connection
            .query_row(
                "SELECT automatic_checks_enabled, updated_at
                 FROM update_settings WHERE id = 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("preserved settings");

        assert_eq!(version, 3);
        assert_eq!(values, (false, 29));
    }

    #[test]
    fn migrates_v5_poems_with_stable_order_and_enforced_category_foreign_key() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v5_fixture(&path);

        let database = Database::open(&path).expect("migrate v5 to v6");
        let connection = database.connect().expect("open migrated database");
        let version: i32 = connection
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("user version");
        assert_eq!(version, 6);
        assert!(table_exists(&connection, "poetry_categories"));

        let poems = database.list_poems().expect("migrated poems");
        assert_eq!(
            poems.iter().map(|poem| poem.id).collect::<Vec<_>>(),
            vec![13, 12, 11]
        );
        assert_eq!(
            poems.iter().map(|poem| poem.sort_order).collect::<Vec<_>>(),
            vec![0, 1, 2]
        );
        assert!(poems.iter().all(|poem| poem.category_id.is_none()));

        let category = database
            .save_poetry_category(PoetryCategoryDraft {
                id: None,
                name: "Migration category".to_owned(),
                color_argb: -1,
            })
            .expect("save poetry category");
        database
            .set_poem_category(13, Some(category.id))
            .expect("assign migrated poem");
        assert!(matches!(
            database.set_poem_category(12, Some(category.id + 10_000)),
            Err(DataError::Sqlite(_))
        ));

        connection
            .execute(
                "DELETE FROM poetry_categories WHERE id = ?1",
                params![category.id],
            )
            .expect("delete category through foreign key");
        let category_id: Option<i64> = connection
            .query_row(
                "SELECT category_id FROM saved_poems WHERE id = 13",
                [],
                |row| row.get(0),
            )
            .expect("read cleared category");
        assert_eq!(category_id, None);
    }

    #[test]
    fn failed_v5_to_v6_migration_rolls_back_columns_tables_order_and_version() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let path = directory.path().join("deskcubby.db");
        create_v5_fixture(&path);
        {
            let connection = Connection::open(&path).expect("open v5 fixture");
            // The migration reaches this index only after creating the category
            // table and adding both poem columns. A global index-name collision
            // therefore verifies that all earlier DDL and data rewrites roll back.
            connection
                .execute_batch(
                    "CREATE TABLE migration_collision(value INTEGER);
                     CREATE INDEX saved_poems_order_idx
                         ON migration_collision(value);",
                )
                .expect("create late migration collision");
        }

        assert!(matches!(Database::open(&path), Err(DataError::Sqlite(_))));
        let connection = Connection::open(&path).expect("inspect rolled-back v5 database");
        let version: i32 = connection
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("user version");
        assert_eq!(version, 5);
        assert!(!table_exists(&connection, "poetry_categories"));

        let columns = connection
            .prepare("PRAGMA table_info(saved_poems)")
            .expect("prepare column query")
            .query_map([], |row| row.get::<_, String>(1))
            .expect("query columns")
            .collect::<Result<Vec<_>, _>>()
            .expect("collect columns");
        assert!(!columns.iter().any(|column| column == "sort_order"));
        assert!(!columns.iter().any(|column| column == "category_id"));

        let poems: Vec<(i64, String)> = connection
            .prepare("SELECT id, content FROM saved_poems ORDER BY id")
            .expect("prepare preserved poems")
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .expect("query preserved poems")
            .collect::<Result<Vec<_>, _>>()
            .expect("collect preserved poems");
        assert_eq!(
            poems,
            vec![
                (11, "v1 poem".to_owned()),
                (12, "newer poem".to_owned()),
                (13, "newest tie".to_owned()),
            ]
        );
    }

    #[test]
    fn poetry_presets_are_idempotent_and_category_scoped_order_is_stable() {
        let (_directory, database) = database();
        let preset = vec![
            ("first poem".to_owned(), "Author《First》".to_owned()),
            ("second poem".to_owned(), "Author《Second》".to_owned()),
        ];

        let (category_id, added, existing) = database
            .import_poetry_preset("Textbook volume", -12_345, &preset)
            .expect("first preset import");
        assert_eq!((added, existing), (2, 0));
        let (same_category_id, added, existing) = database
            .import_poetry_preset("  Textbook   volume  ", -12_345, &preset)
            .expect("repeat preset import");
        assert_eq!(same_category_id, category_id);
        assert_eq!((added, existing), (0, 2));

        let poems = database.list_poems().expect("list imported poems");
        assert_eq!(
            poems
                .iter()
                .map(|poem| poem.content.as_str())
                .collect::<Vec<_>>(),
            vec!["first poem", "second poem"]
        );
        database
            .move_poem(poems[0].id, 1, Some(Some(category_id)))
            .expect("move inside category");
        let reordered = database.list_poems().expect("list reordered poems");
        assert_eq!(
            reordered
                .iter()
                .map(|poem| poem.content.as_str())
                .collect::<Vec<_>>(),
            vec!["second poem", "first poem"]
        );

        database
            .delete_poetry_category(category_id, false)
            .expect("delete category and retain poems");
        assert!(
            database
                .list_poems()
                .expect("retained poems")
                .iter()
                .all(|poem| poem.category_id.is_none())
        );
    }

    #[test]
    fn v1_recovery_snapshot_restores_core_without_touching_v2_private_tables() {
        const GENERATION: &str = "11111111-1111-4111-8111-111111111111";
        let (_directory, database) = database();
        let original = database
            .save_thought(ThoughtDraft {
                id: None,
                content: "restore this core row".to_owned(),
                pinned: false,
                category_id: None,
                highlighted: false,
            })
            .expect("core thought");
        let snapshot = database.snapshot_core().expect("v1-format snapshot");
        assert_eq!(snapshot.schema_version, 1);

        database
            .initialize_vault(&vault_metadata(GENERATION, 0, 1))
            .expect("vault metadata");
        let vault_item = database
            .insert_vault_item(GENERATION, &[9; 32], &[8; 12], 2)
            .expect("vault item");
        let config = cloud_config("cloud-one", 3);
        database
            .save_cloud_sync_config(
                &config,
                CloudSyncSecretMutation::Replace(CloudSyncSecretRecord {
                    config_id: config.id.clone(),
                    dpapi_ciphertext: vec![7; 48],
                    binding_sha256: "a".repeat(64),
                    updated_at: 3,
                }),
            )
            .expect("cloud config and secret");
        database
            .permanently_delete_thought(original.id)
            .expect("mutate core");

        database
            .restore_core_snapshot(&snapshot)
            .expect("restore old recovery format");
        assert_eq!(
            database.list_thoughts(false).expect("restored core")[0].id,
            original.id
        );
        assert_eq!(
            database.list_vault_items().expect("vault still present")[0].id,
            vault_item.id
        );
        assert!(
            database
                .get_cloud_sync_secret(&config.id)
                .expect("cloud secret read")
                .is_some()
        );
        assert_eq!(
            database
                .list_cloud_sync_configs()
                .expect("cloud config remains")
                .len(),
            1
        );
    }

    #[test]
    fn vault_generation_replace_is_complete_and_compare_and_swap_guarded() {
        const OLD: &str = "11111111-1111-4111-8111-111111111111";
        const NEW: &str = "22222222-2222-4222-8222-222222222222";
        const STALE: &str = "33333333-3333-4333-8333-333333333333";
        let (_directory, database) = database();
        database
            .initialize_vault(&vault_metadata(OLD, 0, 1))
            .expect("initialize vault");
        let original = database
            .insert_vault_item(OLD, &[1; 24], &[2; 12], 2)
            .expect("insert vault item");
        assert_eq!(
            database
                .get_vault_metadata()
                .expect("metadata")
                .expect("initialized")
                .revision,
            1
        );
        let replacement = VaultItemRecord {
            id: original.id,
            generation_id: NEW.to_owned(),
            ciphertext: vec![4; 24],
            nonce: vec![5; 12],
            created_at: original.created_at,
            updated_at: 3,
            sort_order: original.sort_order,
        };
        assert!(
            !database
                .replace_vault_generation(
                    STALE,
                    1,
                    &vault_metadata(NEW, 2, 3),
                    std::slice::from_ref(&replacement),
                )
                .expect("stale compare-and-swap")
        );
        assert_eq!(
            database
                .get_vault_metadata()
                .expect("metadata")
                .expect("initialized")
                .generation_id,
            OLD
        );
        assert!(
            database
                .replace_vault_generation(
                    OLD,
                    1,
                    &vault_metadata(NEW, 2, 3),
                    std::slice::from_ref(&replacement),
                )
                .expect("replace generation")
        );
        let stored = database.list_vault_items().expect("re-encrypted items");
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].generation_id, NEW);
        assert_eq!(stored[0].ciphertext, replacement.ciphertext);
        assert_eq!(
            database
                .get_vault_metadata()
                .expect("metadata")
                .expect("initialized")
                .revision,
            2
        );
    }

    #[test]
    fn vault_item_mutations_increment_metadata_revision_once() {
        const GENERATION: &str = "66666666-6666-4666-8666-666666666666";
        let (_directory, database) = database();
        database
            .initialize_vault(&vault_metadata(GENERATION, 0, 1))
            .expect("initialize vault");
        let item = database
            .insert_vault_item(GENERATION, &[1; 24], &[2; 12], 2)
            .expect("insert");
        assert_eq!(
            database
                .get_vault_metadata()
                .expect("metadata")
                .expect("initialized")
                .revision,
            1
        );
        database
            .update_vault_item(item.id, GENERATION, &[3; 24], &[4; 12], 3)
            .expect("update");
        assert_eq!(
            database
                .get_vault_metadata()
                .expect("metadata")
                .expect("initialized")
                .revision,
            2
        );
        database
            .reorder_vault_items(&[item.id], GENERATION, 4)
            .expect("reorder");
        assert_eq!(
            database
                .get_vault_metadata()
                .expect("metadata")
                .expect("initialized")
                .revision,
            3
        );
        database
            .delete_vault_item(item.id, GENERATION, 5)
            .expect("delete");
        assert_eq!(
            database
                .get_vault_metadata()
                .expect("metadata")
                .expect("initialized")
                .revision,
            4
        );
    }

    #[test]
    fn cloud_config_and_secret_write_rolls_back_as_one_transaction() {
        let (_directory, database) = database();
        database
            .connect()
            .expect("connection")
            .execute_batch(
                "CREATE TRIGGER reject_cloud_secret
                 BEFORE INSERT ON cloud_sync_secrets
                 BEGIN
                     SELECT RAISE(ABORT, 'simulated secret failure');
                 END;",
            )
            .expect("failure trigger");
        let config = cloud_config("cloud-rollback", 1);
        let result = database.save_cloud_sync_config(
            &config,
            CloudSyncSecretMutation::Replace(CloudSyncSecretRecord {
                config_id: config.id.clone(),
                dpapi_ciphertext: vec![1; 48],
                binding_sha256: "b".repeat(64),
                updated_at: 1,
            }),
        );
        assert!(matches!(result, Err(DataError::Sqlite(_))));
        assert!(
            database
                .get_cloud_sync_config(&config.id)
                .expect("config lookup")
                .is_none()
        );
        assert!(
            database
                .get_cloud_sync_status(&config.id)
                .expect("status lookup")
                .is_none()
        );
        assert!(
            !database
                .get_cloud_sync_settings()
                .expect("ownership flag")
                .configs_managed
        );
    }

    #[test]
    fn cloud_config_mutations_take_backup_metadata_ownership_atomically() {
        let (_directory, database) = database();
        assert!(
            !database
                .get_cloud_sync_settings()
                .expect("defaults")
                .configs_managed
        );
        let config = cloud_config("owned-config", 7);
        database
            .save_cloud_sync_config(&config, CloudSyncSecretMutation::Clear)
            .expect("save config");
        assert!(
            database
                .get_cloud_sync_settings()
                .expect("owned after save")
                .configs_managed
        );
        database
            .delete_cloud_sync_config(&config.id)
            .expect("delete config");
        assert!(
            database
                .get_cloud_sync_settings()
                .expect("owned after explicit clear")
                .configs_managed
        );
    }

    #[test]
    fn phone_usage_is_rejected_from_cloud_selected_contents() {
        let (_directory, database) = database();
        let mut config = cloud_config("no-phone-usage", 1);
        config.selected_contents_json = r#"["DIARIES","PHONE_USAGE"]"#.to_owned();
        assert!(matches!(
            database.save_cloud_sync_config(&config, CloudSyncSecretMutation::Clear),
            Err(DataError::Validation(_))
        ));
        assert!(
            database
                .list_cloud_sync_configs()
                .expect("config list")
                .is_empty()
        );
    }

    #[test]
    fn cloud_base_secret_mutations_and_run_token_are_bounded() {
        const RUN: &str = "44444444-4444-4444-8444-444444444444";
        const STALE: &str = "55555555-5555-4555-8555-555555555555";
        let (_directory, database) = database();
        let config = cloud_config("cloud-state", 1);
        database
            .save_cloud_sync_config(
                &config,
                CloudSyncSecretMutation::Replace(CloudSyncSecretRecord {
                    config_id: config.id.clone(),
                    dpapi_ciphertext: vec![6; 48],
                    binding_sha256: "c".repeat(64),
                    updated_at: 1,
                }),
            )
            .expect("config");
        let base = CloudSyncBaseStateRecord {
            config_id: config.id.clone(),
            scope_fingerprint: "d".repeat(64),
            hashes_by_key: BTreeMap::from([("diaries/2026-07-29.md".to_owned(), "e".repeat(64))]),
            updated_at: 2,
        };
        database.put_cloud_sync_base(&base).expect("base");
        assert_eq!(
            database
                .get_cloud_sync_base(&config.id)
                .expect("base read")
                .expect("base exists")
                .hashes_by_key,
            base.hashes_by_key
        );

        let running = database
            .begin_cloud_sync_run(&config.id, RUN, 3)
            .expect("begin");
        assert_eq!(running.state, "RUNNING");
        let completed = CloudSyncStatusRecord {
            config_id: config.id.clone(),
            state: "SUCCEEDED".to_owned(),
            run_token: None,
            last_started_at: Some(3),
            last_completed_at: Some(4),
            last_success_at: Some(4),
            last_error_code: None,
            uploaded_count: 1,
            downloaded_count: 2,
            conflict_count: 0,
            transferred_bytes: 123,
            updated_at: 4,
        };
        assert!(
            !database
                .finish_cloud_sync_run(STALE, &completed)
                .expect("stale finish")
        );
        assert!(
            database
                .finish_cloud_sync_run(RUN, &completed)
                .expect("owned finish")
        );
        assert_eq!(
            database
                .get_cloud_sync_status(&config.id)
                .expect("status")
                .expect("status exists")
                .state,
            "SUCCEEDED"
        );

        database
            .save_cloud_sync_config(&config, CloudSyncSecretMutation::Clear)
            .expect("clear secret transactionally");
        assert!(
            database
                .get_cloud_sync_secret(&config.id)
                .expect("secret lookup")
                .is_none()
        );
    }

    #[test]
    fn interrupted_cloud_run_is_terminal_and_retryable_after_restart_recovery() {
        const FIRST: &str = "77777777-7777-4777-8777-777777777777";
        const SECOND: &str = "88888888-8888-4888-8888-888888888888";
        let (_directory, database) = database();
        let config = cloud_config("cloud-restart", 1);
        database
            .save_cloud_sync_config(&config, CloudSyncSecretMutation::Clear)
            .expect("config");
        database
            .begin_cloud_sync_run(&config.id, FIRST, 10)
            .expect("abandoned run");
        assert_eq!(
            database
                .recover_interrupted_cloud_sync_runs(11)
                .expect("recover"),
            1
        );
        let recovered = database
            .get_cloud_sync_status(&config.id)
            .expect("status")
            .expect("row");
        assert_eq!(recovered.state, "FAILED");
        assert_eq!(recovered.last_error_code.as_deref(), Some("interrupted"));
        assert!(recovered.run_token.is_none());
        database
            .begin_cloud_sync_run(&config.id, SECOND, 12)
            .expect("next run is allowed");
    }

    #[test]
    fn update_preference_and_attempt_timestamp_are_persisted_independently() {
        let (_directory, database) = database();
        let defaults = database
            .get_update_settings()
            .expect("default updater settings");
        assert!(defaults.automatic_checks_enabled);
        assert_eq!(defaults.last_attempted_at, None);

        assert!(
            database
                .claim_automatic_update_attempt(100)
                .expect("claim first attempt")
        );
        database
            .set_automatic_update_checks(false, 42)
            .expect("disable automatic checks");
        assert!(
            !database
                .claim_automatic_update_attempt(200)
                .expect("disabled claim is rejected")
        );
        let disabled = database
            .get_update_settings()
            .expect("stored updater settings");
        assert!(!disabled.automatic_checks_enabled);
        assert_eq!(disabled.last_attempted_at, Some(100));
        assert_eq!(disabled.updated_at, 42);

        database
            .set_automatic_update_checks(true, 43)
            .expect("enable automatic checks");
        assert!(
            database
                .claim_automatic_update_attempt(200)
                .expect("claim second attempt")
        );
        let attempted = database
            .get_update_settings()
            .expect("updated attempt timestamp");
        assert!(attempted.automatic_checks_enabled);
        assert_eq!(attempted.last_attempted_at, Some(200));
        assert_eq!(attempted.updated_at, 43);

        assert!(matches!(
            database.claim_automatic_update_attempt(-1),
            Err(DataError::Validation(_))
        ));
        assert!(
            database
                .connect()
                .expect("connection")
                .execute(
                    "UPDATE update_settings SET last_attempted_at = -1 WHERE id = 1",
                    [],
                )
                .is_err()
        );
    }
}
