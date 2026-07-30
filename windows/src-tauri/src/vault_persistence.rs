//! SQLite adapter for the encrypted vault core.
//!
//! The crypto service deliberately depends on a narrow transaction trait. This
//! adapter is the only place that translates between that trait and the
//! Windows database schema; plaintext never crosses this boundary.

use std::sync::Arc;

use crate::{
    db::{self, DataError, Database},
    vault::{
        StoredVaultState, VaultEncryptedItem, VaultMetadataRecord as CryptoMetadata, VaultStore,
        VaultStoreError, validate_encrypted_items, validate_metadata_record,
    },
};

const MAX_STABLE_READ_ATTEMPTS: usize = 4;
const MAX_PERSISTED_VAULT_ITEMS: usize = 100_000;

#[derive(Clone)]
pub(crate) struct DatabaseVaultStore {
    database: Database,
}

impl DatabaseVaultStore {
    pub(crate) fn new(database: Database) -> Arc<Self> {
        Arc::new(Self { database })
    }
}

impl VaultStore for DatabaseVaultStore {
    fn load_state(&self) -> Result<StoredVaultState, VaultStoreError> {
        // Database read helpers use separate short-lived connections. Read the
        // metadata both before and after the item snapshot so a concurrent
        // mutation/rekey cannot produce a torn generation or revision.
        for _ in 0..MAX_STABLE_READ_ATTEMPTS {
            let before = self.database.get_vault_metadata().map_err(map_read_error)?;
            let items = self.database.list_vault_items().map_err(map_read_error)?;
            let after = self.database.get_vault_metadata().map_err(map_read_error)?;
            match assemble_read_attempt(before, after, items)? {
                Some(state) => return Ok(state),
                None => continue,
            }
        }
        Err(VaultStoreError::Unavailable)
    }

    fn initialize_if_empty(&self, metadata: CryptoMetadata) -> Result<(), VaultStoreError> {
        let timestamp = db::now_millis();
        self.database
            .initialize_vault(&metadata_to_database(&metadata, timestamp)?)
            .map_err(|error| match error {
                DataError::Validation(_) => VaultStoreError::AlreadyConfigured,
                other => map_read_error(other),
            })
    }

    fn insert_at_end(
        &self,
        expected_generation: &str,
        item: VaultEncryptedItem,
    ) -> Result<VaultEncryptedItem, VaultStoreError> {
        self.database
            .insert_vault_item(
                expected_generation,
                &item.ciphertext,
                &item.nonce,
                item.created_at,
            )
            .map(item_from_database)
            .map_err(map_mutation_error)
    }

    fn update_ciphertext(
        &self,
        expected_generation: &str,
        id: i64,
        ciphertext: Vec<u8>,
        nonce: Vec<u8>,
        updated_at: i64,
    ) -> Result<(), VaultStoreError> {
        self.database
            .update_vault_item(id, expected_generation, &ciphertext, &nonce, updated_at)
            .map(|_| ())
            .map_err(map_mutation_error)
    }

    fn delete_item(&self, expected_generation: &str, id: i64) -> Result<(), VaultStoreError> {
        self.database
            .delete_vault_item(id, expected_generation, db::now_millis())
            .map_err(map_mutation_error)
    }

    fn reorder_items(
        &self,
        expected_generation: &str,
        ordered_ids: &[i64],
    ) -> Result<(), VaultStoreError> {
        self.database
            .reorder_vault_items(ordered_ids, expected_generation, db::now_millis())
            .map_err(|error| match error {
                DataError::Validation(_) => VaultStoreError::InvalidOrder,
                other => map_mutation_error(other),
            })
    }

    fn replace_all_for_rekey(
        &self,
        expected_generation: &str,
        expected_revision: i64,
        metadata: CryptoMetadata,
        replacement_items: Vec<VaultEncryptedItem>,
    ) -> Result<(), VaultStoreError> {
        let timestamp = db::now_millis();
        let metadata = metadata_to_database(&metadata, timestamp)?;
        let replacement_items = replacement_items
            .into_iter()
            .map(|item| item_to_database(item, &metadata.generation_id))
            .collect::<Vec<_>>();
        match self.database.replace_vault_generation(
            expected_generation,
            expected_revision,
            &metadata,
            &replacement_items,
        ) {
            Ok(true) => Ok(()),
            Ok(false) => Err(VaultStoreError::RevisionMismatch),
            Err(DataError::Validation(_)) => Err(VaultStoreError::RevisionMismatch),
            Err(error) => Err(map_read_error(error)),
        }
    }
}

