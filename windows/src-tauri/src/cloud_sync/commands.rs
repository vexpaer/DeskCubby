use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    path::PathBuf,
    sync::{
        Arc, Mutex, MutexGuard,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

use chrono::{DateTime, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use tauri::Runtime;
use uuid::Uuid;
use zeroize::Zeroize;

use crate::{
    AppState, backup, commands as app_commands,
    db::{
        self, CloudSyncBaseStateRecord, CloudSyncConfigRecord, CloudSyncSecretMutation,
        CloudSyncSecretRecord, CloudSyncSettingsRecord, CloudSyncStatusRecord, DataError, Database,
    },
    security::{CommandResult, SecurityErrorDto},
};

use super::{
    BaseState, BoxFuture, CloudCredentials, CloudRemoteStoreFactory, CloudSyncConfig,
    CloudSyncContent, CloudSyncDirection, CloudSyncEngine, CloudSyncError, CloudSyncErrorCode,
    CloudSyncItemOutcome, CloudSyncLimits, CloudSyncProgress, CloudSyncRunResult,
    CloudSyncServiceType, CloudSyncStateStore, EncryptedCloudCredentials, FileSystemLocalStore,
    JsonBackupBridge, JsonSnapshot, LocalRoots, PendingJson, ReqwestRemoteStoreFactory,
    decrypt_credentials, encrypt_credentials, secret_binding_sha256, validate_cloud_sync_config,
};

const DTO_VERSION: u32 = 1;
const MAX_CONFIGS: usize = 20;
const INITIAL_SCHEDULE_DELAY: Duration = Duration::from_secs(2 * 60);
const DISABLED_POLL_INTERVAL: Duration = Duration::from_secs(60);
const CONFIRMATION_TTL: Duration = Duration::from_secs(10 * 60);

type DiaryChangedHook = Arc<dyn Fn() + Send + Sync>;

pub(crate) struct CloudSyncService {
    database: Database,
    private_dir: PathBuf,
    remote_factory: Arc<dyn CloudRemoteStoreFactory>,
    active: Mutex<Option<ActiveRun>>,
    confirmations: Mutex<HashMap<String, PendingConfirmation>>,
    diary_changed: DiaryChangedHook,
}

struct ActiveRun {
    token: String,
    config_id: Option<String>,
    phase: String,
    cancelled: Arc<AtomicBool>,
    scheduled: bool,
}

#[derive(Clone)]
struct ActiveSnapshot {
    config_id: Option<String>,
    phase: String,
}

struct ActiveGuard<'a> {
    service: &'a CloudSyncService,
    token: String,
}

pub(crate) struct CloudSyncIdleGuard<'a> {
    #[allow(dead_code)]
    guard: MutexGuard<'a, Option<ActiveRun>>,
}

impl Drop for ActiveGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut active) = self.service.active.lock()
            && active
                .as_ref()
                .is_some_and(|current| current.token == self.token)
        {
            *active = None;
        }
    }
}

struct PendingConfirmation {
    id: String,
    sha256: String,
    expires_at: Instant,
}

impl CloudSyncService {
    pub(crate) fn new(
        database: Database,
        private_dir: PathBuf,
        diary_changed: DiaryChangedHook,
    ) -> Result<Self, CloudSyncError> {
        // A process cannot resume an in-flight HTTP request. Clear crash-left
        // RUNNING rows so the next manual/background attempt is retryable.
        database
            .recover_interrupted_cloud_sync_runs(db::now_millis())
            .map_err(map_data_to_cloud)?;
        Ok(Self {
            database,
            private_dir,
            remote_factory: Arc::new(ReqwestRemoteStoreFactory),
            active: Mutex::new(None),
            confirmations: Mutex::new(HashMap::new()),
            diary_changed,
        })
    }

