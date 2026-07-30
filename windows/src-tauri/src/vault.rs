//! Password-derived, local-only encrypted vault core.
//!
//! This module deliberately has no Tauri, SQLite, clipboard, or shell dependencies. The
//! application integrates persistence through [`VaultStore`] and keeps every privileged UI
//! action at the command boundary. Passwords, plaintext, and derived keys do not implement
//! `Debug`, and all public errors contain stable codes only.

use std::sync::{
    Arc, Mutex, MutexGuard,
    atomic::{AtomicU64, Ordering},
};
use std::{collections::HashSet, fmt};

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, KeyInit},
};
use pbkdf2::pbkdf2_hmac;
use reqwest::Url;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Number;
use sha2::Sha256;
use uuid::Uuid;
use zeroize::{Zeroize, Zeroizing};

pub const DEFAULT_KDF_ITERATIONS: u32 = 120_000;
pub const SALT_BYTES: usize = 16;
pub const NONCE_BYTES: usize = 12;
pub const KEY_BYTES: usize = 32;
pub const CURRENT_PAYLOAD_VERSION: u64 = 2;
pub const CURRENT_METADATA_VERSION: u32 = 2;

const MAX_KDF_ITERATIONS: u32 = 10_000_000;
const MIN_KDF_ITERATIONS: u32 = 10_000;
const GCM_TAG_BYTES: usize = 16;
const MAX_ITEM_CIPHERTEXT_BYTES: usize = 1024 * 1024;
const MAX_VAULT_ITEMS: usize = 100_000;
const VERIFIER_PLAINTEXT: &str = "deskcubby-vault-verifier";

/// An owned password that is zeroed when dropped.
///
/// Command DTOs should move their deserialized `String` directly into this type and must not
/// derive `Debug`.
pub struct VaultPassword(Zeroizing<String>);

impl VaultPassword {
    pub fn new(password: String) -> Self {
        Self(Zeroizing::new(password))
    }

    pub fn expose(&self) -> &str {
        self.0.as_str()
    }

    pub fn is_valid_new_password(&self) -> bool {
        is_valid_new_vault_password(self.expose())
    }
}

impl<'de> Deserialize<'de> for VaultPassword {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        String::deserialize(deserializer).map(Self::new)
    }
}

pub fn is_valid_new_vault_password(password: &str) -> bool {
    !password.is_empty()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VaultStoreError {
    Unavailable,
    AlreadyConfigured,
    GenerationMismatch,
    RevisionMismatch,
    NotFound,
    InvalidOrder,
    Corrupt,
}

impl fmt::Display for VaultStoreError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::Unavailable => "VAULT_STORE_UNAVAILABLE",
            Self::AlreadyConfigured => "VAULT_ALREADY_CONFIGURED",
            Self::GenerationMismatch => "VAULT_GENERATION_MISMATCH",
            Self::RevisionMismatch => "VAULT_REVISION_MISMATCH",
            Self::NotFound => "VAULT_ITEM_NOT_FOUND",
            Self::InvalidOrder => "VAULT_ORDER_INVALID",
            Self::Corrupt => "VAULT_STORE_CORRUPT",
        })
    }
}

impl std::error::Error for VaultStoreError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VaultError {
    NotConfigured,
    AlreadyConfigured,
    Locked,
    WrongPassword,
    InvalidPassword,
    InvalidContent,
    MetadataCorrupt,
    CorruptedItems,
    ItemNotFound,
    InvalidOrder,
    SessionChanged,
    StoreUnavailable,
    CryptoFailed,
}

impl VaultError {
    pub fn code(self) -> &'static str {
        match self {
            Self::NotConfigured => "vault_not_configured",
            Self::AlreadyConfigured => "vault_already_configured",
            Self::Locked => "vault_locked",
            Self::WrongPassword => "vault_wrong_password",
            Self::InvalidPassword => "vault_invalid_password",
            Self::InvalidContent => "vault_invalid_content",
            Self::MetadataCorrupt => "vault_metadata_corrupt",
            Self::CorruptedItems => "vault_corrupted_items",
            Self::ItemNotFound => "vault_item_not_found",
            Self::InvalidOrder => "vault_order_invalid",
            Self::SessionChanged => "vault_session_changed",
            Self::StoreUnavailable => "vault_store_unavailable",
            Self::CryptoFailed => "vault_operation_failed",
        }
    }
}

impl fmt::Display for VaultError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.code())
    }
}

impl std::error::Error for VaultError {}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum VaultLockState {
    NotSet,
    Locked,
    Unlocked,
}

#[derive(Clone, PartialEq, Eq)]
pub struct VaultMetadataRecord {
    pub metadata_version: u32,
    pub salt: Vec<u8>,
    pub verifier_ciphertext: Vec<u8>,
    pub verifier_nonce: Vec<u8>,
    pub kdf_iterations: u32,
    pub generation_id: String,
    /// Incremented by every item mutation. Rekey uses it to avoid losing a concurrent write from
    /// another DeskCubby process that still belongs to the same key generation.
    pub revision: i64,
}

#[derive(Clone, PartialEq, Eq)]
pub struct VaultEncryptedItem {
    pub id: i64,
    pub ciphertext: Vec<u8>,
    pub nonce: Vec<u8>,
    pub created_at: i64,
    pub updated_at: i64,
    pub sort_order: i64,
}

#[derive(Clone, PartialEq, Eq)]
pub struct StoredVaultState {
    pub metadata: Option<VaultMetadataRecord>,
    pub items: Vec<VaultEncryptedItem>,
}

/// Persistence transaction boundary used by [`VaultService`].
///
/// Every mutating method must atomically:
///
/// 1. verify `expected_generation` against the current metadata row;
/// 2. apply the requested item change; and
/// 3. increment `VaultMetadataRecord::revision`.
///
/// `replace_all_for_rekey` must additionally compare `expected_revision`, validate that
/// `replacement_items` contains exactly the current item IDs, and replace all ciphertext plus
/// metadata in one transaction. A crash before commit must leave the complete old generation,
/// while a crash after commit must leave the complete new generation.
pub trait VaultStore: Send + Sync {
    fn load_state(&self) -> Result<StoredVaultState, VaultStoreError>;

    fn initialize_if_empty(&self, metadata: VaultMetadataRecord) -> Result<(), VaultStoreError>;

    fn insert_at_end(
        &self,
        expected_generation: &str,
        item: VaultEncryptedItem,
    ) -> Result<VaultEncryptedItem, VaultStoreError>;

    fn update_ciphertext(
        &self,
        expected_generation: &str,
        id: i64,
        ciphertext: Vec<u8>,
        nonce: Vec<u8>,
        updated_at: i64,
    ) -> Result<(), VaultStoreError>;