fn metadata_from_database(
    value: db::VaultMetadataRecord,
) -> Result<CryptoMetadata, VaultStoreError> {
    let metadata = CryptoMetadata {
        metadata_version: u32::try_from(value.crypto_version)
            .map_err(|_| VaultStoreError::Corrupt)?,
        salt: value.salt,
        verifier_ciphertext: value.verifier_ciphertext,
        verifier_nonce: value.verifier_nonce,
        kdf_iterations: u32::try_from(value.kdf_iterations)
            .map_err(|_| VaultStoreError::Corrupt)?,
        generation_id: value.generation_id,
        revision: value.revision,
    };
    validate_metadata_record(&metadata).map_err(|_| VaultStoreError::Corrupt)?;
    Ok(metadata)
}

fn assemble_stable_state(
    metadata: Option<db::VaultMetadataRecord>,
    items: Vec<db::VaultItemRecord>,
) -> Result<StoredVaultState, VaultStoreError> {
    if items.len() > MAX_PERSISTED_VAULT_ITEMS {
        return Err(VaultStoreError::Corrupt);
    }
    match &metadata {
        Some(metadata) => {
            if items
                .iter()
                .any(|item| item.generation_id != metadata.generation_id)
            {
                return Err(VaultStoreError::Corrupt);
            }
        }
        None if !items.is_empty() => return Err(VaultStoreError::Corrupt),
        None => {}
    }
    let state = StoredVaultState {
        metadata: metadata.map(metadata_from_database).transpose()?,
        items: items.into_iter().map(item_from_database).collect(),
    };
    validate_encrypted_items(&state).map_err(|_| VaultStoreError::Corrupt)?;
    Ok(state)
}

fn assemble_read_attempt(
    before: Option<db::VaultMetadataRecord>,
    after: Option<db::VaultMetadataRecord>,
    items: Vec<db::VaultItemRecord>,
) -> Result<Option<StoredVaultState>, VaultStoreError> {
    if before != after {
        return Ok(None);
    }
    assemble_stable_state(after, items).map(Some)
}

fn metadata_to_database(
    value: &CryptoMetadata,
    timestamp: i64,
) -> Result<db::VaultMetadataRecord, VaultStoreError> {
    Ok(db::VaultMetadataRecord {
        crypto_version: i64::from(value.metadata_version),
        generation_id: value.generation_id.clone(),
        revision: value.revision,
        salt: value.salt.clone(),
        kdf_iterations: i64::from(value.kdf_iterations),
        verifier_ciphertext: value.verifier_ciphertext.clone(),
        verifier_nonce: value.verifier_nonce.clone(),
        created_at: timestamp,
        updated_at: timestamp,
    })
}

fn item_from_database(value: db::VaultItemRecord) -> VaultEncryptedItem {
    VaultEncryptedItem {
        id: value.id,
        ciphertext: value.ciphertext,
        nonce: value.nonce,
        created_at: value.created_at,
        updated_at: value.updated_at,
        sort_order: value.sort_order,
    }
}

fn item_to_database(value: VaultEncryptedItem, generation_id: &str) -> db::VaultItemRecord {
    db::VaultItemRecord {
        id: value.id,
        generation_id: generation_id.to_owned(),
        ciphertext: value.ciphertext,
        nonce: value.nonce,
        created_at: value.created_at,
        updated_at: value.updated_at,
        sort_order: value.sort_order,
    }
}

fn map_read_error(error: DataError) -> VaultStoreError {
    match error {
        DataError::Validation(_) | DataError::Json(_) => VaultStoreError::Corrupt,
        DataError::NotFound => VaultStoreError::NotFound,
        DataError::Sqlite(_) | DataError::Io(_) | DataError::UnsupportedVersion => {
            VaultStoreError::Unavailable
        }
    }
}

