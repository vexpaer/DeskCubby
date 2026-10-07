package com.deskcubby.app.ui.desk.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deskcubby.app.ui.components.CountUpNumber
import com.deskcubby.app.ui.desk.model.DeskDayRecap
import com.deskcubby.app.ui.desk.model.RecapSaveState
import com.deskcubby.app.ui.desk.model.spanLabel
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr

/**
 * The card that rises after the day's objects are swept into the drawer: today's counts, the span
 * of recorded time, and an explicit choice to write the recap into today's diary.
 */
@Composable
internal fun DeskDayRecapCard(
    recap: DeskDayRecap,
    saveState: RecapSaveState,
    onSave: () -> Unit,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 28.dp,
        role = PanelRole.FEATURE,
        padding = PaddingValues(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = tr("今天，收好了", "Today, put away"),
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = recap.date.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RecapMetric(recap.diaryWords, tr("字", "Words"))
                RecapMetric(recap.ideaCount, tr("小巧思", "Thoughts"))
                RecapMetric(recap.photoCount, tr("照片", "Photos"))
                RecapMetric(recap.momentCount, tr("痕迹", "Moments"))
            }
            recap.spanLabel()?.let { span ->
                Text(
                    text = tr("记录时间 {span}", "Recorded {span}").replace("{span}", span),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            val status = when (saveState) {
                RecapSaveState.SAVED -> tr("已写入今日日记", "Saved to today's diary")
                RecapSaveState.FAILED -> tr("写入失败，日记没有被改动，可以重试", "Could not save. Your diary was not changed; try again.")
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saveState == RecapSaveState.FAILED) scheme.error else scheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReopen) { Text(tr("重新摊开", "Back to the desk")) }
                Button(
                    onClick = onSave,
                    enabled = !recap.isEmpty &&
                        saveState != RecapSaveState.SAVING &&
                        saveState != RecapSaveState.SAVED,
                ) {
                    if (saveState == RecapSaveState.SAVING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = scheme.onPrimary,
                        )
                    } else {
                        Text(tr("写入今日日记", "Save to today's diary"))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecapMetric(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CountUpNumber(
            target = value,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
