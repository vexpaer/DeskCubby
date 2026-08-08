use std::{
    collections::{BTreeMap, BTreeSet},
    fmt,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use serde_json::Value;
use tokio::sync::Mutex;
use uuid::Uuid;

use super::{
    encoding::sha256_hex,
    types::{
        BoxFuture, CloudLocalStore, CloudSyncContent, CloudSyncError, CloudSyncLimits,
        LocalSyncObject, LocalWriteResult,
    },
    validation::{require_valid_sync_key, valid_hash},
};

const JSON_SYNC_KEY: &str = "json/dc.json";
const LEGACY_JSON_SYNC_KEY: &str = "json/DeskCubby.json";
const READER_PROGRESS_SYNC_KEY: &str = "reading/v1/progress.json";
const MAX_JSON_BYTES: u64 = 64 * 1024 * 1024;
const ANDROID_BACKUP_FORMAT_VERSION: i64 = 28;
const MAX_READER_PROGRESS_BYTES: usize = 512 * 1024;
const MAX_PENDING_JSON: usize = 100;
const RECOVERY_SUFFIX: &str = ".dc-sync-recovery";

#[derive(Clone)]
pub struct LocalRoots {
    pub diary: Option<PathBuf>,
    pub media: Option<PathBuf>,
    /// Application-private directory, never a user-selected shared folder.
    pub incoming_json: PathBuf,
}

impl fmt::Debug for LocalRoots {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("LocalRoots")
            .field("diary", &self.diary.as_ref().map(|_| "<configured>"))
            .field("media", &self.media.as_ref().map(|_| "<configured>"))
            .field("incoming_json", &"<private>")
            .finish()
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct JsonSnapshot {
    pub bytes: Vec<u8>,
    pub last_modified_millis: i64,
    pub local_token: String,
}

impl fmt::Debug for JsonSnapshot {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("JsonSnapshot")
            .field("bytes", &format_args!("<redacted:{}>", self.bytes.len()))
            .field("last_modified_millis", &self.last_modified_millis)
            .field("local_token", &"<redacted>")
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingJson {
    /// Safe leaf identifier, not an absolute path.
    pub id: String,
    pub received_at_millis: i64,
    pub size: u64,
}

pub trait JsonBackupBridge: Send + Sync {
    /// Return a complete Android-readable backup. The local store canonicalizes
    /// `exportedAt` to zero before hashing.
    fn snapshot<'a>(
        &'a self,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<JsonSnapshot, CloudSyncError>>;

    /// Perform the full Android-v28/legacy validation. No database mutation is allowed
    /// here; importing remains a separate user-confirmed command.
    fn validate_incoming<'a>(
        &'a self,
        bytes: &'a [u8],
    ) -> BoxFuture<'a, Result<(), CloudSyncError>>;
}

/// One canonical Android-compatible `usage/v1/{deviceId}.json` object.
/// The bridge owns schema validation and per-day merge semantics; this local
/// store only enforces sync-key, hash, size and transfer boundaries.
#[derive(Clone, PartialEq, Eq)]
pub struct UsageStatisticsSnapshot {
    pub key: String,
    pub bytes: Vec<u8>,
    pub last_modified_millis: i64,
    pub local_token: String,
}

impl fmt::Debug for UsageStatisticsSnapshot {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("UsageStatisticsSnapshot")
            .field("key", &self.key)
            .field("bytes", &format_args!("<redacted:{}>", self.bytes.len()))
            .field("last_modified_millis", &self.last_modified_millis)
            .field("local_token", &"<redacted>")
            .finish()
    }
}

pub trait UsageStatisticsBridge: Send + Sync {
    fn snapshots<'a>(
        &'a self,
        max_objects: usize,
        max_object_bytes: u64,
    ) -> BoxFuture<'a, Result<Vec<UsageStatisticsSnapshot>, CloudSyncError>>;

    #[allow(clippy::too_many_arguments)]
    fn merge_remote<'a>(
        &'a self,
        key: &'a str,
        bytes: &'a [u8],
        content_sha256: &'a str,
        last_modified_millis: i64,
        expected_local_sha256: Option<&'a str>,
        limits: CloudSyncLimits,
    ) -> BoxFuture<'a, Result<LocalWriteResult, CloudSyncError>>;
}

/// One canonical, URI-free `reading/v1/progress.json` object. The bridge owns strict schema
/// validation and record-level LWW merging; the generic store only guards key/hash/size changes.
#[derive(Clone, PartialEq, Eq)]
pub struct ReaderProgressSnapshot {
    pub bytes: Vec<u8>,
    pub last_modified_millis: i64,
    pub local_token: String,
}

impl fmt::Debug for ReaderProgressSnapshot {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ReaderProgressSnapshot")
            .field("bytes", &format_args!("<redacted:{}>", self.bytes.len()))
            .field("last_modified_millis", &self.last_modified_millis)
            .field("local_token", &"<redacted>")
            .finish()
    }
}

pub trait ReaderProgressBridge: Send + Sync {
    fn snapshot<'a>(
        &'a self,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<ReaderProgressSnapshot, CloudSyncError>>;

    fn merge_remote<'a>(
        &'a self,
        bytes: &'a [u8],
        content_sha256: &'a str,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<ReaderProgressSnapshot, CloudSyncError>>;
}

