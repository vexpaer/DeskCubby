use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Arc,
    time::Duration,
};

use chrono::Utc;
use tokio::sync::Mutex;

use super::{
    encoding::sha256_hex,
    types::{
        BaseState, CloudCredentials, CloudLocalStore, CloudRemoteStore, CloudRemoteStoreFactory,
        CloudSyncConfig, CloudSyncDirection, CloudSyncError, CloudSyncErrorCode,
        CloudSyncItemOutcome, CloudSyncItemReport, CloudSyncLimits, CloudSyncProgress,
        CloudSyncRunResult, CloudSyncStateStore, LocalSyncObject, LocalWriteResult,
        RemoteSyncObject,
    },
    validation::{
        require_valid_sync_key, selected_prefixes, valid_hash, validate_cloud_sync_config,
    },
};

pub struct CloudSyncEngine {
    local: Arc<dyn CloudLocalStore>,
    remote_factory: Arc<dyn CloudRemoteStoreFactory>,
    state: Arc<dyn CloudSyncStateStore>,
    run_mutex: Mutex<()>,
}

impl CloudSyncEngine {
    pub fn new(
        local: Arc<dyn CloudLocalStore>,
        remote_factory: Arc<dyn CloudRemoteStoreFactory>,
        state: Arc<dyn CloudSyncStateStore>,
    ) -> Self {
        Self {
            local,
            remote_factory,
            state,
            run_mutex: Mutex::new(()),
        }
    }

    pub async fn synchronize<F>(
        &self,
        config: &CloudSyncConfig,
        credentials: &CloudCredentials,
        limits: CloudSyncLimits,
        mut on_progress: F,
    ) -> Result<CloudSyncRunResult, CloudSyncError>
    where
        F: FnMut(CloudSyncProgress) + Send,
    {
        let _guard = self.run_mutex.lock().await;
        let limits = limits.validate()?;
        let validated = validate_cloud_sync_config(config, credentials)?;
        let started_at = Utc::now().timestamp_millis().max(0);
        match tokio::time::timeout(
            Duration::from_millis(limits.overall_timeout_millis),
            self.synchronize_inner(
                &validated,
                credentials,
                limits,
                started_at,
                &mut on_progress,
            ),
        )
        .await
        {
            Ok(result) => result,
            Err(_) => Err(CloudSyncError::new(
                CloudSyncErrorCode::TimedOut,
                "Cloud synchronization timed out before all objects were processed.",
                true,
            )),
        }
    }

