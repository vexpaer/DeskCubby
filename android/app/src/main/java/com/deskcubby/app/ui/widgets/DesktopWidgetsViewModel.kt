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
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.widget.DeskCubbyWidgetConfigureActivity
import com.deskcubby.app.widget.DeskCubbyWidgetProvider
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
                DeskCubbyWidgetProvider.requestUpdate(context)
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
        if (!manager.isRequestPinAppWidgetSupported) {
            _message.value = if (english) {
                "This launcher does not support in-app pinning. Add DeskCubby from the launcher's Widgets panel."
            } else {
                "当前桌面不支持应用内添加，请从桌面的“小组件/窗口小工具”面板添加 DeskCubby。"
            }
            return
        }
        val accepted = manager.requestPinAppWidget(
            ComponentName(context, DeskCubbyWidgetProvider::class.java),
            Bundle().apply {
                putString(DeskCubbyWidgetConfigureActivity.EXTRA_CONFIG_ID, config.id)
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
                Intent(context, DeskCubbyWidgetProvider::class.java)
                    .setAction(DeskCubbyWidgetProvider.ACTION_PIN_SUCCEEDED)
                    .putExtra(DeskCubbyWidgetConfigureActivity.EXTRA_CONFIG_ID, config.id),
                PendingIntent.FLAG_UPDATE_CURRENT or if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                },
            ),
        )
        _message.value = if (accepted) {
            if (english) {
                "Sent to the launcher. Confirm placement, then resize it there if needed."
            } else {
                "已交给桌面，请确认放置；需要时可在桌面继续拖动缩放。"
            }
        } else if (english) {
            "The launcher declined the pin request."
        } else {
            "桌面未接受添加请求。"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val APPROX_WIDGET_CELL_DP = 70
    }
}
