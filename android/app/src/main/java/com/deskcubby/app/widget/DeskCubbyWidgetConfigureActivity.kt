@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.deskcubby.app.MainActivity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class DeskCubbyWidgetConfigureActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var instanceStore: DesktopWidgetInstanceStore
    @Inject lateinit var renderer: DesktopWidgetRenderer

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var finishingConfiguration = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            val requestedId = intent?.getStringExtra(EXTRA_CONFIG_ID)
                ?: AppWidgetManager.getInstance(this@DeskCubbyWidgetConfigureActivity)
                    .getAppWidgetOptions(appWidgetId)
                    .getString(EXTRA_CONFIG_ID)
            val requested = settings.desktopWidgetConfigs.firstOrNull { it.id == requestedId }
            if (requested != null) {
                bindAndFinish(requested)
                return@launch
            }
            setContent {
                DeskCubbyTheme(settings) {
                    WidgetConfigurationChooser(
                        settings = settings,
                        onChoose = ::bindAndFinish,
                        onOpenDesigner = {
                            startActivity(
                                Intent(this@DeskCubbyWidgetConfigureActivity, MainActivity::class.java)
                                    .putExtra(
                                        DesktopWidgetRenderer.EXTRA_START_ROUTE,
                                        NavItemId.WIDGETS.route,
                                    ),
                            )
                            finish()
                        },
                        onCancel = ::finish,
                    )
                }
            }
        }
    }

    private fun bindAndFinish(config: DesktopWidgetConfig) {
        if (finishingConfiguration) return
        finishingConfiguration = true
        lifecycleScope.launch {
            instanceStore.bind(appWidgetId, config.id)
            val manager = AppWidgetManager.getInstance(this@DeskCubbyWidgetConfigureActivity)
            // A configuration activity does not receive an initial APPWIDGET_UPDATE on every
            // launcher. Render directly before RESULT_OK so OEM launchers never commit a blank
            // initialLayout, then keep the normal broadcast/WorkManager refresh as compensation.
            try {
                withContext(Dispatchers.IO) {
                    renderer.update(manager, intArrayOf(appWidgetId))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The XML initial layout remains usable; the durable update worker will retry.
            }
            // Configuration activities do not receive a guaranteed follow-up onUpdate(). Queue
            // a unique durable refresh as well, so a launcher that ignores the pre-result update
            // gets another chance after it has committed this widget ID.
            DesktopWidgetUpdateScheduler.enqueueImmediate(
                this@DeskCubbyWidgetConfigureActivity,
            )
            DesktopWidgetUpdateScheduler.ensurePeriodic(
                this@DeskCubbyWidgetConfigureActivity,
            )
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_CONFIG_ID = "com.deskcubby.app.extra.WIDGET_CONFIG_ID"
    }
}

@Composable
private fun WidgetConfigurationChooser(
    settings: AppSettings,
    onChoose: (DesktopWidgetConfig) -> Unit,
    onOpenDesigner: () -> Unit,
    onCancel: () -> Unit,
) {
    val english = settings.appLanguage == AppLanguage.ENGLISH
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (english) "Choose a card" else "选择小卡片") },
                navigationIcon = {
                    TextButton(onClick = onCancel) {
                        Text(if (english) "Cancel" else "取消")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.desktopWidgetConfigs.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (english) {
                                "Create a reusable card in DeskCubby before adding this widget."
                            } else {
                                "请先在 DeskCubby 中创建可复用的小卡片，再添加到桌面。"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onOpenDesigner, modifier = Modifier.fillMaxWidth()) {
                            Text(if (english) "Open card designer" else "打开小卡片设计页")
                        }
                    }
                }
            } else {
                items(settings.desktopWidgetConfigs, key = DesktopWidgetConfig::id) { config ->
                    Card(onClick = { onChoose(config) }, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(config.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${config.widthCells} × ${config.heightCells}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    TextButton(onClick = onOpenDesigner, modifier = Modifier.fillMaxWidth()) {
                        Text(if (english) "Manage cards in DeskCubby" else "在 DeskCubby 中管理")
                    }
                }
            }
        }
    }
}