    fn delete_item(&self, expected_generation: &str, id: i64) -> Result<(), VaultStoreError>;

    fn reorder_items(
        &self,
        expected_generation: &str,
        ordered_ids: &[i64],
    ) -> Result<(), VaultStoreError>;

    fn replace_all_for_rekey(
        &self,
        expected_generation: &str,
        expected_revision: i64,
        metadata: VaultMetadataRecord,
        replacement_items: Vec<VaultEncryptedItem>,
    ) -> Result<(), VaultStoreError>;
}

#[derive(Clone, PartialEq, Eq)]
pub struct VaultItem {
    pub id: i64,
    pub content: String,
    pub note: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub sort_order: i64,
}

pub struct VaultContentState {
    pub items: Vec<VaultItem>,
    pub corrupted_item_count: usize,
}

struct VaultSession {
    key: Zeroizing<[u8; KEY_BYTES]>,
    generation_id: String,
}

pub struct VaultService {
    store: Arc<dyn VaultStore>,
    operation_mutex: Mutex<()>,
    session: Mutex<Option<VaultSession>>,
    lock_epoch: AtomicU64,
}

impl VaultService {
    pub fn new(store: Arc<dyn VaultStore>) -> Self {
        Self {
            store,
            operation_mutex: Mutex::new(()),
            session: Mutex::new(None),
            lock_epoch: AtomicU64::new(0),
        }
    }

    pub fn lock_state(&self) -> Result<VaultLockState, VaultError> {
        let state = self.load_validated_state()?;
        let Some(metadata) = state.metadata else {
            self.lock();
            return if state.items.is_empty() {
                Ok(VaultLockState::NotSet)
            } else {
                Err(VaultError::MetadataCorrupt)
            };
        };

        let mut session = recover_lock(&self.session);
        let is_valid = session.as_ref().is_some_and(|candidate| {
            candidate.generation_id == metadata.generation_id
                && key_verifies(&candidate.key, &metadata)
        });
        if is_valid {
            Ok(VaultLockState::Unlocked)
        } else {
            *session = None;
            Ok(VaultLockState::Locked)
        }
    }

    pub fn setup_password(&self, password: &VaultPassword) -> Result<(), VaultError> {
        if !password.is_valid_new_password() {
            return Err(VaultError::InvalidPassword);
        }
        let _operation = recover_lock(&self.operation_mutex);
        let operation_epoch = self.lock_epoch.load(Ordering::SeqCst);
        let existing = self.load_validated_state()?;
        if existing.metadata.is_some() || !existing.items.is_empty() {
            return Err(if existing.metadata.is_some() {
                VaultError::AlreadyConfigured
            } else {
                VaultError::MetadataCorrupt
            });
        }

        let salt = random_bytes::<SALT_BYTES>()?;
        let key = derive_key(password.expose(), &salt, DEFAULT_KDF_ITERATIONS);
        let verifier = encrypt(&key, VERIFIER_PLAINTEXT)?;
        let generation_id = Uuid::new_v4().to_string();
        let metadata = VaultMetadataRecord {
            metadata_version: CURRENT_METADATA_VERSION,
            salt: salt.to_vec(),
            verifier_ciphertext: verifier.ciphertext,
            verifier_nonce: verifier.nonce.to_vec(),
            kdf_iterations: DEFAULT_KDF_ITERATIONS,
            generation_id: generation_id.clone(),
            revision: 0,
        };
        self.store
            .initialize_if_empty(metadata)
            .map_err(|error| self.map_store_error(error))?;
        self.install_session_after_commit(key, generation_id, operation_epoch)
    }

    pub fn unlock(&self, password: &VaultPassword) -> Result<(), VaultError> {
        let _operation = recover_lock(&self.operation_mutex);
        let operation_epoch = self.lock_epoch.load(Ordering::SeqCst);
        let state = self.load_validated_state()?;
        let metadata = match state.metadata {
            Some(metadata) => metadata,
            None => {
                self.lock();
                return Err(if state.items.is_empty() {
                    VaultError::NotConfigured
                } else {
                    VaultError::MetadataCorrupt
                });
            }
        };
        let key = derive_key(password.expose(), &metadata.salt, metadata.kdf_iterations);
        if !key_verifies(&key, &metadata) {
            return Err(VaultError::WrongPassword);
        }
        self.install_session(key, metadata.generation_id, operation_epoch)
    }

    /// Immediately removes the process-memory key and prevents a previously started unlock or
    /// password change from resurrecting it.
    pub fn lock(&self) {
        self.lock_epoch.fetch_add(1, Ordering::SeqCst);
        self.clear_session_without_epoch();
    }

    pub fn content_state(&self) -> Result<VaultContentState, VaultError> {
        let (key, generation_id) = self.session_snapshot()?;
        let mut state = self.load_validated_state()?;
        let Some(metadata) = state.metadata else {
            self.lock();
            return Err(VaultError::SessionChanged);
        };
        if generation_id != metadata.generation_id || !key_verifies(&key, &metadata) {
            self.lock();
            return Err(VaultError::SessionChanged);
        }

        state.items.sort_by(|left, right| {
            left.sort_order
                .cmp(&right.sort_order)
                .then_with(|| right.updated_at.cmp(&left.updated_at))
                .then_with(|| right.id.cmp(&left.id))
        });
        let mut items = Vec::with_capacity(state.items.len());
        let mut corrupted_item_count = 0usize;
        for encrypted in state.items {
            if let Some(item) = decrypt_item(&key, encrypted) {
                items.push(item);
            } else {
                corrupted_item_count = corrupted_item_count.saturating_add(1);
            }
        }
        Ok(VaultContentState {
            items,
            corrupted_item_count,
        })
    }

    pub fn add_item(
        &self,
        content: &str,
        note: Option<&str>,
        now_millis: i64,
    ) -> Result<VaultItem, VaultError> {
        if !valid_item_input(content, note) || now_millis < 0 {
            return Err(VaultError::InvalidContent);
        }
        let _operation = recover_lock(&self.operation_mutex);
        let (key, generation_id) = self.session_snapshot()?;
        self.verify_current_session(&key, &generation_id)?;
        let plaintext = encode_payload(content, note)?;
        require_item_plaintext_size(plaintext.as_bytes())?;
        let encrypted = encrypt(&key, plaintext.as_str())?;
        let stored = self
            .store
            .insert_at_end(
                &generation_id,
                VaultEncryptedItem {
                    id: 0,
                    ciphertext: encrypted.ciphertext,
                    nonce: encrypted.nonce.to_vec(),
                    created_at: now_millis,
                    updated_at: now_millis,
                    sort_order: 0,
                },
            )
            .map_err(|error| self.map_store_error(error))?;
        decrypt_item(&key, stored).ok_or(VaultError::CryptoFailed)
    }

