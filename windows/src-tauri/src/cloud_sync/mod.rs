//! Android-compatible DeskCubby cloud synchronization core.
//!
//! This module intentionally has no dependency on the application database or
//! Tauri commands. Integrators provide the small traits exported below. That
//! keeps credentials, local paths and backup JSON behind the Rust command
//! boundary while making the reconciliation and remote protocol independently
//! testable.

// This module exposes an integration surface for the Tauri command and
// persistence adapters. Not every adapter consumes every protocol primitive.
#![allow(unused_imports)]

pub(crate) mod commands;
mod encoding;
mod engine;
mod local;
mod manifest;
mod secrets;
mod sigv4;
mod transport;
mod types;
mod validation;

pub use engine::CloudSyncEngine;
pub use local::{
    FileSystemLocalStore, JsonBackupBridge, JsonSnapshot, LocalRoots, PendingJson,
    ReaderProgressBridge, ReaderProgressSnapshot, UsageStatisticsBridge, UsageStatisticsSnapshot,
    canonicalize_backup_for_cloud,
};
pub use manifest::ManifestRemoteStore;
pub use secrets::{
    EncryptedCloudCredentials, decrypt_credentials, encrypt_credentials, secret_binding_sha256,
};
pub use sigv4::{SigV4Signer, SignedHeaders};
pub use transport::{ReqwestBlobTransport, ReqwestRemoteStoreFactory};
pub use types::{
    BaseState, BlobMetadata, BlobRead, BlobWriteCondition, BoxFuture, CloudCredentials,
    CloudLocalStore, CloudRemoteStore, CloudRemoteStoreFactory, CloudSyncConfig, CloudSyncContent,
    CloudSyncDirection, CloudSyncError, CloudSyncErrorCode, CloudSyncItemOutcome,
    CloudSyncItemReport, CloudSyncLimits, CloudSyncProgress, CloudSyncRunMode, CloudSyncRunResult,
    CloudSyncServiceType, CloudSyncStateStore, ConditionalBlobTransport, LocalSyncObject,
    LocalWriteResult, RemoteSyncObject, RemoteVersion,
};
pub use validation::{
    ValidatedCloudSyncConfig, normalize_remote_path, require_valid_sync_key,
    validate_cloud_sync_config,
};
