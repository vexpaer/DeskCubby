package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultDiaryFolderSetupTest {
    @Test
    fun createsConventionalNestedDirectories() {
        val root = FakeDirectory("Documents")

        val result = ensureDefaultDiaryDirectories(root)

        val appRoot = root.child(DEFAULT_APP_FOLDER_NAME)
        assertSame(appRoot.child(DEFAULT_DIARY_FOLDER_NAME), result.diary)
        assertSame(appRoot.child(DEFAULT_MEDIA_FOLDER_NAME), result.media)
    }

    @Test
    fun reusesExistingDirectoriesWithoutChangingTheirCase() {
        val diary = FakeDirectory("Diary")
        val media = FakeDirectory("MEDIA")
        val appRoot = FakeDirectory("DeskCubby", children = mutableListOf(diary, media))
        val root = FakeDirectory("Documents", children = mutableListOf(appRoot))

        val result = ensureDefaultDiaryDirectories(root)

        assertSame(diary, result.diary)
        assertSame(media, result.media)
        assertEquals(0, root.createCalls)
        assertEquals(0, appRoot.createCalls)
    }

    @Test
    fun refusesToOverwriteSameNameFile() {
        val conflictingFile = FakeDirectory(DEFAULT_APP_FOLDER_NAME, isDirectory = false)
        val root = FakeDirectory("Documents", children = mutableListOf(conflictingFile))

        assertThrows(DefaultDiaryFolderSetupException::class.java) {
            ensureDefaultDiaryDirectories(root)
        }
        assertEquals(0, root.createCalls)
    }

    @Test
    fun reportsFailureWhenProviderCannotCreateEveryDirectory() {
        val appRoot = FakeDirectory(DEFAULT_APP_FOLDER_NAME)
        appRoot.failNames += DEFAULT_MEDIA_FOLDER_NAME
        val root = FakeDirectory("Documents", children = mutableListOf(appRoot))

        assertThrows(DefaultDiaryFolderSetupException::class.java) {
            ensureDefaultDiaryDirectories(root)
        }
        assertEquals(listOf(DEFAULT_DIARY_FOLDER_NAME), appRoot.children.mapNotNull { it.name })
    }

    @Test
    fun refusesProviderRenamedCreatedDirectory() {
        val root = FakeDirectory("Documents")
        root.createdNames[DEFAULT_APP_FOLDER_NAME] = "$DEFAULT_APP_FOLDER_NAME (1)"

        assertThrows(DefaultDiaryFolderSetupException::class.java) {
            ensureDefaultDiaryDirectories(root)
        }

        assertEquals(listOf("$DEFAULT_APP_FOLDER_NAME (1)"), root.children.mapNotNull { it.name })
        assertEquals(1, root.createCalls)
    }

    @Test
    fun releasesOnlyPermissionBitsAcquiredByFailedAttempt() {
        assertEquals(
            DefaultDiaryPersistedGrantAccess(read = false, write = true),
            defaultDiaryGrantAccessToRelease(
                before = DefaultDiaryPersistedGrantAccess(read = true),
                after = DefaultDiaryPersistedGrantAccess(read = true, write = true),
                referencedBySavedConfiguration = false,
            ),
        )
    }

    @Test
    fun releasesNewReadWritePermissionAfterFailedAttempt() {
        assertEquals(
            DefaultDiaryPersistedGrantAccess(read = true, write = true),
            defaultDiaryGrantAccessToRelease(
                before = DefaultDiaryPersistedGrantAccess(),
                after = DefaultDiaryPersistedGrantAccess(read = true, write = true),
                referencedBySavedConfiguration = false,
            ),
        )
    }

    @Test
    fun keepsNewPermissionWhenSavedConfigurationReferencesGrant() {
        assertEquals(
            DefaultDiaryPersistedGrantAccess(),
            defaultDiaryGrantAccessToRelease(
                before = DefaultDiaryPersistedGrantAccess(),
                after = DefaultDiaryPersistedGrantAccess(read = true, write = true),
                referencedBySavedConfiguration = true,
            ),
        )
    }

    @Test
    fun keepsPermissionWhenPreFailureSnapshotIsUnavailable() {
        assertEquals(
            DefaultDiaryPersistedGrantAccess(),
            defaultDiaryGrantAccessToRelease(
                before = null,
                after = DefaultDiaryPersistedGrantAccess(read = true, write = true),
                referencedBySavedConfiguration = false,
            ),
        )
    }

    private class FakeDirectory(
        override val name: String?,
        override val isDirectory: Boolean = true,
        val children: MutableList<FakeDirectory> = mutableListOf(),
    ) : DefaultDiaryDirectory {
        var createCalls: Int = 0
        val failNames = mutableSetOf<String>()
        val createdNames = mutableMapOf<String, String>()

        override fun children(): List<DefaultDiaryDirectory> = children.toList()

        override fun createDirectory(name: String): DefaultDiaryDirectory? {
            createCalls += 1
            if (name in failNames) return null
            return FakeDirectory(createdNames[name] ?: name).also(children::add)
        }

        fun child(name: String): FakeDirectory = children.single {
            it.name.equals(name, ignoreCase = true)
        }
    }
}
