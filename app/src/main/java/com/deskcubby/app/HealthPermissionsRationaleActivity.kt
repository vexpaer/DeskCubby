@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import com.deskcubby.app.ui.theme.tr
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Health Connect opens this local rationale from the privacy-policy link on its
 * permission sheet. DeskCubby never uploads or adds step history to app backups.
 */
@AndroidEntryPoint
class HealthPermissionsRationaleActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            DeskCubbyTheme(settings) {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = { Text(tr("步数数据与隐私", "Step data and privacy")) },
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            tr(
                                "DeskCubby 只读取你授权的每日步数，用于本机的总览和统计图。原始健康记录不会被修改。",
                                "DeskCubby only reads the daily step totals you authorize, for on-device summaries and charts. It never modifies source health records.",
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            tr(
                                "统计结果保存在应用私有的独立 JSON 中，不进入应用 JSON 备份、云同步或 Android 自动备份。关闭统计会停止后续读取，但保留已经生成的本机历史。",
                                "Results stay in a separate app-private JSON file. They are excluded from app JSON backups, cloud sync, and Android Auto Backup. Turning tracking off stops future reads while preserving existing local history.",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = ::finish) {
                            Text(tr("返回", "Back"))
                        }
                    }
                }
            }
        }
    }
}