    async fn synchronize_inner(
        &self,
        config: &super::ValidatedCloudSyncConfig,
        credentials: &CloudCredentials,
        limits: CloudSyncLimits,
        started_at: i64,
        on_progress: &mut (dyn FnMut(CloudSyncProgress) + Send),
    ) -> Result<CloudSyncRunResult, CloudSyncError> {
        let remote = self.remote_factory.create(config, credentials, limits)?;
        let local_objects = self
            .local
            .list(&config.source.selected_contents, limits)
            .await?;
        let prefixes = selected_prefixes(&config.source.selected_contents);
        let remote_objects = remote.list(&prefixes).await?;
        validate_inventory(&local_objects, &remote_objects, &prefixes, limits)?;

        let local_by_key = local_objects
            .into_iter()
            .map(|item| (item.key.clone(), item))
            .collect::<BTreeMap<_, _>>();
        let remote_by_key = remote_objects
            .into_iter()
            .map(|item| (item.key.clone(), item))
            .collect::<BTreeMap<_, _>>();
        let keys = local_by_key
            .keys()
            .chain(remote_by_key.keys())
            .cloned()
            .collect::<BTreeSet<_>>();
        let old_state = self
            .state
            .load(&config.source.id)
            .await?
            .filter(|state| state.scope_fingerprint == config.scope_fingerprint);
        let mut updated_bases = old_state
            .as_ref()
            .map(|state| state.hashes_by_key.clone())
            .unwrap_or_default();
        updated_bases.retain(|key, _| {
            !prefixes.iter().any(|prefix| key.starts_with(prefix)) || keys.contains(key)
        });
        let mut reports = Vec::with_capacity(keys.len());
        let mut budget = TransferBudget::new(limits.max_transferred_bytes);
        on_progress(CloudSyncProgress {
            completed_objects: 0,
            total_objects: keys.len(),
            transferred_bytes: 0,
            current_key: None,
        });

        for (index, key) in keys.iter().enumerate() {
            let local = local_by_key.get(key);
            let remote_object = remote_by_key.get(key);
            let base_hash = old_state
                .as_ref()
                .and_then(|state| state.hashes_by_key.get(key))
                .map(String::as_str);
            let report = self
                .reconcile_one(
                    config.source.direction,
                    local,
                    remote_object,
                    base_hash,
                    remote.as_ref(),
                    limits,
                    &mut budget,
                )
                .await?;
            match report.outcome {
                CloudSyncItemOutcome::Unchanged
                | CloudSyncItemOutcome::Uploaded
                | CloudSyncItemOutcome::Downloaded => {
                    let hash = match report.outcome {
                        CloudSyncItemOutcome::Downloaded => {
                            remote_object.map(|item| item.sha256.clone())
                        }
                        _ => local
                            .map(|item| item.sha256.clone())
                            .or_else(|| remote_object.map(|item| item.sha256.clone())),
                    }
                    .ok_or_else(CloudSyncError::conflict)?;
                    updated_bases.insert(key.clone(), hash);
                }
                CloudSyncItemOutcome::ConflictCopySaved
                | CloudSyncItemOutcome::RemoteChangeSkipped => {}
            }
            reports.push(report);
            on_progress(CloudSyncProgress {
                completed_objects: index + 1,
                total_objects: keys.len(),
                transferred_bytes: budget.used,
                current_key: Some(key.clone()),
            });
        }
        self.state
            .save(
                &config.source.id,
                &BaseState {
                    scope_fingerprint: config.scope_fingerprint.clone(),
                    hashes_by_key: updated_bases,
                },
            )
            .await?;
        Ok(CloudSyncRunResult {
            config_id: config.source.id.clone(),
            started_at_millis: started_at,
            finished_at_millis: Utc::now().timestamp_millis().max(started_at),
            reports,
            transferred_bytes: budget.used,
        })
    }

