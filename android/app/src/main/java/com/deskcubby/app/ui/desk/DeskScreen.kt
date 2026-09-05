@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.deskcubby.app.ui.desk

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.ui.desk.components.DeskAiOverlay
import com.deskcubby.app.ui.desk.components.DeskDateHeader
import com.deskcubby.app.ui.desk.components.DeskDiaryObject
import com.deskcubby.app.ui.desk.components.DeskEmptyState
import com.deskcubby.app.ui.desk.components.DeskIdeaObject
import com.deskcubby.app.ui.desk.components.DeskPhotoObject
import com.deskcubby.app.ui.desk.components.DeskQuickCapture
import com.deskcubby.app.ui.desk.components.DeskTraces
import com.deskcubby.app.ui.desk.model.DeskAmbient
import com.deskcubby.app.ui.desk.model.DeskItem

@Composable
fun DeskScreen(
    padding: PaddingValues,
    viewModel: DeskViewModel,
    onOpenDiary: (String) -> Unit,
    onOpenTodayDiary: () -> Unit,
    onOpenIdea: () -> Unit,
    onOpenPhoto: (DeskItem) -> Unit,
    onOpenEvent: () -> Unit,
    onOpenAi: (String?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    var aiOpen by remember { mutableStateOf(false) }
    var quickCaptureOpen by remember { mutableStateOf(false) }
    var tracesExpanded by remember { mutableStateOf(false) }

    // Desk's "照片" quick action reuses the system photo picker + the shared durable photo
    // pipeline (appendImageToToday), matching Home/Widget instead of just opening the diary.
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(viewModel::addMealPhoto)
    }
    LaunchedEffect(state.photoNotice) {
        state.photoNotice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            viewModel.consumePhotoNotice()
        }
    }

    // Use the Animatable's observable value directly so each module's threshold crosses at a
    // different moment, producing a real stagger. (A produceState that only writes the final value
    // after animateTo() completes would flip all thresholds on the same frame.)
    val entrance = remember { Animatable(if (state.loading) 0f else 1f) }
    LaunchedEffect(state.loading) {
        if (state.loading) {
            entrance.snapTo(0f)
        } else {
            entrance.animateTo(1f, tween(durationMillis = 520))
        }
    }
    val entranceValue = entrance.value

    val ambientTint = ambientTintColor(state.ambient, scheme.background, scheme.onBackground)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ambientTint),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = padding.calculateTopPadding() + 24.dp)
                .padding(horizontal = 32.dp)
                .padding(bottom = 48.dp + padding.calculateBottomPadding()),
        ) {
            DeskEntrance(entranceValue >= 0f) {
                DeskDateHeader(
                    label = state.dateLabel,
                    ambient = state.ambient,
                    onOpenAi = { aiOpen = true },
                )
            }

            Spacer(Modifier.height(40.dp))

            if (state.isEmpty && !state.loading) {
                DeskEntrance(entranceValue >= 0.45f) {
                    DeskEmptyState(
                        firstLaunch = true,
                        onQuickCapture = { quickCaptureOpen = !quickCaptureOpen },
                    )
                }
            } else {
                state.diary?.let { diary ->
                    DeskEntrance(entranceValue >= 0.4f) {
                        DeskDiaryObject(
                            item = diary,
                            onClick = { diary.diaryUri?.let(onOpenDiary) ?: onOpenTodayDiary() },
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                }

                if (state.ideas.isNotEmpty()) {
                    DeskEntrance(entranceValue >= 0.6f) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.ideas.forEach { idea ->
                                DeskIdeaObject(item = idea, onClick = onOpenIdea)
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }

                if (state.photos.isNotEmpty()) {
                    DeskEntrance(entranceValue >= 0.75f) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            state.photos.forEach { photo ->
                                DeskPhotoObject(item = photo, onClick = { onOpenPhoto(photo) })
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            DeskEntrance(entranceValue >= 0.85f) {
                DeskTraces(
                    traces = if (tracesExpanded) state.traces else state.traces.take(6),
                    totalCount = state.totalTraceCount,
                    onExpand = { tracesExpanded = !tracesExpanded },
                )
            }

            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Quick capture") { quickCaptureOpen = !quickCaptureOpen },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(text = "+", color = scheme.onSurfaceVariant.copy(alpha = 0.85f), fontSize = 28.sp)
            }
        }

        DeskQuickCapture(
            expanded = quickCaptureOpen,
            onSelectDiary = { quickCaptureOpen = false; onOpenTodayDiary() },
            onSelectIdea = { quickCaptureOpen = false; onOpenIdea() },
            onSelectPhoto = {
                quickCaptureOpen = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSelectEvent = { quickCaptureOpen = false; onOpenEvent() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        DeskAiOverlay(
            visible = aiOpen,
            onDismiss = { aiOpen = false },
            onOpenChat = { prompt -> aiOpen = false; onOpenAi(prompt) },
        )
    }
}

/**
 * A stagger-adjusted fade-and-rise wrapper. When system animations are disabled the underlying
 * Animatable completes immediately, so content still appears without motion.
 */
@Composable
private fun DeskEntrance(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 6 },
    ) {
        content()
    }
}

private fun ambientTintColor(ambient: DeskAmbient, background: Color, onBackground: Color): Color {
    val warmth = when (ambient) {
        DeskAmbient.MORNING -> 0.0f
        DeskAmbient.AFTERNOON -> 0.0f
        DeskAmbient.EVENING -> 0.035f
        DeskAmbient.LATE_NIGHT -> 0.07f
    }
    val warm = MaterialTheme.colorScheme.tertiary
    return lerp(background, if (ambient == DeskAmbient.LATE_NIGHT) onBackground else warm, warmth * 0.5f)
}
