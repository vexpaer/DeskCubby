use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Arc, Mutex},
};

use super::{
    encoding::{decode_url_base64_no_pad, sha256_hex, url_base64_no_pad},
    types::{
        BlobWriteCondition, BoxFuture, CloudRemoteStore, CloudSyncError, CloudSyncErrorCode,
        CloudSyncLimits, ConditionalBlobTransport, RemoteSyncObject, RemoteVersion,
    },
    validation::{require_valid_sync_key, valid_hash, valid_storage_name},
};

const MANIFEST_STORAGE_NAME: &str = ".deskcubby-sync-v1.manifest";
const MANIFEST_HEADER: &str = "DeskCubby-Sync\t1";
const MAX_MANIFEST_BYTES: u64 = 4 * 1024 * 1024;
const MAX_ETAG_CHARS: usize = 4_096;

#[derive(Debug, Clone, PartialEq, Eq)]
struct ManifestEntry {
    key: String,
    sha256: String,
    size: u64,
    last_modified_millis: i64,
    storage_name: String,
    blob_etag: String,
}

impl ManifestEntry {
    fn remote_object(&self) -> RemoteSyncObject {
        RemoteSyncObject {
            key: self.key.clone(),
            size: self.size,
            last_modified_millis: self.last_modified_millis,
            sha256: self.sha256.clone(),
            version: RemoteVersion {
                content_sha256: self.sha256.clone(),
                blob_etag: self.blob_etag.clone(),
                storage_name: self.storage_name.clone(),
            },
        }
    }
}

#[derive(Clone)]
struct LoadedManifest {
    etag: Option<String>,
    entries: BTreeMap<String, ManifestEntry>,
}

pub struct ManifestRemoteStore {
    transport: Arc<dyn ConditionalBlobTransport>,
    limits: CloudSyncLimits,
    loaded: Mutex<Option<LoadedManifest>>,
}

impl ManifestRemoteStore {
    pub fn new(transport: Arc<dyn ConditionalBlobTransport>, limits: CloudSyncLimits) -> Self {
        Self {
            transport,
            limits,
            loaded: Mutex::new(None),
        }
    }

    async fn load_manifest(&self) -> Result<LoadedManifest, CloudSyncError> {
        let Some(blob) = self
            .transport
            .get(MANIFEST_STORAGE_NAME, MAX_MANIFEST_BYTES, None)
            .await?
        else {
            return Ok(LoadedManifest {
                etag: None,
                entries: BTreeMap::new(),
            });
        };
        if blob.metadata.etag.is_empty() {
            return Err(CloudSyncError::new(
                CloudSyncErrorCode::UnsupportedRemote,
                "The cloud service did not provide a strong manifest ETag.",
                false,
            ));
        }
        Ok(LoadedManifest {
            etag: Some(blob.metadata.etag),
            entries: decode_manifest(&blob.bytes, self.limits.max_objects)?,
        })
    }

    async fn ensure_manifest_current(
        &self,
        expected: &LoadedManifest,
    ) -> Result<(), CloudSyncError> {
        let expected_etag = expected
            .etag
            .as_deref()
            .ok_or_else(CloudSyncError::conflict)?;
        let blob = self
            .transport
            .get(
                MANIFEST_STORAGE_NAME,
                MAX_MANIFEST_BYTES,
                Some(expected_etag),
            )
            .await?
            .ok_or_else(CloudSyncError::conflict)?;
        let current = decode_manifest(&blob.bytes, self.limits.max_objects)?;
        if current != expected.entries {
            return Err(CloudSyncError::conflict());
        }
        Ok(())
    }
}

