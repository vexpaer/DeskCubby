package com.deskcubby.app.plugin.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.plugin.api.core.api.ContentVersion
import com.deskcubby.plugin.api.core.api.DiaryAPI
import com.deskcubby.plugin.api.core.api.DiaryDocument
import com.deskcubby.plugin.api.core.api.DiaryEntry
import com.deskcubby.plugin.api.core.api.DiaryTrashDocument
import com.deskcubby.plugin.api.core.api.FileMutationOperation
import com.deskcubby.plugin.api.core.api.FileMutationRequest
import com.deskcubby.plugin.api.core.api.VaultAPI
import com.deskcubby.plugin.api.core.api.VaultDocument
import com.deskcubby.plugin.api.core.api.VaultEntry
import com.deskcubby.plugin.api.core.api.VaultFolder
import com.deskcubby.plugin.api.core.api.VaultFolderSnapshot
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileApiAdapterTest {
    @Test
    fun updateUndoRestoresExactPreviousFileContent() = runBlocking {
        val diary = FakeDiaryApi("diary-1", "2026-08-13.md", "before")
        val api = FileApiAdapter(
            diary,
            UnsupportedVaultApi(),
            SettingsRepository(ApplicationProvider.getApplicationContext()),
        )

        val plan = api.prepareMutation(
            FileMutationRequest(
                operation = FileMutationOperation.UPDATE,
                rootId = "diary",
                fileId = "diary-1",
                content = "after",
            ),
        )
        val applied = api.commitMutation(plan.planToken)
        assertEquals("after", diary.load("diary-1").markdown)
        assertNotNull(applied.undoToken)

        val undone = api.undoMutation(requireNotNull(applied.undoToken))

        assertEquals("before", diary.load("diary-1").markdown)
        assertEquals("before", undone.after?.content)
    }

    @Test
    fun undoRefusesToOverwriteExternalFileChange() = runBlocking {
        val diary = FakeDiaryApi("diary-1", "2026-08-13.md", "before")
        val api = FileApiAdapter(
            diary,
            UnsupportedVaultApi(),
            SettingsRepository(ApplicationProvider.getApplicationContext()),
        )
        val plan = api.prepareMutation(
            FileMutationRequest(
                FileMutationOperation.UPDATE,
                "diary",
                fileId = "diary-1",
                content = "agent edit",
            ),
        )
        val applied = api.commitMutation(plan.planToken)
        diary.externalWrite("diary-1", "external edit")

        val error = runCatching { api.undoMutation(requireNotNull(applied.undoToken)) }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("external edit", diary.load("diary-1").markdown)
    }

    @Test
    fun failedCreateCleansUpPartiallyCreatedSafDocument() = runBlocking {
        val diary = FakeDiaryApi()
        diary.failNextSave = true
        val api = FileApiAdapter(
            diary,
            UnsupportedVaultApi(),
            SettingsRepository(ApplicationProvider.getApplicationContext()),
        )
        val plan = api.prepareMutation(
            FileMutationRequest(
                FileMutationOperation.CREATE,
                "diary",
                name = "new-entry.md",
                content = "content",
            ),
        )

        val error = runCatching { api.commitMutation(plan.planToken) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(diary.documents.isEmpty())
        assertEquals(1, diary.trashCalls)
    }

    private class FakeDiaryApi(
        id: String? = null,
        name: String = "",
        content: String = "",
    ) : DiaryAPI {
        val documents = linkedMapOf<String, DiaryDocument>()
        var failNextSave = false
        var trashCalls = 0
        private var revision = 1L
        private var created = 0

        init {
            if (id != null) documents[id] = document(id, name, content)
        }

        override suspend fun list(): List<DiaryEntry> = documents.values.map { value ->
            DiaryEntry(
                value.documentId,
                value.name,
                value.name.substringBeforeLast('.'),
                "2026-08-13",
                "2026-08",
                value.version.lastModifiedMillis,
                value.version.size,
                value.markdown.length,
            )
        }

        override suspend fun load(documentId: String): DiaryDocument =
            checkNotNull(documents[documentId])

        override suspend fun create(title: String, dateIso: String?): DiaryDocument {
            val id = "created-${++created}"
            return document(id, "$title.md", "").also { documents[id] = it }
        }

        override suspend fun openToday(dateIso: String?): DiaryDocument = create("today", dateIso)

        override suspend fun save(
            documentId: String,
            markdown: String,
            expectedVersion: ContentVersion,
            force: Boolean,
        ): DiaryDocument {
            if (failNextSave) {
                failNextSave = false
                throw IOException("simulated SAF failure")
            }
            val current = load(documentId)
            check(force || current.version.sha256 == expectedVersion.sha256)
            return document(documentId, current.name, markdown).also { documents[documentId] = it }
        }

        override suspend fun rename(documentId: String, newFileName: String): DiaryDocument {
            val current = load(documentId)
            return current.copy(name = newFileName).also { documents[documentId] = it }
        }

        override suspend fun moveToTrash(documentId: String): Boolean =
            documents.remove(documentId) != null

        override suspend fun moveToTrashDocument(documentId: String): DiaryTrashDocument {
            val removed = checkNotNull(documents.remove(documentId))
            trashCalls += 1
            return DiaryTrashDocument("trash-$documentId", removed.name, revision++)
        }

        override suspend fun restoreFromTrash(trashDocumentId: String): DiaryDocument =
            error("not used")

        override suspend fun appendToToday(markdown: String, dateIso: String?): DiaryDocument =
            error("not used")

        fun externalWrite(documentId: String, content: String) {
            val current = checkNotNull(documents[documentId])
            documents[documentId] = document(documentId, current.name, content)
        }

        private fun document(id: String, name: String, content: String): DiaryDocument =
            DiaryDocument(
                id,
                name,
                content,
                ContentVersion("sha:${content}:${revision}", content.toByteArray().size.toLong(), revision++),
            )
    }

    private class UnsupportedVaultApi : VaultAPI {
        override suspend fun browse(folder: VaultFolder?): VaultFolderSnapshot = error("not used")
        override suspend fun createFolder(parent: VaultFolder?, name: String): VaultEntry = error("not used")
        override suspend fun createMarkdown(parent: VaultFolder?, name: String): VaultDocument = error("not used")
        override suspend fun load(entry: VaultEntry): VaultDocument = error("not used")
        override suspend fun save(document: VaultDocument, markdown: String, force: Boolean): VaultDocument = error("not used")
        override suspend fun saveConflictCopy(document: VaultDocument, markdown: String): VaultDocument = error("not used")
        override suspend fun rename(entry: VaultEntry, newName: String): VaultEntry = error("not used")
        override suspend fun delete(entry: VaultEntry) = error("not used")
    }
}