    pub fn update_item(
        &self,
        id: i64,
        content: &str,
        note: Option<&str>,
        now_millis: i64,
    ) -> Result<(), VaultError> {
        if id <= 0 || !valid_item_input(content, note) || now_millis < 0 {
            return Err(VaultError::InvalidContent);
        }
        let _operation = recover_lock(&self.operation_mutex);
        let (key, generation_id) = self.session_snapshot()?;
        self.verify_current_session(&key, &generation_id)?;
        let plaintext = encode_payload(content, note)?;
        require_item_plaintext_size(plaintext.as_bytes())?;
        let encrypted = encrypt(&key, plaintext.as_str())?;
        self.store
            .update_ciphertext(
                &generation_id,
                id,
                encrypted.ciphertext,
                encrypted.nonce.to_vec(),
                now_millis,
            )
            .map_err(|error| self.map_store_error(error))
    }

    pub fn delete_item(&self, id: i64) -> Result<(), VaultError> {
        if id <= 0 {
            return Err(VaultError::ItemNotFound);
        }
        let _operation = recover_lock(&self.operation_mutex);
        let (key, generation_id) = self.session_snapshot()?;
        self.verify_current_session(&key, &generation_id)?;
        self.store
            .delete_item(&generation_id, id)
            .map_err(|error| self.map_store_error(error))
    }

    pub fn reorder_items(&self, ordered_ids: &[i64]) -> Result<(), VaultError> {
        if ordered_ids.iter().any(|id| *id <= 0) {
            return Err(VaultError::InvalidOrder);
        }
        let _operation = recover_lock(&self.operation_mutex);
        let (key, generation_id) = self.session_snapshot()?;
        self.verify_current_session(&key, &generation_id)?;
        self.store
            .reorder_items(&generation_id, ordered_ids)
            .map_err(|error| self.map_store_error(error))
    }

    pub fn change_password(
        &self,
        old_password: &VaultPassword,
        new_password: &VaultPassword,
    ) -> Result<(), VaultError> {
        if !new_password.is_valid_new_password() {
            return Err(VaultError::InvalidPassword);
        }
        let _operation = recover_lock(&self.operation_mutex);
        let operation_epoch = self.lock_epoch.load(Ordering::SeqCst);
        let state = self.load_validated_state()?;
        let metadata = match state.metadata {
            Some(metadata) => metadata,
            None => {
                self.lock();
                return Err(if state.items.is_empty() {
                    VaultError::NotConfigured
                } else {
                    VaultError::MetadataCorrupt
                });
            }
        };
        let old_key = derive_key(
            old_password.expose(),
            &metadata.salt,
            metadata.kdf_iterations,
        );
        if !key_verifies(&old_key, &metadata) {
            return Err(VaultError::WrongPassword);
        }

        // Preserve the exact legacy/versioned plaintext representation during rekey, matching
        // Android. Decode is used only to reject corrupt or unsupported payloads.
        let mut plaintext_rows = Vec::with_capacity(state.items.len());
        for item in &state.items {
            let plaintext = decrypt(&old_key, &item.ciphertext, &item.nonce)
                .ok_or(VaultError::CorruptedItems)?;
            if parse_payload(plaintext.as_str()).is_none() {
                return Err(VaultError::CorruptedItems);
            }
            plaintext_rows.push((item, plaintext));
        }

        let new_salt = random_bytes::<SALT_BYTES>()?;
        let new_key = derive_key(new_password.expose(), &new_salt, DEFAULT_KDF_ITERATIONS);
        let new_verifier = encrypt(&new_key, VERIFIER_PLAINTEXT)?;
        let new_generation_id = Uuid::new_v4().to_string();
        let new_revision = metadata
            .revision
            .checked_add(1)
            .ok_or(VaultError::MetadataCorrupt)?;
        let new_metadata = VaultMetadataRecord {
            metadata_version: CURRENT_METADATA_VERSION,
            salt: new_salt.to_vec(),
            verifier_ciphertext: new_verifier.ciphertext,
            verifier_nonce: new_verifier.nonce.to_vec(),
            kdf_iterations: DEFAULT_KDF_ITERATIONS,
            generation_id: new_generation_id.clone(),
            revision: new_revision,
        };
        let mut replacement_items = Vec::with_capacity(plaintext_rows.len());
        for (item, plaintext) in plaintext_rows {
            let encrypted = encrypt(&new_key, plaintext.as_str())?;
            replacement_items.push(VaultEncryptedItem {
                id: item.id,
                ciphertext: encrypted.ciphertext,
                nonce: encrypted.nonce.to_vec(),
                created_at: item.created_at,
                updated_at: item.updated_at,
                sort_order: item.sort_order,
            });
        }
        self.store
            .replace_all_for_rekey(
                &metadata.generation_id,
                metadata.revision,
                new_metadata,
                replacement_items,
            )
            .map_err(|error| self.map_store_error(error))?;
        self.install_session_after_commit(new_key, new_generation_id, operation_epoch)
    }

    fn load_validated_state(&self) -> Result<StoredVaultState, VaultError> {
        let state = self
            .store
            .load_state()
            .map_err(|error| self.map_store_error(error))?;
        if let Some(metadata) = &state.metadata
            && let Err(error) = validate_metadata_record(metadata)
        {
            self.lock();
            return Err(error);
        }
        if let Err(error) = validate_encrypted_items(&state) {
            self.lock();
            return Err(error);
        }
        Ok(state)
    }

    fn verify_current_session(
        &self,
        key: &Zeroizing<[u8; KEY_BYTES]>,
        generation_id: &str,
    ) -> Result<(), VaultError> {
        let state = self.load_validated_state()?;
        let Some(metadata) = state.metadata else {
            self.lock();
            return Err(VaultError::SessionChanged);
        };
        if metadata.generation_id != generation_id || !key_verifies(key, &metadata) {
            self.lock();
            return Err(VaultError::SessionChanged);
        }
        Ok(())
    }

    fn session_snapshot(&self) -> Result<(Zeroizing<[u8; KEY_BYTES]>, String), VaultError> {
        let session = recover_lock(&self.session);
        let session = session.as_ref().ok_or(VaultError::Locked)?;
        Ok((Zeroizing::new(*session.key), session.generation_id.clone()))
    }