impl CloudRemoteStore for ManifestRemoteStore {
    fn list<'a>(
        &'a self,
        prefixes: &'a BTreeSet<String>,
    ) -> BoxFuture<'a, Result<Vec<RemoteSyncObject>, CloudSyncError>> {
        Box::pin(async move {
            let normalized = prefixes
                .iter()
                .map(|prefix| {
                    let without_slash = prefix.strip_suffix('/').unwrap_or(prefix);
                    require_valid_sync_key(without_slash)?;
                    Ok(format!("{without_slash}/"))
                })
                .collect::<Result<BTreeSet<_>, CloudSyncError>>()?;
            let loaded = self.load_manifest().await?;
            let result = loaded
                .entries
                .values()
                .filter(|entry| {
                    normalized
                        .iter()
                        .any(|prefix| entry.key.starts_with(prefix))
                })
                .map(ManifestEntry::remote_object)
                .collect();
            *self.loaded.lock().map_err(|_| CloudSyncError::storage())? = Some(loaded);
            Ok(result)
        })
    }

    fn read<'a>(
        &'a self,
        object: &'a RemoteSyncObject,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<Vec<u8>, CloudSyncError>> {
        Box::pin(async move {
            if object.size > max_bytes
                || object.version.content_sha256 != object.sha256
                || !valid_storage_name(&object.version.storage_name)
            {
                return Err(CloudSyncError::invalid_input());
            }
            let blob = self
                .transport
                .get(
                    &object.version.storage_name,
                    max_bytes,
                    Some(&object.version.blob_etag),
                )
                .await?
                .ok_or_else(CloudSyncError::conflict)?;
            if blob.bytes.len() as u64 != object.size || sha256_hex(&blob.bytes) != object.sha256 {
                return Err(CloudSyncError::conflict());
            }
            Ok(blob.bytes)
        })
    }

    fn write<'a>(
        &'a self,
        key: &'a str,
        bytes: &'a [u8],
        content_sha256: &'a str,
        last_modified_millis: i64,
        expected_remote_version: Option<&'a RemoteVersion>,
    ) -> BoxFuture<'a, Result<RemoteSyncObject, CloudSyncError>> {
        Box::pin(async move {
            let key = require_valid_sync_key(key)?;
            if bytes.len() as u64 > self.limits.max_object_bytes
                || !valid_hash(content_sha256)
                || sha256_hex(bytes) != content_sha256
            {
                return Err(CloudSyncError::conflict());
            }
            let mut loaded = self
                .loaded
                .lock()
                .map_err(|_| CloudSyncError::storage())?
                .clone();
            if loaded.is_none() {
                loaded = Some(self.load_manifest().await?);
            }
            let loaded = loaded.ok_or_else(CloudSyncError::storage)?;
            let existing = loaded.entries.get(key);
            if existing.map(|entry| RemoteVersion {
                content_sha256: entry.sha256.clone(),
                blob_etag: entry.blob_etag.clone(),
                storage_name: entry.storage_name.clone(),
            }) != expected_remote_version.cloned()
            {
                return Err(CloudSyncError::conflict());
            }
            if let Some(existing) = existing
                && existing.sha256 == content_sha256
            {
                self.ensure_manifest_current(&loaded).await?;
                return Ok(existing.remote_object());
            }
            if existing.is_none() && loaded.entries.len() >= self.limits.max_objects {
                return Err(CloudSyncError::limit_exceeded());
            }

            let storage_name = object_storage_name(key, content_sha256);
            let blob_metadata = match self
                .transport
                .put(
                    &storage_name,
                    bytes,
                    content_sha256,
                    BlobWriteCondition::MustNotExist,
                )
                .await
            {
                Ok(metadata) => metadata,
                Err(error) if error.code == CloudSyncErrorCode::Conflict => {
                    let prior = self
                        .transport
                        .get(&storage_name, self.limits.max_object_bytes, None)
                        .await?
                        .ok_or(error)?;
                    if prior.bytes != bytes || sha256_hex(&prior.bytes) != content_sha256 {
                        return Err(CloudSyncError::conflict());
                    }
                    prior.metadata
                }
                Err(error) => return Err(error),
            };
            let replacement = ManifestEntry {
                key: key.to_owned(),
                sha256: content_sha256.to_owned(),
                size: bytes.len() as u64,
                last_modified_millis: last_modified_millis.max(0),
                storage_name,
                blob_etag: blob_metadata.etag,
            };
            let mut updated_entries = loaded.entries.clone();
            updated_entries.insert(key.to_owned(), replacement.clone());
            let manifest_bytes = encode_manifest(updated_entries.values())?;
            if manifest_bytes.len() as u64 > MAX_MANIFEST_BYTES {
                return Err(CloudSyncError::limit_exceeded());
            }
            let condition = loaded
                .etag
                .clone()
                .map(BlobWriteCondition::MustMatch)
                .unwrap_or(BlobWriteCondition::MustNotExist);
            let manifest_hash = sha256_hex(&manifest_bytes);
            let manifest_metadata = self
                .transport
                .put(
                    MANIFEST_STORAGE_NAME,
                    &manifest_bytes,
                    &manifest_hash,
                    condition,
                )
                .await?;
            *self.loaded.lock().map_err(|_| CloudSyncError::storage())? = Some(LoadedManifest {
                etag: Some(manifest_metadata.etag),
                entries: updated_entries,
            });
            Ok(replacement.remote_object())
        })
    }
}

fn object_storage_name(key: &str, content_sha256: &str) -> String {
    format!(
        ".deskcubby-object-{}-{content_sha256}",
        &sha256_hex(key.as_bytes())[..32]
    )
}

