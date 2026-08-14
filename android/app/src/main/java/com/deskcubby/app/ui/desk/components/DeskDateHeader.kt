package com.deskcubby.app.ui.desk.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskcubby.app.ui.desk.model.DeskAmbient
import com.deskcubby.app.ui.desk.model.DeskDateLabel

/**
 * Editorial date masthead. The date itself *is* the page title — no toolbar label is rendered.
 */
@Composable
internal fun DeskDateHeader(
    label: DeskDateLabel,
    ambient: DeskAmbient,
    onOpenAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val onBackground = scheme.onBackground
    val secondary = scheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text(
                text = label.dayNumber,
                color = onBackground,
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp,
            )
            Text(
                text = label.month,
                color = secondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label.weekday,
                color = secondary.copy(alpha = 0.82f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
            )
        }
        DeskAiOrb(onClick = onOpenAi, ambient = ambient)
    }
}

/**
 * The single minimal AI entry — a small "sparkle" glyph, not a button chip.
 */
@Composable
internal fun DeskAiOrb(
    onClick: () -> Unit,
    ambient: DeskAmbient,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (ambient == DeskAmbient.LATE_NIGHT) {
        scheme.onSurfaceVariant
    } else {
        scheme.primary
    }
    Box(
        modifier = modifier
            .width(52.dp)
            .height(52.dp)
            .clickable(onClickLabel = "Open AI") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✦",
            color = accent,
            fontSize = 26.sp,
        )
    }
}