pub struct FileSystemLocalStore {
    roots: LocalRoots,
    config_id: String,
    json_bridge: Option<Arc<dyn JsonBackupBridge>>,
    usage_bridge: Option<Arc<dyn UsageStatisticsBridge>>,
    reader_progress_bridge: Option<Arc<dyn ReaderProgressBridge>>,
    json_snapshot: Mutex<Option<JsonSnapshot>>,
    usage_snapshots: Mutex<BTreeMap<String, UsageStatisticsSnapshot>>,
    reader_progress_snapshot: Mutex<Option<ReaderProgressSnapshot>>,
    mutation_mutex: Mutex<()>,
}

impl FileSystemLocalStore {
    pub fn new(
        roots: LocalRoots,
        config_id: String,
        json_bridge: Option<Arc<dyn JsonBackupBridge>>,
    ) -> Result<Self, CloudSyncError> {
        Self::new_with_usage(roots, config_id, json_bridge, None)
    }

    pub fn new_with_usage(
        roots: LocalRoots,
        config_id: String,
        json_bridge: Option<Arc<dyn JsonBackupBridge>>,
        usage_bridge: Option<Arc<dyn UsageStatisticsBridge>>,
    ) -> Result<Self, CloudSyncError> {
        Self::new_with_bridges(roots, config_id, json_bridge, usage_bridge, None)
    }

    pub fn new_with_bridges(
        roots: LocalRoots,
        config_id: String,
        json_bridge: Option<Arc<dyn JsonBackupBridge>>,
        usage_bridge: Option<Arc<dyn UsageStatisticsBridge>>,
        reader_progress_bridge: Option<Arc<dyn ReaderProgressBridge>>,
    ) -> Result<Self, CloudSyncError> {
        if config_id.trim().is_empty() || config_id.chars().any(char::is_control) {
            return Err(CloudSyncError::invalid_configuration());
        }
        Ok(Self {
            roots,
            config_id,
            json_bridge,
            usage_bridge,
            reader_progress_bridge,
            json_snapshot: Mutex::new(None),
            usage_snapshots: Mutex::new(BTreeMap::new()),
            reader_progress_snapshot: Mutex::new(None),
            mutation_mutex: Mutex::new(()),
        })
    }

    pub async fn list_pending_json(&self) -> Result<Vec<PendingJson>, CloudSyncError> {
        let _guard = self.mutation_mutex.lock().await;
        list_pending_json_files(&self.roots.incoming_json)
    }

    pub async fn read_pending_json(&self, id: &str) -> Result<Vec<u8>, CloudSyncError> {
        let _guard = self.mutation_mutex.lock().await;
        validate_pending_id(id)?;
        let root = validate_directory(&self.roots.incoming_json, false)?;
        let path = resolve_existing_leaf(&root, id)?;
        read_file_bounded(&path, MAX_JSON_BYTES)
    }

    /// Call only after the existing backup preview + confirmation transaction
    /// succeeds. Failed imports deliberately leave the staged recovery source.
    pub async fn remove_pending_json(&self, id: &str) -> Result<(), CloudSyncError> {
        let _guard = self.mutation_mutex.lock().await;
        validate_pending_id(id)?;
        let root = validate_directory(&self.roots.incoming_json, false)?;
        let path = resolve_existing_leaf(&root, id)?;
        fs::remove_file(path).map_err(|_| CloudSyncError::storage())
    }

    async fn stage_json(
        &self,
        bytes: &[u8],
        content_sha256: &str,
    ) -> Result<PendingJson, CloudSyncError> {
        if bytes.is_empty()
            || bytes.len() as u64 > MAX_JSON_BYTES
            || sha256_hex(bytes) != content_sha256
        {
            return Err(CloudSyncError::backup_invalid());
        }
        let bridge = self
            .json_bridge
            .as_ref()
            .ok_or_else(CloudSyncError::invalid_configuration)?;
        bridge.validate_incoming(bytes).await?;
        let root = validate_directory(&self.roots.incoming_json, true)?;
        let source_hash = &sha256_hex(self.config_id.as_bytes())[..8];
        let hash_short = &content_sha256[..8];
        let prefix = format!("DeskCubby-incoming-{source_hash}-{hash_short}-");
        let existing = list_pending_json_files(&root)?.into_iter().find(|item| {
            item.id.starts_with(&prefix)
                && item.size == bytes.len() as u64
                && resolve_existing_leaf(&root, &item.id)
                    .and_then(|path| read_file_bounded(&path, MAX_JSON_BYTES))
                    .is_ok_and(|candidate| sha256_hex(&candidate) == content_sha256)
        });
        if let Some(existing) = existing {
            return Ok(existing);
        }
        if list_pending_json_files(&root)?.len() >= MAX_PENDING_JSON {
            return Err(CloudSyncError::limit_exceeded());
        }
        let now = now_millis();
        let id = format!("{prefix}{now}-{}.json", Uuid::new_v4().simple());
        validate_pending_id(&id)?;
        let target = resolve_new_leaf(&root, &id)?;
        create_verified_new_file(&target, bytes, content_sha256)?;
        Ok(PendingJson {
            id,
            received_at_millis: file_modified_millis(&target),
            size: bytes.len() as u64,
        })
    }
}

