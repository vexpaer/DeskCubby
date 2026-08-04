package com.deskcubby.app.ui.statshub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.GameStateDao
import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.ReaderLibraryState
import com.deskcubby.app.data.repository.ReaderRepository
import com.deskcubby.app.data.statistics.EngagementTimeRepository
import com.deskcubby.app.data.statistics.EngagementTimeSnapshot
import com.deskcubby.app.data.statistics.GameStatisticsRepository
import com.deskcubby.app.data.statistics.GameStatisticsSnapshot
import com.deskcubby.app.data.statistics.StepStatisticsHistory
import com.deskcubby.app.data.statistics.StepStatisticsRepository
import com.deskcubby.app.data.statistics.UsageDeviceRecord
import com.deskcubby.app.data.statistics.UsageDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class StatisticsHubViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    usageDeviceRepository: UsageDeviceRepository,
    stepStatisticsRepository: StepStatisticsRepository,
    engagementTimeRepository: EngagementTimeRepository,
    gameStatisticsRepository: GameStatisticsRepository,
    private val readerRepository: ReaderRepository,
    diaryIndexDao: DiaryIndexDao,
    gameStateDao: GameStateDao,
) : ViewModel() {
    private val gameSources = combine(
        engagementTimeRepository.snapshot,
        gameStatisticsRepository.statistics,
    ) { engagement, gameStatistics ->
        GameStatisticsSources(engagement, gameStatistics)
    }

    private val liveSources = combine(
        settingsRepository.settings,
        usageDeviceRepository.records,
        stepStatisticsRepository.history,
        readerRepository.state,
        gameSources,
    ) { settings, usageRecords, healthHistory, library, games ->
        LiveStatisticsSources(
            settings = settings,
            usageRecords = usageRecords,
            healthHistory = healthHistory,
            engagement = games.engagement,
            gameStatistics = games.gameStatistics,
            library = library,
        )
    }

    private val indexedSources = combine(
        diaryIndexDao.observeAll(),
        gameStateDao.observeAllForBackup(),
    ) { diaries, gameStates ->
        IndexedStatisticsSources(diaries, gameStates)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StatisticsHubUiState> = combine(
        liveSources,
        indexedSources,
    ) { live, indexed -> live to indexed }
        .mapLatest { (live, indexed) ->
            withContext(Dispatchers.Default) {
                deriveStatisticsHubState(
                    diaries = indexed.diaries,
                    usageRecords = live.usageRecords,
                    healthHistory = live.healthHistory,
                    engagement = live.engagement,
                    books = live.library.books,
                    gameStates = indexed.gameStates,
                    usageEnabled = live.settings.usageTrackingEnabled,
                    healthEnabled = live.settings.stepTrackingEnabled,
                    gameMetrics = live.gameStatistics.byGameId.mapValues { (_, values) ->
                        values.asMap()
                    },
                    today = LocalDate.now(),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatisticsHubUiState(),
        )

    init {
        // The reader state file intentionally loads lazily. Statistics needs only its bounded
        // title/id index, and uses the repository's existing serialized initialization path.
        viewModelScope.launch { readerRepository.initialize() }
    }
}

private data class LiveStatisticsSources(
    val settings: AppSettings,
    val usageRecords: List<UsageDeviceRecord>,
    val healthHistory: StepStatisticsHistory,
    val engagement: EngagementTimeSnapshot,
    val gameStatistics: GameStatisticsSnapshot,
    val library: ReaderLibraryState,
)

private data class GameStatisticsSources(
    val engagement: EngagementTimeSnapshot,
    val gameStatistics: GameStatisticsSnapshot,
)

private data class IndexedStatisticsSources(
    val diaries: List<DiaryIndexEntity>,
    val gameStates: List<GameStateEntity>,
)