fn encode_manifest<'a>(
    entries: impl IntoIterator<Item = &'a ManifestEntry>,
) -> Result<Vec<u8>, CloudSyncError> {
    let mut sorted = entries.into_iter().collect::<Vec<_>>();
    sorted.sort_by(|left, right| left.key.cmp(&right.key));
    let mut output = format!("{MANIFEST_HEADER}\n");
    for entry in sorted {
        validate_manifest_entry(entry)?;
        output.push_str(&url_base64_no_pad(entry.key.as_bytes()));
        output.push('\t');
        output.push_str(&entry.sha256);
        output.push('\t');
        output.push_str(&entry.size.to_string());
        output.push('\t');
        output.push_str(&entry.last_modified_millis.to_string());
        output.push('\t');
        output.push_str(&entry.storage_name);
        output.push('\t');
        output.push_str(&url_base64_no_pad(entry.blob_etag.as_bytes()));
        output.push('\n');
    }
    Ok(output.into_bytes())
}

fn decode_manifest(
    bytes: &[u8],
    max_objects: usize,
) -> Result<BTreeMap<String, ManifestEntry>, CloudSyncError> {
    let text = std::str::from_utf8(bytes).map_err(|_| CloudSyncError::invalid_input())?;
    let mut lines = text.split('\n');
    if lines.next() != Some(MANIFEST_HEADER) {
        return Err(CloudSyncError::invalid_input());
    }
    let entry_lines = lines.filter(|line| !line.is_empty()).collect::<Vec<_>>();
    if entry_lines.len() > max_objects {
        return Err(CloudSyncError::limit_exceeded());
    }
    let mut entries = BTreeMap::new();
    for line in entry_lines {
        let fields = line.split('\t').collect::<Vec<_>>();
        if fields.len() != 6 {
            return Err(CloudSyncError::invalid_input());
        }
        let key = decode_utf8_field(fields[0])?;
        let blob_etag = decode_utf8_field(fields[5])?;
        let entry = ManifestEntry {
            key: key.clone(),
            sha256: fields[1].to_owned(),
            size: fields[2]
                .parse()
                .map_err(|_| CloudSyncError::invalid_input())?,
            last_modified_millis: fields[3]
                .parse()
                .map_err(|_| CloudSyncError::invalid_input())?,
            storage_name: fields[4].to_owned(),
            blob_etag,
        };
        validate_manifest_entry(&entry)?;
        if entries.insert(key, entry).is_some() {
            return Err(CloudSyncError::invalid_input());
        }
    }
    Ok(entries)
}

fn decode_utf8_field(value: &str) -> Result<String, CloudSyncError> {
    String::from_utf8(decode_url_base64_no_pad(value)?).map_err(|_| CloudSyncError::invalid_input())
}