    #[allow(clippy::too_many_arguments)]
    async fn reconcile_one(
        &self,
        direction: CloudSyncDirection,
        local: Option<&LocalSyncObject>,
        remote: Option<&RemoteSyncObject>,
        base_hash: Option<&str>,
        remote_store: &dyn CloudRemoteStore,
        limits: CloudSyncLimits,
        budget: &mut TransferBudget,
    ) -> Result<CloudSyncItemReport, CloudSyncError> {
        let key = local
            .map(|item| item.key.as_str())
            .or_else(|| remote.map(|item| item.key.as_str()))
            .ok_or_else(CloudSyncError::invalid_input)?;
        match (local, remote) {
            (None, Some(remote)) => {
                if direction == CloudSyncDirection::UploadOnly {
                    return Ok(report(key, CloudSyncItemOutcome::RemoteChangeSkipped));
                }
                let bytes = read_remote(remote_store, remote, limits, budget).await?;
                let result = self
                    .local
                    .write_remote(
                        key,
                        &bytes,
                        &remote.sha256,
                        remote.last_modified_millis,
                        None,
                        limits,
                    )
                    .await?;
                Ok(report(key, write_outcome(result)))
            }
            (Some(local), None) => {
                let bytes = read_local(self.local.as_ref(), local, limits, budget).await?;
                remote_store
                    .write(key, &bytes, &local.sha256, local.last_modified_millis, None)
                    .await?;
                Ok(report(key, CloudSyncItemOutcome::Uploaded))
            }
            (Some(local), Some(remote)) if local.sha256 == remote.sha256 => {
                Ok(report(key, CloudSyncItemOutcome::Unchanged))
            }
            (Some(local), Some(remote)) => {
                let local_changed = base_hash.is_none_or(|base| local.sha256 != base);
                let remote_changed = base_hash.is_none_or(|base| remote.sha256 != base);
                if local_changed && !remote_changed {
                    let bytes = read_local(self.local.as_ref(), local, limits, budget).await?;
                    remote_store
                        .write(
                            key,
                            &bytes,
                            &local.sha256,
                            local.last_modified_millis,
                            Some(&remote.version),
                        )
                        .await?;
                    return Ok(report(key, CloudSyncItemOutcome::Uploaded));
                }
                if !local_changed && remote_changed {
                    if direction == CloudSyncDirection::UploadOnly {
                        return Ok(report(key, CloudSyncItemOutcome::RemoteChangeSkipped));
                    }
                    let bytes = read_remote(remote_store, remote, limits, budget).await?;
                    let result = self
                        .local
                        .write_remote(
                            key,
                            &bytes,
                            &remote.sha256,
                            remote.last_modified_millis,
                            Some(&local.sha256),
                            limits,
                        )
                        .await?;
                    return Ok(report(key, write_outcome(result)));
                }
                if direction == CloudSyncDirection::UploadOnly {
                    return Ok(report(key, CloudSyncItemOutcome::RemoteChangeSkipped));
                }
                let bytes = read_remote(remote_store, remote, limits, budget).await?;
                let result = self
                    .local
                    .write_remote(
                        key,
                        &bytes,
                        &remote.sha256,
                        remote.last_modified_millis,
                        // Null means must-not-exist. Because the local object is
                        // present, this deliberately creates a deterministic
                        // remote-conflict sibling instead of overwriting it.
                        None,
                        limits,
                    )
                    .await?;
                Ok(report(key, write_outcome(result)))
            }
            (None, None) => Err(CloudSyncError::invalid_input()),
        }
    }
}

fn write_outcome(result: LocalWriteResult) -> CloudSyncItemOutcome {
    match result {
        LocalWriteResult::Applied(_) => CloudSyncItemOutcome::Downloaded,
        LocalWriteResult::ConflictCopy { .. } => CloudSyncItemOutcome::ConflictCopySaved,
    }
}

fn report(key: &str, outcome: CloudSyncItemOutcome) -> CloudSyncItemReport {
    CloudSyncItemReport {
        key: key.to_owned(),
        outcome,
    }
}

async fn read_local(
    store: &dyn CloudLocalStore,
    object: &LocalSyncObject,
    limits: CloudSyncLimits,
    budget: &mut TransferBudget,
) -> Result<Vec<u8>, CloudSyncError> {
    require_object_size(object.size, limits)?;
    budget.reserve(object.size)?;
    let bytes = store.read(object, limits.max_object_bytes).await?;
    if bytes.len() as u64 != object.size || sha256_hex(&bytes) != object.sha256 {
        return Err(CloudSyncError::conflict());
    }
    Ok(bytes)
}

async fn read_remote(
    store: &dyn CloudRemoteStore,
    object: &RemoteSyncObject,
    limits: CloudSyncLimits,
    budget: &mut TransferBudget,
) -> Result<Vec<u8>, CloudSyncError> {
    require_object_size(object.size, limits)?;
    budget.reserve(object.size)?;
    let bytes = store.read(object, limits.max_object_bytes).await?;
    if bytes.len() as u64 != object.size || sha256_hex(&bytes) != object.sha256 {
        return Err(CloudSyncError::conflict());
    }
    Ok(bytes)
}

