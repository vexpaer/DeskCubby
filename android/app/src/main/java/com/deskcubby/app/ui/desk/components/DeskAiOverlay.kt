package com.deskcubby.app.ui.desk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskcubby.app.ui.theme.tr

@Composable
internal fun DeskAiOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenChat: (prompt: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val scheme = MaterialTheme.colorScheme
    val titleText = tr("想做些什么？", "What are you thinking?")
    val summarizePrompt = tr("总结一下我今天的状态", "Summarize how my day went")
    val startText = tr("直接开始", "Just start typing")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = MutableInteractionSource(),
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.surface)
                .clickable(
                    interactionSource = MutableInteractionSource(),
                    indication = null,
                ) { /* swallow clicks inside */ }
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "✦", color = scheme.primary, fontSize = 28.sp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = titleText,
                color = scheme.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(scheme.outlineVariant),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = summarizePrompt,
                color = scheme.onSurfaceVariant,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChat(summarizePrompt) }
                    .padding(vertical = 14.dp),
                textAlign = TextAlign.Start,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(scheme.outlineVariant.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = startText,
                color = scheme.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { onOpenChat(null) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}