fn validate_manifest_entry(entry: &ManifestEntry) -> Result<(), CloudSyncError> {
    require_valid_sync_key(&entry.key)?;
    if !valid_hash(&entry.sha256)
        || entry.last_modified_millis < 0
        || !valid_storage_name(&entry.storage_name)
        || entry.blob_etag.is_empty()
        || entry.blob_etag.len() > MAX_ETAG_CHARS
        || entry
            .blob_etag
            .chars()
            .any(|value| value == '\r' || value == '\n')
    {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU64, Ordering};

    use super::*;
    use crate::cloud_sync::{BlobMetadata, BlobRead};

    #[test]
    fn manifest_codec_matches_android_wire_shape() {
        let entry = ManifestEntry {
            key: "diaries/2026-07-29.md".to_owned(),
            sha256: sha256_hex(b"body"),
            size: 4,
            last_modified_millis: 123,
            storage_name: ".deskcubby-object-name".to_owned(),
            blob_etag: "\"etag-1\"".to_owned(),
        };
        let bytes = encode_manifest([&entry]).unwrap();
        let text = String::from_utf8(bytes.clone()).unwrap();
        assert!(text.starts_with("DeskCubby-Sync\t1\n"));
        assert!(text.ends_with('\n'));
        assert_eq!(decode_manifest(&bytes, 10).unwrap()[&entry.key], entry);
    }

    #[tokio::test]
    async fn publishes_immutable_payload_then_conditional_manifest() {
        let transport = Arc::new(MemoryTransport::default());
        let store = ManifestRemoteStore::new(transport.clone(), test_limits());
        let bytes = b"first";
        let written = store
            .write("diaries/a.md", bytes, &sha256_hex(bytes), 7, None)
            .await
            .unwrap();
        assert_eq!(
            store.list(&prefixes()).await.unwrap(),
            vec![written.clone()]
        );
        assert_eq!(store.read(&written, 1024).await.unwrap(), bytes);
        let puts = transport.puts.lock().unwrap();
        assert_eq!(puts.len(), 2);
        assert_eq!(puts[0].1, BlobWriteCondition::MustNotExist);
        assert_eq!(puts[1].0, MANIFEST_STORAGE_NAME);
        assert_eq!(puts[1].1, BlobWriteCondition::MustNotExist);
    }

    #[tokio::test]
    async fn manifest_race_leaves_orphan_without_publishing_loser() {
        let transport = Arc::new(MemoryTransport::default());
        let seed = ManifestRemoteStore::new(transport.clone(), test_limits());
        let base_bytes = b"base";
        seed.write("diaries/a.md", base_bytes, &sha256_hex(base_bytes), 1, None)
            .await
            .unwrap();
        let winner = ManifestRemoteStore::new(transport.clone(), test_limits());
        let loser = ManifestRemoteStore::new(transport.clone(), test_limits());
        let winner_base = winner.list(&prefixes()).await.unwrap().remove(0);
        let loser_base = loser.list(&prefixes()).await.unwrap().remove(0);
        winner
            .write(
                "diaries/a.md",
                b"winner",
                &sha256_hex(b"winner"),
                2,
                Some(&winner_base.version),
            )
            .await
            .unwrap();
        assert_eq!(
            loser
                .write(
                    "diaries/a.md",
                    b"loser",
                    &sha256_hex(b"loser"),
                    3,
                    Some(&loser_base.version),
                )
                .await
                .unwrap_err()
                .code,
            CloudSyncErrorCode::Conflict
        );
        let fresh = ManifestRemoteStore::new(transport, test_limits());
        let published = fresh.list(&prefixes()).await.unwrap().remove(0);
        assert_eq!(fresh.read(&published, 1024).await.unwrap(), b"winner");
    }

    fn prefixes() -> BTreeSet<String> {
        ["diaries/".to_owned()].into_iter().collect()
    }

    fn test_limits() -> CloudSyncLimits {
        CloudSyncLimits {
            max_object_bytes: 1_024,
            max_transferred_bytes: 10_240,
            max_objects: 10,
            ..CloudSyncLimits::default()
        }
    }

    #[derive(Default)]
    struct MemoryTransport {
        blobs: Mutex<BTreeMap<String, (BlobMetadata, Vec<u8>)>>,
        puts: Mutex<Vec<(String, BlobWriteCondition)>>,
        version: AtomicU64,
    }

    impl ConditionalBlobTransport for MemoryTransport {
        fn get<'a>(
            &'a self,
            storage_name: &'a str,
            max_bytes: u64,
            expected_etag: Option<&'a str>,
        ) -> BoxFuture<'a, Result<Option<BlobRead>, CloudSyncError>> {
            Box::pin(async move {
                let blobs = self.blobs.lock().unwrap();
                let Some((metadata, bytes)) = blobs.get(storage_name) else {
                    return Ok(None);
                };
                if bytes.len() as u64 > max_bytes
                    || expected_etag.is_some_and(|value| value != metadata.etag)
                {
                    return Err(CloudSyncError::conflict());
                }
                Ok(Some(BlobRead {
                    metadata: metadata.clone(),
                    bytes: bytes.clone(),
                }))
            })
        }

        fn put<'a>(
            &'a self,
            storage_name: &'a str,
            bytes: &'a [u8],
            content_sha256: &'a str,
            condition: BlobWriteCondition,
        ) -> BoxFuture<'a, Result<BlobMetadata, CloudSyncError>> {
            Box::pin(async move {
                if sha256_hex(bytes) != content_sha256 {
                    return Err(CloudSyncError::conflict());
                }
                self.puts
                    .lock()
                    .unwrap()
                    .push((storage_name.to_owned(), condition.clone()));
                let mut blobs = self.blobs.lock().unwrap();
                let current = blobs.get(storage_name);
                match &condition {
                    BlobWriteCondition::MustNotExist if current.is_some() => {
                        return Err(CloudSyncError::conflict());
                    }
                    BlobWriteCondition::MustMatch(expected)
                        if current.map(|value| &value.0.etag) != Some(expected) =>
                    {
                        return Err(CloudSyncError::conflict());
                    }
                    _ => {}
                }
                let next = self.version.fetch_add(1, Ordering::SeqCst) + 1;
                let metadata = BlobMetadata {
                    etag: format!("\"memory-{next}\""),
                    size: bytes.len() as u64,
                    last_modified_millis: next as i64,
                };
                blobs.insert(storage_name.to_owned(), (metadata.clone(), bytes.to_vec()));
                Ok(metadata)
            })
        }
    }
}