fn validate_inventory(
    local: &[LocalSyncObject],
    remote: &[RemoteSyncObject],
    prefixes: &BTreeSet<String>,
    limits: CloudSyncLimits,
) -> Result<(), CloudSyncError> {
    if local.len() > limits.max_objects
        || remote.len() > limits.max_objects
        || local
            .iter()
            .map(|item| &item.key)
            .collect::<BTreeSet<_>>()
            .len()
            != local.len()
        || remote
            .iter()
            .map(|item| &item.key)
            .collect::<BTreeSet<_>>()
            .len()
            != remote.len()
    {
        return Err(CloudSyncError::limit_exceeded());
    }
    for (key, size, hash) in local
        .iter()
        .map(|item| (&item.key, item.size, &item.sha256))
        .chain(
            remote
                .iter()
                .map(|item| (&item.key, item.size, &item.sha256)),
        )
    {
        require_valid_sync_key(key)?;
        if !prefixes.iter().any(|prefix| key.starts_with(prefix)) || !valid_hash(hash) {
            return Err(CloudSyncError::invalid_input());
        }
        require_object_size(size, limits)?;
    }
    Ok(())
}

fn require_object_size(size: u64, limits: CloudSyncLimits) -> Result<(), CloudSyncError> {
    if size > limits.max_object_bytes {
        Err(CloudSyncError::limit_exceeded())
    } else {
        Ok(())
    }
}

struct TransferBudget {
    maximum: u64,
    used: u64,
}

impl TransferBudget {
    const fn new(maximum: u64) -> Self {
        Self { maximum, used: 0 }
    }

