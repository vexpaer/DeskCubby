package com.deskcubby.app

import android.app.Application
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.LegacyAiKeyMigrator
import com.deskcubby.app.data.repository.PoetryRefreshResult
import com.deskcubby.app.data.repository.PoetryRepository
import com.deskcubby.app.data.statistics.StatisticsRefreshOutcome
import com.deskcubby.app.data.statistics.StatisticsScheduler
import com.deskcubby.app.data.statistics.UsageStatisticsRepository
import com.deskcubby.app.data.sync.CloudSyncScheduler
import com.deskcubby.app.data.taskqueue.AiTaskQueue
import com.deskcubby.app.plugin.PluginRuntime
import com.deskcubby.app.widget.DeskCubbyWidgetProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class DeskCubbyApplication : Application() {
    @Inject lateinit var poetryRepository: PoetryRepository
    @Inject lateinit var cloudSyncScheduler: CloudSyncScheduler
    @Inject lateinit var statisticsScheduler: StatisticsScheduler
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usageStatisticsRepository: UsageStatisticsRepository
    @Inject lateinit var pluginRuntime: PluginRuntime
    @Inject lateinit var aiTaskQueue: AiTaskQueue
    @Inject lateinit var legacyAiKeyMigrator: LegacyAiKeyMigrator
    @Inject lateinit var structuredRecordsRepository: com.deskcubby.app.data.structuredrecords.StructuredRecordsRepository
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        cloudSyncScheduler.start()
        statisticsScheduler.start()
        applicationScope.launch {
            try {
                // Old releases kept AI keys in AndroidKeyStore-backed preferences. Background work
                // can start before SettingsViewModel exists, so migrate credentials first.
                legacyAiKeyMigrator.migrateIfNeeded()
                aiTaskQueue.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Startup must continue even if queue recovery fails; the next enqueue reschedules it.
            }
        }
        DeskCubbyWidgetProvider.requestUpdate(this)
        if (pluginRuntime.hasPlugins()) {
            applicationScope.launch {
                try {
                    pluginRuntime.start()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A future optional plugin must never prevent DeskCubby from starting.
                }
            }
        }
        applicationScope.launch {
            try {
                if (poetryRepository.refresh(force = false) == PoetryRefreshResult.UPDATED) {
                    DeskCubbyWidgetProvider.requestUpdate(this@DeskCubbyApplication)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Startup must continue with the cached or fallback poem when the network is unavailable.
            }
        }
        applicationScope.launch {
            try {
                if (settingsRepository.settings.first().usageTrackingEnabled) {
                    if (
                        usageStatisticsRepository.refreshOnAppOpenIfNeeded() ==
                        StatisticsRefreshOutcome.SUCCESS
                    ) {
                        DeskCubbyWidgetProvider.requestUpdate(this@DeskCubbyApplication)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Usage access is optional; a failed startup collection must not block the app.
            }
        }
        applicationScope.launch {
            try {
                val stored = settingsRepository.settings.first()
                if (stored.diaryTreeUri != null) {
                    // This scan is startup-only background reconciliation. User record writes use
                    // direct one-file index updates and never wait for this full-directory pass.
                    structuredRecordsRepository.refreshIncremental(stored)
                    structuredRecordsRepository.settleAutomaticSleepWake(stored)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The index is a derived cache; a failed refresh simply retries next launch.
            }
        }
    }
}
