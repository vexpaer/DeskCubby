package com.deskcubby.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.deskcubby.app.ui.theme.tr
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Color as AndroidColor

private const val OPAQUE_MASK: Int = 0xFF shl 24

private fun hsvToOpaqueArgb(hue: Float, saturation: Float, value: Float): Int {
    val safeHue = if (hue >= 360f) 359.99f else hue.coerceAtLeast(0f)
    val hsv = floatArrayOf(safeHue, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    return AndroidColor.HSVToColor(hsv) or OPAQUE_MASK
}

private fun formatHex(argb: Int): String =
    String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

private fun parseHexOrNull(input: String): Int? {
    val cleaned = input.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return cleaned.toInt(16) or OPAQUE_MASK
}

/**
 * HSV color picker dialog. The confirmed color is always fully opaque
 * (alpha is forced to 0xFF); alpha is intentionally not exposed.
 */
@Composable
fun ColorPickerDialog(
    initialColorArgb: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    title: String = tr("选择颜色", "Pick a color"),
) {
    val initialHsv = remember(initialColorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColorArgb or OPAQUE_MASK, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var hsvValue by remember { mutableFloatStateOf(initialHsv[2]) }
    var hexText by remember { mutableStateOf(formatHex(initialColorArgb or OPAQUE_MASK)) }
    var hexError by remember { mutableStateOf(false) }

    val currentArgb = hsvToOpaqueArgb(hue, saturation, hsvValue)
    val previewColor = Color(currentArgb)
    val onPreviewColor = if (previewColor.luminance() > 0.5f) Color.Black else Color.White

    fun updateFromSliders(newHue: Float = hue, newSaturation: Float = saturation, newValue: Float = hsvValue) {
        hue = newHue
        saturation = newSaturation
        hsvValue = newValue
        hexText = formatHex(hsvToOpaqueArgb(newHue, newSaturation, newValue))
        hexError = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(previewColor, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatHex(currentArgb),
                        color = onPreviewColor,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                HexHoneycombPicker(
                    hue = hue,
                    saturation = saturation,
                    value = hsvValue,
                    onPick = { pickedHue, pickedSaturation ->
                        updateFromSliders(
                            newHue = pickedHue,
                            newSaturation = pickedSaturation,
                        )
                    },
                )

                CompactColorSlider(
                    label = tr("色相", "Hue"),
                    value = hue,
                    onValueChange = { updateFromSliders(newHue = it) },
                    valueRange = 0f..360f,
                )

                CompactColorSlider(
                    label = tr("饱和度", "Saturation"),
                    value = saturation,
                    onValueChange = { updateFromSliders(newSaturation = it) },
                    valueRange = 0f..1f,
                )

                CompactColorSlider(
                    label = tr("明度", "Value"),
                    value = hsvValue,
                    onValueChange = { updateFromSliders(newValue = it) },
                    valueRange = 0f..1f,
                )

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        hexText = input
                        val parsed = parseHexOrNull(input)
                        if (parsed != null) {
                            val hsv = FloatArray(3)
                            AndroidColor.colorToHSV(parsed, hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            hsvValue = hsv[2]
                            hexError = false
                        } else {
                            hexError = true
                        }
                    },
                    label = { Text(tr("十六进制", "Hex")) },
                    placeholder = { Text("#RRGGBB") },
                    isError = hexError,
                    supportingText = if (hexError) {
                        { Text(tr("格式应为 #RRGGBB", "Expected format: #RRGGBB")) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentArgb or OPAQUE_MASK) }) {
                Text(tr("确定", "OK"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel"))
            }
        },
    )
}

@Composable
private fun CompactColorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(0.28f),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(0.72f),
        )
    }
}

