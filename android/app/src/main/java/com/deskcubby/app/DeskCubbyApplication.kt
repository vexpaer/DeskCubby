package com.deskcubby.app

import android.app.Application
import com.deskcubby.app.data.backup.AutoBackupCoordinator
import com.deskcubby.app.data.repository.PoetryRepository
import com.deskcubby.app.data.repository.PoetryRefreshResult
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.statistics.UsageStatisticsRepository
import com.deskcubby.app.data.statistics.StatisticsRefreshOutcome
import com.deskcubby.app.data.statistics.StatisticsScheduler
import com.deskcubby.app.data.sync.CloudSyncScheduler
import com.deskcubby.app.plugin.PluginRuntime
import com.deskcubby.app.widget.DeskCubbyWidgetProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@HiltAndroidApp
class DeskCubbyApplication : Application() {
    @Inject lateinit var autoBackupCoordinator: AutoBackupCoordinator
    @Inject lateinit var poetryRepository: PoetryRepository
    @Inject lateinit var cloudSyncScheduler: CloudSyncScheduler
    @Inject lateinit var statisticsScheduler: StatisticsScheduler
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usageStatisticsRepository: UsageStatisticsRepository
    @Inject lateinit var pluginRuntime: PluginRuntime
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        autoBackupCoordinator.start()
        cloudSyncScheduler.start()
        statisticsScheduler.start()
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
    }
}
