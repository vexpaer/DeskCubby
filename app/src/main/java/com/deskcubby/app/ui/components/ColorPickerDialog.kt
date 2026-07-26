package com.deskcubby.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.deskcubby.app.ui.theme.tr
import java.util.Locale
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

                Text(
                    text = tr("色相", "Hue"),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = hue,
                    onValueChange = { updateFromSliders(newHue = it) },
                    valueRange = 0f..360f,
                )

                Text(
                    text = tr("饱和度", "Saturation"),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = saturation,
                    onValueChange = { updateFromSliders(newSaturation = it) },
                    valueRange = 0f..1f,
                )

                Text(
                    text = tr("明度", "Value"),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
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