    pub(crate) fn start_scheduler(self: &Arc<Self>) {
        let service = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(INITIAL_SCHEDULE_DELAY).await;
            loop {
                let settings = service.database.get_cloud_sync_settings();
                let delay = match settings {
                    Ok(settings) if settings.automatic_sync_enabled => {
                        // Best effort, but the coordinator itself records every
                        // attempted run as SUCCEEDED/FAILED/CANCELLED.
                        let _ = service.run(None, true).await;
                        Duration::from_secs(
                            u64::try_from(settings.interval_minutes)
                                .unwrap_or(360)
                                .saturating_mul(60),
                        )
                    }
                    Ok(_) => DISABLED_POLL_INTERVAL,
                    Err(_) => DISABLED_POLL_INTERVAL,
                };
                tokio::time::sleep(delay).await;
            }
        });
    }

    pub(crate) fn acquire_idle(&self) -> CommandResult<CloudSyncIdleGuard<'_>> {
        let guard = self
            .active
            .lock()
            .map_err(|_| SecurityErrorDto::storage_unavailable())?;
        if guard.is_some() {
            return Err(cloud_busy_error());
        }
        Ok(CloudSyncIdleGuard { guard })
    }

    fn active_snapshot(&self) -> Result<Option<ActiveSnapshot>, CloudSyncError> {
        Ok(self
            .active
            .lock()
            .map_err(|_| CloudSyncError::storage())?
            .as_ref()
            .map(|active| ActiveSnapshot {
                config_id: active.config_id.clone(),
                phase: active.phase.clone(),
            }))
    }

    fn set_active_phase(
        &self,
        token: &str,
        config_id: Option<&str>,
        phase: String,
    ) -> Result<(), CloudSyncError> {
        let mut active = self.active.lock().map_err(|_| CloudSyncError::storage())?;
        let current = active.as_mut().ok_or_else(CloudSyncError::conflict)?;
        if current.token != token {
            return Err(CloudSyncError::conflict());
        }
        current.config_id = config_id.map(str::to_owned);
        current.phase = phase;
        Ok(())
    }

    pub(crate) fn cancel(&self) -> Result<(), CloudSyncError> {
        let active = self.active.lock().map_err(|_| CloudSyncError::storage())?;
        let Some(active) = active.as_ref() else {
            return Ok(());
        };
        active.cancelled.store(true, Ordering::SeqCst);
        Ok(())
    }

    pub(crate) fn cancel_scheduled(&self) -> Result<(), CloudSyncError> {
        let active = self.active.lock().map_err(|_| CloudSyncError::storage())?;
        if let Some(active) = active.as_ref()
            && active.scheduled
        {
            active.cancelled.store(true, Ordering::SeqCst);
        }
        Ok(())
    }

    async fn run(
        &self,
        requested_id: Option<String>,
        scheduled: bool,
    ) -> Result<CloudSyncRunSummaryV1, CloudSyncError> {
        let token = Uuid::new_v4().to_string();
        let cancelled = Arc::new(AtomicBool::new(false));
        {
            let mut active = self.active.lock().map_err(|_| CloudSyncError::storage())?;
            if active.is_some() {
                return Err(CloudSyncError::new(
                    CloudSyncErrorCode::Conflict,
                    "Cloud synchronization is already running.",
                    true,
                ));
            }
            *active = Some(ActiveRun {
                token: token.clone(),
                config_id: None,
                phase: "preparing".to_owned(),
                cancelled: Arc::clone(&cancelled),
                scheduled,
            });
        }
        let _active_guard = ActiveGuard {
            service: self,
            token: token.clone(),
        };
        if scheduled
            && !self
                .database
                .get_cloud_sync_settings()
                .map_err(map_data_to_cloud)?
                .automatic_sync_enabled
        {
            return Ok(CloudSyncRunSummaryV1::default());
        }

        let mut configs = self
            .database
            .list_cloud_sync_configs()
            .map_err(map_data_to_cloud)?
            .into_iter()
            .filter(|config| config.enabled)
            .collect::<Vec<_>>();
        if let Some(id) = requested_id.as_deref() {
            configs.retain(|config| config.id == id);
            if configs.is_empty() {
                return Err(CloudSyncError::invalid_configuration());
            }
        }
        if configs.is_empty() {
            if scheduled {
                return Ok(CloudSyncRunSummaryV1::default());
            }
            return Err(CloudSyncError::invalid_configuration());
        }

        let mut summary = CloudSyncRunSummaryV1::default();
        for config_record in configs {
            if cancelled.load(Ordering::SeqCst) {
                return Err(cancelled_error());
            }
            self.set_active_phase(&token, Some(&config_record.id), "preparing".to_owned())?;
            let core_config = config_from_record(&config_record)?;
            let credentials = self.credentials_for_run(&core_config)?;
            let roots = self.local_roots()?;
            let bridge: Arc<dyn JsonBackupBridge> = Arc::new(DatabaseJsonBackupBridge {
                database: self.database.clone(),
            });
            let local = match FileSystemLocalStore::new(roots, core_config.id.clone(), Some(bridge))
            {
                Ok(local) => Arc::new(local),
                Err(error) => return Err(error),
            };
            let state: Arc<dyn CloudSyncStateStore> = Arc::new(DatabaseStateStore {
                database: self.database.clone(),
            });
            let engine = CloudSyncEngine::new(local, Arc::clone(&self.remote_factory), state);
            let started = self
                .database
                .begin_cloud_sync_run(&config_record.id, &token, db::now_millis())
                .map_err(map_data_to_cloud)?;
            let counters = Arc::new(Mutex::new(RunCounters::default()));
            let progress_counters = Arc::clone(&counters);
            let progress_service = self;
            let progress_token = token.clone();
            let config_id = core_config.id.clone();
            let sync = engine.synchronize(
                &core_config,
                &credentials,
                CloudSyncLimits::default(),
                move |progress| {
                    if let Ok(mut latest) = progress_counters.lock() {
                        latest.transferred_bytes = progress.transferred_bytes;
                    }
                    let _ = progress_service.set_active_phase(
                        &progress_token,
                        Some(&config_id),
                        format!("{}/{}", progress.completed_objects, progress.total_objects),
                    );
                },
            );
            tokio::pin!(sync);
            let outcome = loop {
                tokio::select! {
                    result = &mut sync => break result,
                    _ = tokio::time::sleep(Duration::from_millis(50)) => {
                        if cancelled.load(Ordering::SeqCst) {
                            break Err(cancelled_error());
                        }
                    }
                }
            };
            let partial = counters
                .lock()
                .map(|value| value.clone())
                .unwrap_or_default();
            match outcome {
                Ok(result) => {
                    let run_summary = match summary_from_result(&result) {
                        Ok(summary) => summary,
                        Err(error) => {
                            self.finish_failed(&started, &token, &error, partial)?;
                            return Err(error);
                        }
                    };
                    if let Err(error) = summary.add(&run_summary) {
                        self.finish_failed(&started, &token, &error, partial)?;
                        return Err(error);
                    }
                    self.finish_succeeded(&started, &token, &result)?;
                    if core_config
                        .selected_contents
                        .contains(&CloudSyncContent::Diaries)
                        && result.reports.iter().any(|report| {
                            matches!(
                                report.outcome,
                                CloudSyncItemOutcome::Downloaded
                                    | CloudSyncItemOutcome::ConflictCopySaved
                            )
                        })
                    {
                        (self.diary_changed)();
                    }
                }
                Err(error) => {
                    if error.code == CloudSyncErrorCode::Cancelled {
                        self.finish_cancelled(&started, &token, partial)?;
                    } else {
                        self.finish_failed(&started, &token, &error, partial)?;
                    }
                    return Err(error);
                }
            }
        }
        Ok(summary)
    }

    fn credentials_for_run(
        &self,
        config: &CloudSyncConfig,
    ) -> Result<CloudCredentials, CloudSyncError> {
        let Some(secret) = self
            .database
            .get_cloud_sync_secret(&config.id)
            .map_err(map_data_to_cloud)?
        else {
            if config.service_type == CloudSyncServiceType::Webdav {
                return Ok(CloudCredentials::default());
            }
            return Err(CloudSyncError::invalid_configuration());
        };
        decrypt_credentials(
            config,
            &EncryptedCloudCredentials {
                ciphertext: secret.dpapi_ciphertext,
                binding_sha256: secret.binding_sha256,
            },
        )
    }

    fn local_roots(&self) -> Result<LocalRoots, CloudSyncError> {
        let paths = self.database.get_local_paths().map_err(map_data_to_cloud)?;
        Ok(LocalRoots {
            diary: paths.diary_path.map(PathBuf::from),
            media: paths.media_path.map(PathBuf::from),
            incoming_json: self.private_dir.join("cloud-incoming"),
        })
    }

    fn finish_succeeded(
        &self,
        started: &CloudSyncStatusRecord,
        token: &str,
        result: &CloudSyncRunResult,
    ) -> Result<(), CloudSyncError> {
        let completed_at = db::now_millis().max(started.last_started_at.unwrap_or(0));
        let status = CloudSyncStatusRecord {
            config_id: started.config_id.clone(),
            state: "SUCCEEDED".to_owned(),
            run_token: None,
            last_started_at: started.last_started_at,
            last_completed_at: Some(completed_at),
            last_success_at: Some(completed_at),
            last_error_code: None,
            uploaded_count: bounded_i64(result.uploaded_count())?,
            downloaded_count: bounded_i64(result.downloaded_count())?,
            conflict_count: bounded_i64(result.conflict_count())?,
            transferred_bytes: i64::try_from(result.transferred_bytes)
                .map_err(|_| CloudSyncError::limit_exceeded())?,
            updated_at: completed_at,
        };
        require_status_finish(&self.database, token, &status)
    }

    fn finish_failed(
        &self,
        started: &CloudSyncStatusRecord,
        token: &str,
        error: &CloudSyncError,
        counters: RunCounters,
    ) -> Result<(), CloudSyncError> {
        self.finish_terminal(
            started,
            token,
            "FAILED",
            Some(error_code(error.code)),
            counters,
        )
    }

    fn finish_cancelled(
        &self,
        started: &CloudSyncStatusRecord,
        token: &str,
        counters: RunCounters,
    ) -> Result<(), CloudSyncError> {
        self.finish_terminal(started, token, "CANCELLED", None, counters)
    }

    fn finish_terminal(
        &self,
        started: &CloudSyncStatusRecord,
        token: &str,
        state: &str,
        error_code: Option<String>,
        counters: RunCounters,
    ) -> Result<(), CloudSyncError> {
        let completed_at = db::now_millis().max(started.last_started_at.unwrap_or(0));
        let status = CloudSyncStatusRecord {
            config_id: started.config_id.clone(),
            state: state.to_owned(),
            run_token: None,
            last_started_at: started.last_started_at,
            last_completed_at: Some(completed_at),
            last_success_at: started.last_success_at,
            last_error_code: error_code,
            uploaded_count: counters.uploaded,
            downloaded_count: counters.downloaded,
            conflict_count: counters.conflicts,
            transferred_bytes: i64::try_from(counters.transferred_bytes)
                .map_err(|_| CloudSyncError::limit_exceeded())?,
            updated_at: completed_at,
        };
        require_status_finish(&self.database, token, &status)
    }

    fn pending_store(&self) -> Result<FileSystemLocalStore, CloudSyncError> {
        FileSystemLocalStore::new(
            LocalRoots {
                diary: None,
                media: None,
                incoming_json: self.private_dir.join("cloud-incoming"),
            },
            "_pending".to_owned(),
            None,
        )
    }

    async fn list_pending(&self) -> Result<Vec<PendingJson>, CloudSyncError> {
        self.pending_store()?.list_pending_json().await
    }

    fn remember_confirmation(&self, id: &str, sha256: String) -> Result<String, CloudSyncError> {
        let now = Instant::now();
        let mut confirmations = self
            .confirmations
            .lock()
            .map_err(|_| CloudSyncError::storage())?;
        confirmations.retain(|_, value| value.expires_at > now);
        if confirmations.len() >= 100 {
            confirmations.clear();
        }
        let token = Uuid::new_v4().to_string();
        confirmations.insert(
            token.clone(),
            PendingConfirmation {
                id: id.to_owned(),
                sha256,
                expires_at: now + CONFIRMATION_TTL,
            },
        );
        Ok(token)
    }

    fn take_confirmation(&self, id: &str, token: &str, sha256: &str) -> Result<(), CloudSyncError> {
        let mut confirmations = self
            .confirmations
            .lock()
            .map_err(|_| CloudSyncError::storage())?;
        let confirmation = confirmations
            .remove(token)
            .ok_or_else(CloudSyncError::invalid_input)?;
        if confirmation.expires_at <= Instant::now()
            || confirmation.id != id
            || confirmation.sha256 != sha256
        {
            return Err(CloudSyncError::conflict());
        }
        Ok(())
    }
}

