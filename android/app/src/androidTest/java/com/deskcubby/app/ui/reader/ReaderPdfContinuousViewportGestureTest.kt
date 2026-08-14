package com.deskcubby.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderPdfContinuousViewportGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun oneFingerDiagonalDragMovesPageOnBothAxes() {
        var firstPagePosition = Offset.Zero
        composeRule.setContent {
            val listState = rememberLazyListState()
            ReaderPdfContinuousViewport(
                viewportKey = "gesture-test",
                listState = listState,
                pageCount = 3,
                requestedZoomPercent = 200,
                onZoomPercentChanged = {},
                modifier = Modifier
                    .size(width = 360.dp, height = 640.dp)
                    .testTag(VIEWPORT_TAG),
            ) { pageIndex, displayWidth, _, horizontalOffsetPx ->
                ReaderPdfPageViewport(
                    displayWidth = displayWidth,
                    imageWidthPx = 1_000,
                    imageHeightPx = 1_000,
                    horizontalOffsetPx = horizontalOffsetPx,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .then(
                                if (pageIndex == 0) {
                                    Modifier.onGloballyPositioned {
                                        firstPagePosition = it.positionInRoot()
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val initial = firstPagePosition
        composeRule.onNodeWithTag(VIEWPORT_TAG).performTouchInput {
            swipe(
                start = center,
                end = center + Offset(-140f, -140f),
                durationMillis = 350,
            )
        }
        composeRule.waitForIdle()
        val moved = firstPagePosition

        assertTrue("horizontal page position did not move: $initial -> $moved", moved.x < initial.x)
        assertTrue("vertical page position did not move: $initial -> $moved", moved.y < initial.y)
    }

    private companion object {
        const val VIEWPORT_TAG = "reader-pdf-two-dimensional-viewport"
    }
}
