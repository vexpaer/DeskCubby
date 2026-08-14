package com.deskcubby.app.ui.desk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskcubby.app.ui.theme.tr

/**
 * First-run / no-data state. Blank space is the design: a single quiet line and a "+" affordance.
 */
@Composable
internal fun DeskEmptyState(
    firstLaunch: Boolean,
    onQuickCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = if (firstLaunch) {
                tr(
                    "这是你的日子留下痕迹的地方。",
                    "This is where your days leave traces.",
                )
            } else {
                tr(
                    "今天还没有留下什么。",
                    "Nothing here yet.",
                )
            },
            color = scheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .size(56.dp)
                .clickable(onClickLabel = "Quick capture") { onQuickCapture() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = scheme.primary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

/**
 * A lightweight, in-place quick-capture tray that fans out existing create actions without a
 * dialog. Selecting an action invokes the mapped existing flow.
 */
@Composable
internal fun DeskQuickCapture(
    expanded: Boolean,
    onSelectDiary: () -> Unit,
    onSelectIdea: () -> Unit,
    onSelectPhoto: () -> Unit,
    onSelectEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!expanded) return
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer.copy(alpha = 0.96f))
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            CaptureAction("✎", tr("日记", "Diary"), onSelectDiary)
            CaptureAction("✦", tr("小巧思", "Idea"), onSelectIdea)
            CaptureAction("□", tr("照片", "Photo"), onSelectPhoto)
            CaptureAction("○", tr("事件", "Event"), onSelectEvent)
        }
    }
}

@Composable
private fun CaptureAction(
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClickLabel = label) { onClick() }.padding(8.dp),
    ) {
        Text(text = glyph, color = scheme.primary, fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(text = label, color = scheme.onSurface, fontSize = 13.sp)
    }
}