#[derive(Clone, Default)]
struct RunCounters {
    uploaded: i64,
    downloaded: i64,
    conflicts: i64,
    transferred_bytes: u64,
}

#[derive(Clone)]
struct DatabaseStateStore {
    database: Database,
}

impl CloudSyncStateStore for DatabaseStateStore {
    fn load<'a>(
        &'a self,
        config_id: &'a str,
    ) -> BoxFuture<'a, Result<Option<BaseState>, CloudSyncError>> {
        Box::pin(async move {
            Ok(self
                .database
                .get_cloud_sync_base(config_id)
                .map_err(map_data_to_cloud)?
                .map(|state| BaseState {
                    scope_fingerprint: state.scope_fingerprint,
                    hashes_by_key: state.hashes_by_key,
                }))
        })
    }

    fn save<'a>(
        &'a self,
        config_id: &'a str,
        state: &'a BaseState,
    ) -> BoxFuture<'a, Result<(), CloudSyncError>> {
        Box::pin(async move {
            self.database
                .put_cloud_sync_base(&CloudSyncBaseStateRecord {
                    config_id: config_id.to_owned(),
                    scope_fingerprint: state.scope_fingerprint.clone(),
                    hashes_by_key: state.hashes_by_key.clone(),
                    updated_at: db::now_millis(),
                })
                .map_err(map_data_to_cloud)
        })
    }
}

#[derive(Clone)]
struct DatabaseJsonBackupBridge {
    database: Database,
}

