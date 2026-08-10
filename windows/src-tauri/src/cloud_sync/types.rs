use std::{
    collections::{BTreeMap, BTreeSet},
    fmt,
    future::Future,
    pin::Pin,
    sync::Arc,
};

use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;
pub const DEFAULT_CLOUD_SYNC_USER_AGENT: &str = "DeskCubby-Sync/1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CloudSyncServiceType {
    Webdav,
    S3Compatible,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CloudSyncContent {
    Diaries,
    Media,
    JsonBackup,
    UsageStatistics,
    ReadingProgress,
}

impl CloudSyncContent {
    pub const fn remote_directory(self) -> &'static str {
        match self {
            Self::Diaries => "diaries",
            Self::Media => "media",
            Self::JsonBackup => "json",
            Self::UsageStatistics => "usage/v1",
            Self::ReadingProgress => "reading/v1",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CloudSyncDirection {
    UploadOnly,
    TwoWay,
}

/// Explicit reconciliation policy for a user-initiated synchronization run.
///
/// Forced modes choose one side only for objects present on both sides. They
/// never propagate deletions, and the engine still requires the remote version
/// or local content hash captured by the current inventory scan before a write.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CloudSyncRunMode {
    #[default]
    Normal,
    ForceUpload,
    ForceDownload,
}

/// Non-secret metadata. Secret material is always supplied separately through
/// [`CloudCredentials`] and must never be serialized into Android v28 backup
/// settings.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudSyncConfig {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub service_type: CloudSyncServiceType,
    pub endpoint_url: String,
    pub remote_path: String,
    pub user_agent: String,
    pub web_dav_username: String,
    pub s3_bucket: String,
    pub s3_region: String,
    pub s3_path_style: bool,
    pub allow_insecure_http: bool,
    pub selected_contents: BTreeSet<CloudSyncContent>,
    pub direction: CloudSyncDirection,
}

impl fmt::Debug for CloudSyncConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("CloudSyncConfig")
            .field("id", &self.id)
            .field("name", &self.name)
            .field("enabled", &self.enabled)
            .field("service_type", &self.service_type)
            .field(
                "endpoint_url",
                &if self.endpoint_url.is_empty() {
                    "<empty>"
                } else {
                    "<configured>"
                },
            )
            .field("remote_path", &self.remote_path)
            .field("user_agent", &self.user_agent)
            .field(
                "web_dav_username",
                &if self.web_dav_username.is_empty() {
                    "<empty>"
                } else {
                    "<redacted>"
                },
            )
            .field("s3_bucket", &self.s3_bucket)
            .field("s3_region", &self.s3_region)
            .field("s3_path_style", &self.s3_path_style)
            .field("allow_insecure_http", &self.allow_insecure_http)
            .field("selected_contents", &self.selected_contents)
            .field("direction", &self.direction)
            .finish()
    }
}

impl Default for CloudSyncConfig {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            enabled: true,
            service_type: CloudSyncServiceType::Webdav,
            endpoint_url: String::new(),
            remote_path: "DeskCubby".to_owned(),
            user_agent: DEFAULT_CLOUD_SYNC_USER_AGENT.to_owned(),
            web_dav_username: String::new(),
            s3_bucket: String::new(),
            s3_region: "us-east-1".to_owned(),
            s3_path_style: true,
            allow_insecure_http: false,
            selected_contents: [
                CloudSyncContent::Diaries,
                CloudSyncContent::Media,
                CloudSyncContent::JsonBackup,
                CloudSyncContent::UsageStatistics,
                CloudSyncContent::ReadingProgress,
            ]
            .into_iter()
            .collect(),
            direction: CloudSyncDirection::TwoWay,
        }
    }
}

#[derive(Clone, Default, PartialEq, Eq)]
pub struct CloudCredentials {
    pub web_dav_password: String,
    pub s3_access_key: String,
    pub s3_secret_key: String,
    pub s3_session_token: String,
}

impl CloudCredentials {
    pub fn is_empty(&self) -> bool {
        self.web_dav_password.is_empty()
            && self.s3_access_key.is_empty()
            && self.s3_secret_key.is_empty()
            && self.s3_session_token.is_empty()
    }
}

impl Zeroize for CloudCredentials {
    fn zeroize(&mut self) {
        self.web_dav_password.zeroize();
        self.s3_access_key.zeroize();
        self.s3_secret_key.zeroize();
        self.s3_session_token.zeroize();
    }
}

impl Drop for CloudCredentials {
    fn drop(&mut self) {
        self.zeroize();
    }
}