impl CloudLocalStore for FileSystemLocalStore {
    fn list<'a>(
        &'a self,
        selected_contents: &'a BTreeSet<CloudSyncContent>,
        limits: CloudSyncLimits,
    ) -> BoxFuture<'a, Result<Vec<LocalSyncObject>, CloudSyncError>> {
        Box::pin(async move {
            let mut result = Vec::new();
            if selected_contents.contains(&CloudSyncContent::Diaries) {
                let root = self
                    .roots
                    .diary
                    .as_ref()
                    .ok_or_else(CloudSyncError::invalid_configuration)?;
                result.extend(scan_root(
                    root,
                    CloudSyncContent::Diaries,
                    limits,
                    limits.max_objects.saturating_sub(result.len()),
                )?);
            }
            if selected_contents.contains(&CloudSyncContent::Media) {
                let root = self
                    .roots
                    .media
                    .as_ref()
                    .ok_or_else(CloudSyncError::invalid_configuration)?;
                result.extend(scan_root(
                    root,
                    CloudSyncContent::Media,
                    limits,
                    limits.max_objects.saturating_sub(result.len()),
                )?);
            }
            if selected_contents.contains(&CloudSyncContent::JsonBackup) {
                let bridge = self
                    .json_bridge
                    .as_ref()
                    .ok_or_else(CloudSyncError::invalid_configuration)?;
                let mut snapshot = bridge.snapshot(limits.max_object_bytes).await?;
                snapshot.bytes = canonicalize_backup_for_cloud(&snapshot.bytes)?;
                if snapshot.bytes.len() as u64 > limits.max_object_bytes {
                    return Err(CloudSyncError::limit_exceeded());
                }
                let object = LocalSyncObject {
                    key: JSON_SYNC_KEY.to_owned(),
                    content: CloudSyncContent::JsonBackup,
                    size: snapshot.bytes.len() as u64,
                    last_modified_millis: 0,
                    sha256: sha256_hex(&snapshot.bytes),
                    local_token: snapshot.local_token.clone(),
                };
                *self.json_snapshot.lock().await = Some(snapshot);
                result.push(object);
            } else {
                *self.json_snapshot.lock().await = None;
            }
            if selected_contents.contains(&CloudSyncContent::UsageStatistics) {
                let bridge = self
                    .usage_bridge
                    .as_ref()
                    .ok_or_else(CloudSyncError::invalid_configuration)?;
                let snapshots = bridge
                    .snapshots(
                        limits.max_objects.saturating_sub(result.len()),
                        limits.max_object_bytes,
                    )
                    .await?;
                let mut cached = BTreeMap::new();
                for snapshot in snapshots {
                    require_valid_sync_key(&snapshot.key)?;
                    if !snapshot.key.starts_with("usage/v1/")
                        || snapshot.key["usage/v1/".len()..].contains('/')
                        || snapshot.bytes.is_empty()
                        || snapshot.bytes.len() as u64 > limits.max_object_bytes
                        || cached.contains_key(&snapshot.key)
                    {
                        return Err(CloudSyncError::invalid_input());
                    }
                    let sha256 = sha256_hex(&snapshot.bytes);
                    result.push(LocalSyncObject {
                        key: snapshot.key.clone(),
                        content: CloudSyncContent::UsageStatistics,
                        size: snapshot.bytes.len() as u64,
                        last_modified_millis: snapshot.last_modified_millis.max(0),
                        sha256,
                        local_token: snapshot.local_token.clone(),
                    });
                    cached.insert(snapshot.key.clone(), snapshot);
                }
                *self.usage_snapshots.lock().await = cached;
            } else {
                self.usage_snapshots.lock().await.clear();
            }
            if selected_contents.contains(&CloudSyncContent::ReadingProgress) {
                let bridge = self
                    .reader_progress_bridge
                    .as_ref()
                    .ok_or_else(CloudSyncError::invalid_configuration)?;
                let snapshot = bridge.snapshot(limits.max_object_bytes).await?;
                if snapshot.bytes.is_empty()
                    || snapshot.bytes.len() > MAX_READER_PROGRESS_BYTES
                    || snapshot.bytes.len() as u64 > limits.max_object_bytes
                    || snapshot.local_token.is_empty()
                {
                    return Err(CloudSyncError::limit_exceeded());
                }
                let object = LocalSyncObject {
                    key: READER_PROGRESS_SYNC_KEY.to_owned(),
                    content: CloudSyncContent::ReadingProgress,
                    size: snapshot.bytes.len() as u64,
                    last_modified_millis: snapshot.last_modified_millis.max(0),
                    sha256: sha256_hex(&snapshot.bytes),
                    local_token: snapshot.local_token.clone(),
                };
                *self.reader_progress_snapshot.lock().await = Some(snapshot);
                result.push(object);
            } else {
                *self.reader_progress_snapshot.lock().await = None;
            }
            if result.len() > limits.max_objects {
                return Err(CloudSyncError::limit_exceeded());
            }
            Ok(result)
        })
    }

    fn read<'a>(
        &'a self,
        object: &'a LocalSyncObject,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<Vec<u8>, CloudSyncError>> {
        Box::pin(async move {
            if object.content == CloudSyncContent::JsonBackup {
                let snapshot = self.json_snapshot.lock().await;
                let snapshot = snapshot.as_ref().ok_or_else(CloudSyncError::conflict)?;
                if snapshot.local_token != object.local_token
                    || snapshot.bytes.len() as u64 != object.size
                    || sha256_hex(&snapshot.bytes) != object.sha256
                {
                    return Err(CloudSyncError::conflict());
                }
                return Ok(snapshot.bytes.clone());
            }
            if object.content == CloudSyncContent::UsageStatistics {
                let snapshots = self.usage_snapshots.lock().await;
                let snapshot = snapshots
                    .get(&object.key)
                    .ok_or_else(CloudSyncError::conflict)?;
                if snapshot.local_token != object.local_token
                    || snapshot.bytes.len() as u64 != object.size
                    || sha256_hex(&snapshot.bytes) != object.sha256
                {
                    return Err(CloudSyncError::conflict());
                }
                return Ok(snapshot.bytes.clone());
            }
            if object.content == CloudSyncContent::ReadingProgress {
                let snapshot = self.reader_progress_snapshot.lock().await;
                let snapshot = snapshot.as_ref().ok_or_else(CloudSyncError::conflict)?;
                if object.key != READER_PROGRESS_SYNC_KEY
                    || snapshot.local_token != object.local_token
                    || snapshot.bytes.len() as u64 != object.size
                    || snapshot.bytes.len() > MAX_READER_PROGRESS_BYTES
                    || sha256_hex(&snapshot.bytes) != object.sha256
                {
                    return Err(CloudSyncError::conflict());
                }
                return Ok(snapshot.bytes.clone());
            }
            let (content, leaf) = parse_file_key(&object.key)?;
            if content != object.content || object.local_token != object.key {
                return Err(CloudSyncError::invalid_input());
            }
            let root = root_for_content(&self.roots, content)?;
            let root = validate_directory(root, false)?;
            let path = resolve_existing_leaf(&root, leaf)?;
            let bytes = read_file_bounded(&path, max_bytes)?;
            if bytes.len() as u64 != object.size || sha256_hex(&bytes) != object.sha256 {
                return Err(CloudSyncError::conflict());
            }
            Ok(bytes)
        })
    }

    fn write_remote<'a>(
        &'a self,
        key: &'a str,
        bytes: &'a [u8],
        content_sha256: &'a str,
        last_modified_millis: i64,
        expected_local_sha256: Option<&'a str>,
        limits: CloudSyncLimits,
    ) -> BoxFuture<'a, Result<LocalWriteResult, CloudSyncError>> {
        Box::pin(async move {
            let _guard = self.mutation_mutex.lock().await;
            require_valid_sync_key(key)?;
            if bytes.len() as u64 > limits.max_object_bytes
                || !valid_hash(content_sha256)
                || sha256_hex(bytes) != content_sha256
            {
                return Err(CloudSyncError::conflict());
            }
            if key == JSON_SYNC_KEY || key == LEGACY_JSON_SYNC_KEY {
                let current = match self.json_snapshot.lock().await.as_ref() {
                    Some(snapshot) => snapshot.clone(),
                    None => {
                        let bridge = self
                            .json_bridge
                            .as_ref()
                            .ok_or_else(CloudSyncError::invalid_configuration)?;
                        let mut snapshot = bridge.snapshot(limits.max_object_bytes).await?;
                        snapshot.bytes = canonicalize_backup_for_cloud(&snapshot.bytes)?;
                        snapshot
                    }
                };
                let pending = self.stage_json(bytes, content_sha256).await?;
                return Ok(LocalWriteResult::ConflictCopy {
                    existing: Some(LocalSyncObject {
                        key: JSON_SYNC_KEY.to_owned(),
                        content: CloudSyncContent::JsonBackup,
                        size: current.bytes.len() as u64,
                        last_modified_millis: 0,
                        sha256: sha256_hex(&current.bytes),
                        local_token: current.local_token,
                    }),
                    copy: LocalSyncObject {
                        key: format!("json/dc.remote-conflict-{}.json", &content_sha256[..8]),
                        content: CloudSyncContent::JsonBackup,
                        size: bytes.len() as u64,
                        last_modified_millis: pending.received_at_millis,
                        sha256: content_sha256.to_owned(),
                        local_token: pending.id,
                    },
                });
            }
            if key == READER_PROGRESS_SYNC_KEY {
                if bytes.is_empty() || bytes.len() > MAX_READER_PROGRESS_BYTES {
                    return Err(CloudSyncError::limit_exceeded());
                }
                let bridge = self
                    .reader_progress_bridge
                    .as_ref()
                    .ok_or_else(CloudSyncError::invalid_configuration)?;
                // Record-level LWW intentionally ignores the stale whole-object local hash. A
                // newer page saved during the scan is merged, never replaced by an older remote
                // snapshot; the next run uploads the merged object and converges both devices.
                let merged = bridge
                    .merge_remote(bytes, content_sha256, limits.max_object_bytes)
                    .await?;
                if merged.bytes.is_empty()
                    || merged.bytes.len() > MAX_READER_PROGRESS_BYTES
                    || merged.bytes.len() as u64 > limits.max_object_bytes
                    || merged.local_token.is_empty()
                {
                    return Err(CloudSyncError::limit_exceeded());
                }
                let object = LocalSyncObject {
                    key: READER_PROGRESS_SYNC_KEY.to_owned(),
                    content: CloudSyncContent::ReadingProgress,
                    size: merged.bytes.len() as u64,
                    last_modified_millis: merged.last_modified_millis.max(0),
                    sha256: sha256_hex(&merged.bytes),
                    local_token: merged.local_token.clone(),
                };
                *self.reader_progress_snapshot.lock().await = Some(merged);
                return Ok(LocalWriteResult::Applied(object));
            }
            if key.starts_with("usage/v1/") {
                let bridge = self
                    .usage_bridge
                    .as_ref()
                    .ok_or_else(CloudSyncError::invalid_configuration)?;
                return bridge
                    .merge_remote(
                        key,
                        bytes,
                        content_sha256,
                        last_modified_millis,
                        expected_local_sha256,
                        limits,
                    )
                    .await;
            }
            let (content, leaf) = parse_file_key(key)?;
            let root = validate_directory(root_for_content(&self.roots, content)?, false)?;
            write_remote_file(
                &root,
                content,
                leaf,
                bytes,
                content_sha256,
                last_modified_millis,
                expected_local_sha256,
                limits.max_object_bytes,
            )
        })
    }
}