    fn install_session(
        &self,
        key: Zeroizing<[u8; KEY_BYTES]>,
        generation_id: String,
        operation_epoch: u64,
    ) -> Result<(), VaultError> {
        if self.lock_epoch.load(Ordering::SeqCst) != operation_epoch {
            return Err(VaultError::SessionChanged);
        }
        let mut session = recover_lock(&self.session);
        if self.lock_epoch.load(Ordering::SeqCst) != operation_epoch {
            *session = None;
            return Err(VaultError::SessionChanged);
        }
        *session = Some(VaultSession { key, generation_id });
        Ok(())
    }

    /// Setup and rekey are durable before the new session is installed. If an
    /// explicit lock races that final step, keep the committed vault locked and
    /// still report the persistence operation as successful. This avoids the
    /// dangerous "reported failure, password actually changed" ambiguity.
    fn install_session_after_commit(
        &self,
        key: Zeroizing<[u8; KEY_BYTES]>,
        generation_id: String,
        operation_epoch: u64,
    ) -> Result<(), VaultError> {
        match self.install_session(key, generation_id, operation_epoch) {
            Ok(()) | Err(VaultError::SessionChanged) => Ok(()),
            Err(error) => Err(error),
        }
    }

    fn clear_session_without_epoch(&self) {
        *recover_lock(&self.session) = None;
    }

    fn map_store_error(&self, error: VaultStoreError) -> VaultError {
        match error {
            VaultStoreError::Unavailable => VaultError::StoreUnavailable,
            VaultStoreError::AlreadyConfigured => VaultError::AlreadyConfigured,
            VaultStoreError::GenerationMismatch | VaultStoreError::RevisionMismatch => {
                self.lock();
                VaultError::SessionChanged
            }
            VaultStoreError::NotFound => VaultError::ItemNotFound,
            VaultStoreError::InvalidOrder => VaultError::InvalidOrder,
            VaultStoreError::Corrupt => {
                self.lock();
                VaultError::MetadataCorrupt
            }
        }
    }
}

fn recover_lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

pub(crate) fn validate_metadata_record(metadata: &VaultMetadataRecord) -> Result<(), VaultError> {
    if metadata.metadata_version == 0
        || metadata.metadata_version > CURRENT_METADATA_VERSION
        || metadata.salt.len() != SALT_BYTES
        || metadata.verifier_ciphertext.len() < GCM_TAG_BYTES
        || metadata.verifier_ciphertext.len() > 4_096
        || metadata.verifier_nonce.len() != NONCE_BYTES
        || !(MIN_KDF_ITERATIONS..=MAX_KDF_ITERATIONS).contains(&metadata.kdf_iterations)
        || metadata.revision < 0
        || !valid_generation_id(&metadata.generation_id)
    {
        return Err(VaultError::MetadataCorrupt);
    }
    Ok(())
}

fn valid_generation_id(value: &str) -> bool {
    Uuid::parse_str(value)
        .is_ok_and(|parsed| !parsed.is_nil() && parsed.hyphenated().to_string() == value)
}

pub(crate) fn validate_encrypted_items(state: &StoredVaultState) -> Result<(), VaultError> {
    if state.items.len() > MAX_VAULT_ITEMS {
        return Err(VaultError::MetadataCorrupt);
    }
    if state.metadata.is_none() {
        return if state.items.is_empty() {
            Ok(())
        } else {
            Err(VaultError::MetadataCorrupt)
        };
    }
    let metadata = state.metadata.as_ref().ok_or(VaultError::MetadataCorrupt)?;
    let mut ids = HashSet::with_capacity(state.items.len());
    let mut sort_orders = HashSet::with_capacity(state.items.len());
    let mut nonces = HashSet::with_capacity(state.items.len().saturating_add(1));
    nonces.insert(metadata.verifier_nonce.as_slice());
    for item in &state.items {
        if item.id <= 0
            || !ids.insert(item.id)
            || item.created_at < 0
            || item.updated_at < item.created_at
            || item.sort_order < 0
            || !sort_orders.insert(item.sort_order)
            || item.ciphertext.len() < GCM_TAG_BYTES
            || item.ciphertext.len() > MAX_ITEM_CIPHERTEXT_BYTES
            || item.nonce.len() != NONCE_BYTES
            || !nonces.insert(item.nonce.as_slice())
        {
            return Err(VaultError::MetadataCorrupt);
        }
    }
    Ok(())
}

fn valid_item_input(content: &str, note: Option<&str>) -> bool {
    if content.trim().is_empty() || content.len() > MAX_ITEM_CIPHERTEXT_BYTES {
        return false;
    }
    let note_bytes = note
        .filter(|value| !value.trim().is_empty())
        .map_or(0, str::len);
    content
        .len()
        .checked_add(note_bytes)
        .is_some_and(|total| total <= MAX_ITEM_CIPHERTEXT_BYTES)
}

fn require_item_plaintext_size(plaintext: &[u8]) -> Result<(), VaultError> {
    if plaintext
        .len()
        .checked_add(GCM_TAG_BYTES)
        .is_some_and(|ciphertext_len| ciphertext_len <= MAX_ITEM_CIPHERTEXT_BYTES)
    {
        Ok(())
    } else {
        Err(VaultError::InvalidContent)
    }
}

fn derive_key(password: &str, salt: &[u8], iterations: u32) -> Zeroizing<[u8; KEY_BYTES]> {
    let mut key = Zeroizing::new([0_u8; KEY_BYTES]);
    pbkdf2_hmac::<Sha256>(password.as_bytes(), salt, iterations, key.as_mut());
    key
}

struct EncryptedValue {
    ciphertext: Vec<u8>,
    nonce: [u8; NONCE_BYTES],
}

fn encrypt(
    key: &Zeroizing<[u8; KEY_BYTES]>,
    plaintext: &str,
) -> Result<EncryptedValue, VaultError> {
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).map_err(|_| VaultError::CryptoFailed)?;
    let nonce = random_bytes::<NONCE_BYTES>()?;
    let ciphertext = cipher
        .encrypt(Nonce::from_slice(&nonce), plaintext.as_bytes())
        .map_err(|_| VaultError::CryptoFailed)?;
    Ok(EncryptedValue { ciphertext, nonce })
}

fn decrypt(
    key: &Zeroizing<[u8; KEY_BYTES]>,
    ciphertext: &[u8],
    nonce: &[u8],
) -> Option<Zeroizing<String>> {
    if ciphertext.len() < GCM_TAG_BYTES || nonce.len() != NONCE_BYTES {
        return None;
    }
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).ok()?;
    let plaintext = cipher.decrypt(Nonce::from_slice(nonce), ciphertext).ok()?;
    match String::from_utf8(plaintext) {
        Ok(plaintext) => Some(Zeroizing::new(plaintext)),
        Err(error) => {
            let mut invalid_plaintext = error.into_bytes();
            invalid_plaintext.zeroize();
            None
        }
    }
}

