package com.deskcubby.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.rememberDeskHaptics
import com.deskcubby.app.ui.theme.tr

/**
 * One-time invitation for people who installed before the Desk became the start page. Promoting
 * the Desk only changes navigation order and the start page; it never touches records.
 */
@Composable
internal fun DeskIntroCard(
    onOpenDesk: () -> Unit,
    onMakeStartPage: (onDone: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberDeskHaptics()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        role = PanelRole.FEATURE,
        padding = PaddingValues(start = 18.dp, top = 12.dp, end = 8.dp, bottom = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("认识你的桌面", "Meet your Desk"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = tr("不再显示", "Don't show again"))
                }
            }
            Text(
                text = tr(
                    "今天的日记、小巧思和照片会像纸张一样摊在桌面上，一天结束时还能把它们收起来，看一眼今天的回顾。",
                    "Today's diary, thoughts and photos lie on the Desk like paper. At the end of the day, put them away and see a recap of your day.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
            if (failed) {
                Text(
                    text = tr("设置保存失败，请重试", "Could not save the setting. Try again."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        failed = false
                        onMakeStartPage { saved ->
                            saving = false
                            failed = !saved
                            if (saved) haptics.confirm()
                        }
                    },
                ) {
                    Text(tr("设为启动页", "Make it my start page"))
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onOpenDesk) { Text(tr("先看看", "Take a look")) }
            }
        }
    }
}

