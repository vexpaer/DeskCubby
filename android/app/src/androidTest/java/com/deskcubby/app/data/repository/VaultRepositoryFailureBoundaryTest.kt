package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.VaultItemDao
import com.deskcubby.app.data.local.VaultItemEntity
import com.deskcubby.app.data.vault.VaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultRepositoryFailureBoundaryTest {
    @Test
    fun metadataReadFailureDuringConstructionKeepsVaultLocked() = runBlocking {
        val store = FakeVaultMetadataStore().apply {
            nextReadFailure = true
        }

        val repository = VaultRepository(FakeVaultItemDao(), store)
        delay(100)

        assertEquals(VaultLockState.LOCKED, repository.lockState.value)
    }

    @Test
    fun preparedMetadataWriteFailureLeavesOldPasswordAndRowsUntouched() = runBlocking {
        val fixture = seededVault()
        fixture.store.nextPreparedFailure = FailureMode.BEFORE_COMMIT

        assertNotNull(
            runCatching {
                fixture.repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
            }.exceptionOrNull(),
        )

        val restarted = VaultRepository(fixture.dao, fixture.store)
        assertTrue(restarted.unlock(OLD_PASSWORD))
        assertFalse(restarted.unlock(NEW_PASSWORD))
        assertEquals(
            listOf("first", "second"),
            restarted.items.first().map(VaultItem::content).sorted(),
        )
        assertNull(fixture.store.metadata?.pending)
    }

    @Test
    fun roomFailureBeforeCommitRollsPreparedMetadataBackOnOldUnlock() = runBlocking {
        val fixture = seededVault()
        fixture.dao.nextReplaceFailure = FailureMode.BEFORE_COMMIT

        assertNotNull(
            runCatching {
                fixture.repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
            }.exceptionOrNull(),
        )
        assertNotNull(fixture.store.metadata?.pending)

        val restarted = VaultRepository(fixture.dao, fixture.store)
        assertTrue(restarted.unlock(OLD_PASSWORD))
        assertNull(fixture.store.metadata?.pending)
        assertEquals(
            listOf("first", "second"),
            restarted.items.first().map(VaultItem::content).sorted(),
        )
    }

    @Test
    fun roomFailureAfterAtomicCommitIsRecoverableOnlyWithNewPassword() = runBlocking {
        val fixture = seededVault(includeLegacyPayload = true)
        fixture.dao.nextReplaceFailure = FailureMode.AFTER_COMMIT

        assertNotNull(
            runCatching {
                fixture.repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
            }.exceptionOrNull(),
        )
        assertNotNull(fixture.store.metadata?.pending)

        val restarted = VaultRepository(fixture.dao, fixture.store)
        assertTrue(restarted.unlock(NEW_PASSWORD))
        assertNull(fixture.store.metadata?.pending)
        assertEquals(
            listOf("first", "second", "旧正文"),
            restarted.items.first().map(VaultItem::content).sorted(),
        )
        assertEquals(
            "旧标题",
            restarted.items.first().first { it.content == "旧正文" }.note,
        )

        val oldPasswordAttempt = VaultRepository(fixture.dao, fixture.store)
        assertFalse(oldPasswordAttempt.unlock(OLD_PASSWORD))
    }

    @Test
    fun finalMetadataFailureBeforeCommitIsCompletedByNewPasswordUnlock() = runBlocking {
        val fixture = seededVault()
        fixture.store.nextStableFailure = FailureMode.BEFORE_COMMIT

        assertNotNull(
            runCatching {
                fixture.repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
            }.exceptionOrNull(),
        )
        assertNotNull(fixture.store.metadata?.pending)

        val restarted = VaultRepository(fixture.dao, fixture.store)
        assertTrue(restarted.unlock(NEW_PASSWORD))
        assertNull(fixture.store.metadata?.pending)
        assertEquals(
            listOf("first", "second"),
            restarted.items.first().map(VaultItem::content).sorted(),
        )
    }

    @Test
    fun finalMetadataFailureAfterCommitIsAlreadyAValidStableNewVault() = runBlocking {
        val fixture = seededVault()
        fixture.store.nextStableFailure = FailureMode.AFTER_COMMIT

        assertNotNull(
            runCatching {
                fixture.repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
            }.exceptionOrNull(),
        )

        assertNull(fixture.store.metadata?.pending)
        val restarted = VaultRepository(fixture.dao, fixture.store)
        assertTrue(restarted.unlock(NEW_PASSWORD))
        assertEquals(
            listOf("first", "second"),
            restarted.items.first().map(VaultItem::content).sorted(),
        )
    }

    @Test
    fun rowMutationsWaitForPasswordChangeAndThenUseTheNewKey() = runBlocking {
        val fixture = seededVault()
        fixture.dao.replaceEntered = CompletableDeferred()
        fixture.dao.releaseReplace = CompletableDeferred()

        val passwordChange = async(Dispatchers.Default) {
            fixture.repository.changePassword(OLD_PASSWORD, NEW_PASSWORD)
        }
        fixture.dao.replaceEntered?.await()
        val lateAdd = async(Dispatchers.Default) {
            fixture.repository.addItem("after-change", "new-key")
        }
        delay(50)
        assertFalse(lateAdd.isCompleted)

        fixture.dao.releaseReplace?.complete(Unit)
        assertEquals(VaultPasswordChangeResult.SUCCESS, passwordChange.await())
        assertTrue(lateAdd.await())

        fixture.repository.lock()
        assertTrue(fixture.repository.unlock(NEW_PASSWORD))
        assertEquals(
            listOf("after-change", "first", "second"),
            fixture.repository.items.first().map(VaultItem::content).sorted(),
        )
    }

    private suspend fun seededVault(includeLegacyPayload: Boolean = false): Fixture {
        val dao = FakeVaultItemDao()
        val store = FakeVaultMetadataStore()
        val repository = VaultRepository(dao, store)
        repository.setupPassword(OLD_PASSWORD)
        assertTrue(repository.addItem("first", "one"))
        assertTrue(repository.addItem("second", null))
        if (includeLegacyPayload) {
            val active = checkNotNull(store.metadata?.active)
            val oldKey = VaultCrypto.deriveKey(
                OLD_PASSWORD,
                active.salt,
                active.iterations,
            )
            val legacy = VaultCrypto.encrypt(
                oldKey,
                """{"title":"旧标题","content":"旧正文"}""",
            )
            dao.insert(
                VaultItemEntity(
                    cipherText = legacy.cipherBase64,
                    iv = legacy.ivBase64,
                    createdAt = 3L,
                    updatedAt = 3L,
                ),
            )
        }
        return Fixture(repository, dao, store)
    }

    private data class Fixture(
        val repository: VaultRepository,
        val dao: FakeVaultItemDao,
        val store: FakeVaultMetadataStore,
    )

    private enum class FailureMode { BEFORE_COMMIT, AFTER_COMMIT }

    private class FakeVaultMetadataStore : VaultMetadataStore {
        @Volatile
        var metadata: VaultMetadata? = null

        @Volatile
        var nextPreparedFailure: FailureMode? = null

        @Volatile
        var nextStableFailure: FailureMode? = null

        @Volatile
        var nextReadFailure: Boolean = false

        override suspend fun read(): VaultMetadataReadResult {
            if (nextReadFailure) {
                nextReadFailure = false
                error("injected metadata read failure")
            }
            return VaultMetadataReadResult(
                metadata = metadata,
                hasStoredMetadata = metadata != null,
            )
        }

        override suspend fun writePrepared(
            active: VaultKeyMetadata,
            pending: VaultKeyMetadata,
        ) {
            val failure = nextPreparedFailure.also { nextPreparedFailure = null }
            if (failure == FailureMode.BEFORE_COMMIT) error("injected prepared write failure")
            metadata = VaultMetadata(active = active, pending = pending)
            if (failure == FailureMode.AFTER_COMMIT) error("injected prepared write failure")
        }

        override suspend fun writeStable(active: VaultKeyMetadata) {
            val failure = nextStableFailure.also { nextStableFailure = null }
            if (failure == FailureMode.BEFORE_COMMIT) error("injected stable write failure")
            metadata = VaultMetadata(active = active, pending = null)
            if (failure == FailureMode.AFTER_COMMIT) error("injected stable write failure")
        }
    }

    private class FakeVaultItemDao : VaultItemDao {
        private val rows = MutableStateFlow<List<VaultItemEntity>>(emptyList())

        @Volatile
        var nextReplaceFailure: FailureMode? = null

        var replaceEntered: CompletableDeferred<Unit>? = null
        var releaseReplace: CompletableDeferred<Unit>? = null

        override fun observeAll(): Flow<List<VaultItemEntity>> = rows

        override suspend fun getAll(): List<VaultItemEntity> =
            rows.value.sortedBy(VaultItemEntity::id)

        override suspend fun getById(id: Long): VaultItemEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun insert(item: VaultItemEntity): Long {
            val id = if (item.id == 0L) {
                (rows.value.asSequence().map(VaultItemEntity::id).filter { it > 0L }.maxOrNull()
                    ?: 0L) + 1L
            } else {
                item.id
            }
            rows.value = rows.value.filterNot { it.id == id } + item.copy(id = id)
            return id
        }

        override suspend fun nextSortOrder(): Long =
            (rows.value.maxOfOrNull(VaultItemEntity::sortOrder) ?: -1L) + 1L

        override suspend fun getUserIdsInOrder(excludedId: Long): List<Long> =
            rows.value
                .filterNot { it.id == excludedId }
                .sortedWith(compareBy(VaultItemEntity::sortOrder, VaultItemEntity::id))
                .map(VaultItemEntity::id)

        override suspend fun updateSortOrder(id: Long, sortOrder: Long) {
            rows.value = rows.value.map { item ->
                if (item.id == id) item.copy(sortOrder = sortOrder) else item
            }
        }

        override suspend fun update(
            id: Long,
            cipherText: String,
            iv: String,
            updatedAt: Long,
        ): Int {
            var changed = false
            rows.value = rows.value.map {
                if (it.id == id) {
                    changed = true
                    it.copy(cipherText = cipherText, iv = iv, updatedAt = updatedAt)
                } else {
                    it
                }
            }
            return if (changed) 1 else 0
        }

        override suspend fun delete(id: Long): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.id == id }
            return if (rows.value.size != before) 1 else 0
        }

        override suspend fun insertAll(items: List<VaultItemEntity>) {
            val replacements = items.associateBy(VaultItemEntity::id)
            rows.value = rows.value.filterNot { it.id in replacements } + items
        }

        override suspend fun clearAll() {
            rows.value = emptyList()
        }

        override suspend fun replaceAll(items: List<VaultItemEntity>) {
            replaceEntered?.complete(Unit)
            releaseReplace?.await()
            val failure = nextReplaceFailure.also { nextReplaceFailure = null }
            if (failure == FailureMode.BEFORE_COMMIT) error("injected Room failure")
            rows.value = items.toList()
            if (failure == FailureMode.AFTER_COMMIT) error("injected Room failure")
        }
    }

    private companion object {
        const val OLD_PASSWORD = "old-password"
        const val NEW_PASSWORD = "new-password"
    }
}
