package com.deskcubby.app.data.backup

import com.deskcubby.app.data.preferences.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AutoBackupStatus(
    val isSaving: Boolean = false,
    val lastSavedAt: Long? = null,
    val error: String? = null,
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AutoBackupCoordinator @Inject constructor(
    private val backupRepository: AppBackupRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val saveMutex = Mutex()
    private val _status = MutableStateFlow(AutoBackupStatus())

    val status: StateFlow<AutoBackupStatus> = _status.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settingsRepository.settings
                .map { it.backupTreeUri?.takeIf(String::isNotBlank) }
                .distinctUntilChanged()
                .flatMapLatest { treeUri ->
                    if (treeUri == null) {
                        flowOf(null)
                    } else {
                        backupRepository.observeContent().map { treeUri }
                    }
                }
                .debounce(AUTO_SAVE_DEBOUNCE_MILLIS)
                .conflate()
                .retryWhen { cause, attempt ->
                    if (cause is CancellationException) return@retryWhen false
                    _status.update {
                        it.copy(
                            isSaving = false,
                            error = "自动保存监听异常，正在重试：" +
                                (cause.message ?: "无法读取应用数据"),
                        )
                    }
                    delay((attempt + 1).coerceAtMost(5) * RETRY_DELAY_MILLIS)
                    true
                }
                .collect { treeUri ->
                    if (treeUri == null) {
                        _status.update { it.copy(isSaving = false, error = null) }
                        return@collect
                    }
                    try {
                        save(treeUri)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // save() has already exposed a user-facing error through status.
                    }
                }
        }
    }

    suspend fun saveNow(): BackupSummary {
        val treeUri = settingsRepository.settings.first().backupTreeUri
            ?.takeIf(String::isNotBlank)
            ?: throw AppBackupException("保存失败：尚未选择应用内容保存文件夹。")
        return save(treeUri)
    }

    private suspend fun save(treeUri: String): BackupSummary = saveMutex.withLock {
        _status.update { it.copy(isSaving = true, error = null) }
        try {
            val summary = backupRepository.writeCurrentAutomatic(treeUri)
            _status.value = AutoBackupStatus(
                isSaving = false,
                lastSavedAt = summary.exportedAt,
                error = null,
            )
            summary
        } catch (error: CancellationException) {
            _status.update { it.copy(isSaving = false) }
            throw error
        } catch (error: AutomaticBackupConfigurationChangedException) {
            _status.update { it.copy(isSaving = false, error = null) }
            throw error
        } catch (error: Exception) {
            _status.update {
                it.copy(
                    isSaving = false,
                    error = error.message ?: "自动保存失败，请检查文件夹权限。",
                )
            }
            throw error
        }
    }

    private companion object {
        const val AUTO_SAVE_DEBOUNCE_MILLIS = 1_000L
        const val RETRY_DELAY_MILLIS = 1_000L
    }
}
