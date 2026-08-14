package com.deskcubby.app.ui.desk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.DateRecordDao
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtDao
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.MealCalendarPhoto
import com.deskcubby.app.ui.desk.model.DeskAmbient
import com.deskcubby.app.ui.desk.model.DeskDateLabel
import com.deskcubby.app.ui.desk.model.DeskItem
import com.deskcubby.app.ui.desk.model.DeskItemKind
import com.deskcubby.app.ui.desk.model.DeskTrace
import com.deskcubby.app.ui.desk.model.DeskUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DeskViewModel @Inject constructor(
    private val diaryIndexDao: DiaryIndexDao,
    private val thoughtDao: FlashThoughtDao,
    private val dateRecordDao: DateRecordDao,
    private val diaryRepository: DiaryFileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    private val _state = MutableStateFlow(DeskUiState())
    val state: StateFlow<DeskUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                diaryIndexDao.observeAll(),
                thoughtDao.observeActive(),
                dateRecordDao.observeAll(),
                settings,
            ) { diaries, thoughts, dates, current ->
                Source(diaries, thoughts, dates, current)
            }.collect { source ->
                refresh(source)
            }
        }
    }

    private suspend fun refresh(source: Source) {
        _state.value = _state.value.copy(loading = true)
        try {
            val today = LocalDate.now(ZoneId.systemDefault())
            val language = source.settings.appLanguage
            val ambient = ambientFor(LocalDateTime.now().hour)

            val todayDiary = source.diaries
                .filter { it.dateIso == today.toString() }
                .maxByOrNull { it.lastModified }
            val diaryItem = todayDiary?.let { diary ->
                val excerpt = runCatching {
                    plainExcerpt(diaryRepository.load(diary.uri).content)
                }.getOrDefault("")
                DeskItem(
                    kind = DeskItemKind.DIARY,
                    id = "diary:" + diary.uri,
                    title = diary.title.ifBlank { diary.name },
                    excerpt = excerpt,
                    meta = wordCountLabel(diary.wordCount, language),
                    rotationDeg = seedRotation(diary.uri + ":" + today, -0.5f..0.5f),
                    diaryUri = diary.uri,
                )
            }

            val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val todayIdeas = source.thoughts
                .filter { it.createdAt in startOfDay until endOfDay }
                .sortedWith(compareByDescending<FlashThoughtEntity> { it.highlighted }
                    .thenByDescending { it.createdAt })
            val ideaItems = todayIdeas.take(MAX_IDEAS).mapIndexed { index, idea ->
                DeskItem(
                    kind = DeskItemKind.IDEA,
                    id = "idea:" + idea.id,
                    title = idea.content,
                    excerpt = "",
                    meta = ideaMetaLabel(index, language),
                    rotationDeg = seedRotation(idea.id.toString() + ":" + today, -0.6f..0.6f),
                    ideaId = idea.id,
                )
            }

            val mealDays = runCatching {
                diaryRepository.scanMealCalendar(source.settings, forceRefresh = false)
            }.getOrDefault(emptyList())
            val todayPhotos = mealDays
                .firstOrNull { it.dateIso == today.toString() }
                ?.photos
                ?.take(MAX_PHOTOS)
                ?: emptyList()
            val photoItems = todayPhotos.mapIndexed { index, photo ->
                DeskItem(
                    kind = DeskItemKind.PHOTO,
                    id = "photo:" + photo.fileName.ifBlank { photo.uri.toString() } + ":" + index,
                    title = photo.caption,
                    excerpt = "",
                    meta = photoMetaLabel(photo, language),
                    rotationDeg = seedRotation(photo.fileName + ":" + today, -0.9f..0.9f),
                    imageUri = photo.uri,
                    diaryUri = photo.diaryUri.toString(),
                )
            }

            val traces = buildTraces(source, todayIdeas, todayDiary, todayPhotos, language)
            val totalTraceCount = traces.size + todayIdeas.size + todayPhotos.size +
                (if (todayDiary != null) 1 else 0)

            _state.value = DeskUiState(
                loading = false,
                dateLabel = DeskDateLabel(
                    dayNumber = today.dayOfMonth.toString(),
                    month = today.month.getDisplayName(TextStyle.SHORT, uiLocale(language))
                        .uppercase(Locale.ROOT),
                    weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, uiLocale(language)),
                ),
                diary = diaryItem,
                ideas = ideaItems,
                photos = photoItems,
                traces = traces,
                totalTraceCount = totalTraceCount,
                ambient = ambient,
                isEmpty = diaryItem == null && ideaItems.isEmpty() && photoItems.isEmpty() && traces.isEmpty(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.value = _state.value.copy(loading = false)
        }
    }

    private fun buildTraces(
        source: Source,
        ideas: List<FlashThoughtEntity>,
        diary: DiaryIndexEntity?,
        photos: List<MealCalendarPhoto>,
        language: AppLanguage,
    ): List<DeskTrace> {
        val rows = mutableListOf<Pair<Long, String>>()
        ideas.forEach { rows += it.createdAt to ideaTraceLabel(language) }
        diary?.let { rows += it.lastModified to diaryTraceLabel(language) }
        photos.forEach { photo ->
            rows += photoTraceTime(photo, ideas, diary) to photoTraceLabel(photo, language)
        }
        source.dates
            .filter { it.dateIso == LocalDate.now(ZoneId.systemDefault()).toString() }
            .forEach { rows += it.createdAt to (it.name.ifBlank { eventTraceLabel(language) }) }

        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        return rows.sortedBy { (time, _) -> time }
            .take(MAX_TRACES)
            .map { (time, label) ->
                val local = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(time),
                    ZoneId.systemDefault(),
                )
                DeskTrace(
                    timeLabel = local.format(timeFormatter),
                    label = label,
                    weight = 1,
                )
            }
    }

    private fun photoTraceTime(
        photo: MealCalendarPhoto,
        ideas: List<FlashThoughtEntity>,
        diary: DiaryIndexEntity?,
    ): Long {
        // MealCalendarPhoto has no stable timestamp; derive a deterministic value from the file
        // name so ordering is reproducible within a day and never mutates across recompositions.
        val fallback = photo.fileName.hashCode().toLong() and 0x7FFFFFFF
        return fallback
    }

    private fun photoMetaLabel(photo: MealCalendarPhoto, language: AppLanguage): String =
        photo.caption.ifBlank {
            if (language == AppLanguage.ENGLISH) photo.category.englishLabel else photo.category.chineseLabel
        }

    private fun photoTraceLabel(photo: MealCalendarPhoto, language: AppLanguage): String =
        photo.caption.ifBlank { if (language == AppLanguage.ENGLISH) "photo" else "照片" }

    private fun ideaMetaLabel(index: Int, language: AppLanguage): String =
        if (language == AppLanguage.ENGLISH) "Idea" else "小巧思"

    private fun wordCountLabel(count: Int, language: AppLanguage): String =
        if (language == AppLanguage.ENGLISH) count.toString() + " words" else count.toString() + " 字"

    private fun ideaTraceLabel(language: AppLanguage): String =
        if (language == AppLanguage.ENGLISH) "idea" else "小巧思"

    private fun diaryTraceLabel(language: AppLanguage): String =
        if (language == AppLanguage.ENGLISH) "diary" else "日记"

    private fun eventTraceLabel(language: AppLanguage): String =
        if (language == AppLanguage.ENGLISH) "event" else "事件"

    private fun uiLocale(language: AppLanguage): Locale = when (language) {
        AppLanguage.ENGLISH -> Locale.ENGLISH
        else -> Locale.SIMPLIFIED_CHINESE
    }

    private companion object {
        const val MAX_IDEAS = 2
        const val MAX_PHOTOS = 2
        const val MAX_TRACES = 6
    }
}

