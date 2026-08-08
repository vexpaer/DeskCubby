package com.deskcubby.app.plugin.adapter

import android.content.ContentResolver
import android.net.Uri
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.ExternalFileConflictException
import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.DiaryConflictException
import com.deskcubby.plugin.api.core.api.DiaryDocument
import com.deskcubby.plugin.api.core.api.ImportedMedia
import com.deskcubby.plugin.api.core.api.MediaAPI
import com.deskcubby.plugin.api.core.api.MediaDeleteResult
import com.deskcubby.plugin.api.core.api.MediaResource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class MediaApiAdapter @Inject constructor(
    private val repository: DiaryFileRepository,
    private val settingsRepository: SettingsRepository,
) : MediaAPI {
    override suspend fun importImage(
        sourceContentUri: String,
        category: String?,
        dateIso: String?,
    ): ImportedMedia = repository.importImage(
        sourceUri = sourceUri(sourceContentUri),
        category = category,
        settings = settingsRepository.settings.first(),
        date = parsePluginDate(dateIso),
    ).let { media ->
        ImportedMedia(media.documentUri, media.fileName, media.markdown)
    }

    override suspend fun appendImageToToday(
        sourceContentUri: String,
        category: String,
        dateIso: String?,
    ): ImportedMedia = repository.appendImageToToday(
        sourceUri = sourceUri(sourceContentUri),
        category = category,
        settings = settingsRepository.settings.first(),
        date = parsePluginDate(dateIso),
    ).let { media ->
        ImportedMedia(media.documentUri, media.fileName, media.markdown)
    }

    override suspend fun resolve(markdownTargets: Collection<String>): List<MediaResource> {
        val resolved = repository.resolveDiaryPreviewMedia(
            markdownTargets = markdownTargets,
            settings = settingsRepository.settings.first(),
        )
        return markdownTargets.distinct().map { target ->
            val media = resolved[target]
            MediaResource(
                markdownTarget = target,
                contentUri = media?.uri?.toString(),
                locationName = media?.locationName,
            )
        }
    }

    override suspend fun setMealPhotoEnergy(fileName: String, energyKj: Int) {
        repository.setMealPhotoEnergy(
            fileName = fileName,
            energyKj = energyKj,
            settings = settingsRepository.settings.first(),
        )
    }

    override suspend fun deleteFromDiary(
        diary: DiaryDocument,
        markdownTarget: String,
    ): MediaDeleteResult = try {
        repository.deleteMediaAndReferences(
            diaryUri = diary.documentId,
            editorContent = diary.markdown,
            expectedSha256 = diary.version.sha256,
            markdownTarget = markdownTarget,
            settings = settingsRepository.settings.first(),
        ).let { result ->
            MediaDeleteResult(
                diary = result.document.toPluginDocument(),
                mediaFileDeleted = result.mediaFileDeleted,
            )
        }
    } catch (conflict: ExternalFileConflictException) {
        throw DiaryConflictException(conflict.diskDocument.toPluginDocument(), conflict)
    }

    private fun sourceUri(value: String): Uri {
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        if (uri?.scheme != ContentResolver.SCHEME_CONTENT) {
            throw PluginApiException(
                code = "INVALID_MEDIA_URI",
                message = "Media imports require a content URI selected by the user.",
            )
        }
        return uri
    }
}
