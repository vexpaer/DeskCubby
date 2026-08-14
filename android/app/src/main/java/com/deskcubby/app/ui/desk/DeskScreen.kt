@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.deskcubby.app.ui.desk

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    onOpenTraces: () -> Unit,
    onOpenAi: (String?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    var aiOpen by remember { mutableStateOf(false) }
    var quickCaptureOpen by remember { mutableStateOf(false) }

    val entrance = produceState(initialValue = 0f, state.loading) {
        if (state.loading) return@produceState
        val animatable = Animatable(0f)
        animatable.animateTo(1f, tween(durationMillis = 520))
        value = animatable.value
    }.value

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
            DeskEntrance(entrance >= 0f) {
                DeskDateHeader(
                    label = state.dateLabel,
                    ambient = state.ambient,
                    onOpenAi = { aiOpen = true },
                )
            }

            Spacer(Modifier.height(40.dp))

            if (state.isEmpty && !state.loading) {
                DeskEntrance(entrance >= 0.45f) {
                    DeskEmptyState(
                        firstLaunch = true,
                        onQuickCapture = { quickCaptureOpen = !quickCaptureOpen },
                    )
                }
            } else {
                state.diary?.let { diary ->
                    DeskEntrance(entrance >= 0.4f) {
                        DeskDiaryObject(
                            item = diary,
                            onClick = { diary.diaryUri?.let(onOpenDiary) ?: onOpenTodayDiary() },
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                }

                if (state.ideas.isNotEmpty()) {
                    DeskEntrance(entrance >= 0.6f) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.ideas.forEach { idea ->
                                DeskIdeaObject(item = idea, onClick = onOpenIdea)
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }

                if (state.photos.isNotEmpty()) {
                    DeskEntrance(entrance >= 0.75f) {
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
            DeskEntrance(entrance >= 0.85f) {
                DeskTraces(
                    traces = state.traces,
                    totalCount = state.totalTraceCount,
                    onExpand = onOpenTraces,
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
            onSelectPhoto = { quickCaptureOpen = false; onOpenTodayDiary() },
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
    val warm = Color(0xFFC96F4A)
    return lerp(background, if (ambient == DeskAmbient.LATE_NIGHT) onBackground else warm, warmth * 0.5f)
}