fn random_bytes<const N: usize>() -> Result<[u8; N], VaultError> {
    let mut bytes = [0_u8; N];
    getrandom::fill(&mut bytes).map_err(|_| VaultError::CryptoFailed)?;
    Ok(bytes)
}

struct DecodedPayload {
    content: String,
    note: Option<String>,
}

fn encode_payload(content: &str, note: Option<&str>) -> Result<Zeroizing<String>, VaultError> {
    #[derive(Serialize)]
    struct PayloadToEncode<'a> {
        version: u64,
        content: &'a str,
        #[serde(skip_serializing_if = "Option::is_none")]
        note: Option<&'a str>,
    }

    serde_json::to_string(&PayloadToEncode {
        version: CURRENT_PAYLOAD_VERSION,
        content,
        note: note.filter(|value| !value.trim().is_empty()),
    })
    .map(Zeroizing::new)
    .map_err(|_| VaultError::CryptoFailed)
}

#[derive(Default)]
enum JsonField<T> {
    #[default]
    Missing,
    Null,
    Value(T),
}

impl<'de, T> Deserialize<'de> for JsonField<T>
where
    T: Deserialize<'de>,
{
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        Option::<T>::deserialize(deserializer).map(|value| match value {
            Some(value) => Self::Value(value),
            None => Self::Null,
        })
    }
}

struct SensitiveString(Zeroizing<String>);

impl SensitiveString {
    fn empty() -> Self {
        Self(Zeroizing::new(String::new()))
    }

    fn as_str(&self) -> &str {
        self.0.as_str()
    }
}

impl<'de> Deserialize<'de> for SensitiveString {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        String::deserialize(deserializer).map(|value| Self(Zeroizing::new(value)))
    }
}

#[derive(Deserialize)]
struct RawPayload {
    #[serde(default)]
    version: JsonField<Number>,
    #[serde(default)]
    content: JsonField<SensitiveString>,
    #[serde(default)]
    note: JsonField<SensitiveString>,
    #[serde(default)]
    title: JsonField<SensitiveString>,
}

struct ParsedPayload {
    content: SensitiveString,
    note: Option<SensitiveString>,
}

fn parse_payload(plaintext: &str) -> Option<ParsedPayload> {
    let payload = serde_json::from_str::<RawPayload>(plaintext).ok()?;
    match payload.version {
        JsonField::Value(version)
            if version
                .as_f64()
                .is_some_and(|value| value == CURRENT_PAYLOAD_VERSION as f64) =>
        {
            let JsonField::Value(content) = payload.content else {
                return None;
            };
            let note = match payload.note {
                JsonField::Missing | JsonField::Null => None,
                JsonField::Value(note) => (!note.as_str().trim().is_empty()).then_some(note),
            };
            Some(ParsedPayload { content, note })
        }
        JsonField::Value(_) | JsonField::Null => None,
        JsonField::Missing => {
            let title = match payload.title {
                JsonField::Missing | JsonField::Null => SensitiveString::empty(),
                JsonField::Value(title) => title,
            };
            let content = match payload.content {
                JsonField::Missing | JsonField::Null => SensitiveString::empty(),
                JsonField::Value(content) => content,
            };
            if content.as_str().is_empty() {
                Some(ParsedPayload {
                    content: title,
                    note: None,
                })
            } else {
                let has_title = !title.as_str().is_empty();
                Some(ParsedPayload {
                    content,
                    note: has_title.then_some(title),
                })
            }
        }
    }
}

fn decode_payload(plaintext: &str) -> Option<DecodedPayload> {
    let payload = parse_payload(plaintext)?;
    Some(DecodedPayload {
        content: payload.content.as_str().to_owned(),
        note: payload.note.map(|note| note.as_str().to_owned()),
    })
}

fn decrypt_item(
    key: &Zeroizing<[u8; KEY_BYTES]>,
    encrypted: VaultEncryptedItem,
) -> Option<VaultItem> {
    if encrypted.id <= 0 || encrypted.created_at < 0 || encrypted.updated_at < encrypted.created_at
    {
        return None;
    }
    let plaintext = decrypt(key, &encrypted.ciphertext, &encrypted.nonce)?;
    let payload = decode_payload(plaintext.as_str())?;
    Some(VaultItem {
        id: encrypted.id,
        content: payload.content,
        note: payload.note,
        created_at: encrypted.created_at,
        updated_at: encrypted.updated_at,
        sort_order: encrypted.sort_order,
    })
}

fn key_verifies(key: &Zeroizing<[u8; KEY_BYTES]>, metadata: &VaultMetadataRecord) -> bool {
    decrypt(key, &metadata.verifier_ciphertext, &metadata.verifier_nonce)
        .is_some_and(|plaintext| plaintext.as_str() == VERIFIER_PLAINTEXT)
}