pub fn canonicalize_backup_for_cloud(bytes: &[u8]) -> Result<Vec<u8>, CloudSyncError> {
    if bytes.is_empty() || bytes.len() as u64 > MAX_JSON_BYTES {
        return Err(CloudSyncError::backup_invalid());
    }
    let mut value: Value =
        serde_json::from_slice(bytes).map_err(|_| CloudSyncError::backup_invalid())?;
    let root = value
        .as_object_mut()
        .ok_or_else(CloudSyncError::backup_invalid)?;
    if root.get("format").and_then(Value::as_str) != Some("DeskCubby")
        || root.get("version").and_then(Value::as_i64) != Some(ANDROID_BACKUP_FORMAT_VERSION)
    {
        return Err(CloudSyncError::backup_invalid());
    }
    root.insert("exportedAt".to_owned(), Value::from(0));
    let encoded = serde_json::to_vec(&value).map_err(|_| CloudSyncError::backup_invalid())?;
    if encoded.len() as u64 > MAX_JSON_BYTES {
        return Err(CloudSyncError::limit_exceeded());
    }
    Ok(encoded)
}

#[allow(clippy::too_many_arguments)]
fn write_remote_file(
    root: &Path,
    content: CloudSyncContent,
    leaf: &str,
    bytes: &[u8],
    content_sha256: &str,
    last_modified_millis: i64,
    expected_local_sha256: Option<&str>,
    maximum: u64,
) -> Result<LocalWriteResult, CloudSyncError> {
    validate_leaf(leaf)?;
    if content == CloudSyncContent::Diaries
        && !leaf
            .rsplit_once('.')
            .is_some_and(|(_, extension)| extension.eq_ignore_ascii_case("md"))
    {
        return Err(CloudSyncError::invalid_input());
    }
    recover_interrupted_replace(root, leaf, maximum)?;
    let target = root.join(leaf);
    let existing = if target.exists() {
        Some(snapshot_file(root, content, leaf, maximum)?)
    } else {
        None
    };
    if existing.as_ref().map(|item| item.sha256.as_str()) != expected_local_sha256 {
        let copy = write_conflict_copy(
            root,
            content,
            leaf,
            bytes,
            content_sha256,
            last_modified_millis,
            maximum,
        )?;
        return Ok(LocalWriteResult::ConflictCopy { existing, copy });
    }
    if existing.is_none() {
        create_verified_new_file(&target, bytes, content_sha256)?;
        return Ok(LocalWriteResult::Applied(snapshot_file(
            root, content, leaf, maximum,
        )?));
    }

    let temporary_name = format!(
        ".{leaf}.sync-pending-{}-{}.tmp",
        &content_sha256[..8],
        Uuid::new_v4().simple()
    );
    let temporary = resolve_new_leaf(root, &temporary_name)?;
    create_verified_new_file(&temporary, bytes, content_sha256)?;
    let current = snapshot_file(root, content, leaf, maximum)?;
    if current.sha256 != expected_local_sha256.unwrap_or_default() {
        let _ = fs::remove_file(&temporary);
        let copy = write_conflict_copy(
            root,
            content,
            leaf,
            bytes,
            content_sha256,
            last_modified_millis,
            maximum,
        )?;
        return Ok(LocalWriteResult::ConflictCopy {
            existing: Some(current),
            copy,
        });
    }
    let recovery_name = recovery_name(leaf);
    let recovery = root.join(&recovery_name);
    if recovery.exists() {
        fs::remove_file(&recovery).map_err(|_| CloudSyncError::storage())?;
    }
    fs::rename(&target, &recovery).map_err(|_| CloudSyncError::storage())?;
    if fs::rename(&temporary, &target).is_err() {
        let _ = fs::rename(&recovery, &target);
        let _ = fs::remove_file(&temporary);
        return Err(CloudSyncError::storage());
    }
    let verified = snapshot_file(root, content, leaf, maximum);
    match verified {
        Ok(object) if object.sha256 == content_sha256 => {
            let _ = fs::remove_file(recovery);
            Ok(LocalWriteResult::Applied(object))
        }
        _ => {
            let _ = fs::remove_file(&target);
            let _ = fs::rename(&recovery, &target);
            Err(CloudSyncError::storage())
        }
    }
}

