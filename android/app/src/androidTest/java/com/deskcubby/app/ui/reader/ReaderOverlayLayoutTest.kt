package com.deskcubby.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderOverlayLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showingReaderChromeDoesNotMoveOrResizeContentPlane() {
        val controlsVisible = mutableStateOf(false)
        composeRule.setContent {
            DeskCubbyTheme(
                AppSettings(
                    darkMode = DarkMode.LIGHT,
                    visualStyle = VisualStyle.MATERIAL,
                ),
            ) {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    ReaderOverlayScaffold(
                        background = MaterialTheme.colorScheme.surface,
                        foreground = MaterialTheme.colorScheme.onSurface,
                        showReaderControls = controlsVisible.value,
                        controlsOverlayContent = true,
                        snackbarHostState = remember { SnackbarHostState() },
                        topBar = {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(96.dp)
                                    .background(Color.Black),
                            )
                        },
                    ) {}
                }
            }
        }

        val hiddenBounds = composeRule.onNodeWithTag(READER_CONTENT_PLANE_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        composeRule.runOnIdle { controlsVisible.value = true }
        composeRule.waitForIdle()
        val shownBounds = composeRule.onNodeWithTag(READER_CONTENT_PLANE_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(hiddenBounds.left, shownBounds.left, 0.01f)
        assertEquals(hiddenBounds.top, shownBounds.top, 0.01f)
        assertEquals(hiddenBounds.width, shownBounds.width, 0.01f)
        assertEquals(hiddenBounds.height, shownBounds.height, 0.01f)
    }
}