/// Returns the trimmed original entry only when the complete value is a browser-safe absolute
/// HTTP(S) URL. This mirrors Android's vault card behavior; all other values are ordinary text.
pub fn safe_vault_http_url_or_null(raw_content: &str) -> Option<String> {
    let candidate = raw_content.trim();
    if candidate.is_empty()
        || candidate
            .chars()
            .any(|character| character.is_whitespace() || character.is_control())
    {
        return None;
    }
    let colon = candidate.find(':')?;
    let scheme = &candidate[..colon];
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return None;
    }
    let remainder = candidate.get(colon + 1..)?;
    let authority_and_rest = remainder.strip_prefix("//")?;
    let authority_end = authority_and_rest
        .find(['/', '?', '#'])
        .unwrap_or(authority_and_rest.len());
    let authority = &authority_and_rest[..authority_end];
    if authority.is_empty() || authority.contains('@') {
        return None;
    }
    let parsed = Url::parse(candidate).ok()?;
    if !parsed.scheme().eq_ignore_ascii_case("http")
        && !parsed.scheme().eq_ignore_ascii_case("https")
    {
        return None;
    }
    if !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.host_str().is_none_or(str::is_empty)
    {
        return None;
    }
    Some(candidate.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    use std::sync::atomic::AtomicBool;

    #[derive(Default)]
    struct MemoryState {
        metadata: Option<VaultMetadataRecord>,
        items: Vec<VaultEncryptedItem>,
        next_id: i64,
    }

    #[derive(Default)]
    struct MemoryVaultStore {
        state: Mutex<MemoryState>,
        bump_revision_before_rekey: AtomicBool,
    }

    impl MemoryVaultStore {
        fn next_revision(
            state: &MemoryState,
            expected_generation: &str,
        ) -> Result<i64, VaultStoreError> {
            let metadata = state.metadata.as_ref().ok_or(VaultStoreError::Corrupt)?;
            if metadata.generation_id != expected_generation {
                return Err(VaultStoreError::GenerationMismatch);
            }
            metadata
                .revision
                .checked_add(1)
                .ok_or(VaultStoreError::Corrupt)
        }

        fn commit_revision(state: &mut MemoryState, revision: i64) {
            if let Some(metadata) = &mut state.metadata {
                metadata.revision = revision;
            }
        }
    }

    impl VaultStore for MemoryVaultStore {
        fn load_state(&self) -> Result<StoredVaultState, VaultStoreError> {
            let state = recover_lock(&self.state);
            Ok(StoredVaultState {
                metadata: state.metadata.clone(),
                items: state.items.clone(),
            })
        }

        fn initialize_if_empty(
            &self,
            metadata: VaultMetadataRecord,
        ) -> Result<(), VaultStoreError> {
            let mut state = recover_lock(&self.state);
            if state.metadata.is_some() || !state.items.is_empty() {
                return Err(VaultStoreError::AlreadyConfigured);
            }
            state.metadata = Some(metadata);
            state.next_id = 1;
            Ok(())
        }

        fn insert_at_end(
            &self,
            expected_generation: &str,
            mut item: VaultEncryptedItem,
        ) -> Result<VaultEncryptedItem, VaultStoreError> {
            let mut state = recover_lock(&self.state);
            let next_revision = Self::next_revision(&state, expected_generation)?;
            let id = state.next_id.max(1);
            state.next_id = id.checked_add(1).ok_or(VaultStoreError::Corrupt)?;
            let sort_order = state
                .items
                .iter()
                .map(|existing| existing.sort_order)
                .max()
                .unwrap_or(-1)
                .checked_add(1)
                .ok_or(VaultStoreError::Corrupt)?;
            item.id = id;
            item.sort_order = sort_order;
            state.items.push(item.clone());
            Self::commit_revision(&mut state, next_revision);
            Ok(item)
        }

        fn update_ciphertext(
            &self,
            expected_generation: &str,
            id: i64,
            ciphertext: Vec<u8>,
            nonce: Vec<u8>,
            updated_at: i64,
        ) -> Result<(), VaultStoreError> {
            let mut state = recover_lock(&self.state);
            let next_revision = Self::next_revision(&state, expected_generation)?;
            let index = state
                .items
                .iter()
                .position(|item| item.id == id)
                .ok_or(VaultStoreError::NotFound)?;
            let item = &mut state.items[index];
            if updated_at < item.created_at {
                return Err(VaultStoreError::Corrupt);
            }
            item.ciphertext = ciphertext;
            item.nonce = nonce;
            item.updated_at = updated_at;
            Self::commit_revision(&mut state, next_revision);
            Ok(())
        }

        fn delete_item(&self, expected_generation: &str, id: i64) -> Result<(), VaultStoreError> {
            let mut state = recover_lock(&self.state);
            let next_revision = Self::next_revision(&state, expected_generation)?;
            let old_len = state.items.len();
            state.items.retain(|item| item.id != id);
            if state.items.len() == old_len {
                return Err(VaultStoreError::NotFound);
            }
            Self::commit_revision(&mut state, next_revision);
            Ok(())
        }

        fn reorder_items(
            &self,
            expected_generation: &str,
            ordered_ids: &[i64],
        ) -> Result<(), VaultStoreError> {
            let mut state = recover_lock(&self.state);
            let next_revision = Self::next_revision(&state, expected_generation)?;
            let requested: HashSet<_> = ordered_ids.iter().copied().collect();
            let current: HashSet<_> = state.items.iter().map(|item| item.id).collect();
            if requested.len() != ordered_ids.len()
                || requested.len() != state.items.len()
                || requested != current
            {
                return Err(VaultStoreError::InvalidOrder);
            }
            for (index, id) in ordered_ids.iter().enumerate() {
                let item = state
                    .items
                    .iter_mut()
                    .find(|item| item.id == *id)
                    .ok_or(VaultStoreError::InvalidOrder)?;
                item.sort_order =
                    i64::try_from(index).map_err(|_| VaultStoreError::InvalidOrder)?;
            }
            Self::commit_revision(&mut state, next_revision);
            Ok(())
        }

        fn replace_all_for_rekey(
            &self,
            expected_generation: &str,
            expected_revision: i64,
            metadata: VaultMetadataRecord,
            replacement_items: Vec<VaultEncryptedItem>,
        ) -> Result<(), VaultStoreError> {
            let mut state = recover_lock(&self.state);
            if self
                .bump_revision_before_rekey
                .swap(false, Ordering::SeqCst)
            {
                let active = state.metadata.as_mut().ok_or(VaultStoreError::Corrupt)?;
                active.revision = active
                    .revision
                    .checked_add(1)
                    .ok_or(VaultStoreError::Corrupt)?;
            }
            let active = state.metadata.as_ref().ok_or(VaultStoreError::Corrupt)?;
            if active.generation_id != expected_generation {
                return Err(VaultStoreError::GenerationMismatch);
            }
            if active.revision != expected_revision {
                return Err(VaultStoreError::RevisionMismatch);
            }
            let current_ids: HashSet<_> = state.items.iter().map(|item| item.id).collect();
            let replacement_ids: HashSet<_> =
                replacement_items.iter().map(|item| item.id).collect();
            if current_ids != replacement_ids
                || replacement_ids.len() != replacement_items.len()
                || metadata.revision
                    != expected_revision
                        .checked_add(1)
                        .ok_or(VaultStoreError::Corrupt)?
            {
                return Err(VaultStoreError::RevisionMismatch);
            }
            state.metadata = Some(metadata);
            state.items = replacement_items;
            Ok(())
        }
    }

    fn service() -> (Arc<MemoryVaultStore>, VaultService) {
        let store = Arc::new(MemoryVaultStore::default());
        let service = VaultService::new(store.clone());
        (store, service)
    }

    fn password(value: &str) -> VaultPassword {
        VaultPassword::new(value.to_owned())
    }

    #[test]
    fn setup_crud_reorder_lock_and_unlock_round_trip() {
        let (_store, service) = service();
        assert!(matches!(service.lock_state(), Ok(VaultLockState::NotSet)));
        assert!(service.setup_password(&password("🔐 pass")).is_ok());
        assert!(matches!(service.lock_state(), Ok(VaultLockState::Unlocked)));

        let first = service.add_item("first", Some("note"), 10);
        assert!(first.is_ok());
        let first = match first {
            Ok(item) => item,
            Err(_) => return,
        };
        let second = service.add_item("https://example.com", None, 20);
        assert!(second.is_ok());
        let second = match second {
            Ok(item) => item,
            Err(_) => return,
        };
        assert!(service.reorder_items(&[second.id, first.id]).is_ok());
        let content = service.content_state();
        assert!(content.is_ok());
        let content = match content {
            Ok(content) => content,
            Err(_) => return,
        };
        assert_eq!(content.corrupted_item_count, 0);
        assert_eq!(content.items.len(), 2);
        assert_eq!(content.items[0].content, "https://example.com");
        assert_eq!(content.items[1].note.as_deref(), Some("note"));

        assert!(
            service
                .update_item(first.id, "updated", Some(" "), 30)
                .is_ok()
        );
        assert!(service.delete_item(second.id).is_ok());
        service.lock();
        assert!(matches!(service.content_state(), Err(VaultError::Locked)));
        assert!(matches!(
            service.unlock(&password("wrong")),
            Err(VaultError::WrongPassword)
        ));
        assert!(service.unlock(&password("🔐 pass")).is_ok());
        let content = service.content_state();
        assert!(content.is_ok());
        let content = match content {
            Ok(content) => content,
            Err(_) => return,
        };
        assert_eq!(content.items.len(), 1);
        assert_eq!(content.items[0].content, "updated");
        assert!(content.items[0].note.is_none());
    }

    #[test]
    fn change_password_reencrypts_every_item_and_invalidates_old_password() {
        let (_store, service) = service();
        assert!(service.setup_password(&password("old")).is_ok());
        assert!(service.add_item("秘密", Some("备注"), 1).is_ok());
        assert!(
            service
                .change_password(&password("old"), &password("new"))
                .is_ok()
        );
        service.lock();
        assert!(matches!(
            service.unlock(&password("old")),
            Err(VaultError::WrongPassword)
        ));
        assert!(service.unlock(&password("new")).is_ok());
        let content = service.content_state();
        assert!(content.is_ok());
        let content = match content {
            Ok(content) => content,
            Err(_) => return,
        };
        assert_eq!(content.items.len(), 1);
        assert_eq!(content.items[0].content, "秘密");
        assert_eq!(content.items[0].note.as_deref(), Some("备注"));
    }

    #[test]
    fn corrupt_item_is_counted_and_aborts_rekey_without_mutation() {
        let (store, service) = service();
        assert!(service.setup_password(&password("old")).is_ok());
        assert!(service.add_item("readable", None, 1).is_ok());
        {
            let mut state = recover_lock(&store.state);
            state.items.push(VaultEncryptedItem {
                id: 99,
                ciphertext: vec![0; GCM_TAG_BYTES],
                nonce: vec![0; NONCE_BYTES],
                created_at: 2,
                updated_at: 2,
                sort_order: 1,
            });
            state.next_id = 100;
        }
        let before = store.load_state();
        assert!(before.is_ok());
        let content = service.content_state();
        assert!(content.is_ok());
        let content = match content {
            Ok(content) => content,
            Err(_) => return,
        };
        assert_eq!(content.corrupted_item_count, 1);
        assert!(matches!(
            service.change_password(&password("old"), &password("new")),
            Err(VaultError::CorruptedItems)
        ));
        let after = store.load_state();
        assert!(after.is_ok());
        if let (Ok(before), Ok(after)) = (before, after) {
            assert!(before == after);
        }
    }

    #[test]
    fn invalid_reorder_is_rejected_without_partial_changes() {
        let (_store, service) = service();
        assert!(service.setup_password(&password("password")).is_ok());
        let one = service.add_item("one", None, 1);
        let two = service.add_item("two", None, 2);
        assert!(one.is_ok() && two.is_ok());
        let (one, two) = match (one, two) {
            (Ok(one), Ok(two)) => (one, two),
            _ => return,
        };
        assert!(matches!(
            service.reorder_items(&[one.id, one.id]),
            Err(VaultError::InvalidOrder)
        ));
        assert!(matches!(
            service.reorder_items(&[one.id]),
            Err(VaultError::InvalidOrder)
        ));
        let content = service.content_state();
        assert!(content.is_ok());
        if let Ok(content) = content {
            assert_eq!(content.items[0].id, one.id);
            assert_eq!(content.items[1].id, two.id);
        }
    }

    #[test]
    fn versioned_and_legacy_payloads_match_android_behavior() {
        let encoded = encode_payload("正文\n带有\"引号\"和反斜杠\\", Some("备注\n第二行"));
        assert!(encoded.is_ok());
        if let Ok(encoded) = encoded {
            let decoded = decode_payload(encoded.as_str());
            assert!(decoded.is_some());
            if let Some(decoded) = decoded {
                assert_eq!(decoded.content, "正文\n带有\"引号\"和反斜杠\\");
                assert_eq!(decoded.note.as_deref(), Some("备注\n第二行"));
            }
        }
        let legacy = decode_payload(r#"{"title":"旧标题","content":"旧正文"}"#);
        assert!(legacy.is_some());
        if let Some(legacy) = legacy {
            assert_eq!(legacy.content, "旧正文");
            assert_eq!(legacy.note.as_deref(), Some("旧标题"));
        }
        let title_only = decode_payload(r#"{"title":"只有标题","content":""}"#);
        assert!(title_only.is_some());
        if let Some(title_only) = title_only {
            assert_eq!(title_only.content, "只有标题");
            assert!(title_only.note.is_none());
        }
        assert!(decode_payload(r#"{"version":3,"content":"future"}"#).is_none());
        assert!(decode_payload(r#"{"version":null,"content":"future"}"#).is_none());
        assert!(decode_payload(r#"{"version":2,"content":42}"#).is_none());
        assert!(decode_payload(r#"{"version":2,"content":"ok","note":42}"#).is_none());
        assert!(decode_payload(r#"{"version":2.0,"content":"ok"}"#).is_some());
        assert!(decode_payload(r#"{"title":42,"content":"legacy"}"#).is_none());
    }

    #[test]
    fn strict_metadata_and_nonce_validation_rejects_unsafe_state() {
        let metadata = VaultMetadataRecord {
            metadata_version: CURRENT_METADATA_VERSION,
            salt: vec![7; SALT_BYTES],
            verifier_ciphertext: vec![9; GCM_TAG_BYTES],
            verifier_nonce: vec![1; NONCE_BYTES],
            kdf_iterations: DEFAULT_KDF_ITERATIONS,
            generation_id: "92f5f07a-7cc5-43d8-ae0f-95352029c7aa".to_owned(),
            revision: 0,
        };
        assert!(validate_metadata_record(&metadata).is_ok());

        let mut invalid = metadata.clone();
        invalid.salt.pop();
        assert!(matches!(
            validate_metadata_record(&invalid),
            Err(VaultError::MetadataCorrupt)
        ));
        let mut invalid = metadata.clone();
        invalid.kdf_iterations = MIN_KDF_ITERATIONS - 1;
        assert!(matches!(
            validate_metadata_record(&invalid),
            Err(VaultError::MetadataCorrupt)
        ));
        let mut invalid = metadata.clone();
        invalid.generation_id = invalid.generation_id.to_ascii_uppercase();
        assert!(matches!(
            validate_metadata_record(&invalid),
            Err(VaultError::MetadataCorrupt)
        ));

        let state = StoredVaultState {
            metadata: Some(metadata),
            items: vec![VaultEncryptedItem {
                id: 1,
                ciphertext: vec![0; GCM_TAG_BYTES],
                nonce: vec![1; NONCE_BYTES],
                created_at: 1,
                updated_at: 1,
                sort_order: 0,
            }],
        };
        assert!(matches!(
            validate_encrypted_items(&state),
            Err(VaultError::MetadataCorrupt)
        ));
    }

    #[test]
    fn escaped_payload_size_is_rejected_before_store_mutation() {
        let (store, service) = service();
        assert!(service.setup_password(&password("password")).is_ok());
        let before = store.load_state();
        assert!(before.is_ok());
        let content = "\\".repeat(MAX_ITEM_CIPHERTEXT_BYTES - 128);
        assert!(matches!(
            service.add_item(&content, None, 1),
            Err(VaultError::InvalidContent)
        ));
        let after = store.load_state();
        assert!(after.is_ok());
        if let (Ok(before), Ok(after)) = (before, after) {
            assert!(before == after);
        }
    }

    #[test]
    fn lock_racing_post_commit_session_install_keeps_vault_locked_but_succeeds() {
        let (_store, service) = service();
        let operation_epoch = service.lock_epoch.load(Ordering::SeqCst);
        service.lock();
        assert!(
            service
                .install_session_after_commit(
                    Zeroizing::new([7; KEY_BYTES]),
                    "92f5f07a-7cc5-43d8-ae0f-95352029c7aa".to_owned(),
                    operation_epoch,
                )
                .is_ok()
        );
        assert!(recover_lock(&service.session).is_none());
    }

    #[test]
    fn stale_revision_aborts_rekey_and_locks_the_session() {
        let (store, service) = service();
        assert!(service.setup_password(&password("old")).is_ok());
        assert!(service.add_item("secret", None, 1).is_ok());
        let before = store.load_state();
        assert!(before.is_ok());
        store
            .bump_revision_before_rekey
            .store(true, Ordering::SeqCst);
        assert!(matches!(
            service.change_password(&password("old"), &password("new")),
            Err(VaultError::SessionChanged)
        ));
        let after = store.load_state();
        assert!(after.is_ok());
        if let (Ok(before), Ok(after)) = (before, after) {
            assert_eq!(
                before
                    .metadata
                    .as_ref()
                    .map(|metadata| metadata.generation_id.as_str()),
                after
                    .metadata
                    .as_ref()
                    .map(|metadata| metadata.generation_id.as_str())
            );
            assert!(before.items == after.items);
        }
        assert!(matches!(service.content_state(), Err(VaultError::Locked)));
        assert!(service.unlock(&password("old")).is_ok());
        assert!(matches!(
            service.unlock(&password("new")),
            Err(VaultError::WrongPassword)
        ));
    }

    #[test]
    fn wrong_key_and_tampering_do_not_expose_plaintext() {
        let salt = [7_u8; SALT_BYTES];
        let right = derive_key("right", &salt, 1_000);
        let wrong = derive_key("wrong", &salt, 1_000);
        let encrypted = encrypt(&right, "secret");
        assert!(encrypted.is_ok());
        if let Ok(mut encrypted) = encrypted {
            assert!(decrypt(&wrong, &encrypted.ciphertext, &encrypted.nonce).is_none());
            encrypted.ciphertext[0] ^= 1;
            assert!(decrypt(&right, &encrypted.ciphertext, &encrypted.nonce).is_none());
        }
    }

    #[test]
    fn authenticated_non_utf8_plaintext_is_rejected() {
        let key = Zeroizing::new([7_u8; KEY_BYTES]);
        let cipher = Aes256Gcm::new_from_slice(key.as_ref());
        assert!(cipher.is_ok());
        let nonce = [3_u8; NONCE_BYTES];
        if let Ok(cipher) = cipher {
            let ciphertext = cipher.encrypt(Nonce::from_slice(&nonce), &[0xff_u8][..]);
            assert!(ciphertext.is_ok());
            if let Ok(ciphertext) = ciphertext {
                assert!(decrypt(&key, &ciphertext, &nonce).is_none());
            }
        }
    }

    #[test]
    fn new_password_validation_counts_unicode_scalars_and_has_no_maximum() {
        assert!(!is_valid_new_vault_password(""));
        assert!(is_valid_new_vault_password("🔐"));
        assert!(is_valid_new_vault_password(&"很".repeat(100_000)));
    }

    #[test]
    fn safe_urls_match_android_card_rules() {
        for accepted in [
            "https://example.com/path?q=one#result",
            "  HTTP://example.com:8080/path  ",
            "https://[::1]/local",
        ] {
            assert!(
                safe_vault_http_url_or_null(accepted).is_some(),
                "{accepted}"
            );
        }
        for rejected in [
            "javascript:alert(1)",
            "file:///tmp/secret",
            "content://provider/item",
            "//example.com/path",
            "/relative/path",
            "https:example.com",
            "https:///missing-host",
            "https://trusted.example@attacker.example/path",
            "https://example.com/path with space",
            "https://example.com/\nnext",
            "https://example.com:65536/path",
            "not a link",
            "",
        ] {
            assert!(
                safe_vault_http_url_or_null(rejected).is_none(),
                "{rejected}"
            );
        }
    }

    #[test]
    fn generation_change_locks_stale_session_before_mutation() {
        let (store, service) = service();
        assert!(service.setup_password(&password("password")).is_ok());
        {
            let mut state = recover_lock(&store.state);
            if let Some(metadata) = &mut state.metadata {
                metadata.generation_id = Uuid::new_v4().to_string();
            }
        }
        assert!(matches!(
            service.add_item("must not write", None, 1),
            Err(VaultError::SessionChanged)
        ));
        assert!(matches!(service.content_state(), Err(VaultError::Locked)));
    }
}