fn write_conflict_copy(
    root: &Path,
    content: CloudSyncContent,
    original: &str,
    bytes: &[u8],
    hash: &str,
    _last_modified_millis: i64,
    maximum: u64,
) -> Result<LocalSyncObject, CloudSyncError> {
    let preferred = sibling_name(original, &format!(".remote-conflict-{}", &hash[..8]));
    if root.join(&preferred).exists() {
        let existing = snapshot_file(root, content, &preferred, maximum)?;
        if existing.sha256 == hash {
            return Ok(existing);
        }
    }
    for sequence in 1..=10_000 {
        let candidate = if sequence == 1 {
            preferred.clone()
        } else {
            numbered_sibling(&preferred, sequence)
        };
        validate_leaf(&candidate)?;
        let path = root.join(&candidate);
        if path.exists() {
            continue;
        }
        match create_verified_new_file(&path, bytes, hash) {
            Ok(()) => return snapshot_file(root, content, &candidate, maximum),
            Err(_error) if path.exists() => continue,
            Err(error) => return Err(error),
        }
    }
    Err(CloudSyncError::limit_exceeded())
}

fn scan_root(
    root: &Path,
    content: CloudSyncContent,
    limits: CloudSyncLimits,
    remaining_objects: usize,
) -> Result<Vec<LocalSyncObject>, CloudSyncError> {
    let root = validate_directory(root, false)?;
    recover_directory(&root, limits.max_object_bytes)?;
    let mut names = fs::read_dir(&root)
        .map_err(|_| CloudSyncError::storage())?
        .map(|entry| entry.map_err(|_| CloudSyncError::storage()))
        .collect::<Result<Vec<_>, _>>()?;
    names.sort_by_key(|entry| entry.file_name().to_string_lossy().to_lowercase());
    let mut result = Vec::new();
    for entry in names {
        let metadata = fs::symlink_metadata(entry.path()).map_err(|_| CloudSyncError::storage())?;
        if !metadata.is_file() || is_reparse(&metadata) {
            continue;
        }
        let leaf = entry
            .file_name()
            .into_string()
            .map_err(|_| CloudSyncError::invalid_input())?;
        if is_internal_sync_file(&leaf) {
            continue;
        }
        if content == CloudSyncContent::Diaries
            && !leaf
                .rsplit_once('.')
                .is_some_and(|(_, extension)| extension.eq_ignore_ascii_case("md"))
        {
            continue;
        }
        validate_leaf(&leaf)?;
        if result.len() >= remaining_objects {
            return Err(CloudSyncError::limit_exceeded());
        }
        result.push(snapshot_file(
            &root,
            content,
            &leaf,
            limits.max_object_bytes,
        )?);
    }
    Ok(result)
}