impl JsonBackupBridge for DatabaseJsonBackupBridge {
    fn snapshot<'a>(
        &'a self,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<JsonSnapshot, CloudSyncError>> {
        Box::pin(async move {
            let bytes = app_commands::build_cloud_backup_bytes(&self.database)
                .map_err(map_security_to_cloud)?;
            if bytes.len() as u64 > max_bytes {
                return Err(CloudSyncError::limit_exceeded());
            }
            let hash = super::encoding::sha256_hex(&bytes);
            Ok(JsonSnapshot {
                bytes,
                last_modified_millis: db::now_millis(),
                local_token: hash,
            })
        })
    }

    fn validate_incoming<'a>(
        &'a self,
        bytes: &'a [u8],
    ) -> BoxFuture<'a, Result<(), CloudSyncError>> {
        Box::pin(async move {
            let text = std::str::from_utf8(bytes).map_err(|_| CloudSyncError::backup_invalid())?;
            backup::parse_v18(text)
                .map(|_| ())
                .map_err(|_| CloudSyncError::backup_invalid())
        })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CloudSyncConfigV1 {
    id: String,
    name: String,
    enabled: bool,
    service_type: CloudSyncServiceType,
    endpoint_url: String,
    remote_path: String,
    web_dav_username: String,
    s3_bucket: String,
    s3_region: String,
    allow_insecure_http: bool,
    selected_contents: Vec<CloudSyncContent>,
    direction: CloudSyncDirection,
    has_credentials: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CloudSyncStatusV1 {
    running: bool,
    running_config_id: Option<String>,
    phase: Option<String>,
    last_completed_at: Option<String>,
    last_error_code: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CloudSyncConfigListV1 {
    schema_version: u32,
    global_enabled: bool,
    configs: Vec<CloudSyncConfigV1>,
    status: CloudSyncStatusV1,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct CloudSyncConfigDraftV1 {
    id: Option<String>,
    name: String,
    enabled: bool,
    service_type: CloudSyncServiceType,
    endpoint_url: String,
    remote_path: String,
    web_dav_username: String,
    s3_bucket: String,
    s3_region: String,
    allow_insecure_http: bool,
    selected_contents: Vec<CloudSyncContent>,
    direction: CloudSyncDirection,
}

#[derive(Deserialize)]
#[serde(transparent)]
pub(crate) struct SecretValue(String);

impl SecretValue {
    fn copy_value(value: &Option<Self>) -> String {
        value
            .as_ref()
            .map(|value| value.0.clone())
            .unwrap_or_default()
    }
}

impl Drop for SecretValue {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

#[derive(Deserialize)]
#[serde(
    tag = "mode",
    rename_all = "camelCase",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub(crate) enum CloudCredentialUpdateV1 {
    Preserve,
    Clear,
    Replace {
        #[serde(default)]
        web_dav_password: Option<SecretValue>,
        #[serde(default)]
        s3_access_key: Option<SecretValue>,
        #[serde(default)]
        s3_secret_key: Option<SecretValue>,
        #[serde(default)]
        s3_session_token: Option<SecretValue>,
    },
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SaveCloudSyncConfigRequestV1 {
    schema_version: u32,
    config: CloudSyncConfigDraftV1,
    credential_update: CloudCredentialUpdateV1,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct IdRequestV1 {
    schema_version: u32,
    id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SetEnabledRequestV1 {
    schema_version: u32,
    enabled: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RunRequestV1 {
    schema_version: u32,
    config_id: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RestorePendingRequestV1 {
    schema_version: u32,
    id: String,
    confirmation_token: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CloudPendingJsonV1 {
    id: String,
    received_at: String,
    size: u64,
    source_label: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CloudPendingJsonPreviewV1 {
    schema_version: u32,
    id: String,
    confirmation_token: String,
    format_version: i32,
    exported_at: Option<String>,
    thought_count: usize,
    category_count: usize,
    date_record_count: usize,
    poem_count: usize,
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CloudSyncRunSummaryV1 {
    schema_version: u32,
    uploaded: u32,
    downloaded: u32,
    conflicts: u32,
    skipped: u32,
}

impl CloudSyncRunSummaryV1 {
    fn add(&mut self, other: &Self) -> Result<(), CloudSyncError> {
        self.schema_version = DTO_VERSION;
        self.uploaded = self
            .uploaded
            .checked_add(other.uploaded)
            .ok_or_else(CloudSyncError::limit_exceeded)?;
        self.downloaded = self
            .downloaded
            .checked_add(other.downloaded)
            .ok_or_else(CloudSyncError::limit_exceeded)?;
        self.conflicts = self
            .conflicts
            .checked_add(other.conflicts)
            .ok_or_else(CloudSyncError::limit_exceeded)?;
        self.skipped = self
            .skipped
            .checked_add(other.skipped)
            .ok_or_else(CloudSyncError::limit_exceeded)?;
        Ok(())
    }
}

#[tauri::command]
pub(crate) fn list_cloud_sync_configs(
    state: tauri::State<'_, AppState>,
) -> CommandResult<CloudSyncConfigListV1> {
    let records = state
        .database
        .list_cloud_sync_configs()
        .map_err(map_data_error)?;
    let mut configs = Vec::with_capacity(records.len());
    for record in records {
        configs.push(config_dto(&state.database, &record)?);
    }
    let settings = state
        .database
        .get_cloud_sync_settings()
        .map_err(map_data_error)?;
    let statuses = state
        .database
        .list_cloud_sync_statuses()
        .map_err(map_data_error)?;
    let latest = statuses.iter().max_by_key(|status| status.updated_at);
    let active = state
        .cloud_sync
        .active_snapshot()
        .map_err(map_cloud_error)?;
    Ok(CloudSyncConfigListV1 {
        schema_version: DTO_VERSION,
        global_enabled: settings.automatic_sync_enabled,
        configs,
        status: CloudSyncStatusV1 {
            running: active.is_some(),
            running_config_id: active.as_ref().and_then(|active| active.config_id.clone()),
            phase: active.map(|active| active.phase),
            last_completed_at: latest
                .and_then(|status| status.last_completed_at)
                .and_then(millis_to_rfc3339),
            last_error_code: latest.and_then(|status| status.last_error_code.clone()),
        },
    })
}

#[tauri::command]
pub(crate) fn save_cloud_sync_config(
    request: SaveCloudSyncConfigRequestV1,
    state: tauri::State<'_, AppState>,
) -> CommandResult<CloudSyncConfigV1> {
    require_schema(request.schema_version)?;
    let _idle = state.cloud_sync.acquire_idle()?;
    let existing = match request.config.id.as_deref() {
        Some(id) => Some(
            state
                .database
                .get_cloud_sync_config(id)
                .map_err(map_data_error)?
                .ok_or_else(SecurityErrorDto::not_found)?,
        ),
        None => None,
    };
    if existing.is_none()
        && state
            .database
            .list_cloud_sync_configs()
            .map_err(map_data_error)?
            .len()
            >= MAX_CONFIGS
    {
        return Err(map_cloud_error(CloudSyncError::limit_exceeded()));
    }
    let id = existing
        .as_ref()
        .map(|config| config.id.clone())
        .unwrap_or_else(|| Uuid::new_v4().simple().to_string());
    let sort_order = existing
        .as_ref()
        .map(|config| config.sort_order)
        .unwrap_or_else(|| {
            state
                .database
                .list_cloud_sync_configs()
                .ok()
                .and_then(|items| items.iter().map(|item| item.sort_order).max())
                .unwrap_or(-1)
                .saturating_add(1)
        });
    let config = config_from_draft(id, &request.config)?;
    let stored_secret = existing
        .as_ref()
        .map(|_| {
            state
                .database
                .get_cloud_sync_secret(&config.id)
                .map_err(map_data_error)
        })
        .transpose()?
        .flatten();

    let (mutation, validation_credentials) = credential_mutation(
        &config,
        request.credential_update,
        stored_secret.as_ref(),
        existing.as_ref(),
    )?;
    let mut runnable = config.clone();
    runnable.enabled = true;
    validate_cloud_sync_config(&runnable, &validation_credentials).map_err(map_cloud_error)?;
    if config.enabled
        && config.service_type == CloudSyncServiceType::S3Compatible
        && matches!(mutation, CloudSyncSecretMutation::Clear)
    {
        return Err(map_cloud_error(CloudSyncError::invalid_configuration()));
    }
    let record = record_from_config(&config, sort_order, db::now_millis())?;
    state
        .database
        .save_cloud_sync_config(&record, mutation)
        .map_err(map_data_error)?;
    config_dto(&state.database, &record)
}

#[tauri::command]
pub(crate) fn delete_cloud_sync_config(
    request: IdRequestV1,
    state: tauri::State<'_, AppState>,
) -> CommandResult<()> {
    require_schema(request.schema_version)?;
    let _idle = state.cloud_sync.acquire_idle()?;
    state
        .database
        .delete_cloud_sync_config(&request.id)
        .map_err(map_data_error)
}

#[tauri::command]
pub(crate) fn copy_cloud_sync_config(
    request: IdRequestV1,
    state: tauri::State<'_, AppState>,
) -> CommandResult<CloudSyncConfigV1> {
    require_schema(request.schema_version)?;
    let _idle = state.cloud_sync.acquire_idle()?;
    let mut configs = state
        .database
        .list_cloud_sync_configs()
        .map_err(map_data_error)?;
    if configs.len() >= MAX_CONFIGS {
        return Err(map_cloud_error(CloudSyncError::limit_exceeded()));
    }
    let source = configs
        .iter()
        .find(|config| config.id == request.id)
        .cloned()
        .ok_or_else(SecurityErrorDto::not_found)?;
    let next_order = configs
        .iter()
        .map(|config| config.sort_order)
        .max()
        .unwrap_or(-1)
        .saturating_add(1);
    let mut copy = source;
    copy.id = Uuid::new_v4().simple().to_string();
    copy.name = format!("{} (copy)", copy.name);
    copy.enabled = false;
    copy.sort_order = next_order;
    copy.updated_at = db::now_millis();
    state
        .database
        .save_cloud_sync_config(&copy, CloudSyncSecretMutation::Clear)
        .map_err(map_data_error)?;
    configs.clear();
    config_dto(&state.database, &copy)
}

#[tauri::command]
pub(crate) fn set_cloud_sync_enabled(
    request: SetEnabledRequestV1,
    state: tauri::State<'_, AppState>,
) -> CommandResult<()> {
    require_schema(request.schema_version)?;
    let mut settings = state
        .database
        .get_cloud_sync_settings()
        .map_err(map_data_error)?;
    settings.automatic_sync_enabled = request.enabled;
    settings.updated_at = db::now_millis();
    state
        .database
        .put_cloud_sync_settings(&settings)
        .map_err(map_data_error)?;
    if !request.enabled {
        state
            .cloud_sync
            .cancel_scheduled()
            .map_err(map_cloud_error)?;
    }
    Ok(())
}

#[tauri::command]
pub(crate) async fn run_cloud_sync(
    request: RunRequestV1,
    state: tauri::State<'_, AppState>,
) -> CommandResult<CloudSyncRunSummaryV1> {
    require_schema(request.schema_version)?;
    let service = Arc::clone(&state.cloud_sync);
    // The owned task continues terminal-status cleanup even if the invoking
    // webview navigates away and drops its command future.
    tauri::async_runtime::spawn(async move { service.run(request.config_id, false).await })
        .await
        .map_err(|_| SecurityErrorDto::storage_unavailable())?
        .map_err(map_cloud_error)
}

#[tauri::command]
pub(crate) fn cancel_cloud_sync(state: tauri::State<'_, AppState>) -> CommandResult<()> {
    state.cloud_sync.cancel().map_err(map_cloud_error)
}

#[tauri::command]
pub(crate) async fn list_pending_cloud_json(
    state: tauri::State<'_, AppState>,
) -> CommandResult<Vec<CloudPendingJsonV1>> {
    let configs = state
        .database
        .list_cloud_sync_configs()
        .map_err(map_data_error)?;
    let labels = configs
        .into_iter()
        .map(|config| {
            (
                super::encoding::sha256_hex(config.id.as_bytes())[..8].to_owned(),
                config.name,
            )
        })
        .collect::<BTreeMap<_, _>>();
    state
        .cloud_sync
        .list_pending()
        .await
        .map_err(map_cloud_error)?
        .into_iter()
        .filter(|item| {
            item.size > 0 && item.size <= u64::try_from(backup::MAX_JSON_BYTES).unwrap_or(u64::MAX)
        })
        .map(|item| {
            let source_hash = item
                .id
                .strip_prefix("DeskCubby-incoming-")
                .and_then(|rest| rest.split('-').next())
                .unwrap_or_default()
                .to_owned();
            Ok(CloudPendingJsonV1 {
                id: item.id,
                received_at: millis_to_rfc3339(item.received_at_millis)
                    .ok_or_else(SecurityErrorDto::invalid_input)?,
                size: item.size,
                source_label: labels.get(&source_hash).cloned(),
            })
        })
        .collect()
}

#[tauri::command]
pub(crate) async fn preview_pending_cloud_json(
    request: IdRequestV1,
    state: tauri::State<'_, AppState>,
) -> CommandResult<CloudPendingJsonPreviewV1> {
    require_schema(request.schema_version)?;
    let mut bytes = state
        .cloud_sync
        .pending_store()
        .map_err(map_cloud_error)?
        .read_pending_json(&request.id)
        .await
        .map_err(map_cloud_error)?;
    let text = std::str::from_utf8(&bytes).map_err(|_| SecurityErrorDto::backup_invalid())?;
    let parsed = backup::parse_v18(text).map_err(|_| SecurityErrorDto::backup_invalid())?;
    let preview = parsed.preview();
    let hash = super::encoding::sha256_hex(&bytes);
    let token = state
        .cloud_sync
        .remember_confirmation(&request.id, hash)
        .map_err(map_cloud_error)?;
    bytes.zeroize();
    Ok(CloudPendingJsonPreviewV1 {
        schema_version: DTO_VERSION,
        id: request.id,
        confirmation_token: token,
        format_version: preview.format_version,
        exported_at: (preview.exported_at > 0)
            .then(|| millis_to_rfc3339(preview.exported_at))
            .flatten(),
        thought_count: preview.thought_count,
        category_count: preview.category_count,
        date_record_count: preview.date_record_count,
        poem_count: preview.poem_count,
    })
}

#[tauri::command]
pub(crate) async fn restore_pending_cloud_json(
    request: RestorePendingRequestV1,
    state: tauri::State<'_, AppState>,
) -> CommandResult<()> {
    require_schema(request.schema_version)?;
    let store = state.cloud_sync.pending_store().map_err(map_cloud_error)?;
    let mut bytes = store
        .read_pending_json(&request.id)
        .await
        .map_err(map_cloud_error)?;
    let hash = super::encoding::sha256_hex(&bytes);
    state
        .cloud_sync
        .take_confirmation(&request.id, &request.confirmation_token, &hash)
        .map_err(map_cloud_error)?;
    let _idle = state.cloud_sync.acquire_idle()?;
    let result = app_commands::restore_cloud_backup_bytes(&state, &bytes);
    bytes.zeroize();
    result?;
    drop(_idle);
    // The database transaction already committed. Cleanup is best effort so a
    // transient delete failure is never misreported as a failed restore.
    let _ = store.remove_pending_json(&request.id).await;
    Ok(())
}

pub(crate) fn backup_configs_from_database(
    database: &Database,
) -> Result<Vec<backup::CloudSyncConfig>, CloudSyncError> {
    database
        .list_cloud_sync_configs()
        .map_err(map_data_to_cloud)?
        .into_iter()
        .map(|record| {
            let config = config_from_record(&record)?;
            Ok(backup::CloudSyncConfig {
                id: config.id,
                name: config.name,
                enabled: config.enabled,
                service_type: match config.service_type {
                    CloudSyncServiceType::Webdav => backup::CloudSyncServiceType::WebDav,
                    CloudSyncServiceType::S3Compatible => {
                        backup::CloudSyncServiceType::S3Compatible
                    }
                },
                endpoint_url: config.endpoint_url,
                remote_path: config.remote_path,
                web_dav_username: config.web_dav_username,
                s3_bucket: config.s3_bucket,
                s3_region: config.s3_region,
                allow_insecure_http: config.allow_insecure_http,
                selected_contents: config
                    .selected_contents
                    .into_iter()
                    .map(|content| match content {
                        CloudSyncContent::Diaries => backup::CloudSyncContent::Diaries,
                        CloudSyncContent::Media => backup::CloudSyncContent::Media,
                        CloudSyncContent::JsonBackup => backup::CloudSyncContent::JsonBackup,
                    })
                    .collect(),
                direction: match config.direction {
                    CloudSyncDirection::UploadOnly => backup::CloudSyncDirection::UploadOnly,
                    CloudSyncDirection::TwoWay => backup::CloudSyncDirection::TwoWay,
                },
            })
        })
        .collect()
}

fn config_from_draft(
    id: String,
    draft: &CloudSyncConfigDraftV1,
) -> Result<CloudSyncConfig, SecurityErrorDto> {
    let selected_contents = draft
        .selected_contents
        .iter()
        .copied()
        .collect::<BTreeSet<_>>();
    if selected_contents.len() != draft.selected_contents.len()
        || id.len() > 80
        || draft.endpoint_url.encode_utf16().count() > 4_096
    {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(CloudSyncConfig {
        id,
        name: draft.name.clone(),
        enabled: draft.enabled,
        service_type: draft.service_type,
        endpoint_url: draft.endpoint_url.clone(),
        remote_path: draft.remote_path.clone(),
        web_dav_username: draft.web_dav_username.clone(),
        s3_bucket: draft.s3_bucket.clone(),
        s3_region: draft.s3_region.clone(),
        allow_insecure_http: draft.allow_insecure_http,
        selected_contents,
        direction: draft.direction,
    })
}

fn credential_mutation(
    config: &CloudSyncConfig,
    update: CloudCredentialUpdateV1,
    stored: Option<&CloudSyncSecretRecord>,
    existing: Option<&CloudSyncConfigRecord>,
) -> CommandResult<(CloudSyncSecretMutation, CloudCredentials)> {
    match update {
        CloudCredentialUpdateV1::Preserve => {
            let Some(stored) = stored else {
                let placeholder = validation_credentials(config, CloudCredentials::default());
                if config.enabled && config.service_type == CloudSyncServiceType::S3Compatible {
                    return Err(map_cloud_error(CloudSyncError::invalid_configuration()));
                }
                return Ok((CloudSyncSecretMutation::Preserve, placeholder));
            };
            if let Some(existing) = existing {
                let old = config_from_record(existing).map_err(map_cloud_error)?;
                if secret_binding_sha256(&old).map_err(map_cloud_error)?
                    != secret_binding_sha256(config).map_err(map_cloud_error)?
                {
                    return Err(SecurityErrorDto::new(
                        "cloud_credentials_replacement_required",
                        "Saved credentials cannot be preserved after changing their service binding.",
                        true,
                    ));
                }
            }
            let credentials = decrypt_credentials(
                config,
                &EncryptedCloudCredentials {
                    ciphertext: stored.dpapi_ciphertext.clone(),
                    binding_sha256: stored.binding_sha256.clone(),
                },
            )
            .map_err(map_cloud_error)?;
            Ok((CloudSyncSecretMutation::Preserve, credentials))
        }
        CloudCredentialUpdateV1::Clear => {
            if config.enabled && config.service_type == CloudSyncServiceType::S3Compatible {
                return Err(map_cloud_error(CloudSyncError::invalid_configuration()));
            }
            Ok((
                CloudSyncSecretMutation::Clear,
                validation_credentials(config, CloudCredentials::default()),
            ))
        }
        CloudCredentialUpdateV1::Replace {
            web_dav_password,
            s3_access_key,
            s3_secret_key,
            s3_session_token,
        } => {
            let credentials = CloudCredentials {
                web_dav_password: SecretValue::copy_value(&web_dav_password),
                s3_access_key: SecretValue::copy_value(&s3_access_key),
                s3_secret_key: SecretValue::copy_value(&s3_secret_key),
                s3_session_token: SecretValue::copy_value(&s3_session_token),
            };
            match config.service_type {
                CloudSyncServiceType::Webdav => {
                    if !credentials.s3_access_key.is_empty()
                        || !credentials.s3_secret_key.is_empty()
                        || !credentials.s3_session_token.is_empty()
                    {
                        return Err(SecurityErrorDto::invalid_input());
                    }
                }
                CloudSyncServiceType::S3Compatible => {
                    if !credentials.web_dav_password.is_empty()
                        || credentials.s3_access_key.is_empty()
                        || credentials.s3_secret_key.is_empty()
                    {
                        return Err(SecurityErrorDto::invalid_input());
                    }
                }
            }
            if credentials.is_empty() {
                return Ok((
                    CloudSyncSecretMutation::Clear,
                    validation_credentials(config, CloudCredentials::default()),
                ));
            }
            let encrypted = encrypt_credentials(config, &credentials).map_err(map_cloud_error)?;
            Ok((
                CloudSyncSecretMutation::Replace(CloudSyncSecretRecord {
                    config_id: config.id.clone(),
                    dpapi_ciphertext: encrypted.ciphertext,
                    binding_sha256: encrypted.binding_sha256,
                    updated_at: db::now_millis(),
                }),
                credentials,
            ))
        }
    }
}

fn validation_credentials(
    config: &CloudSyncConfig,
    mut credentials: CloudCredentials,
) -> CloudCredentials {
    if config.service_type == CloudSyncServiceType::S3Compatible && credentials.is_empty() {
        credentials.s3_access_key = "validation-only".to_owned();
        credentials.s3_secret_key = "validation-only".to_owned();
    }
    credentials
}

fn config_from_record(record: &CloudSyncConfigRecord) -> Result<CloudSyncConfig, CloudSyncError> {
    if record.id.len() > 80 || record.endpoint_url.encode_utf16().count() > 4_096 {
        return Err(CloudSyncError::invalid_configuration());
    }
    let selected: Vec<CloudSyncContent> = serde_json::from_str(&record.selected_contents_json)
        .map_err(|_| CloudSyncError::invalid_configuration())?;
    let selected_contents = selected.iter().copied().collect::<BTreeSet<_>>();
    if selected.is_empty() || selected.len() != selected_contents.len() {
        return Err(CloudSyncError::invalid_configuration());
    }
    let config = CloudSyncConfig {
        id: record.id.clone(),
        name: record.name.clone(),
        enabled: record.enabled,
        service_type: parse_service(&record.service_type)?,
        endpoint_url: record.endpoint_url.clone(),
        remote_path: record.remote_path.clone(),
        web_dav_username: record.webdav_username.clone(),
        s3_bucket: record.s3_bucket.clone(),
        s3_region: record.s3_region.clone(),
        allow_insecure_http: record.allow_insecure_http,
        selected_contents,
        direction: parse_direction(&record.direction)?,
    };
    let mut structural = config.clone();
    structural.enabled = true;
    let credentials = validation_credentials(&structural, CloudCredentials::default());
    validate_cloud_sync_config(&structural, &credentials)?;
    Ok(config)
}

fn record_from_config(
    config: &CloudSyncConfig,
    sort_order: i64,
    updated_at: i64,
) -> Result<CloudSyncConfigRecord, SecurityErrorDto> {
    Ok(CloudSyncConfigRecord {
        id: config.id.clone(),
        name: config.name.clone(),
        enabled: config.enabled,
        service_type: match config.service_type {
            CloudSyncServiceType::Webdav => "WEBDAV",
            CloudSyncServiceType::S3Compatible => "S3_COMPATIBLE",
        }
        .to_owned(),
        endpoint_url: config.endpoint_url.clone(),
        remote_path: config.remote_path.clone(),
        webdav_username: config.web_dav_username.clone(),
        s3_bucket: config.s3_bucket.clone(),
        s3_region: config.s3_region.clone(),
        allow_insecure_http: config.allow_insecure_http,
        selected_contents_json: serde_json::to_string(
            &config.selected_contents.iter().copied().collect::<Vec<_>>(),
        )
        .map_err(|_| SecurityErrorDto::invalid_input())?,
        direction: match config.direction {
            CloudSyncDirection::UploadOnly => "UPLOAD_ONLY",
            CloudSyncDirection::TwoWay => "TWO_WAY",
        }
        .to_owned(),
        sort_order,
        updated_at,
    })
}

fn config_dto(
    database: &Database,
    record: &CloudSyncConfigRecord,
) -> CommandResult<CloudSyncConfigV1> {
    let config = config_from_record(record).map_err(map_cloud_error)?;
    let has_credentials = match database
        .get_cloud_sync_secret(&record.id)
        .map_err(map_data_error)?
    {
        Some(secret) => {
            secret.binding_sha256 == secret_binding_sha256(&config).map_err(map_cloud_error)?
        }
        None => false,
    };
    Ok(CloudSyncConfigV1 {
        id: config.id,
        name: config.name,
        enabled: config.enabled,
        service_type: config.service_type,
        endpoint_url: config.endpoint_url,
        remote_path: config.remote_path,
        web_dav_username: config.web_dav_username,
        s3_bucket: config.s3_bucket,
        s3_region: config.s3_region,
        allow_insecure_http: config.allow_insecure_http,
        selected_contents: config.selected_contents.into_iter().collect(),
        direction: config.direction,
        has_credentials,
    })
}

fn parse_service(value: &str) -> Result<CloudSyncServiceType, CloudSyncError> {
    match value {
        "WEBDAV" => Ok(CloudSyncServiceType::Webdav),
        "S3_COMPATIBLE" => Ok(CloudSyncServiceType::S3Compatible),
        _ => Err(CloudSyncError::invalid_configuration()),
    }
}

fn parse_direction(value: &str) -> Result<CloudSyncDirection, CloudSyncError> {
    match value {
        "UPLOAD_ONLY" => Ok(CloudSyncDirection::UploadOnly),
        "TWO_WAY" => Ok(CloudSyncDirection::TwoWay),
        _ => Err(CloudSyncError::invalid_configuration()),
    }
}

fn summary_from_result(
    result: &CloudSyncRunResult,
) -> Result<CloudSyncRunSummaryV1, CloudSyncError> {
    Ok(CloudSyncRunSummaryV1 {
        schema_version: DTO_VERSION,
        uploaded: u32::try_from(result.uploaded_count())
            .map_err(|_| CloudSyncError::limit_exceeded())?,
        downloaded: u32::try_from(result.downloaded_count())
            .map_err(|_| CloudSyncError::limit_exceeded())?,
        conflicts: u32::try_from(result.conflict_count())
            .map_err(|_| CloudSyncError::limit_exceeded())?,
        skipped: u32::try_from(
            result
                .reports
                .iter()
                .filter(|report| report.outcome == CloudSyncItemOutcome::RemoteChangeSkipped)
                .count(),
        )
        .map_err(|_| CloudSyncError::limit_exceeded())?,
    })
}

fn require_status_finish(
    database: &Database,
    token: &str,
    status: &CloudSyncStatusRecord,
) -> Result<(), CloudSyncError> {
    if database
        .finish_cloud_sync_run(token, status)
        .map_err(map_data_to_cloud)?
    {
        Ok(())
    } else {
        Err(CloudSyncError::conflict())
    }
}

fn bounded_i64(value: usize) -> Result<i64, CloudSyncError> {
    i64::try_from(value).map_err(|_| CloudSyncError::limit_exceeded())
}

fn require_schema(version: u32) -> CommandResult<()> {
    if version == DTO_VERSION {
        Ok(())
    } else {
        Err(SecurityErrorDto::new(
            "ipc_version_unsupported",
            "This request version is not supported.",
            false,
        ))
    }
}

fn cloud_busy_error() -> SecurityErrorDto {
    SecurityErrorDto::new(
        "cloud_sync_busy",
        "Cloud synchronization is currently running.",
        true,
    )
}

fn cancelled_error() -> CloudSyncError {
    CloudSyncError::new(
        CloudSyncErrorCode::Cancelled,
        "Cloud synchronization was cancelled.",
        true,
    )
}

fn map_data_to_cloud(error: DataError) -> CloudSyncError {
    match error {
        DataError::Validation(_) | DataError::NotFound | DataError::UnsupportedVersion => {
            CloudSyncError::invalid_configuration()
        }
        DataError::Sqlite(_) | DataError::Io(_) | DataError::Json(_) => CloudSyncError::storage(),
    }
}

fn map_data_error(error: DataError) -> SecurityErrorDto {
    map_cloud_error(map_data_to_cloud(error))
}

fn map_security_to_cloud(error: SecurityErrorDto) -> CloudSyncError {
    match error.code.as_str() {
        "backup_invalid" => CloudSyncError::backup_invalid(),
        "invalid_input" => CloudSyncError::invalid_input(),
        _ => CloudSyncError::storage(),
    }
}

fn map_cloud_error(error: CloudSyncError) -> SecurityErrorDto {
    SecurityErrorDto::new(error_code(error.code), error.message, error.retryable)
}

fn error_code(code: CloudSyncErrorCode) -> String {
    match code {
        CloudSyncErrorCode::InvalidConfiguration => "invalid_configuration",
        CloudSyncErrorCode::InvalidInput => "invalid_input",
        CloudSyncErrorCode::AuthenticationFailed => "authentication_failed",
        CloudSyncErrorCode::PermissionDenied => "permission_denied",
        CloudSyncErrorCode::RemoteDirectoryMissing => "remote_directory_missing",
        CloudSyncErrorCode::UnsupportedRemote => "unsupported_remote",
        CloudSyncErrorCode::Conflict => "conflict",
        CloudSyncErrorCode::LimitExceeded => "limit_exceeded",
        CloudSyncErrorCode::NetworkUnavailable => "network_unavailable",
        CloudSyncErrorCode::TimedOut => "timed_out",
        CloudSyncErrorCode::Cancelled => "cancelled",
        CloudSyncErrorCode::StorageUnavailable => "storage_unavailable",
        CloudSyncErrorCode::BackupInvalid => "backup_invalid",
    }
    .to_owned()
}

fn millis_to_rfc3339(value: i64) -> Option<String> {
    DateTime::<Utc>::from_timestamp_millis(value)
        .map(|value| value.to_rfc3339_opts(SecondsFormat::Millis, true))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runtime_record_rejects_phone_usage_and_duplicate_content() {
        let mut record = CloudSyncConfigRecord {
            id: "cloud".to_owned(),
            name: "Cloud".to_owned(),
            enabled: false,
            service_type: "WEBDAV".to_owned(),
            endpoint_url: "https://example.test/dav".to_owned(),
            remote_path: "DeskCubby".to_owned(),
            webdav_username: String::new(),
            s3_bucket: String::new(),
            s3_region: "us-east-1".to_owned(),
            allow_insecure_http: false,
            selected_contents_json: r#"["DIARIES","DIARIES"]"#.to_owned(),
            direction: "TWO_WAY".to_owned(),
            sort_order: 0,
            updated_at: 0,
        };
        assert!(config_from_record(&record).is_err());
        record.selected_contents_json = r#"["PHONE_USAGE"]"#.to_owned();
        assert!(config_from_record(&record).is_err());
    }

    #[test]
    fn run_summary_is_bounded_and_contains_no_item_keys() {
        let result = CloudSyncRunResult {
            config_id: "private-config".to_owned(),
            started_at_millis: 1,
            finished_at_millis: 2,
            reports: vec![super::super::CloudSyncItemReport {
                key: "diaries/private-name.md".to_owned(),
                outcome: CloudSyncItemOutcome::Uploaded,
            }],
            transferred_bytes: 12,
        };
        let encoded = serde_json::to_string(&summary_from_result(&result).unwrap()).unwrap();
        assert!(!encoded.contains("private"));
        assert_eq!(
            encoded,
            r#"{"schemaVersion":1,"uploaded":1,"downloaded":0,"conflicts":0,"skipped":0}"#
        );
    }

    #[test]
    fn credential_update_uses_exact_frontend_camel_case_fields() {
        let update: CloudCredentialUpdateV1 = serde_json::from_str(
            r#"{"mode":"replace","webDavPassword":"dav-secret","s3AccessKey":"","s3SecretKey":"","s3SessionToken":""}"#,
        )
        .expect("frontend credential payload");
        match update {
            CloudCredentialUpdateV1::Replace {
                web_dav_password,
                s3_access_key,
                s3_secret_key,
                s3_session_token,
            } => {
                assert_eq!(
                    web_dav_password.as_ref().map(|value| value.0.as_str()),
                    Some("dav-secret")
                );
                assert_eq!(
                    s3_access_key.as_ref().map(|value| value.0.as_str()),
                    Some("")
                );
                assert_eq!(
                    s3_secret_key.as_ref().map(|value| value.0.as_str()),
                    Some("")
                );
                assert_eq!(
                    s3_session_token.as_ref().map(|value| value.0.as_str()),
                    Some("")
                );
            }
            _ => panic!("expected replacement"),
        }
        assert!(
            serde_json::from_str::<CloudCredentialUpdateV1>(
                r#"{"mode":"replace","webdav_password":"secret"}"#
            )
            .is_err()
        );
    }
}
