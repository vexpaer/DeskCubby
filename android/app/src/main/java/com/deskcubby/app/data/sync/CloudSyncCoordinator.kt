package com.deskcubby.app.data.sync

import android.content.Context
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.repository.DiaryFileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CloudSyncConfigRun(
    val configId: String,
    val result: CloudSyncRunResult? = null,
    val errorMessage: String? = null,
)

/**
 * Public integration point for settings/ViewModels and a future CoroutineWorker.
 *
 * Runs are serialized so two services never race while applying the same SAF document. WorkManager
 * scheduling intentionally stays outside this class; the Worker can call the same cancellable
 * suspend API after loading current device-local credentials.
 */
class CloudSyncCoordinator(
    context: Context,
    private val diaryRepository: DiaryFileRepository,
    private val settingsProvider: suspend () -> AppSettings,
    private val jsonBridge: CloudSyncJsonBridge? = null,
    private val usageBridge: CloudSyncUsageBridge? = null,
    private val readerProgressBridge: CloudSyncReaderProgressBridge? = null,
    private val agentChatBridge: CloudSyncAgentChatBridge? = null,
    private val remoteStoreFactory: CloudSyncRemoteStoreFactory =
        DefaultCloudSyncRemoteStoreFactory(),
    private val stateStore: CloudSyncStateStore =
        FileCloudSyncStateStore(context.applicationContext),
) {
    private val mutex = Mutex()

    suspend fun sync(
        config: CloudSyncConfig,
        limits: CloudSyncLimits = CloudSyncLimits(),
        mode: CloudSyncRunMode = CloudSyncRunMode.NORMAL,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ): CloudSyncRunResult = mutex.withLock {
        newEngine(config).sync(config, limits, mode, onProgress)
    }

    suspend fun syncEnabled(
        configs: List<CloudSyncConfig>,
        limits: CloudSyncLimits = CloudSyncLimits(),
        mode: CloudSyncRunMode = CloudSyncRunMode.NORMAL,
        onProgress: (configId: String, progress: CloudSyncProgress) -> Unit = { _, _ -> },
    ): List<CloudSyncConfigRun> = mutex.withLock {
        buildList {
            configs.filter(CloudSyncConfig::enabled).forEach { config ->
                try {
                    add(
                        CloudSyncConfigRun(
                            configId = config.id,
                            result = newEngine(config).sync(config, limits, mode) { progress ->
                                onProgress(config.id, progress)
                            },
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    add(
                        CloudSyncConfigRun(
                            configId = config.id,
                            errorMessage = formatCloudSyncError(error),
                        ),
                    )
                }
            }
        }
    }

    private fun newEngine(config: CloudSyncConfig): CloudSyncEngine = CloudSyncEngine(
        localStore = DiaryCloudSyncLocalStore(
            diaryRepository = diaryRepository,
            settingsProvider = settingsProvider,
            configId = config.id,
            jsonBridge = jsonBridge,
            usageBridge = usageBridge,
            readerProgressBridge = readerProgressBridge,
            agentChatBridge = agentChatBridge,
        ),
        remoteStoreFactory = remoteStoreFactory,
        stateStore = stateStore,
    )
}