    fn reserve(&mut self, amount: u64) -> Result<(), CloudSyncError> {
        if amount > self.maximum.saturating_sub(self.used) {
            return Err(CloudSyncError::limit_exceeded());
        }
        self.used += amount;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex as StdMutex;

    use super::*;
    use crate::cloud_sync::{BoxFuture, CloudSyncContent, RemoteVersion, ValidatedCloudSyncConfig};

    const KEY: &str = "diaries/2026-07-29.md";

    #[tokio::test]
    async fn local_only_uploads_and_remote_only_downloads_without_deletion() {
        let local = Arc::new(FakeLocal::default());
        local.put(KEY, b"local");
        let remote = Arc::new(FakeRemote::default());
        let state = Arc::new(FakeState::default());
        let engine = engine(local.clone(), remote.clone(), state.clone());
        let first = engine
            .synchronize(&config(), &CloudCredentials::default(), limits(), |_| {})
            .await
            .unwrap();
        assert_eq!(first.reports[0].outcome, CloudSyncItemOutcome::Uploaded);
        local.remove(KEY);
        let second = engine
            .synchronize(&config(), &CloudCredentials::default(), limits(), |_| {})
            .await
            .unwrap();
        assert_eq!(second.reports[0].outcome, CloudSyncItemOutcome::Downloaded);
        assert_eq!(local.bytes(KEY), b"local");
    }

    #[tokio::test]
    async fn both_sides_changed_preserves_local_and_records_conflict() {
        let local = Arc::new(FakeLocal::default());
        local.put(KEY, b"base");
        let remote = Arc::new(FakeRemote::default());
        remote.put(KEY, b"base");
        let state = Arc::new(FakeState::default());
        let engine = engine(local.clone(), remote.clone(), state);
        engine
            .synchronize(&config(), &CloudCredentials::default(), limits(), |_| {})
            .await
            .unwrap();
        local.put(KEY, b"local");
        remote.put(KEY, b"remote");
        let result = engine
            .synchronize(&config(), &CloudCredentials::default(), limits(), |_| {})
            .await
            .unwrap();
        assert_eq!(
            result.reports[0].outcome,
            CloudSyncItemOutcome::ConflictCopySaved
        );
        assert_eq!(local.bytes(KEY), b"local");
        assert_eq!(
            local.conflicts.lock().unwrap().as_slice(),
            &[b"remote".to_vec()]
        );
    }

    #[tokio::test]
    async fn upload_only_skips_remote_change_without_reading_it() {
        let local = Arc::new(FakeLocal::default());
        local.put(KEY, b"base");
        let remote = Arc::new(FakeRemote::default());
        remote.put(KEY, b"base");
        let state = Arc::new(FakeState::default());
        let engine = engine(local.clone(), remote.clone(), state);
        let mut value = config();
        value.direction = CloudSyncDirection::UploadOnly;
        engine
            .synchronize(&value, &CloudCredentials::default(), limits(), |_| {})
            .await
            .unwrap();
        remote.put(KEY, b"remote");
        let result = engine
            .synchronize(&value, &CloudCredentials::default(), limits(), |_| {})
            .await
            .unwrap();
        assert_eq!(
            result.reports[0].outcome,
            CloudSyncItemOutcome::RemoteChangeSkipped
        );
        assert_eq!(
            remote.read_count.load(std::sync::atomic::Ordering::SeqCst),
            0
        );
    }

    fn engine(
        local: Arc<FakeLocal>,
        remote: Arc<FakeRemote>,
        state: Arc<FakeState>,
    ) -> CloudSyncEngine {
        CloudSyncEngine::new(local, Arc::new(FakeFactory(remote)), state)
    }

    fn config() -> CloudSyncConfig {
        CloudSyncConfig {
            id: "test".to_owned(),
            name: "Test".to_owned(),
            endpoint_url: "https://example.test/dav".to_owned(),
            selected_contents: [CloudSyncContent::Diaries].into_iter().collect(),
            ..CloudSyncConfig::default()
        }
    }

    fn limits() -> CloudSyncLimits {
        CloudSyncLimits {
            max_object_bytes: 1_024,
            max_transferred_bytes: 10_240,
            max_objects: 100,
            ..CloudSyncLimits::default()
        }
    }

    #[derive(Default)]
    struct FakeLocal {
        entries: StdMutex<BTreeMap<String, Vec<u8>>>,
        conflicts: StdMutex<Vec<Vec<u8>>>,
    }

    impl FakeLocal {
        fn put(&self, key: &str, bytes: &[u8]) {
            self.entries
                .lock()
                .unwrap()
                .insert(key.to_owned(), bytes.to_vec());
        }

        fn remove(&self, key: &str) {
            self.entries.lock().unwrap().remove(key);
        }

        fn bytes(&self, key: &str) -> Vec<u8> {
            self.entries.lock().unwrap()[key].clone()
        }
    }

    impl CloudLocalStore for FakeLocal {
        fn list<'a>(
            &'a self,
            _: &'a BTreeSet<CloudSyncContent>,
            _: CloudSyncLimits,
        ) -> BoxFuture<'a, Result<Vec<LocalSyncObject>, CloudSyncError>> {
            Box::pin(async move {
                Ok(self
                    .entries
                    .lock()
                    .unwrap()
                    .iter()
                    .map(|(key, bytes)| LocalSyncObject {
                        key: key.clone(),
                        content: CloudSyncContent::Diaries,
                        size: bytes.len() as u64,
                        last_modified_millis: 1,
                        sha256: sha256_hex(bytes),
                        local_token: key.clone(),
                    })
                    .collect())
            })
        }

