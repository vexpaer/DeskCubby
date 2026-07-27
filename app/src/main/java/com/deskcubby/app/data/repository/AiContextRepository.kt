package com.deskcubby.app.data.repository

import com.deskcubby.app.data.local.DateRecordDao
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.FlashThoughtDao
import com.deskcubby.app.data.local.SavedPoemDao
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AiContextRepository @Inject constructor(
    private val diaryIndexDao: DiaryIndexDao,
    private val flashThoughtDao: FlashThoughtDao,
    private val dateRecordDao: DateRecordDao,
    private val savedPoemDao: SavedPoemDao,
    private val diaryFileRepository: DiaryFileRepository,
) {
    suspend fun listCandidates(): List<AiContextCandidate> = withContext(Dispatchers.IO) {
        val diaries = diaryIndexDao.getAll().map { diary ->
            AiContextCandidate(
                selectionKey = diaryKey(diary.uri),
                source = AiContextSource.DIARY,
                title = diary.title.ifBlank { diary.name },
                subtitle = diary.dateIso,
                estimatedBytes = diary.size.takeIf { it >= 0L },
            )
        }
        val thoughts = flashThoughtDao.getAllForBackup()
            .asSequence()
            .filter { it.deletedAt == null }
            .sortedWith(compareByDescending<com.deskcubby.app.data.local.FlashThoughtEntity> { it.updatedAt }.thenByDescending { it.id })
            .map { thought ->
                val preview = excerpt(thought.content)
                AiContextCandidate(
                    selectionKey = roomKey(THOUGHT_PREFIX, thought.id),
                    source = AiContextSource.THOUGHT,
                    title = firstMeaningfulLine(thought.content),
                    subtitle = formatInstant(thought.updatedAt),
                    previewExcerpt = preview.text,
                    previewIsExcerpt = preview.isExcerpt,
                    estimatedBytes = thought.content.utf8Size().toLong(),
                )
            }
            .toList()
        val dates = dateRecordDao.getAllForBackup()
            .sortedWith(compareBy<com.deskcubby.app.data.local.DateRecordEntity> { it.dateIso }.thenBy { it.name })
            .map { record ->
                AiContextCandidate(
                    selectionKey = roomKey(DATE_PREFIX, record.id),
                    source = AiContextSource.DATE_RECORD,
                    title = listOf(record.icon, record.name).filter(String::isNotBlank).joinToString(" "),
                    subtitle = record.dateIso,
                    previewExcerpt = record.dateIso,
                    estimatedBytes = record.name.utf8Size().toLong(),
                )
            }
        val poems = savedPoemDao.getAllForBackup()
            .sortedWith(compareByDescending<com.deskcubby.app.data.local.SavedPoemEntity> { it.updatedAt }.thenByDescending { it.id })
            .map { poem ->
                val preview = excerpt(poem.content)
                AiContextCandidate(
                    selectionKey = roomKey(POEM_PREFIX, poem.id),
                    source = AiContextSource.POEM,
                    title = firstMeaningfulLine(poem.content),
                    subtitle = poem.source,
                    previewExcerpt = preview.text,
                    previewIsExcerpt = preview.isExcerpt,
                    estimatedBytes = poem.content.utf8Size().toLong(),
                )
            }
        diaries + thoughts + dates + poems
    }

    /**
     * Resolves every selected lookup token immediately before send. The returned objects no
     * longer contain provider/Room identifiers, so subsequent source edits cannot affect them.
     */
    suspend fun freeze(selectionKeys: Collection<String>): AiContextSnapshot =
        withContext(Dispatchers.IO) {
            if (selectionKeys.size > AiContextCodec.MAX_ITEMS) {
                throw AiContextException(
                    failure = AiContextFailure.TOO_MANY_ITEMS,
                    itemCount = selectionKeys.size,
                )
            }
            val items = selectionKeys.map { key -> loadItem(key) }
            AiContextSnapshot(items).also {
                // Encoding is also the definitive byte-limit validation. Nothing is truncated.
                AiContextCodec.encode(it)
            }
        }

    suspend fun preview(selectionKey: String): AiContextItemPreview = withContext(Dispatchers.IO) {
        val item = loadItem(selectionKey)
        val bytes = AiContextCodec.encodedItemBytes(item)
        val preview = excerpt(item.content, PREVIEW_CONTENT_CHARS)
        AiContextItemPreview(
            source = item.source,
            title = item.title,
            date = item.date,
            attribution = item.attribution,
            contentExcerpt = preview.text,
            contentIsExcerpt = preview.isExcerpt,
            encodedBytes = bytes,
            exceedsItemLimit = bytes > AiContextCodec.MAX_ITEM_BYTES,
        )
    }

    private suspend fun loadItem(selectionKey: String): AiContextItem {
        return try {
            when {
                selectionKey.startsWith(DIARY_PREFIX) -> loadDiary(
                    selectionKey.removePrefix(DIARY_PREFIX),
                )

                selectionKey.startsWith(THOUGHT_PREFIX) -> {
                    val id = selectionKey.roomId(THOUGHT_PREFIX)
                    val thought = flashThoughtDao.getAllForBackup()
                        .firstOrNull { it.id == id && it.deletedAt == null }
                        ?: unavailable()
                    AiContextItem(
                        source = AiContextSource.THOUGHT,
                        title = firstMeaningfulLine(thought.content),
                        date = formatInstant(thought.updatedAt),
                        content = thought.content,
                    )
                }

                selectionKey.startsWith(DATE_PREFIX) -> {
                    val id = selectionKey.roomId(DATE_PREFIX)
                    val record = dateRecordDao.getAllForBackup().firstOrNull { it.id == id }
                        ?: unavailable()
                    AiContextItem(
                        source = AiContextSource.DATE_RECORD,
                        title = record.name,
                        date = record.dateIso,
                        attribution = record.icon,
                    )
                }

                selectionKey.startsWith(POEM_PREFIX) -> {
                    val id = selectionKey.roomId(POEM_PREFIX)
                    val poem = savedPoemDao.getAllForBackup().firstOrNull { it.id == id }
                        ?: unavailable()
                    AiContextItem(
                        source = AiContextSource.POEM,
                        title = firstMeaningfulLine(poem.content),
                        date = formatInstant(poem.updatedAt),
                        attribution = poem.source,
                        content = poem.content,
                    )
                }

                else -> unavailable()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiContextException) {
            throw error
        } catch (error: Exception) {
            throw AiContextException(
                failure = AiContextFailure.SOURCE_UNAVAILABLE,
                cause = error,
            )
        }
    }

    private suspend fun loadDiary(uri: String): AiContextItem {
        if (uri.isBlank()) unavailable()
        val index = diaryIndexDao.getAll().firstOrNull { it.uri == uri } ?: unavailable()
        val content = try {
            diaryFileRepository.readDiaryTextBounded(uri, AiContextCodec.MAX_ITEM_BYTES)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DiaryTextLimitExceededException) {
            throw AiContextException(
                failure = AiContextFailure.ITEM_TOO_LARGE,
                itemTitle = index.title.ifBlank { index.name },
                cause = error,
            )
        } catch (error: DiaryTextInvalidUtf8Exception) {
            throw AiContextException(
                failure = AiContextFailure.INVALID_TEXT_ENCODING,
                itemTitle = index.title.ifBlank { index.name },
                cause = error,
            )
        }
        return AiContextItem(
            source = AiContextSource.DIARY,
            title = index.title.ifBlank { index.name },
            date = index.dateIso,
            content = content,
        )
    }

    private fun unavailable(): Nothing =
        throw AiContextException(AiContextFailure.SOURCE_UNAVAILABLE)

    private fun String.roomId(prefix: String): Long =
        removePrefix(prefix).toLongOrNull() ?: unavailable()

    private companion object {
        const val DIARY_PREFIX = "diary:"
        const val THOUGHT_PREFIX = "thought:"
        const val DATE_PREFIX = "date:"
        const val POEM_PREFIX = "poem:"
        const val CANDIDATE_PREVIEW_CHARS = 220
        const val PREVIEW_CONTENT_CHARS = 12_000
        val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun diaryKey(uri: String): String = DIARY_PREFIX + uri
        fun roomKey(prefix: String, id: Long): String = prefix + id

        fun firstMeaningfulLine(content: String): String =
            content.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                ?.let { excerpt(it, 80).text }
                .orEmpty()

        fun formatInstant(value: Long): String = runCatching {
            Instant.ofEpochMilli(value)
                .atZone(ZoneId.systemDefault())
                .format(DATE_TIME_FORMATTER)
        }.getOrDefault("")

        fun excerpt(value: String, maxChars: Int = CANDIDATE_PREVIEW_CHARS): Excerpt {
            if (value.length <= maxChars) return Excerpt(value, false)
            return Excerpt(value.take(maxChars), true)
        }

        fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
    }

    private data class Excerpt(val text: String, val isExcerpt: Boolean)
}