impl fmt::Debug for CloudCredentials {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("CloudCredentials")
            .field(
                "web_dav_password",
                &if self.web_dav_password.is_empty() {
                    "<empty>"
                } else {
                    "<redacted>"
                },
            )
            .field(
                "s3_access_key",
                &if self.s3_access_key.is_empty() {
                    "<empty>"
                } else {
                    "<redacted>"
                },
            )
            .field(
                "s3_secret_key",
                &if self.s3_secret_key.is_empty() {
                    "<empty>"
                } else {
                    "<redacted>"
                },
            )
            .field(
                "s3_session_token",
                &if self.s3_session_token.is_empty() {
                    "<empty>"
                } else {
                    "<redacted>"
                },
            )
            .finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CloudSyncErrorCode {
    InvalidConfiguration,
    ForceDownloadSourceCount,
    InvalidInput,
    AuthenticationFailed,
    PermissionDenied,
    RemoteDirectoryMissing,
    UnsupportedRemote,
    Conflict,
    LimitExceeded,
    NetworkUnavailable,
    TimedOut,
    Cancelled,
    StorageUnavailable,
    BackupInvalid,
}

/// Safe error crossing the command boundary. It deliberately does not retain a
/// network error, URL, local path, response body or secret as a source value.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudSyncError {
    pub code: CloudSyncErrorCode,
    pub message: &'static str,
    pub retryable: bool,
}

impl CloudSyncError {
    pub const fn new(code: CloudSyncErrorCode, message: &'static str, retryable: bool) -> Self {
        Self {
            code,
            message,
            retryable,
        }
    }

    pub const fn invalid_configuration() -> Self {
        Self::new(
            CloudSyncErrorCode::InvalidConfiguration,
            "The cloud synchronization configuration is invalid.",
            true,
        )
    }

    pub const fn force_download_source_count() -> Self {
        Self::new(
            CloudSyncErrorCode::ForceDownloadSourceCount,
            "Force download requires exactly one enabled cloud source.",
            false,
        )
    }

    pub const fn invalid_input() -> Self {
        Self::new(
            CloudSyncErrorCode::InvalidInput,
            "The synchronization input is invalid.",
            true,
        )
    }

    pub const fn conflict() -> Self {
        Self::new(
            CloudSyncErrorCode::Conflict,
            "Cloud or local content changed during synchronization.",
            true,
        )
    }

    pub const fn limit_exceeded() -> Self {
        Self::new(
            CloudSyncErrorCode::LimitExceeded,
            "The synchronization safety limit was exceeded.",
            true,
        )
    }

    pub const fn network() -> Self {
        Self::new(
            CloudSyncErrorCode::NetworkUnavailable,
            "The cloud service could not be reached.",
            true,
        )
    }

    pub const fn storage() -> Self {
        Self::new(
            CloudSyncErrorCode::StorageUnavailable,
            "The local synchronization data could not be read or written.",
            true,
        )
    }

    pub const fn backup_invalid() -> Self {
        Self::new(
            CloudSyncErrorCode::BackupInvalid,
            "The incoming application backup is invalid.",
            true,
        )
    }
}

impl fmt::Display for CloudSyncError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message)
    }
}

impl std::error::Error for CloudSyncError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CloudSyncLimits {
    pub connect_timeout_millis: u64,
    pub read_timeout_millis: u64,
    pub overall_timeout_millis: u64,
    pub max_object_bytes: u64,
    pub max_transferred_bytes: u64,
    pub max_objects: usize,
}

impl Default for CloudSyncLimits {
    fn default() -> Self {
        Self {
            connect_timeout_millis: 15_000,
            read_timeout_millis: 30_000,
            overall_timeout_millis: 10 * 60_000,
            max_object_bytes: 64 * 1024 * 1024,
            max_transferred_bytes: 512 * 1024 * 1024,
            max_objects: 10_000,
        }
    }
}