        fn read<'a>(
            &'a self,
            object: &'a LocalSyncObject,
            _: u64,
        ) -> BoxFuture<'a, Result<Vec<u8>, CloudSyncError>> {
            Box::pin(async move { Ok(self.bytes(&object.key)) })
        }

        fn write_remote<'a>(
            &'a self,
            key: &'a str,
            bytes: &'a [u8],
            _: &'a str,
            _: i64,
            expected: Option<&'a str>,
            _: CloudSyncLimits,
        ) -> BoxFuture<'a, Result<LocalWriteResult, CloudSyncError>> {
            Box::pin(async move {
                let current = self.entries.lock().unwrap().get(key).cloned();
                let current_hash = current.as_deref().map(sha256_hex);
                if current_hash.as_deref() == expected {
                    self.put(key, bytes);
                    let item = self.list(&BTreeSet::new(), limits()).await?.remove(0);
                    Ok(LocalWriteResult::Applied(item))
                } else {
                    self.conflicts.lock().unwrap().push(bytes.to_vec());
                    Ok(LocalWriteResult::ConflictCopy {
                        existing: None,
                        copy: LocalSyncObject {
                            key: format!("{key}.remote-conflict"),
                            content: CloudSyncContent::Diaries,
                            size: bytes.len() as u64,
                            last_modified_millis: 1,
                            sha256: sha256_hex(bytes),
                            local_token: "conflict".to_owned(),
                        },
                    })
                }
            })
        }
    }

    #[derive(Default)]
    struct FakeRemote {
        entries: StdMutex<BTreeMap<String, (Vec<u8>, String)>>,
        version: std::sync::atomic::AtomicU64,
        read_count: std::sync::atomic::AtomicU64,
    }

    impl FakeRemote {
        fn put(&self, key: &str, bytes: &[u8]) {
            let version = self
                .version
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst)
                + 1;
            self.entries
                .lock()
                .unwrap()
                .insert(key.to_owned(), (bytes.to_vec(), format!("v{version}")));
        }
    }

    impl CloudRemoteStore for FakeRemote {
        fn list<'a>(
            &'a self,
            _: &'a BTreeSet<String>,
        ) -> BoxFuture<'a, Result<Vec<RemoteSyncObject>, CloudSyncError>> {
            Box::pin(async move {
                Ok(self
                    .entries
                    .lock()
                    .unwrap()
                    .iter()
                    .map(|(key, (bytes, version))| RemoteSyncObject {
                        key: key.clone(),
                        size: bytes.len() as u64,
                        last_modified_millis: 1,
                        sha256: sha256_hex(bytes),
                        version: RemoteVersion {
                            content_sha256: sha256_hex(bytes),
                            blob_etag: version.clone(),
                            storage_name: "fake".to_owned(),
                        },
                    })
                    .collect())
            })
        }

        fn read<'a>(
            &'a self,
            object: &'a RemoteSyncObject,
            _: u64,
        ) -> BoxFuture<'a, Result<Vec<u8>, CloudSyncError>> {
            Box::pin(async move {
                self.read_count
                    .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Ok(self.entries.lock().unwrap()[&object.key].0.clone())
            })
        }

        fn write<'a>(
            &'a self,
            key: &'a str,
            bytes: &'a [u8],
            _: &'a str,
            _: i64,
            expected: Option<&'a RemoteVersion>,
        ) -> BoxFuture<'a, Result<RemoteSyncObject, CloudSyncError>> {
            Box::pin(async move {
                let current_version = self
                    .entries
                    .lock()
                    .unwrap()
                    .get(key)
                    .map(|value| value.1.clone());
                if current_version.as_deref() != expected.map(|value| value.blob_etag.as_str()) {
                    return Err(CloudSyncError::conflict());
                }
                self.put(key, bytes);
                Ok(self.list(&BTreeSet::new()).await?.remove(0))
            })
        }
    }

    struct FakeFactory(Arc<FakeRemote>);

    impl CloudRemoteStoreFactory for FakeFactory {
        fn create(
            &self,
            _: &ValidatedCloudSyncConfig,
            _: &CloudCredentials,
            _: CloudSyncLimits,
        ) -> Result<Arc<dyn CloudRemoteStore>, CloudSyncError> {
            Ok(self.0.clone())
        }
    }

    #[derive(Default)]
    struct FakeState(StdMutex<BTreeMap<String, BaseState>>);

    impl CloudSyncStateStore for FakeState {
        fn load<'a>(
            &'a self,
            id: &'a str,
        ) -> BoxFuture<'a, Result<Option<BaseState>, CloudSyncError>> {
            Box::pin(async move { Ok(self.0.lock().unwrap().get(id).cloned()) })
        }

        fn save<'a>(
            &'a self,
            id: &'a str,
            state: &'a BaseState,
        ) -> BoxFuture<'a, Result<(), CloudSyncError>> {
            Box::pin(async move {
                self.0.lock().unwrap().insert(id.to_owned(), state.clone());
                Ok(())
            })
        }
    }
}
