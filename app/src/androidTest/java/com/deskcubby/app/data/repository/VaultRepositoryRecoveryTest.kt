package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.VaultItemDao
import com.deskcubby.app.data.local.VaultItemEntity
import com.deskcubby.app.data.vault.VaultCrypto
import com.deskcubby.app.ui.vault.VaultUiError
import com.deskcubby.app.ui.vault.VaultViewModel
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultRepositoryRecoveryTest {
    @Test
    fun crashAfterJournalRestartsWithOldPasswordAndRollsBack() = runBlocking {
        assertInjectedCrashRecovers(
            faultPoint = VaultRekeyFaultPoint.AFTER_JOURNAL_WRITTEN,
            recoveryPassword = OLD_PASSWORD,
        )
    }

    @Test
    fun crashAfterRoomTransactionRestartsWithNewPasswordAndCompletes() = runBlocking {
        assertInjectedCrashRecovers(
            faultPoint = VaultRekeyFaultPoint.AFTER_ROWS_REPLACED,
            recoveryPassword = NEW_PASSWORD,
        )
    }

    @Test
    fun crashAfterMetadataCommitKeepsStableNewPassword() = runBlocking {
        assertInjectedCrashRecovers(
            faultPoint = VaultRekeyFaultPoint.AFTER_METADATA_COMMITTED,
            recoveryPassword = NEW_PASSWORD,
        )
    }

    @Test
    fun wrongGenerationAttemptNeverClearsPendingRecoveryMetadata() = runBlocking {
        val oldGeneration = generation(OLD_PASSWORD)
        val newGeneration = generation(NEW_PASSWORD, generationId = "new-generation")
        val row = encryptedEntity(newGeneration.key, "new ciphertext")
        val marker = encryptedMarker(newGeneration)
        val store = FakeVaultMetadataStore(
            initial = VaultMetadata(
                active = oldGeneration.metadata,
                pending = newGeneration.metadata,
            ),
        )
        val dao = FakeVaultItemDao(listOf(row, marker))
        val repository = VaultRepository(dao, store)

        assertFalse(repository.unlock(OLD_PASSWORD))
        assertNotNull(store.snapshot?.pending)

        assertTrue(repository.unlock(NEW_PASSWORD))
        assertNull(store.snapshot?.pending)
        assertMetadataEquals(newGeneration.metadata, store.snapshot?.active)
        assertEquals("new ciphertext", repository.contentState.first().items.single().content)
    }

    @Test
    fun legacyEmptyOldPasswordCanUnlockAndChangeToNonEmptyPassword() = runBlocking {
        val legacyGeneration = generation("")
        val dao = FakeVaultItemDao(
            listOf(encryptedEntity(legacyGeneration.key, "legacy empty password")),
        )
        val store = FakeVaultMetadataStore(
            initial = VaultMetadata(active = legacyGeneration.metadata, pending = null),
        )
        val repository = VaultRepository(dao, store)

        assertTrue(repository.unlock(""))
        assertEquals(
            VaultPasswordChangeResult.SUCCESS,
            repository.changePassword("", NEW_PASSWORD),
        )
        repository.lock()
        assertTrue(repository.unlock(NEW_PASSWORD))
        assertEquals(
            "legacy empty password",
            repository.contentState.first().items.single().content,
        )
    }

    @Test
    fun corruptRowIsCountedAndAbortsRekeyWithoutTouchingRowsOrMetadata() = runBlocking {
        val generation = generation(OLD_PASSWORD)
        val validRow = encryptedEntity(generation.key, "readable")
        val corruptRow = VaultItemEntity(
            id = 2,
            cipherText = "not-base64",
            iv = "not-base64",
            createdAt = 2,
            updatedAt = 2,
        )
        val originalRows = listOf(validRow, corruptRow)
        val dao = FakeVaultItemDao(originalRows)
        val store = FakeVaultMetadataStore(
            initial = VaultMetadata(active = generation.metadata, pending = null),
        )
        val repository = VaultRepository(dao, store)

        assertTrue(repository.unlock(OLD_PASSWORD))
        val content = repository.contentState.first()
        assertEquals(listOf("readable"), content.items.map(VaultItem::content))
        assertEquals(1, content.corruptedItemCount)

        assertEquals(
            VaultPasswordChangeResult.CORRUPTED_ITEMS,
            repository.changePassword(OLD_PASSWORD, NEW_PASSWORD),
        )
        assertEquals(originalRows, dao.getAll())
        assertNull(store.snapshot?.pending)
        assertMetadataEquals(generation.metadata, store.snapshot?.active)
    }

    @Test
    fun decryptableButMalformedPayloadAlsoAbortsWithoutRewritingTheRow() = runBlocking {
        val generation = generation(OLD_PASSWORD)
        val encrypted = VaultCrypto.encrypt(generation.key, "not-a-vault-payload")
        val malformedRow = VaultItemEntity(
            id = 1,
            cipherText = encrypted.cipherBase64,
            iv = encrypted.ivBase64,
            createdAt = 1,
            updatedAt = 1,
        )
        val dao = FakeVaultItemDao(listOf(malformedRow))
        val store = FakeVaultMetadataStore(
            initial = VaultMetadata(active = generation.metadata, pending = null),
        )
        val repository = VaultRepository(dao, store)

        assertTrue(repository.unlock(OLD_PASSWORD))
        assertEquals(1, repository.contentState.first().corruptedItemCount)
        assertEquals(
            VaultPasswordChangeResult.CORRUPTED_ITEMS,
            repository.changePassword(OLD_PASSWORD, NEW_PASSWORD),
        )
        assertEquals(listOf(malformedRow), dao.getAll())
        assertNull(store.snapshot?.pending)
    }

    @Test
    fun crudWaitsForRekeyMutexAndUsesTheCommittedGeneration() = runBlocking {
        val store = FakeVaultMetadataStore()
        val dao = FakeVaultItemDao()
        val repository = VaultRepository(dao, store)
        assertTrue(repository.setupPassword(OLD_PASSWORD))
        assertTrue(repository.addItem("before", null))

        val atJournal = CompletableDeferred<Unit>()
        val continueRekey = CompletableDeferred<Unit>()
        repository.rekeyFaultInjector = VaultRekeyFaultInjector { point ->
            if (point == VaultRekeyFaultPoint.AFTER_JOURNAL_WRITTEN) {
                atJournal.complete(Unit)
                continueRekey.await()
            }
        }

        val rekey = async {
            repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
        }
        atJournal.await()

        val addCallStarted = CompletableDeferred<Unit>()
        val insertsBeforeConcurrentCall = dao.insertCallCount
        val concurrentAdd = async {
            addCallStarted.complete(Unit)
            repository.addItem("after", "new generation")
        }
        addCallStarted.await()
        yield()
        assertEquals(insertsBeforeConcurrentCall, dao.insertCallCount)

        continueRekey.complete(Unit)
        assertEquals(VaultPasswordChangeResult.SUCCESS, rekey.await())
        assertTrue(concurrentAdd.await())
        assertEquals(insertsBeforeConcurrentCall + 1, dao.insertCallCount)

        repository.lock()
        assertTrue(repository.unlock(NEW_PASSWORD))
        assertEquals(
            setOf("before", "after"),
            repository.contentState.first().items.map(VaultItem::content).toSet(),
        )
    }

    @Test
    fun firstSetupPersistenceFailureReachesViewModelErrorState() = runBlocking {
        val store = object : FakeVaultMetadataStore() {
            override suspend fun writeStable(active: VaultKeyMetadata) {
                throw IllegalStateException()
            }
        }
        val viewModel = VaultViewModel(VaultRepository(FakeVaultItemDao(), store))

        viewModel.setupPassword("new")

        val error = withTimeout(5_000) {
            viewModel.error.first { it != null }
        }
        assertEquals(VaultUiError.OPERATION_FAILED, error)
        assertNull(store.snapshot)
    }

    private suspend fun assertInjectedCrashRecovers(
        faultPoint: VaultRekeyFaultPoint,
        recoveryPassword: String,
    ) {
        val store = FakeVaultMetadataStore()
        val dao = FakeVaultItemDao()
        val repository = VaultRepository(dao, store)
        assertTrue(repository.setupPassword(OLD_PASSWORD))
        assertTrue(repository.addItem("survives restart", "note"))
        repository.rekeyFaultInjector = VaultRekeyFaultInjector { point ->
            if (point == faultPoint) throw InjectedCrash()
        }

        try {
            repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
            fail("Expected injected crash at $faultPoint")
        } catch (_: InjectedCrash) {
            // Simulate process restart below with the same durable stores.
        }
        if (faultPoint == VaultRekeyFaultPoint.AFTER_METADATA_COMMITTED) {
            assertNull(store.snapshot?.pending)
        } else {
            assertNotNull(store.snapshot?.pending)
        }

        val restarted = VaultRepository(dao, store)
        assertTrue(restarted.unlock(recoveryPassword))
        assertNull(store.snapshot?.pending)
        assertEquals(
            "survives restart",
            restarted.contentState.first().items.single().content,
        )

        restarted.lock()
        assertTrue(restarted.unlock(recoveryPassword))
        val obsoletePassword = if (recoveryPassword == OLD_PASSWORD) NEW_PASSWORD else OLD_PASSWORD
        restarted.lock()
        assertFalse(restarted.unlock(obsoletePassword))
    }

    private fun generation(
        password: String,
        generationId: String? = null,
    ): TestGeneration {
        val salt = VaultCrypto.generateSalt()
        val key = VaultCrypto.deriveKey(password, salt, TEST_ITERATIONS)
        val verifier = VaultCrypto.encrypt(key, VERIFIER_PLAINTEXT)
        return TestGeneration(
            metadata = VaultKeyMetadata(
                salt = salt,
                verifierCipher = verifier.cipherBase64,
                verifierIv = verifier.ivBase64,
                iterations = TEST_ITERATIONS,
                generationId = generationId,
            ),
            key = key,
        )
    }

    private fun encryptedEntity(
        key: SecretKey,
        content: String,
    ): VaultItemEntity {
        val encrypted = VaultCrypto.encrypt(key, encodeVaultItemPayload(content, null))
        return VaultItemEntity(
            id = 1,
            cipherText = encrypted.cipherBase64,
            iv = encrypted.ivBase64,
            createdAt = 1,
            updatedAt = 1,
        )
    }

    private fun encryptedMarker(generation: TestGeneration): VaultItemEntity {
        val generationId = checkNotNull(generation.metadata.generationId)
        val encrypted = VaultCrypto.encrypt(
            generation.key,
            vaultKeyMarkerPlaintext(generationId),
        )
        return VaultItemEntity(
            id = VAULT_KEY_MARKER_ENTITY_ID,
            cipherText = encrypted.cipherBase64,
            iv = encrypted.ivBase64,
            createdAt = 0,
            updatedAt = 0,
        )
    }

    private fun assertMetadataEquals(
        expected: VaultKeyMetadata,
        actual: VaultKeyMetadata?,
    ) {
        assertNotNull(actual)
        assertTrue(expected.salt.contentEquals(actual!!.salt))
        assertEquals(expected.verifierCipher, actual.verifierCipher)
        assertEquals(expected.verifierIv, actual.verifierIv)
        assertEquals(expected.iterations, actual.iterations)
        assertEquals(expected.generationId, actual.generationId)
    }

    private data class TestGeneration(
        val metadata: VaultKeyMetadata,
        val key: SecretKey,
    )

    private class InjectedCrash : RuntimeException()

    private open class FakeVaultMetadataStore(
        initial: VaultMetadata? = null,
    ) : VaultMetadataStore {
        var snapshot: VaultMetadata? = initial
            private set

        override suspend fun read(): VaultMetadataReadResult =
            VaultMetadataReadResult(
                metadata = snapshot,
                hasStoredMetadata = snapshot != null,
            )

        override suspend fun writePrepared(
            active: VaultKeyMetadata,
            pending: VaultKeyMetadata,
        ) {
            snapshot = VaultMetadata(active = active, pending = pending)
        }

        override suspend fun writeStable(active: VaultKeyMetadata) {
            snapshot = VaultMetadata(active = active, pending = null)
        }
    }

    private class FakeVaultItemDao(
        initialRows: List<VaultItemEntity> = emptyList(),
    ) : VaultItemDao {
        private val rows = MutableStateFlow(initialRows)
        private var nextId = (
            initialRows.asSequence().map(VaultItemEntity::id).filter { it > 0L }.maxOrNull()
                ?: 0L
            ) + 1L

        @Volatile
        var insertCallCount: Int = 0
            private set

        override fun observeAll(): Flow<List<VaultItemEntity>> = rows

        override suspend fun getAll(): List<VaultItemEntity> = rows.value.sortedBy { it.id }

        override suspend fun getById(id: Long): VaultItemEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun insert(item: VaultItemEntity): Long {
            insertCallCount += 1
            val stored = if (item.id == 0L) item.copy(id = nextId++) else item
            rows.value = rows.value.filterNot { it.id == stored.id } + stored
            return stored.id
        }

        override suspend fun update(
            id: Long,
            cipherText: String,
            iv: String,
            updatedAt: Long,
        ): Int {
            var updated = false
            rows.value = rows.value.map { item ->
                if (item.id == id) {
                    updated = true
                    item.copy(cipherText = cipherText, iv = iv, updatedAt = updatedAt)
                } else {
                    item
                }
            }
            return if (updated) 1 else 0
        }

        override suspend fun delete(id: Long): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.id == id }
            return before - rows.value.size
        }

        override suspend fun insertAll(items: List<VaultItemEntity>) {
            val byId = rows.value.associateBy(VaultItemEntity::id).toMutableMap()
            items.forEach { byId[it.id] = it }
            rows.value = byId.values.toList()
        }

        override suspend fun clearAll() {
            rows.value = emptyList()
        }

        override suspend fun replaceAll(items: List<VaultItemEntity>) {
            rows.value = items
        }
    }

    private companion object {
        const val OLD_PASSWORD = "old"
        const val NEW_PASSWORD = "new"
        const val TEST_ITERATIONS = 1_000
        const val VERIFIER_PLAINTEXT = "deskcubby-vault-verifier"
    }
}