fn snapshot_file(
    root: &Path,
    content: CloudSyncContent,
    leaf: &str,
    maximum: u64,
) -> Result<LocalSyncObject, CloudSyncError> {
    let path = resolve_existing_leaf(root, leaf)?;
    let bytes = read_file_bounded(&path, maximum)?;
    Ok(LocalSyncObject {
        key: format!("{}/{}", content.remote_directory(), leaf),
        content,
        size: bytes.len() as u64,
        last_modified_millis: file_modified_millis(&path),
        sha256: sha256_hex(&bytes),
        local_token: format!("{}/{}", content.remote_directory(), leaf),
    })
}

fn root_for_content(
    roots: &LocalRoots,
    content: CloudSyncContent,
) -> Result<&Path, CloudSyncError> {
    match content {
        CloudSyncContent::Diaries => roots.diary.as_deref(),
        CloudSyncContent::Media => roots.media.as_deref(),
        CloudSyncContent::JsonBackup => None,
        CloudSyncContent::UsageStatistics => None,
        CloudSyncContent::ReadingProgress => None,
    }
    .ok_or_else(CloudSyncError::invalid_configuration)
}

fn parse_file_key(key: &str) -> Result<(CloudSyncContent, &str), CloudSyncError> {
    require_valid_sync_key(key)?;
    let (directory, leaf) = key
        .split_once('/')
        .ok_or_else(CloudSyncError::invalid_input)?;
    if leaf.contains('/') {
        return Err(CloudSyncError::invalid_input());
    }
    validate_leaf(leaf)?;
    let content = match directory {
        "diaries" => CloudSyncContent::Diaries,
        "media" => CloudSyncContent::Media,
        _ => return Err(CloudSyncError::invalid_input()),
    };
    Ok((content, leaf))
}

fn validate_directory(path: &Path, create: bool) -> Result<PathBuf, CloudSyncError> {
    if create && !path.exists() {
        fs::create_dir_all(path).map_err(|_| CloudSyncError::storage())?;
    }
    let metadata = fs::symlink_metadata(path).map_err(|_| CloudSyncError::storage())?;
    if !metadata.is_dir() || is_reparse(&metadata) {
        return Err(CloudSyncError::invalid_configuration());
    }
    path.canonicalize().map_err(|_| CloudSyncError::storage())
}

