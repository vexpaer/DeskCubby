package com.deskcubby.app.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import com.deskcubby.app.data.backup.AppBackupException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.backup.AppBackupRepository
import com.deskcubby.app.data.backup.AutoBackupCoordinator
import com.deskcubby.app.data.backup.AutoBackupStatus
import com.deskcubby.app.data.backup.BackupSummary
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.CustomThemeSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.LauncherIcon
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.PoetryTextAlignment
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.ThoughtReopenMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.preferences.normalizeS3EndpointScheme
import com.deskcubby.app.data.repository.LegacyAiKeyMigrationStore
import com.deskcubby.app.data.repository.DownloadedUpdate
import com.deskcubby.app.data.repository.UpdateCheckResult
import com.deskcubby.app.data.repository.UpdateDownloadFailure
import com.deskcubby.app.data.repository.UpdateDownloadResult
import com.deskcubby.app.data.repository.UpdateInstallRequest
import com.deskcubby.app.data.repository.UpdateRepository
import com.deskcubby.app.data.repository.AppDataUsageRepository
import com.deskcubby.app.data.repository.AppDataUsageSnapshot
import com.deskcubby.app.data.sync.AppCloudSyncService
import com.deskcubby.app.data.sync.AppCloudSyncStatus
import com.deskcubby.app.data.sync.CloudSyncSecretStore
import com.deskcubby.app.data.sync.CloudSyncUndoStore
import com.deskcubby.app.data.sync.CloudSyncRunMode
import com.deskcubby.app.data.sync.PendingCloudSyncJson
import com.deskcubby.app.data.sync.formatCloudSyncError
import com.deskcubby.app.data.sync.validateForSync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

data class BackupJsonPreviewState(
    val busy: Boolean = false,
    val json: String? = null,
    val error: String? = null,
)

data class AppDataUsageState(
    val loading: Boolean = false,
    val snapshot: AppDataUsageSnapshot? = null,
    val failed: Boolean = false,
)

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateDownloadState
    data class Preparing(val version: String) : UpdateDownloadState
    data class AwaitingInstallPermission(val version: String) : UpdateDownloadState
    data class ReadyToInstall(val version: String) : UpdateDownloadState
    data class Failed(val reason: UpdateDownloadFailure) : UpdateDownloadState
}

internal fun UpdateDownloadState.isUpdateOperationInProgress(): Boolean =
    this is UpdateDownloadState.Downloading || this is UpdateDownloadState.Preparing

