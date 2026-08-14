package com.deskcubby.app.ui.widgets

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.ui.theme.translate
import com.deskcubby.app.widget.DeskCubbyWidgetConfigureActivity
import com.deskcubby.app.widget.DeskCubbyWidgetProvider
import com.deskcubby.app.widget.DesktopWidgetPinResultReceiver
import com.deskcubby.app.widget.DesktopWidgetNavigationTokenStore
import com.deskcubby.app.widget.DesktopWidgetInstanceStore
import com.deskcubby.app.widget.CloudSyncForceWidgetProvider
import com.deskcubby.app.widget.CloudSyncNowWidgetProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Collator
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

@HiltViewModel
class DesktopWidgetsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val instanceStore: DesktopWidgetInstanceStore,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    private val _launchableApps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val launchableApps: StateFlow<List<LaunchableApp>> = _launchableApps.asStateFlow()

    private val _loadingApps = MutableStateFlow(false)
    val loadingApps: StateFlow<Boolean> = _loadingApps.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun loadLaunchableApps() {
        if (_loadingApps.value || _launchableApps.value.isNotEmpty()) return
        _loadingApps.value = true
        viewModelScope.launch {
            try {
                _launchableApps.value = withContext(Dispatchers.IO) {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.queryIntentActivities(
                            intent,
                            PackageManager.ResolveInfoFlags.of(0),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.queryIntentActivities(intent, 0)
                    }
                    val collator = Collator.getInstance()
                    results.asSequence()
                        .mapNotNull { info ->
                            val packageName = info.activityInfo?.packageName
                                ?.takeIf(String::isNotBlank)
                                ?: return@mapNotNull null
                            val label = runCatching {
                                info.loadLabel(context.packageManager).toString().trim()
                            }.getOrDefault(packageName).ifBlank { packageName }
                            LaunchableApp(packageName, label)
                        }
                        .distinctBy(LaunchableApp::packageName)
                        .sortedWith { first, second -> collator.compare(first.label, second.label) }
                        .toList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "无法读取可启动应用 / Could not load launchable apps"
            } finally {
                _loadingApps.value = false
            }
        }
    }

    fun save(config: DesktopWidgetConfig, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val current = settingsRepository.settings.first().desktopWidgetConfigs
                val updated = if (current.any { it.id == config.id }) {
                    current.map { if (it.id == config.id) config else it }
                } else {
                    current + config
                }
                settingsRepository.setDesktopWidgetConfigs(updated)
                val boundInstanceIds = withContext(Dispatchers.IO) {
                    instanceStore.refreshTemplateSnapshot(config)
                }
                DeskCubbyWidgetProvider.requestUpdate(
                    context,
                    boundInstanceIds.takeIf { it.isNotEmpty() },
                )
                onDone(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.message
                    ?: "小卡片保存失败 / Could not save widget card"
                onDone(false)
            }
        }
    }

    fun delete(configId: String) {
        viewModelScope.launch {
            try {
                val current = settingsRepository.settings.first().desktopWidgetConfigs
                settingsRepository.setDesktopWidgetConfigs(current.filterNot { it.id == configId })
                DeskCubbyWidgetProvider.requestUpdate(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.message
                    ?: "小卡片删除失败 / Could not delete widget card"
            }
        }
    }

    fun requestPin(config: DesktopWidgetConfig, english: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val pinSupported = runCatching { manager.isRequestPinAppWidgetSupported }
            .getOrDefault(false)
        if (!pinSupported) {
            _message.value = desktopWidgetManualAddMessage(english)
            return
        }
        val configToken = DesktopWidgetNavigationTokenStore.issueConfigToken(config.id)
        val callbackToken = DesktopWidgetNavigationTokenStore.issueConfigToken(config.id)
        val accepted = try {
            manager.requestPinAppWidget(
                ComponentName(context, DeskCubbyWidgetProvider::class.java),
                Bundle().apply {
                    putString(DeskCubbyWidgetConfigureActivity.EXTRA_CONFIG_TOKEN, configToken)
                    putInt(
                        AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                        config.widthCells * APPROX_WIDGET_CELL_DP,
                    )
                    putInt(
                        AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                        config.heightCells * APPROX_WIDGET_CELL_DP,
                    )
                },
                PendingIntent.getBroadcast(
                    context,
                    config.id.hashCode(),
                    Intent(context, DesktopWidgetPinResultReceiver::class.java)
                        .setAction(DesktopWidgetPinResultReceiver.ACTION_PIN_SUCCEEDED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(DeskCubbyWidgetConfigureActivity.EXTRA_CONFIG_TOKEN, callbackToken),
                    PendingIntent.FLAG_UPDATE_CURRENT or if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        // The launcher must be able to add EXTRA_APPWIDGET_ID to the callback.
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    },
                ),
            )
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            DesktopWidgetNavigationTokenStore.discardConfigToken(configToken)
            DesktopWidgetNavigationTokenStore.discardConfigToken(callbackToken)
        }
        _message.value = if (accepted) {
            desktopWidgetPinAcceptedMessage(english)
        } else {
            desktopWidgetManualAddMessage(english)
        }
    }

    fun requestPinSyncWidget(forceActions: Boolean, english: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val pinSupported = runCatching { manager.isRequestPinAppWidgetSupported }
            .getOrDefault(false)
        if (!pinSupported) {
            _message.value = desktopWidgetManualAddMessage(english)
            return
        }
        val provider = if (forceActions) {
            CloudSyncForceWidgetProvider::class.java
        } else {
            CloudSyncNowWidgetProvider::class.java
        }
        val accepted = try {
            manager.requestPinAppWidget(ComponentName(context, provider), null, null)
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        _message.value = if (accepted) {
            desktopWidgetPinAcceptedMessage(english)
        } else {
            desktopWidgetManualAddMessage(english)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val APPROX_WIDGET_CELL_DP = 70
    }
}

internal fun desktopWidgetManualAddMessage(
    english: Boolean,
): String = translate(
    "桌面未完成应用内放置。请在桌面空白处双指捏合或长按，进入“小组件/窗口小工具”，" +
        "再选择 DeskCubby。若列表中没有，请检查桌面的小组件/快捷方式权限及系统后台活动设置；" +
        "最终放置仍由系统桌面决定。",
    "The launcher did not complete in-app placement. Pinch or touch and hold an empty area of " +
        "the home screen, open Widgets, and choose DeskCubby. If it is missing, check the " +
        "launcher's widget/shortcut permission and the system background-activity setting. " +
        "The launcher makes the final placement decision.",
    if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE,
)

internal fun desktopWidgetPinAcceptedMessage(
    english: Boolean,
): String = translate(
    "已交给桌面，请确认放置；若没有出现确认窗口，请打开桌面“小组件/窗口小工具”面板选择 " +
        "DeskCubby。需要时可在桌面继续拖动缩放。",
    "Sent to the launcher. Confirm placement; if no prompt appears, open the home-screen " +
        "Widgets panel and choose DeskCubby. Resize it there if needed.",
    if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE,
)