@Composable
private fun HexHoneycombPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onPick: (hue: Float, saturation: Float) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val selection = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(152.dp)
            .pointerInput(hue, value) {
                detectTapGestures { tap ->
                    val cells = honeycombCells(size.width.toFloat(), size.height.toFloat())
                    cells.minByOrNull { cell ->
                        val dx = tap.x - cell.center.x
                        val dy = tap.y - cell.center.y
                        dx * dx + dy * dy
                    }?.takeIf { cell ->
                        val dx = tap.x - cell.center.x
                        val dy = tap.y - cell.center.y
                        dx * dx + dy * dy <= cell.radius * cell.radius * 1.45f
                    }?.let { cell ->
                        onPick(
                            if (cell.saturation < 0.001f) hue else cell.hue,
                            cell.saturation,
                        )
                    }
                }
            },
    ) {
        val cells = honeycombCells(size.width, size.height)
        val selected = cells.minByOrNull { cell ->
            val hueDistance = circularHueDistance(cell.hue, hue) / 180f
            abs(cell.saturation - saturation) + hueDistance * max(cell.saturation, saturation)
        }
        cells.forEach { cell ->
            val path = hexagonPath(cell.center, cell.radius)
            drawPath(
                path = path,
                color = Color(
                    hsvToOpaqueArgb(
                        hue = cell.hue,
                        saturation = cell.saturation,
                        value = value,
                    ),
                ),
            )
            drawPath(
                path = path,
                color = outline,
                style = Stroke(width = 0.75.dp.toPx(), join = StrokeJoin.Round),
            )
            if (cell == selected) {
                drawPath(
                    path = path,
                    color = selection,
                    style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round),
                )
            }
        }
    }
}

private data class HoneycombCell(
    val center: Offset,
    val radius: Float,
    val hue: Float,
    val saturation: Float,
)

private fun honeycombCells(width: Float, height: Float): List<HoneycombCell> {
    if (width <= 0f || height <= 0f) return emptyList()
    val coordinates = buildList {
        for (q in -HONEYCOMB_RINGS..HONEYCOMB_RINGS) {
            for (r in -HONEYCOMB_RINGS..HONEYCOMB_RINGS) {
                val s = -q - r
                if (max(abs(q), max(abs(r), abs(s))) <= HONEYCOMB_RINGS) {
                    add(q to r)
                }
            }
        }
    }
    val sqrtThree = sqrt(3f)
    val rawCenters = coordinates.map { (q, r) ->
        Offset(
            x = sqrtThree * (q + r / 2f),
            y = 1.5f * r,
        )
    }
    val minX = rawCenters.minOf(Offset::x)
    val maxX = rawCenters.maxOf(Offset::x)
    val minY = rawCenters.minOf(Offset::y)
    val maxY = rawCenters.maxOf(Offset::y)
    val margin = 4f
    val radius = min(
        (width - margin * 2f) / (maxX - minX + sqrtThree),
        (height - margin * 2f) / (maxY - minY + 2f),
    ).coerceAtLeast(1f)
    val drawingWidth = (maxX - minX) * radius
    val drawingHeight = (maxY - minY) * radius
    val offsetX = (width - drawingWidth) / 2f - minX * radius
    val offsetY = (height - drawingHeight) / 2f - minY * radius
    val center = Offset(width / 2f, height / 2f)
    return rawCenters.mapIndexed { index, raw ->
        val point = Offset(
            x = offsetX + raw.x * radius,
            y = offsetY + raw.y * radius,
        )
        val (q, r) = coordinates[index]
        val ring = max(abs(q), max(abs(r), abs(-q - r)))
        val saturation = ring.toFloat() / HONEYCOMB_RINGS
        val angle = Math.toDegrees(
            atan2(
                (point.y - center.y).toDouble(),
                (point.x - center.x).toDouble(),
            ),
        ).toFloat()
        HoneycombCell(
            center = point,
            radius = radius * 0.98f,
            hue = (angle + 360f) % 360f,
            saturation = saturation,
        )
    }
}

private fun hexagonPath(center: Offset, radius: Float): Path = Path().apply {
    repeat(6) { index ->
        val angle = -PI / 2.0 + index * PI / 3.0
        val point = Offset(
            x = center.x + cos(angle).toFloat() * radius,
            y = center.y + sin(angle).toFloat() * radius,
        )
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

private fun circularHueDistance(first: Float, second: Float): Float {
    val direct = abs(first - second) % 360f
    return min(direct, 360f - direct)
}

private const val HONEYCOMB_RINGS = 5
