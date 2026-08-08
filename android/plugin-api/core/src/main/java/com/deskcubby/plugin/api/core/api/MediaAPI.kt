package com.deskcubby.plugin.api.core.api

interface MediaAPI {
    suspend fun importImage(
        sourceContentUri: String,
        category: String? = null,
        dateIso: String? = null,
    ): ImportedMedia

    suspend fun appendImageToToday(
        sourceContentUri: String,
        category: String,
        dateIso: String? = null,
    ): ImportedMedia

    suspend fun resolve(markdownTargets: Collection<String>): List<MediaResource>

    suspend fun setMealPhotoEnergy(fileName: String, energyKj: Int)

    suspend fun deleteFromDiary(
        diary: DiaryDocument,
        markdownTarget: String,
    ): MediaDeleteResult
}

data class ImportedMedia(
    val mediaId: String,
    val fileName: String,
    val markdown: String,
)

data class MediaResource(
    val markdownTarget: String,
    val contentUri: String?,
    val locationName: String? = null,
)

data class MediaDeleteResult(
    val diary: DiaryDocument,
    val mediaFileDeleted: Boolean,
)
