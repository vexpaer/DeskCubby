@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.diary.filter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr
import kotlin.math.roundToInt

/**
 * A self-contained draft editor. [onSave] owns persistence and navigation after a successful save.
 */
@Composable
fun MealPhotoFilterSettingsScreen(
    settings: MealPhotoFilterSettings,
    onBack: () -> Unit,
    onSave: (MealPhotoFilterSettings) -> Unit,
    previewImageModel: Any? = null,
) {
    val initial = settings.normalized()
    var enabled by rememberSaveable(initial.enabled) { mutableStateOf(initial.enabled) }
    var brightness by rememberSaveable(initial.brightness) { mutableStateOf(initial.brightness) }
    var contrast by rememberSaveable(initial.contrast) { mutableStateOf(initial.contrast) }
    var saturation by rememberSaveable(initial.saturation) { mutableStateOf(initial.saturation) }
    var warmth by rememberSaveable(initial.warmth) { mutableStateOf(initial.warmth) }
    var tint by rememberSaveable(initial.tint) { mutableStateOf(initial.tint) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }

    val draft = MealPhotoFilterSettings(
        enabled = enabled,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        warmth = warmth,
        tint = tint,
    ).normalized()
    val dirty = draft != initial

    fun leaveOrConfirm() {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    BackHandler(onBack = ::leaveOrConfirm)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("吃历滤镜", "Meal photo filter")) },
                navigationIcon = {
                    IconButton(onClick = ::leaveOrConfirm) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
                    }
                },
                actions = {
                    TextButton(
                        enabled = dirty,
                        onClick = { onSave(draft) },
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("保存", "Save"))
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "meal-filter-switch") {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    role = PanelRole.FEATURE,
                    padding = PaddingValues(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("统一照片滤镜", "One filter for every photo"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                tr(
                                    "只改变吃历中的显示效果，不会修改原始图片。",
                                    "Changes meal-calendar rendering only; original files stay untouched.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            }

            item(key = "meal-filter-preview") {
                FilterPreview(
                    settings = draft,
                    imageModel = previewImageModel,
                )
            }

            item(key = "meal-filter-adjustments") {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    padding = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tr("调整", "Adjustments"),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = draft.hasVisibleAdjustment(),
                                onClick = {
                                    brightness = MealPhotoFilterSettings.DEFAULT_BRIGHTNESS
                                    contrast = MealPhotoFilterSettings.DEFAULT_CONTRAST
                                    saturation = MealPhotoFilterSettings.DEFAULT_SATURATION
                                    warmth = MealPhotoFilterSettings.DEFAULT_WARMTH
                                    tint = MealPhotoFilterSettings.DEFAULT_TINT
                                },
                            ) {
                                Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(tr("重置", "Reset"))
                            }
                        }

                        FilterAdjustmentSlider(
                            label = tr("亮度", "Brightness"),
                            valueLabel = signedPercent(brightness),
                            value = brightness,
                            onValueChange = { brightness = it.roundToStep(0.05f) },
                            valueRange = MealPhotoFilterSettings.MIN_BRIGHTNESS..
                                MealPhotoFilterSettings.MAX_BRIGHTNESS,
                            steps = 39,
                        )
                        FilterAdjustmentSlider(
                            label = tr("对比度", "Contrast"),
                            valueLabel = "${(contrast * 100f).roundToInt()}%",
                            value = contrast,
                            onValueChange = { contrast = it.roundToStep(0.05f) },
                            valueRange = MealPhotoFilterSettings.MIN_CONTRAST..
                                MealPhotoFilterSettings.MAX_CONTRAST,
                            steps = 39,
                        )
                        FilterAdjustmentSlider(
                            label = tr("饱和度", "Saturation"),
                            valueLabel = "${(saturation * 100f).roundToInt()}%",
                            value = saturation,
                            onValueChange = { saturation = it.roundToStep(0.05f) },
                            valueRange = MealPhotoFilterSettings.MIN_SATURATION..
                                MealPhotoFilterSettings.MAX_SATURATION,
                            steps = 39,
                        )
                        FilterAdjustmentSlider(
                            label = tr("色温", "Warmth"),
                            valueLabel = signedPercent(warmth),
                            value = warmth,
                            onValueChange = { warmth = it.roundToStep(0.05f) },
                            valueRange = MealPhotoFilterSettings.MIN_WARMTH..
                                MealPhotoFilterSettings.MAX_WARMTH,
                            steps = 39,
                            startLabel = tr("冷", "Cool"),
                            endLabel = tr("暖", "Warm"),
                        )
                        FilterAdjustmentSlider(
                            label = tr("色调", "Tint"),
                            valueLabel = signedPercent(tint),
                            value = tint,
                            onValueChange = { tint = it.roundToStep(0.05f) },
                            valueRange = MealPhotoFilterSettings.MIN_TINT..
                                MealPhotoFilterSettings.MAX_TINT,
                            steps = 39,
                            startLabel = tr("绿色", "Green"),
                            endLabel = tr("洋红", "Magenta"),
                        )
                    }
                }
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(tr("设置尚未保存", "Unsaved settings")) },
            text = {
                Text(
                    tr(
                        "返回会丢失刚才的滤镜调整。",
                        "Going back will discard your filter adjustments.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onSave(draft)
                    },
                ) { Text(tr("保存", "Save")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(tr("继续编辑", "Keep editing"))
                    }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            onBack()
                        },
                    ) { Text(tr("放弃", "Discard")) }
                }
            },
        )
    }
}

@Composable
private fun FilterPreview(
    settings: MealPhotoFilterSettings,
    imageModel: Any?,
) {
    val shape = deskCubbyVisuals.mediaShape
    val colorFilter = remember(settings) { settings.asComposeColorFilter() }
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        role = PanelRole.MEDIA,
        padding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("实时预览", "Live preview"), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (settings.enabled) tr("滤镜已开启", "Filter on")
                    else tr("滤镜已关闭", "Filter off"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (settings.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = tr("滤镜效果预览", "Filter effect preview"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(shape),
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clip(shape)
                        .graphicsLayer { this.colorFilter = colorFilter }
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFE94B5F),
                                    Color(0xFFFFB13B),
                                    Color(0xFF75C66A),
                                    Color(0xFF45A7D9),
                                    Color(0xFF7457C8),
                                ),
                            ),
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Black, Color.Gray, Color.White),
                                ),
                            ),
                    )
                }
            }
            Text(
                tr(
                    "调整会立即显示在预览中；保存后同一效果会应用到全部吃历照片。",
                    "Adjustments update this preview immediately. Saving applies the same look to every meal photo.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterAdjustmentSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    startLabel: String? = null,
    endLabel: String? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
        if (startLabel != null && endLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    startLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    endLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun signedPercent(value: Float): String {
    val percent = (value * 100f).roundToInt()
    return if (percent > 0) "+$percent%" else "$percent%"
}

private fun Float.roundToStep(step: Float): Float = (this / step).roundToInt() * step
