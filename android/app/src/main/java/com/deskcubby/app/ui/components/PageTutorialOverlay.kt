package com.deskcubby.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr

data class PageTutorialTarget(
    val pageId: String,
    val title: String,
    val description: String,
    val hints: List<String> = emptyList(),
)

/** A blocking, accessible first-visit walkthrough shown once for each logical page. */
@Composable
fun PageTutorialOverlay(
    target: PageTutorialTarget,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassPanel(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                cornerRadius = 28.dp,
                padding = PaddingValues(24.dp),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.School,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            target.title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Text(
                        target.description,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    target.hints.forEach { hint ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("•", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(hint, modifier = Modifier.weight(1f))
                        }
                    }
                    Text(
                        tr(
                            "这段说明只显示一次；可在“设置 → 关于 → 页面教学”关闭或重新显示。",
                            "This guide appears once. Turn it off or replay it in Settings → About → Page tutorials.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("知道了", "Got it"))
                    }
                }
            }
        }
    }
}