fn resolve_existing_leaf(root: &Path, leaf: &str) -> Result<PathBuf, CloudSyncError> {
    validate_leaf(leaf)?;
    let path = root.join(leaf);
    let metadata = fs::symlink_metadata(&path).map_err(|_| CloudSyncError::storage())?;
    if !metadata.is_file() || is_reparse(&metadata) {
        return Err(CloudSyncError::invalid_input());
    }
    let canonical = path.canonicalize().map_err(|_| CloudSyncError::storage())?;
    if canonical.parent() != Some(root) {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(canonical)
}

fn resolve_new_leaf(root: &Path, leaf: &str) -> Result<PathBuf, CloudSyncError> {
    validate_leaf(leaf)?;
    if !root.is_absolute() {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(root.join(leaf))
}

fn validate_leaf(leaf: &str) -> Result<(), CloudSyncError> {
    if leaf.is_empty()
        || leaf.encode_utf16().count() > 255
        || leaf == "."
        || leaf == ".."
        || leaf.ends_with([' ', '.'])
        || leaf.chars().any(|value| {
            value.is_control()
                || matches!(value, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*')
        })
    {
        return Err(CloudSyncError::invalid_input());
    }
    let stem = leaf
        .split('.')
        .next()
        .unwrap_or_default()
        .trim_end_matches([' ', '.'])
        .to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || stem.strip_prefix("COM").is_some_and(|suffix| {
            matches!(suffix, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9")
        })
        || stem.strip_prefix("LPT").is_some_and(|suffix| {
            matches!(suffix, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9")
        });
    if reserved {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(())
}

fn create_verified_new_file(
    path: &Path,
    bytes: &[u8],
    expected_hash: &str,
) -> Result<(), CloudSyncError> {
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)
        .map_err(|_| CloudSyncError::storage())?;
    if let Err(error) = (|| {
        file.write_all(bytes)?;
        file.sync_all()?;
        Ok::<(), std::io::Error>(())
    })() {
        drop(file);
        let _ = fs::remove_file(path);
        let _ = error;
        return Err(CloudSyncError::storage());
    }
    drop(file);
    let verified = read_file_bounded(path, bytes.len() as u64)?;
    if verified.len() != bytes.len() || sha256_hex(&verified) != expected_hash {
        let _ = fs::remove_file(path);
        return Err(CloudSyncError::storage());
    }
    Ok(())
}

fn read_file_bounded(path: &Path, maximum: u64) -> Result<Vec<u8>, CloudSyncError> {
    let mut file = open_regular_no_reparse(path)?;
    let metadata = file.metadata().map_err(|_| CloudSyncError::storage())?;
    if metadata.len() > maximum {
        return Err(CloudSyncError::limit_exceeded());
    }
    let mut output = Vec::with_capacity(metadata.len().min(usize::MAX as u64) as usize);
    let mut buffer = [0_u8; 16 * 1024];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| CloudSyncError::storage())?;
        if count == 0 {
            break;
        }
        if count as u64 > maximum.saturating_sub(output.len() as u64) {
            return Err(CloudSyncError::limit_exceeded());
        }
        output.extend_from_slice(&buffer[..count]);
    }
    Ok(output)
}

#[cfg(windows)]
fn open_regular_no_reparse(path: &Path) -> Result<File, CloudSyncError> {
    use std::os::windows::fs::OpenOptionsExt;
    const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
        .open(path)
        .map_err(|_| CloudSyncError::storage())?;
    let metadata = file.metadata().map_err(|_| CloudSyncError::storage())?;
    if !metadata.is_file() || is_reparse(&metadata) {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(file)
}

#[cfg(not(windows))]
fn open_regular_no_reparse(path: &Path) -> Result<File, CloudSyncError> {
    let metadata = fs::symlink_metadata(path).map_err(|_| CloudSyncError::storage())?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err(CloudSyncError::invalid_input());
    }
    File::open(path).map_err(|_| CloudSyncError::storage())
}

#[cfg(windows)]
fn is_reparse(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
    metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(not(windows))]
fn is_reparse(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

fn list_pending_json_files(root: &Path) -> Result<Vec<PendingJson>, CloudSyncError> {
    if !root.exists() {
        return Ok(Vec::new());
    }
    let root = validate_directory(root, false)?;
    let mut items = Vec::new();
    for entry in fs::read_dir(root).map_err(|_| CloudSyncError::storage())? {
        let entry = entry.map_err(|_| CloudSyncError::storage())?;
        let metadata = fs::symlink_metadata(entry.path()).map_err(|_| CloudSyncError::storage())?;
        if !metadata.is_file() || is_reparse(&metadata) {
            continue;
        }
        let id = match entry.file_name().into_string() {
            Ok(value) if validate_pending_id(&value).is_ok() => value,
            _ => continue,
        };
        items.push(PendingJson {
            id,
            received_at_millis: modified_millis(&metadata),
            size: metadata.len(),
        });
    }
    items.sort_by_key(|item| std::cmp::Reverse(item.received_at_millis));
    Ok(items)
}

fn validate_pending_id(id: &str) -> Result<(), CloudSyncError> {
    validate_leaf(id)?;
    if !id.starts_with("DeskCubby-incoming-") || !id.ends_with(".json") {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(())
}

fn recover_directory(root: &Path, maximum: u64) -> Result<(), CloudSyncError> {
    let names = fs::read_dir(root)
        .map_err(|_| CloudSyncError::storage())?
        .filter_map(Result::ok)
        .filter_map(|entry| entry.file_name().into_string().ok())
        .filter(|name| name.starts_with('.') && name.ends_with(RECOVERY_SUFFIX))
        .collect::<Vec<_>>();
    for name in names {
        let original = name
            .strip_prefix('.')
            .and_then(|value| value.strip_suffix(RECOVERY_SUFFIX))
            .ok_or_else(CloudSyncError::storage)?;
        if validate_leaf(original).is_err() {
            continue;
        }
        let recovery = root.join(&name);
        let target = root.join(original);
        if target.exists() {
            // The replacement is always a fully written, verified file renamed
            // into place. If both remain after a crash, the target won commit.
            if read_file_bounded(&target, maximum).is_ok() {
                let _ = fs::remove_file(recovery);
            }
        } else {
            fs::rename(recovery, target).map_err(|_| CloudSyncError::storage())?;
        }
    }
    Ok(())
}

fn recover_interrupted_replace(
    root: &Path,
    leaf: &str,
    maximum: u64,
) -> Result<(), CloudSyncError> {
    let recovery = root.join(recovery_name(leaf));
    if !recovery.exists() {
        return Ok(());
    }
    let target = root.join(leaf);
    if target.exists() && read_file_bounded(&target, maximum).is_ok() {
        fs::remove_file(recovery).map_err(|_| CloudSyncError::storage())
    } else if !target.exists() {
        fs::rename(recovery, target).map_err(|_| CloudSyncError::storage())
    } else {
        Err(CloudSyncError::storage())
    }
}

fn recovery_name(leaf: &str) -> String {
    format!(".{leaf}{RECOVERY_SUFFIX}")
}

fn is_internal_sync_file(leaf: &str) -> bool {
    leaf.ends_with(RECOVERY_SUFFIX) || leaf.contains(".sync-pending-") || leaf.ends_with(".pending")
}

fn sibling_name(original: &str, suffix: &str) -> String {
    match original.rsplit_once('.') {
        Some((stem, extension)) if !stem.is_empty() => format!("{stem}{suffix}.{extension}"),
        _ => format!("{original}{suffix}"),
    }
}

fn numbered_sibling(preferred: &str, sequence: usize) -> String {
    match preferred.rsplit_once('.') {
        Some((stem, extension)) if !stem.is_empty() => {
            format!("{stem} ({sequence}).{extension}")
        }
        _ => format!("{preferred} ({sequence})"),
    }
}

fn file_modified_millis(path: &Path) -> i64 {
    fs::metadata(path)
        .ok()
        .map(|metadata| modified_millis(&metadata))
        .unwrap_or(0)
}

fn modified_millis(metadata: &fs::Metadata) -> i64 {
    metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|value| i64::try_from(value.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| i64::try_from(value.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_json_ignores_export_time() {
        let first = br#"{"format":"DeskCubby","version":28,"exportedAt":1,"settings":{}}"#;
        let second = br#"{"format":"DeskCubby","version":28,"exportedAt":999,"settings":{}}"#;
        assert_eq!(
            canonicalize_backup_for_cloud(first).unwrap(),
            canonicalize_backup_for_cloud(second).unwrap()
        );
    }

    #[test]
    fn rejects_traversal_reserved_and_ads_names() {
        for value in [
            "..",
            "../a.md",
            "CON.md",
            "lpt9.txt",
            "safe.md:secret",
            "a.",
        ] {
            assert!(validate_leaf(value).is_err(), "{value}");
        }
        validate_leaf("雪-2026.md").unwrap();
    }

    #[test]
    fn external_change_creates_deterministic_conflict_copy() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("a.md"), b"external").unwrap();
        let result = write_remote_file(
            &directory.path().canonicalize().unwrap(),
            CloudSyncContent::Diaries,
            "a.md",
            b"remote",
            &sha256_hex(b"remote"),
            1,
            Some(&sha256_hex(b"old")),
            1024,
        )
        .unwrap();
        match result {
            LocalWriteResult::ConflictCopy { copy, .. } => {
                assert_eq!(copy.key, "diaries/a.remote-conflict-b71199eb.md");
            }
            _ => panic!("expected conflict"),
        }
        assert_eq!(
            fs::read(directory.path().join("a.md")).unwrap(),
            b"external"
        );
    }

    #[test]
    fn verified_replace_recovers_after_interrupted_move() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().canonicalize().unwrap();
        fs::write(root.join("a.md"), b"old").unwrap();
        fs::rename(root.join("a.md"), root.join(recovery_name("a.md"))).unwrap();
        recover_interrupted_replace(&root, "a.md", 1024).unwrap();
        assert_eq!(fs::read(root.join("a.md")).unwrap(), b"old");
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlink_escape() {
        use std::os::unix::fs::symlink;
        let directory = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        fs::write(outside.path().join("secret.md"), b"secret").unwrap();
        symlink(
            outside.path().join("secret.md"),
            directory.path().join("link.md"),
        )
        .unwrap();
        let root = directory.path().canonicalize().unwrap();
        assert!(resolve_existing_leaf(&root, "link.md").is_err());
    }
}
