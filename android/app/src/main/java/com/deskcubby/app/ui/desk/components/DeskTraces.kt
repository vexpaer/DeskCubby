package com.deskcubby.app.ui.desk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskcubby.app.ui.desk.model.DeskTrace

@Composable
internal fun DeskTraces(
    traces: List<DeskTrace>,
    totalCount: Int,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (traces.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val secondary = scheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Today Traces",
            color = secondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
        )
        Spacer(Modifier.height(18.dp))
        traces.forEach { trace ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = trace.timeLabel,
                    color = secondary.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.width(52.dp),
                )
                Box(
                    modifier = Modifier
                        .width((12 * trace.weight).dp.coerceAtMost(40.dp))
                        .height(1.dp)
                        .background(secondary.copy(alpha = 0.4f)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = trace.label,
                    color = scheme.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.clickable { onExpand() }.padding(vertical = 6.dp),
        ) {
            Text(
                text = "+ " + traceCountLabel(totalCount),
                color = scheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun traceCountLabel(count: Int): String = count.toString() + " traces"
