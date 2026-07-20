package com.deskcubby.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deskcubby.app.data.backup.AppBackupException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.backup.AppBackupRepository
import com.deskcubby.app.data.backup.AutoBackupCoordinator
import com.deskcubby.app.data.backup.AutoBackupStatus
import com.deskcubby.app.data.backup.BackupSummary
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupOperationState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val folderConflict: BackupFolderConflict? = null,
)

data class BackupFolderConflict(
    val treeUri: String,
    val summary: BackupSummary,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val backupRepository: AppBackupRepository,
    private val autoBackupCoordinator: AutoBackupCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val settings: StateFlow<AppSettings> = repository.settings.onEach { _ready.value = true }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    private val _backupOperation = MutableStateFlow(BackupOperationState())
    val backupOperation: StateFlow<BackupOperationState> = _backupOperation.asStateFlow()
    val autoBackupStatus: StateFlow<AutoBackupStatus> = autoBackupCoordinator.status

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError: StateFlow<String?> = _settingsError.asStateFlow()

    fun persistFolder(uri: Uri, diary: Boolean) {
        launch {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            if (diary) repository.setDiaryTreeUri(uri.toString()) else repository.setMediaTreeUri(uri.toString())
        }
    }

    fun consumeSettingsError() {
        _settingsError.value = null
    }

    fun setVisualStyle(value: VisualStyle) = launch { repository.setVisualStyle(value) }
    fun setDarkMode(value: DarkMode) = launch { repository.setDarkMode(value) }
    fun setAppLanguage(value: AppLanguage) = launch { repository.setAppLanguage(value) }
    fun setUserName(value: String) = launch { repository.setUserName(value) }
    fun setThemeColor(value: Int) = launch { repository.setThemeColor(value) }
    fun setFileNamePattern(value: String) = launch { repository.setFileNamePattern(value) }
    fun setTemplate(value: String) = launch { repository.setMarkdownTemplate(value) }
    fun setImageNamePattern(value: String) = launch { repository.setImageNamePattern(value) }
    fun setImageMaxWidth(value: Int) = launch { repository.setImageMaxWidth(value) }
    fun setImageMaxHeight(value: Int) = launch { repository.setImageMaxHeight(value) }
    fun setMealImageCompressionEnabled(value: Boolean) =
        launch { repository.setMealImageCompressionEnabled(value) }
    fun setMealImageCompressionQuality(value: Int) =
        launch { repository.setMealImageCompressionQuality(value) }
    fun setBrowserHome(value: String) = launch { repository.setBrowserHomeUrl(value) }
    fun setBrowserTheme(value: BrowserTheme) = launch { repository.setBrowserTheme(value) }
    fun setBrowserDesktopMode(value: Boolean) = launch { repository.setBrowserDesktopMode(value) }
    fun setThoughtRowHeight(value: Int) = launch { repository.setThoughtRowHeight(value) }
    fun setMealButtonsUseIcons(value: Boolean) = launch { repository.setMealButtonsUseIcons(value) }
    fun setMealButtonIcons(value: List<String>) = launch { repository.setMealButtonIcons(value) }
    fun setDefaultPage(value: NavItemId) = launch { repository.setDefaultPage(value) }
    fun setNavItems(value: List<NavItemConfig>) = launch { repository.setNavItems(value) }
    fun setHomeWidgets(value: List<String>) = launch { repository.setHomeWidgets(value) }
    fun setHomeWidgetTitles(value: List<String>) = launch { repository.setHomeWidgetTitles(value) }
    fun setBottomNavShowLabels(value: Boolean) = launch { repository.setBottomNavShowLabels(value) }
    fun setHomeWidgetBordersEnabled(value: Boolean) =
        launch { repository.setHomeWidgetBordersEnabled(value) }

    fun selectBackupFolder(uri: Uri) = viewModelScope.launch {
        _backupOperation.value = BackupOperationState(busy = true)
        try {
            persistAndVerifyFolderPermission(uri)
            if (settings.value.backupTreeUri == uri.toString()) {
                _backupOperation.value = BackupOperationState(
                    message = "此文件夹已用于自动保存 / This folder is already used for auto-save",
                )
                return@launch
            }

            val existing = backupRepository.inspectAutomatic(uri)
            if (existing != null) {
                _backupOperation.value = BackupOperationState(
                    folderConflict = BackupFolderConflict(uri.toString(), existing),
                )
                return@launch
            }

            val previousTreeUri = settings.value.backupTreeUri
            val summary = activateFolderAndSave(uri, previousTreeUri)
            _backupOperation.value = BackupOperationState(
                message = successMessage(
                    actionZh = "自动保存文件夹已设置，当前数据已保存",
                    actionEn = "Auto-save folder selected and current data saved",
                    summary = summary,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            setBackupOperationError(error)
        }
    }

    fun importExistingBackup() {
        val conflict = _backupOperation.value.folderConflict ?: return
        val treeUri = Uri.parse(conflict.treeUri)
        val previousTreeUri = settings.value.backupTreeUri
        runBackupOperation {
            val summary = backupRepository.importAutomatic(treeUri)
            try {
                activateFolderAndSave(treeUri, previousTreeUri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw AppBackupException(
                    "已有备份已导入，但无法为该文件夹开启自动保存；已恢复原自动保存设置。",
                    error,
                )
            }
            successMessage(
                actionZh = "已有备份已导入，并已开启自动保存",
                actionEn = "Existing backup imported and auto-save enabled",
                summary = summary,
            )
        }
    }

    fun overwriteExistingBackup() {
        val conflict = _backupOperation.value.folderConflict ?: return
        val treeUri = Uri.parse(conflict.treeUri)
        val previousTreeUri = settings.value.backupTreeUri
        runBackupOperation {
            successMessage(
                actionZh = "已有备份已被当前数据覆盖",
                actionEn = "Existing backup replaced with current data",
                summary = activateFolderAndSave(treeUri, previousTreeUri),
            )
        }
    }

    fun cancelBackupFolderConflict() {
        if (!_backupOperation.value.busy) _backupOperation.value = BackupOperationState()
    }

    fun disableAutoBackup() = runBackupOperation {
        repository.setBackupTreeUri(null)
        "已停止自动保存 / Auto-save stopped"
    }

    fun exportBackup(uri: Uri) = runBackupOperation {
        successMessage(
            actionZh = "JSON 已导出",
            actionEn = "JSON exported",
            summary = backupRepository.exportTo(uri),
        )
    }

    fun importBackup(uri: Uri) = runBackupOperation {
        val shouldSyncFolder = settings.value.backupTreeUri != null
        val summary = backupRepository.importFrom(uri)
        if (shouldSyncFolder) {
            try {
                autoBackupCoordinator.saveNow()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw AppBackupException(
                    "JSON 已导入，但无法同步到自动保存文件夹；请重新选择文件夹或稍后重试。",
                    error,
                )
            }
        }
        successMessage(
            actionZh = "JSON 已导入",
            actionEn = "JSON imported",
            summary = summary,
        )
    }

    fun saveBackupNow() = runBackupOperation {
        successMessage(
            actionZh = "当前数据已保存",
            actionEn = "Current data saved",
            summary = autoBackupCoordinator.saveNow(),
        )
    }

    private fun runBackupOperation(block: suspend () -> String) = viewModelScope.launch {
        _backupOperation.value = BackupOperationState(busy = true)
        try {
            _backupOperation.value = BackupOperationState(message = block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            setBackupOperationError(error)
        }
    }

    private fun persistAndVerifyFolderPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        check(persisted?.let { it.isReadPermission && it.isWritePermission } == true) {
            "无法保留所选文件夹的读写权限 / Could not retain read and write access to the selected folder"
        }
    }

    private suspend fun activateFolderAndSave(uri: Uri, previousTreeUri: String?): BackupSummary {
        return try {
            repository.setBackupTreeUri(uri.toString())
            autoBackupCoordinator.saveNow()
        } catch (error: CancellationException) {
            restoreBackupTreeUri(previousTreeUri, error)
            throw error
        } catch (error: Exception) {
            restoreBackupTreeUri(previousTreeUri, error)
            throw error
        }
    }

    private suspend fun restoreBackupTreeUri(previousTreeUri: String?, cause: Throwable) {
        withContext(NonCancellable) {
            try {
                repository.setBackupTreeUri(previousTreeUri)
            } catch (restoreError: Exception) {
                cause.addSuppressed(restoreError)
            }
        }
    }

    private fun setBackupOperationError(error: Throwable) {
        _backupOperation.value = BackupOperationState(
            error = error.message?.takeIf(String::isNotBlank) ?: "未知错误 / Unknown error",
        )
    }

    private fun successMessage(actionZh: String, actionEn: String, summary: BackupSummary): String =
        "$actionZh：${summary.thoughtCount} 条小巧思、${summary.categoryCount} 个小巧思分类、" +
            "${summary.favoriteCount} 个浏览器收藏、" +
            "${summary.dateRecordCount} 个日期记录、${summary.poemCount} 首诗词；" +
            "$actionEn: ${summary.thoughtCount} thoughts, " +
            "${summary.categoryCount} thought categories, ${summary.favoriteCount} bookmarks, " +
            "${summary.dateRecordCount} date records, ${summary.poemCount} poems"

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message?.takeIf(String::isNotBlank)
                ?: "设置保存失败 / Could not save settings"
        }
    }
}