internal fun updateActionUnavailableFailure(action: String?): UpdateDownloadFailure =
    if (action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) {
        UpdateDownloadFailure.INSTALL_PERMISSION_SETTINGS_UNAVAILABLE
    } else {
        UpdateDownloadFailure.INSTALLER_UNAVAILABLE
    }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val backupRepository: AppBackupRepository,
    private val autoBackupCoordinator: AutoBackupCoordinator,
    private val legacyAiKeyMigrationStore: LegacyAiKeyMigrationStore,
    private val cloudSyncService: AppCloudSyncService,
    private val cloudSyncSecretStore: CloudSyncSecretStore,
    private val cloudSyncUndoStore: CloudSyncUndoStore,
    private val updateRepository: UpdateRepository,
    private val appDataUsageRepository: AppDataUsageRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val languageSelected: StateFlow<Boolean> = repository.languageSelected.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    private var legacyAiKeyMigrationAttempted = false
    val settings: StateFlow<AppSettings> = repository.settings.map { current ->
        if (legacyAiKeyMigrationAttempted) current else {
            legacyAiKeyMigrationAttempted = true
            migrateLegacyAiKeys(current)
        }
    }.onEach { _ready.value = true }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    private val _backupOperation = MutableStateFlow(BackupOperationState())
    val backupOperation: StateFlow<BackupOperationState> = _backupOperation.asStateFlow()
    private val _backupJsonPreview = MutableStateFlow(BackupJsonPreviewState())
    val backupJsonPreview: StateFlow<BackupJsonPreviewState> = _backupJsonPreview.asStateFlow()
    val autoBackupStatus: StateFlow<AutoBackupStatus> = autoBackupCoordinator.status
    val cloudSyncStatus: StateFlow<AppCloudSyncStatus> = cloudSyncService.status
    private val _cloudSyncUndoAvailable = MutableStateFlow(cloudSyncUndoStore.hasUndo())
    val cloudSyncUndoAvailable: StateFlow<Boolean> = _cloudSyncUndoAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            cloudSyncService.status.collect { status ->
                if (!status.running) {
                    _cloudSyncUndoAvailable.value = cloudSyncUndoStore.hasUndo()
                }
            }
        }
    }
    private val _appDataUsage = MutableStateFlow(AppDataUsageState())
    val appDataUsage: StateFlow<AppDataUsageState> = _appDataUsage.asStateFlow()

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError: StateFlow<String?> = _settingsError.asStateFlow()

    private val _updateCheckInProgress = MutableStateFlow(false)
    val updateCheckInProgress: StateFlow<Boolean> = _updateCheckInProgress.asStateFlow()
    private val _updateCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateCheckResult: StateFlow<UpdateCheckResult?> = _updateCheckResult.asStateFlow()
    private val _updateDownloadState =
        MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateDownloadState: StateFlow<UpdateDownloadState> =
        _updateDownloadState.asStateFlow()
    // Never replay installer intents after the About page collector has gone away.
    private val _updateInstallActions = MutableSharedFlow<Intent>(
        replay = 0,
        extraBufferCapacity = 0,
    )
    val updateInstallActions = _updateInstallActions.asSharedFlow()
    private val updateOperationMutex = Mutex()
    private var updateOperationJob: Job? = null
    private var downloadedUpdate: DownloadedUpdate? = null

    fun checkForUpdate() {
        if (_updateCheckInProgress.value) return
        viewModelScope.launch {
            _updateCheckInProgress.value = true
            try {
                _updateCheckResult.value = updateRepository.checkForUpdate()
            } finally {
                _updateCheckInProgress.value = false
            }
        }
    }

    fun refreshAppDataUsage() {
        if (_appDataUsage.value.loading) return
        viewModelScope.launch {
            _appDataUsage.value = _appDataUsage.value.copy(loading = true, failed = false)
            try {
                _appDataUsage.value = AppDataUsageState(
                    snapshot = appDataUsageRepository.calculate(settings.value),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _appDataUsage.value = AppDataUsageState(
                    snapshot = _appDataUsage.value.snapshot,
                    failed = true,
                )
            }
        }
    }

    fun downloadAndInstallUpdate(available: UpdateCheckResult.UpdateAvailable) {
        launchUpdateOperation {
            val cached = downloadedUpdate
            if (cached != null && cached.version == available.latestVersion) {
                prepareUpdateInstall(cached)
                return@launchUpdateOperation
            }
            downloadedUpdate = null
            _updateDownloadState.value = UpdateDownloadState.Downloading(
                downloadedBytes = 0L,
                totalBytes = available.updatePackage?.sizeBytes ?: 0L,
            )
            when (
                val result = updateRepository.downloadUpdate(available) { downloaded, total ->
                    _updateDownloadState.value = UpdateDownloadState.Downloading(
                        downloadedBytes = downloaded,
                        totalBytes = total,
                    )
                }
            ) {
                is UpdateDownloadResult.Downloaded -> {
                    downloadedUpdate = result.update
                    prepareUpdateInstall(result.update)
                }
                is UpdateDownloadResult.Failed -> {
                    _updateDownloadState.value = UpdateDownloadState.Failed(result.reason)
                }
            }
        }
    }

    /** Called after returning from Android's "install unknown apps" settings screen. */
    fun resumeUpdateInstallAfterPermission() {
        if (_updateDownloadState.value !is UpdateDownloadState.AwaitingInstallPermission) return
        val canInstallPackages = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            true
        } else {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: RuntimeException) {
                false
            }
        }
        if (!canInstallPackages) {
            return
        }
        val cached = downloadedUpdate ?: return
        launchUpdateOperation { prepareUpdateInstall(cached) }
    }

    fun reportUpdateActionUnavailable(intent: Intent) {
        _updateDownloadState.value =
            UpdateDownloadState.Failed(updateActionUnavailableFailure(intent.action))
    }

    private suspend fun prepareUpdateInstall(update: DownloadedUpdate) {
        _updateDownloadState.value = UpdateDownloadState.Preparing(update.version)
        when (val request = updateRepository.prepareInstall(update)) {
            is UpdateInstallRequest.LaunchInstaller -> {
                _updateDownloadState.value = UpdateDownloadState.ReadyToInstall(update.version)
                _updateInstallActions.emit(request.intent)
            }
            is UpdateInstallRequest.RequestPermission -> {
                _updateDownloadState.value =
                    UpdateDownloadState.AwaitingInstallPermission(update.version)
                _updateInstallActions.emit(request.intent)
            }
            is UpdateInstallRequest.Failed -> {
                downloadedUpdate = null
                _updateDownloadState.value = UpdateDownloadState.Failed(request.reason)
            }
        }
    }

    private fun launchUpdateOperation(block: suspend () -> Unit): Boolean {
        if (!updateOperationMutex.tryLock()) return false
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            block()
        }
        updateOperationJob = job
        job.invokeOnCompletion {
            if (updateOperationJob === job) {
                updateOperationJob = null
            }
            updateOperationMutex.unlock()
        }
        job.start()
        return true
    }

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

    /** First-launch picker: applies the language and records the device-local "chosen" flag. */
    fun chooseFirstLaunchLanguage(value: AppLanguage) = viewModelScope.launch {
        repository.setAppLanguage(value)
        repository.markLanguageSelected()
    }
    fun setUserName(value: String) = launch { repository.setUserName(value) }
    fun setHomeGreetingSettings(
        userName: String,
        greetings: List<HomeGreetingTemplate>,
        onComplete: (Boolean) -> Unit,
    ) = viewModelScope.launch {
        try {
            repository.setHomeGreetingSettings(userName, greetings)
            onComplete(true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message?.takeIf(String::isNotBlank)
                ?: "主页问候保存失败 / Could not save home greetings"
            onComplete(false)
        }
    }
    fun setThemeColor(value: Int) = launch { repository.setThemeColor(value) }
    fun setThemeSecondaryColors(value: List<Int>) =
        launch { repository.setThemeSecondaryColors(value) }
    fun setFontScale(value: Float) = launch { repository.setFontScale(value) }
    fun setCompactMode(value: Boolean) = launch { repository.setCompactMode(value) }
    fun setAppearanceSettings(
        visualStyle: VisualStyle,
        customTheme: CustomThemeSettings,
        darkMode: DarkMode,
        appLanguage: AppLanguage,
        themeColorArgb: Int,
        themeSecondaryColorsArgb: List<Int>,
        fontScale: Float,
        compactMode: Boolean,
        backgroundImageUri: String?,
        backgroundImageOpacity: Float,
        backgroundImageBlurDp: Float,
        onComplete: (Boolean) -> Unit,
    ) = viewModelScope.launch {
        try {
            repository.setAppearanceSettings(
                visualStyle = visualStyle,
                customTheme = customTheme,
                darkMode = darkMode,
                appLanguage = appLanguage,
                themeColorArgb = themeColorArgb,
                themeSecondaryColorsArgb = themeSecondaryColorsArgb,
                fontScale = fontScale,
                compactMode = compactMode,
                backgroundImageUri = backgroundImageUri,
                backgroundImageOpacity = backgroundImageOpacity,
                backgroundImageBlurDp = backgroundImageBlurDp,
            )
            onComplete(true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _settingsError.value = "外观设置保存失败 / Could not save appearance settings"
            onComplete(false)
        }
    }

    fun persistAppBackground(uri: Uri, onComplete: (Boolean) -> Unit) =
        viewModelScope.launch {
            var permissionTaken = false
            try {
                val alreadyPersisted = context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
                if (!alreadyPersisted) {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    permissionTaken = true
                }
                val readable = withContext(Dispatchers.IO) {
                    val declaredSize = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                    }
                    require(declaredSize == null || declaredSize in 1..MAX_APP_BACKGROUND_IMAGE_BYTES) {
                        "背景图片超过 128 MiB 安全上限 / Background image exceeds the 128 MiB limit"
                    }
                    val actualSize = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= MAX_APP_BACKGROUND_IMAGE_BYTES) {
                                "背景图片超过 128 MiB 安全上限 / Background image exceeds the 128 MiB limit"
                            }
                        }
                        total
                    } ?: 0L
                    require(actualSize > 0L) {
                        "所选文件不是可读取的图片 / The selected file is not a readable image"
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                    options.outWidth in 1..32_768 && options.outHeight in 1..32_768
                }
                require(readable) {
                    "所选文件不是可读取的图片 / The selected file is not a readable image"
                }
                onComplete(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (permissionTaken) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                _settingsError.value = error.message?.takeIf(String::isNotBlank)
                    ?: "背景图片导入失败 / Could not import background image"
                onComplete(false)
            }
        }
    fun setUseChineseLauncherName(value: Boolean) =
        launch { repository.setUseChineseLauncherName(value) }
    fun setLauncherIcon(value: LauncherIcon) = launch { repository.setLauncherIcon(value) }
    fun setUsageTrackingEnabled(value: Boolean) =
        launch { repository.setUsageTrackingEnabled(value) }
    fun setStepTrackingEnabled(value: Boolean) =
        launch { repository.setStepTrackingEnabled(value) }
    fun setSaveOriginalToGallery(value: Boolean) =
        launch { repository.setSaveOriginalToGallery(value) }
    fun setPhotoLocationEnabled(value: Boolean) =
        launch { repository.setPhotoLocationEnabled(value) }
    fun setFileNamePattern(value: String) = launch { repository.setFileNamePattern(value) }
    fun setTemplate(value: String) = launch { repository.setMarkdownTemplate(value) }
    fun setImageNamePattern(value: String) = launch { repository.setImageNamePattern(value) }
    fun setImageMaxWidth(value: Int) = launch { repository.setImageMaxWidth(value) }
    fun setImageMaxHeight(value: Int) = launch { repository.setImageMaxHeight(value) }
    fun setMarkdownHeadingSizes(value: List<Float>) =
        launch { repository.setMarkdownHeadingSizes(value) }
    fun setMealImageCompressionEnabled(value: Boolean) =
        launch { repository.setMealImageCompressionEnabled(value) }
    fun setMealImageCompressionQuality(value: Int) =
        launch { repository.setMealImageCompressionQuality(value) }
    fun setBrowserHome(value: String) = launch { repository.setBrowserHomeUrl(value) }
    fun setBrowserTheme(value: BrowserTheme) = launch { repository.setBrowserTheme(value) }
    fun setBrowserDesktopMode(value: Boolean) = launch { repository.setBrowserDesktopMode(value) }
    fun setThoughtRowHeight(value: Int) = launch { repository.setThoughtRowHeight(value) }
    fun setVaultRowHeight(value: Int) = launch { repository.setVaultRowHeight(value) }
    fun setThoughtSettings(
        rowHeightDp: Int,
        reopenMode: ThoughtReopenMode,
        displayMode: ThoughtDisplayMode,
        highlightColorArgb: Int,
        editorMaxHeightDp: Int,
    ) = launch {
        repository.setThoughtSettings(
            rowHeightDp = rowHeightDp,
            reopenMode = reopenMode,
            displayMode = displayMode,
            highlightColorArgb = highlightColorArgb,
            editorMaxHeightDp = editorMaxHeightDp,
        )
    }
    fun persistPoetryFont(uri: Uri, onComplete: (Boolean) -> Unit) =
        viewModelScope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val readable = withContext(Dispatchers.IO) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        runCatching { Typeface.Builder(descriptor.fileDescriptor).build() }.isSuccess
                    } == true
                }
                require(readable) { "所选文件不是可读取的字体 / The selected file is not a readable font" }
                onComplete(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message?.takeIf(String::isNotBlank)
                    ?: "字体导入失败 / Could not import font"
                onComplete(false)
            }
        }

    fun setPoetryDisplaySettings(
        fontUri: String?,
        fontSizeSp: Float,
        lineSpacing: Float,
        textAlignment: PoetryTextAlignment,
        showSource: Boolean,
        showQuoteMark: Boolean,
        sevenCharacterWrapEnabled: Boolean,
        onComplete: (Boolean) -> Unit,
    ) = viewModelScope.launch {
        try {
            repository.setPoetryDisplaySettings(
                fontUri = fontUri,
                fontSizeSp = fontSizeSp,
                lineSpacing = lineSpacing,
                textAlignment = textAlignment,
                showSource = showSource,
                showQuoteMark = showQuoteMark,
                sevenCharacterWrapEnabled = sevenCharacterWrapEnabled,
            )
            onComplete(true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message?.takeIf(String::isNotBlank)
                ?: "诗词显示设置保存失败 / Could not save poetry display settings"
            onComplete(false)
        }
    }
    fun setMealCalendarImageMaxHeight(value: Int) =
        launch { repository.setMealCalendarImageMaxHeight(value) }
    fun setMealCalendarShowCaptions(value: Boolean) =
        launch { repository.setMealCalendarShowCaptions(value) }
    fun setMealCalendarWrap(enabled: Boolean, photosPerRow: MealPhotosPerRow) =
        launch { repository.setMealCalendarWrap(enabled, photosPerRow) }
    fun setMealPhotoFilter(
        value: MealPhotoFilterSettings,
        onSaved: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            repository.setMealPhotoFilter(value)
            onSaved()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message?.takeIf(String::isNotBlank)
                ?: "滤镜设置保存失败 / Could not save filter settings"
        }
    }
    fun toggleMealPhotoFilter() {
        val current = settings.value.mealPhotoFilter
        setMealPhotoFilter(current.copy(enabled = !current.enabled))
    }
    fun setMealButtonsUseIcons(value: Boolean) = launch { repository.setMealButtonsUseIcons(value) }
    fun setMealButtonIcons(value: List<String>) = launch { repository.setMealButtonIcons(value) }
    fun hasCloudSyncCredentials(config: CloudSyncConfig): Boolean =
        cloudSyncSecretStore.hasCredentials(config)
    fun cloudSyncConfigForEdit(config: CloudSyncConfig): CloudSyncConfig =
        runCatching { cloudSyncSecretStore.hydrate(config) }.getOrDefault(config)
    fun pendingCloudSyncJson(): List<PendingCloudSyncJson> =
        cloudSyncService.pendingIncomingJson()

    fun saveCloudSyncConfig(
        config: CloudSyncConfig,
        clearExistingCredentials: Boolean = false,
        onDone: (Boolean) -> Unit = {},
    ) = viewModelScope.launch {
        try {
            val normalized = if (config.serviceType == CloudSyncServiceType.S3_COMPATIBLE) {
                config.copy(
                    endpointUrl = normalizeS3EndpointScheme(
                        config.endpointUrl,
                        config.allowInsecureHttp,
                    ),
                )
            } else {
                config
            }
            val stored = when {
                normalized.serviceType == CloudSyncServiceType.S3_COMPATIBLE -> normalized.copy(
                    webDavPassword = "",
                )

                clearExistingCredentials -> normalized.copy(webDavPassword = "")
                else -> {
                    val existing = cloudSyncSecretStore.hydrate(normalized)
                    normalized.copy(
                        webDavPassword = normalized.webDavPassword.ifBlank {
                            existing.webDavPassword
                        },
                    )
                }
            }
            val candidate = if (stored.enabled) {
                stored
            } else {
                stored.copy(
                    enabled = true,
                    s3AccessKey = stored.s3AccessKey.ifBlank { "not-configured" },
                    s3SecretKey = stored.s3SecretKey.ifBlank { "not-configured" },
                )
            }
            candidate.validateForSync()
            val isS3 = stored.serviceType == CloudSyncServiceType.S3_COMPATIBLE
            val withSavedCredentials = if (isS3) {
                stored
            } else {
                cloudSyncSecretStore.save(
                    stored,
                    clearExisting = clearExistingCredentials,
                )
            }
            val current = settings.value
            val configs = if (current.cloudSyncConfigs.any { it.id == config.id }) {
                current.cloudSyncConfigs.map { item ->
                    if (item.id == config.id) withSavedCredentials else item
                }
            } else {
                current.cloudSyncConfigs + withSavedCredentials
            }
            repository.setCloudSyncSettings(current.cloudSyncEnabled, configs)
            if (isS3) {
                // DataStore now owns the plaintext values. Delete the legacy encrypted copy only
                // after that durable write succeeds, so a failed save cannot lose credentials.
                cloudSyncSecretStore.delete(stored.id)
            }
            onDone(true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message ?: "无法保存云端同步配置"
            onDone(false)
        }
    }

    fun deleteCloudSyncConfig(config: CloudSyncConfig) = viewModelScope.launch {
        try {
            val current = settings.value
            repository.setCloudSyncSettings(
                enabled = current.cloudSyncEnabled,
                configs = current.cloudSyncConfigs.filterNot { it.id == config.id },
            )
            cloudSyncSecretStore.delete(config.id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message ?: "无法删除云端同步配置"
        }
    }

    fun copyCloudSyncConfig(config: CloudSyncConfig) = viewModelScope.launch {
        try {
            val copy = config.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = "${config.name} - 副本",
                enabled = false,
                webDavPassword = "",
                s3AccessKey = "",
                s3SecretKey = "",
                s3SessionToken = "",
            )
            val current = settings.value
            repository.setCloudSyncSettings(
                enabled = current.cloudSyncEnabled,
                configs = current.cloudSyncConfigs + copy,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message ?: "无法复制云端同步配置"
        }
    }

    fun setCloudSyncEnabled(
        enabled: Boolean,
        onDone: (Boolean) -> Unit = {},
    ) = viewModelScope.launch {
        try {
            val current = settings.value
            if (enabled) {
                val configs = current.cloudSyncConfigs.filter(CloudSyncConfig::enabled)
                require(configs.isNotEmpty()) { "请先新增并启用至少一个同步配置" }
                configs.forEach { config ->
                    require(
                        CloudSyncContent.DIARIES !in config.selectedContents ||
                            current.diaryTreeUri != null,
                    ) { "同步日记前请先选择日记目录" }
                    require(
                        CloudSyncContent.MEDIA !in config.selectedContents ||
                            current.mediaTreeUri != null,
                    ) { "同步媒体前请先选择媒体目录" }
                    cloudSyncSecretStore.hydrate(config).validateForSync()
                }
            }
            repository.setCloudSyncSettings(enabled, current.cloudSyncConfigs)
            onDone(true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message ?: "无法更新云端同步设置"
            onDone(false)
        }
    }

    fun syncCloudNow() = syncCloud(CloudSyncRunMode.NORMAL)

    fun forceUploadCloudNow() = syncCloud(CloudSyncRunMode.FORCE_UPLOAD)

    fun forceDownloadCloudNow() = syncCloud(CloudSyncRunMode.FORCE_DOWNLOAD)

    private fun syncCloud(mode: CloudSyncRunMode) = viewModelScope.launch {
        try {
            cloudSyncService.syncEnabled(mode)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = formatCloudSyncError(error)
        } finally {
            _cloudSyncUndoAvailable.value = cloudSyncUndoStore.hasUndo()
        }
    }

    fun undoLastCloudSync() = viewModelScope.launch {
        try {
            cloudSyncService.undoLastSync()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = formatCloudSyncError(error)
        } finally {
            _cloudSyncUndoAvailable.value = cloudSyncUndoStore.hasUndo()
        }
    }

    fun restoreIncomingCloudJson(fileName: String, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch {
            try {
                cloudSyncService.restoreIncomingJson(fileName)
                onDone(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message ?: "无法导入云端 JSON"
                onDone(false)
            }
        }
    fun setDefaultPage(value: NavItemId) = launch { repository.setDefaultPage(value) }
    fun setNavItems(value: List<NavItemConfig>) = launch { repository.setNavItems(value) }
    fun setNavigationSettings(
        defaultPage: NavItemId,
        items: List<NavItemConfig>,
        showLabels: Boolean,
        musicVisualizerEnabled: Boolean,
        musicVisualizerStyle: MusicVisualizerStyle,
        musicVisualizerFrequencyMode: MusicVisualizerFrequencyMode,
        musicVisualizerMinFrequencyHz: Int,
        musicVisualizerMaxFrequencyHz: Int,
        onDone: (Boolean) -> Unit = {},
    ) = launchSave(onDone) {
        repository.setNavigationSettings(
            defaultPage,
            items,
            showLabels,
            musicVisualizerEnabled,
            musicVisualizerStyle,
            musicVisualizerFrequencyMode,
            musicVisualizerMinFrequencyHz,
            musicVisualizerMaxFrequencyHz,
        )
    }
    fun setMorePageSettings(
        showDescriptions: Boolean,
        columns: Int,
        items: List<NavItemConfig>,
        onDone: (Boolean) -> Unit = {},
    ) = launchSave(onDone) {
        repository.setMorePageSettings(showDescriptions, columns, items)
    }

    fun setAiPageSettings(
        fontSizeSp: Float,
        replyBoxWidthDp: Float,
        agentPrompt: String,
        onDone: (Boolean) -> Unit = {},
    ) = launchSave(onDone) {
        repository.setAiPageSettings(fontSizeSp, replyBoxWidthDp, agentPrompt)
    }
    fun setMorePageOrder(
        value: List<NavItemId>,
        onDone: (Boolean) -> Unit = {},
    ) = viewModelScope.launch {
        try {
            repository.setMorePageOrder(value)
            onDone(true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onDone(false)
        }
    }
    fun setRssSettings(maxItemsPerFeed: Int, showSummaries: Boolean) =
        launch { repository.setRssSettings(maxItemsPerFeed, showSummaries) }
    fun saveAiConfig(config: AiModelConfig, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch {
            try {
                val endpoint = URL(config.endpointUrl.trim())
                val protocol = endpoint.protocol.lowercase()
                require(endpoint.host.isNotBlank() && endpoint.userInfo.isNullOrBlank() &&
                    (protocol == "https" || protocol == "http" && config.allowInsecureHttp)) {
                    "AI 接口地址无效，或尚未允许 HTTP"
                }
                require(config.name.isNotBlank()) { "请填写配置名称" }
                require(config.model.isNotBlank()) { "请填写模型名称" }
                val normalized = config.copy(
                    name = config.name.trim(), endpointUrl = config.endpointUrl.trim(),
                    model = config.model.trim(), enabled = true, apiKey = config.apiKey.take(8_192),
                )
                val current = settings.value.aiConfigs
                repository.setAiConfigs(
                    if (current.any { it.id == normalized.id }) current.map { if (it.id == normalized.id) normalized else it }
                    else current + normalized,
                )
                onDone(true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _settingsError.value = error.message ?: "无法保存 AI 配置"
                onDone(false)
            }
        }

    fun copyAiConfig(config: AiModelConfig) = viewModelScope.launch {
        try {
            val copy = config.copy(id = java.util.UUID.randomUUID().toString(), name = "${config.name} - 副本", enabled = true)
            repository.setAiConfigs(settings.value.aiConfigs + copy)
        } catch (error: Exception) { _settingsError.value = error.message ?: "无法复制 AI 配置" }
    }

    fun deleteAiConfig(config: AiModelConfig) = viewModelScope.launch {
        val snapshot = settings.value
        repository.setAiConfigs(snapshot.aiConfigs.filterNot { it.id == config.id })
        if (snapshot.aiChatConfigId == config.id) repository.setAiChatConfigId(null)
        val textId = snapshot.calorieTextConfigId.takeUnless { it == config.id }
        val imageId = snapshot.calorieImageConfigId.takeUnless { it == config.id }
        if (textId != snapshot.calorieTextConfigId || imageId != snapshot.calorieImageConfigId) {
            repository.setCalorieEstimationSettings(false, textId, imageId,
                snapshot.calorieVisionPrompt, snapshot.calorieTextPrompt)
        }
    }

    private suspend fun migrateLegacyAiKeys(current: AppSettings): AppSettings {
        var allCurrentKeysReadable = true
        val migrated = withContext(Dispatchers.IO) {
            current.aiConfigs.map { config ->
                if (config.apiKey.isNotEmpty()) return@map config
                if (!legacyAiKeyMigrationStore.containsApiKey(config.id)) return@map config
                val endpoint = runCatching { URL(config.endpointUrl) }.getOrNull() ?: run {
                    allCurrentKeysReadable = false
                    return@map config
                }
                val legacyKey = legacyAiKeyMigrationStore.readApiKey(config.id, endpoint)
                    ?.take(8_192)
                    .orEmpty()
                if (legacyKey.isEmpty()) {
                    allCurrentKeysReadable = false
                    config
                } else {
                    config.copy(apiKey = legacyKey)
                }
            }
        }
        return try {
            if (migrated != current.aiConfigs) repository.setAiConfigs(migrated)
            if (allCurrentKeysReadable) {
                withContext(Dispatchers.IO) { legacyAiKeyMigrationStore.discardLegacyStore() }
            }
            current.copy(aiConfigs = migrated)
        } catch (_: Exception) {
            // Keep the obsolete store intact so migration can be retried on the next launch.
            current
        }
    }

    fun setCalorieEstimationSettings(
        enabled: Boolean, textConfigId: String?, imageConfigId: String?,
        visionPrompt: String, textPrompt: String,
    ) = launch {
        val textValid = settings.value.aiConfigs.any { it.id == textConfigId && it.type == com.deskcubby.app.data.model.AiModelType.TEXT }
        val imageValid = settings.value.aiConfigs.any { it.id == imageConfigId && it.type == com.deskcubby.app.data.model.AiModelType.IMAGE }
        require(!enabled || textValid && imageValid) { "请选择有效的文字模型和图片模型" }
        repository.setCalorieEstimationSettings(enabled, textConfigId, imageConfigId, visionPrompt, textPrompt)
    }
    fun acknowledgeNavigationIntro() = launch { repository.acknowledgeNavigationIntro() }
    fun setTutorialModeEnabled(value: Boolean) = launch {
        repository.setTutorialModeEnabled(value)
    }
    fun acknowledgeTutorialPage(pageId: String) = launch {
        repository.acknowledgeTutorialPage(pageId)
    }
    fun resetTutorialPages() = launch { repository.resetTutorialPages() }
    fun setHomeWidgets(value: List<String>) = launch { repository.setHomeWidgets(value) }
    fun setHomeWidgetTitles(value: List<String>) = launch { repository.setHomeWidgetTitles(value) }
    fun setBottomNavShowLabels(value: Boolean) = launch { repository.setBottomNavShowLabels(value) }
    fun setHomeWidgetBordersEnabled(value: Boolean) =
        launch { repository.setHomeWidgetBordersEnabled(value) }
    fun setHomePageSettings(
        userName: String,
        widgetBordersEnabled: Boolean,
        widgets: List<String>,
        gameShortcuts: List<String>,
        visibleWidgetTitles: List<String>,
        mealButtonsUseIcons: Boolean,
        mealButtonIcons: List<String>,
    ) = launch {
        repository.setHomePageSettings(
            userName = userName,
            widgetBordersEnabled = widgetBordersEnabled,
            widgets = widgets,
            gameShortcuts = gameShortcuts,
            visibleWidgetTitles = visibleWidgetTitles,
            mealButtonsUseIcons = mealButtonsUseIcons,
            mealButtonIcons = mealButtonIcons,
        )
    }

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

    fun openBackupJsonPreview() {
        if (_backupJsonPreview.value.busy) return
        viewModelScope.launch {
            _backupJsonPreview.value = BackupJsonPreviewState(busy = true)
            try {
                _backupJsonPreview.value = BackupJsonPreviewState(
                    json = backupRepository.currentBackupJson(),
                )
            } catch (error: CancellationException) {
                _backupJsonPreview.value = BackupJsonPreviewState()
                throw error
            } catch (error: Exception) {
                _backupJsonPreview.value = BackupJsonPreviewState(
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: "无法生成 JSON / Could not build JSON",
                )
            }
        }
    }

    fun closeBackupJsonPreview() {
        _backupJsonPreview.value = BackupJsonPreviewState()
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
            "${summary.dateRecordCount} 个日期记录、${summary.poetryCategoryCount} 个诗词分类、" +
            "${summary.poemCount} 首诗词、${summary.vaultItemCount} 条收藏夹密文、" +
            "${summary.gameStateCount} 个游戏存档、${summary.gameStatisticCount} 项游戏统计、" +
            "${summary.usageDeviceCount} 台设备的 " +
            "${summary.usageDayCount} 天使用时间、${summary.readerProgressCount} 本书的阅读进度；" +
            "$actionEn: ${summary.thoughtCount} thoughts, " +
            "${summary.categoryCount} thought categories, ${summary.favoriteCount} bookmarks, " +
            "${summary.dateRecordCount} date records, " +
            "${summary.poetryCategoryCount} poetry categories, ${summary.poemCount} poems, " +
            "${summary.vaultItemCount} encrypted Vault items, " +
            "${summary.gameStateCount} game saves, ${summary.gameStatisticCount} game metrics, " +
            "${summary.usageDayCount} screen-time days from ${summary.usageDeviceCount} devices, " +
            "and reading progress for ${summary.readerProgressCount} books"

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

    private fun launchSave(
        onDone: (Boolean) -> Unit,
        block: suspend () -> Unit,
    ) = viewModelScope.launch {
        try {
            block()
            onDone(true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _settingsError.value = error.message?.takeIf(String::isNotBlank)
                ?: "设置保存失败 / Could not save settings"
            onDone(false)
        }
    }
}

private const val MAX_APP_BACKGROUND_IMAGE_BYTES = 128L * 1024L * 1024L