fn map_mutation_error(error: DataError) -> VaultStoreError {
    match error {
        DataError::Validation(_) => VaultStoreError::GenerationMismatch,
        DataError::NotFound => VaultStoreError::NotFound,
        other => map_read_error(other),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vault::{
        CURRENT_METADATA_VERSION, DEFAULT_KDF_ITERATIONS, NONCE_BYTES, SALT_BYTES, VaultError,
        VaultPassword, VaultService,
    };

    const GENERATION: &str = "92f5f07a-7cc5-43d8-ae0f-95352029c7aa";
    const OTHER_GENERATION: &str = "8c7589f2-98af-4500-987e-679d9ed8c0a5";

    fn metadata(generation_id: &str, revision: i64) -> db::VaultMetadataRecord {
        db::VaultMetadataRecord {
            crypto_version: i64::from(CURRENT_METADATA_VERSION),
            generation_id: generation_id.to_owned(),
            revision,
            salt: vec![7; SALT_BYTES],
            kdf_iterations: i64::from(DEFAULT_KDF_ITERATIONS),
            verifier_ciphertext: vec![9; 16],
            verifier_nonce: vec![1; NONCE_BYTES],
            created_at: 1,
            updated_at: revision.saturating_add(1),
        }
    }

    fn item(generation_id: &str) -> db::VaultItemRecord {
        db::VaultItemRecord {
            id: 1,
            generation_id: generation_id.to_owned(),
            ciphertext: vec![8; 16],
            nonce: vec![2; NONCE_BYTES],
            created_at: 1,
            updated_at: 1,
            sort_order: 0,
        }
    }

    #[test]
    fn stable_read_attempt_retries_when_revision_changes() {
        let result = assemble_read_attempt(
            Some(metadata(GENERATION, 2)),
            Some(metadata(GENERATION, 3)),
            vec![item(GENERATION)],
        );
        assert!(matches!(result, Ok(None)));
    }

    #[test]
    fn stable_state_rejects_mixed_generations() {
        let result =
            assemble_stable_state(Some(metadata(GENERATION, 2)), vec![item(OTHER_GENERATION)]);
        assert!(matches!(result, Err(VaultStoreError::Corrupt)));
    }

    #[test]
    fn stable_state_applies_strict_crypto_validation() {
        let mut weak = metadata(GENERATION, 2);
        weak.kdf_iterations = 9_999;
        assert!(matches!(
            assemble_stable_state(Some(weak), Vec::new()),
            Err(VaultStoreError::Corrupt)
        ));

        let state = assemble_stable_state(Some(metadata(GENERATION, 2)), vec![item(GENERATION)]);
        assert!(state.is_ok());
        if let Ok(state) = state {
            assert_eq!(state.items.len(), 1);
            assert_eq!(
                state.metadata.as_ref().map(|metadata| metadata.revision),
                Some(2)
            );
        }
    }

    #[test]
    fn sqlite_adapter_round_trips_crud_and_atomic_rekey() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let database =
            Database::open(directory.path().join("vault.db")).expect("open test database");
        let service = VaultService::new(DatabaseVaultStore::new(database));
        let old_password = VaultPassword::new("old password".to_owned());
        let new_password = VaultPassword::new("new password".to_owned());

        service
            .setup_password(&old_password)
            .expect("initialize vault");
        service
            .add_item("secret\nwith escapes \\", Some("private note"), 1)
            .expect("insert encrypted item");
        service
            .change_password(&old_password, &new_password)
            .expect("transactional rekey");
        service.lock();
        assert!(matches!(
            service.unlock(&old_password),
            Err(VaultError::WrongPassword)
        ));
        service.unlock(&new_password).expect("unlock after rekey");
        let content = service.content_state().expect("decrypt after rekey");
        assert_eq!(content.corrupted_item_count, 0);
        assert_eq!(content.items.len(), 1);
        assert_eq!(content.items[0].content, "secret\nwith escapes \\");
        assert_eq!(content.items[0].note.as_deref(), Some("private note"));
    }
}
