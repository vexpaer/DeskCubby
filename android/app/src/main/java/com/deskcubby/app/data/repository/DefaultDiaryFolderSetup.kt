package com.deskcubby.app.data.repository

import java.io.IOException

internal const val DEFAULT_APP_FOLDER_NAME = "deskcubby"
internal const val DEFAULT_DIARY_FOLDER_NAME = "diary"
internal const val DEFAULT_MEDIA_FOLDER_NAME = "media"

/**
 * Minimal directory boundary used by the default SAF folder initializer.
 *
 * Keeping the traversal independent from [androidx.documentfile.provider.DocumentFile] makes the
 * collision and retry behaviour testable without treating a provider document ID as a path.
 */
internal interface DefaultDiaryDirectory {
    val name: String?
    val isDirectory: Boolean

    fun children(): List<DefaultDiaryDirectory>

    fun createDirectory(name: String): DefaultDiaryDirectory?
}

internal data class DefaultDiaryDirectories(
    val diary: DefaultDiaryDirectory,
    val media: DefaultDiaryDirectory,
)

internal class DefaultDiaryFolderSetupException : IOException()

internal data class DefaultDiaryPersistedGrantAccess(
    val read: Boolean = false,
    val write: Boolean = false,
)

/**
 * Returns only permission bits acquired by the failed setup attempt.
 *
 * An unknown snapshot fails closed because releasing in that case could revoke a grant that
 * predates this attempt. A grant referenced by saved folder settings is likewise retained.
 */
internal fun defaultDiaryGrantAccessToRelease(
    before: DefaultDiaryPersistedGrantAccess?,
    after: DefaultDiaryPersistedGrantAccess?,
    referencedBySavedConfiguration: Boolean,
): DefaultDiaryPersistedGrantAccess {
    if (before == null || after == null || referencedBySavedConfiguration) {
        return DefaultDiaryPersistedGrantAccess()
    }
    return DefaultDiaryPersistedGrantAccess(
        read = after.read && !before.read,
        write = after.write && !before.write,
    )
}

internal fun ensureDefaultDiaryDirectories(
    selectedRoot: DefaultDiaryDirectory,
): DefaultDiaryDirectories {
    if (!selectedRoot.isDirectory) throw DefaultDiaryFolderSetupException()

    val appRoot = selectedRoot.findOrCreateDirectory(DEFAULT_APP_FOLDER_NAME)
    val diary = appRoot.findOrCreateDirectory(DEFAULT_DIARY_FOLDER_NAME)
    val media = appRoot.findOrCreateDirectory(DEFAULT_MEDIA_FOLDER_NAME)
    return DefaultDiaryDirectories(diary = diary, media = media)
}

private fun DefaultDiaryDirectory.findOrCreateDirectory(
    requestedName: String,
): DefaultDiaryDirectory {
    fun matchingChildren(): List<DefaultDiaryDirectory> = children().filter { child ->
        child.name?.equals(requestedName, ignoreCase = true) == true
    }

    val existing = matchingChildren()
    if (existing.size > 1 || existing.singleOrNull()?.isDirectory == false) {
        throw DefaultDiaryFolderSetupException()
    }
    existing.singleOrNull()?.let { return it }

    val created = createDirectory(requestedName)
    if (created != null) {
        // Some providers silently resolve a collision by creating "name (1)". That is not the
        // conventional directory requested by this initializer, so never persist it as though it
        // were the requested folder.
        if (created.isDirectory && created.name == requestedName) return created
        throw DefaultDiaryFolderSetupException()
    }

    // Some providers report a failed create when another client won the same-name race. Re-read
    // once and accept exactly one directory, while never overwriting a same-name file.
    val afterCreate = matchingChildren()
    if (afterCreate.size == 1 && afterCreate.single().isDirectory) return afterCreate.single()
    throw DefaultDiaryFolderSetupException()
}