private data class Source(
    val diaries: List<DiaryIndexEntity>,
    val thoughts: List<FlashThoughtEntity>,
    val dates: List<DateRecordEntity>,
    val settings: AppSettings,
)

internal fun seedRotation(seedKey: String, range: ClosedFloatingPointRange<Float>): Float {
    val hash = seedKey.fold(0L) { acc, ch -> acc * 31 + ch.code }
    val unit = ((hash and 0xFFFF).toFloat() / 0xFFFF.toFloat())
    return range.start + unit * (range.endInclusive - range.start)
}

internal fun ambientFor(hour: Int): DeskAmbient = when {
    hour in 5..10 -> DeskAmbient.MORNING
    hour in 11..16 -> DeskAmbient.AFTERNOON
    hour in 17..21 -> DeskAmbient.EVENING
    else -> DeskAmbient.LATE_NIGHT
}

internal fun plainExcerpt(markdown: String, maxChars: Int = 140): String {
    val sb = StringBuilder()
    var inCode = false
    for (ch in markdown) {
        if (ch == '`') { inCode = !inCode; continue }
        if (ch == '#' || ch == '!' || ch == '>' || ch == '*' || ch == '-' || ch == '_' || ch == '[' || ch == ']' || ch == '(' || ch == ')') {
            if (!inCode) continue
        }
        sb.append(ch)
    }
    val collapsed = sb.toString().replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length > maxChars) collapsed.take(maxChars) + "…" else collapsed
}