impl CloudSyncLimits {
    pub fn validate(self) -> Result<Self, CloudSyncError> {
        if !(1_000..=120_000).contains(&self.connect_timeout_millis)
            || !(1_000..=300_000).contains(&self.read_timeout_millis)
            || !(1_000..=3_600_000).contains(&self.overall_timeout_millis)
            || !(1..=512 * 1024 * 1024).contains(&self.max_object_bytes)
            || self.max_transferred_bytes < self.max_object_bytes
            || self.max_transferred_bytes > 4 * 1024 * 1024 * 1024
            || !(1..=100_000).contains(&self.max_objects)
        {
            return Err(CloudSyncError::limit_exceeded());
        }
        Ok(self)
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct LocalSyncObject {
    pub key: String,
    pub content: CloudSyncContent,
    pub size: u64,
    pub last_modified_millis: i64,
    pub sha256: String,
    /// Store-private token. It may identify a file but must not cross IPC.
    pub(crate) local_token: String,
}

impl fmt::Debug for LocalSyncObject {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("LocalSyncObject")
            .field("key", &self.key)
            .field("content", &self.content)
            .field("size", &self.size)
            .field("last_modified_millis", &self.last_modified_millis)
            .field("sha256", &self.sha256)
            .field("local_token", &"<redacted>")
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoteVersion {
    pub(crate) content_sha256: String,
    pub(crate) blob_etag: String,
    pub(crate) storage_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoteSyncObject {
    pub key: String,
    pub size: u64,
    pub last_modified_millis: i64,
    pub sha256: String,
    pub version: RemoteVersion,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LocalWriteResult {
    Applied(LocalSyncObject),
    ConflictCopy {
        existing: Option<LocalSyncObject>,
        copy: LocalSyncObject,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BaseState {
    pub scope_fingerprint: String,
    pub hashes_by_key: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CloudSyncItemOutcome {
    Unchanged,
    Uploaded,
    Downloaded,
    ConflictCopySaved,
    RemoteChangeSkipped,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudSyncItemReport {
    pub key: String,
    pub outcome: CloudSyncItemOutcome,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudSyncProgress {
    pub completed_objects: usize,
    pub total_objects: usize,
    pub transferred_bytes: u64,
    /// Logical key, never an absolute local path. Commands may omit it if the
    /// UI should expose less private metadata.
    pub current_key: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudSyncRunResult {
    pub config_id: String,
    pub started_at_millis: i64,
    pub finished_at_millis: i64,
    pub reports: Vec<CloudSyncItemReport>,
    pub transferred_bytes: u64,
}

impl CloudSyncRunResult {
    pub fn uploaded_count(&self) -> usize {
        self.reports
            .iter()
            .filter(|item| item.outcome == CloudSyncItemOutcome::Uploaded)
            .count()
    }

    pub fn downloaded_count(&self) -> usize {
        self.reports
            .iter()
            .filter(|item| item.outcome == CloudSyncItemOutcome::Downloaded)
            .count()
    }

    pub fn conflict_count(&self) -> usize {
        self.reports
            .iter()
            .filter(|item| item.outcome == CloudSyncItemOutcome::ConflictCopySaved)
            .count()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlobMetadata {
    pub etag: String,
    pub size: u64,
    pub last_modified_millis: i64,
}

#[derive(Clone, PartialEq, Eq)]
pub struct BlobRead {
    pub metadata: BlobMetadata,
    pub bytes: Vec<u8>,
}

impl fmt::Debug for BlobRead {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("BlobRead")
            .field("metadata", &self.metadata)
            .field("bytes", &format_args!("<redacted:{}>", self.bytes.len()))
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BlobWriteCondition {
    MustNotExist,
    MustMatch(String),
}

pub trait ConditionalBlobTransport: Send + Sync {
    fn get<'a>(
        &'a self,
        storage_name: &'a str,
        max_bytes: u64,
        expected_etag: Option<&'a str>,
    ) -> BoxFuture<'a, Result<Option<BlobRead>, CloudSyncError>>;

    fn put<'a>(
        &'a self,
        storage_name: &'a str,
        bytes: &'a [u8],
        content_sha256: &'a str,
        condition: BlobWriteCondition,
    ) -> BoxFuture<'a, Result<BlobMetadata, CloudSyncError>>;
}

pub trait CloudLocalStore: Send + Sync {
    fn list<'a>(
        &'a self,
        selected_contents: &'a BTreeSet<CloudSyncContent>,
        limits: CloudSyncLimits,
    ) -> BoxFuture<'a, Result<Vec<LocalSyncObject>, CloudSyncError>>;

    fn read<'a>(
        &'a self,
        object: &'a LocalSyncObject,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<Vec<u8>, CloudSyncError>>;

    #[allow(clippy::too_many_arguments)]
    fn write_remote<'a>(
        &'a self,
        key: &'a str,
        bytes: &'a [u8],
        content_sha256: &'a str,
        last_modified_millis: i64,
        expected_local_sha256: Option<&'a str>,
        limits: CloudSyncLimits,
    ) -> BoxFuture<'a, Result<LocalWriteResult, CloudSyncError>>;
}

pub trait CloudRemoteStore: Send + Sync {
    fn list<'a>(
        &'a self,
        prefixes: &'a BTreeSet<String>,
    ) -> BoxFuture<'a, Result<Vec<RemoteSyncObject>, CloudSyncError>>;

    fn read<'a>(
        &'a self,
        object: &'a RemoteSyncObject,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<Vec<u8>, CloudSyncError>>;

    fn write<'a>(
        &'a self,
        key: &'a str,
        bytes: &'a [u8],
        content_sha256: &'a str,
        last_modified_millis: i64,
        expected_remote_version: Option<&'a RemoteVersion>,
    ) -> BoxFuture<'a, Result<RemoteSyncObject, CloudSyncError>>;
}

pub trait CloudRemoteStoreFactory: Send + Sync {
    fn create(
        &self,
        config: &crate::cloud_sync::ValidatedCloudSyncConfig,
        credentials: &CloudCredentials,
        limits: CloudSyncLimits,
    ) -> Result<Arc<dyn CloudRemoteStore>, CloudSyncError>;
}

pub trait CloudSyncStateStore: Send + Sync {
    fn load<'a>(
        &'a self,
        config_id: &'a str,
    ) -> BoxFuture<'a, Result<Option<BaseState>, CloudSyncError>>;

    fn save<'a>(
        &'a self,
        config_id: &'a str,
        state: &'a BaseState,
    ) -> BoxFuture<'a, Result<(), CloudSyncError>>;
}
