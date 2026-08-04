package com.deskcubby.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationBottomBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visualizerLayerDoesNotExpandBottomBarToParentHeight() {
        composeRule.setContent {
            DeskCubbyTheme(
                AppSettings(
                    darkMode = DarkMode.LIGHT,
                    visualStyle = VisualStyle.MATERIAL,
                ),
            ) {
                Box(
                    Modifier
                        .size(width = 360.dp, height = 640.dp)
                        .testTag(PARENT_TAG),
                ) {
                    DeskBottomBar(
                        items = listOf(NavItemConfig(NavItemId.HOME)),
                        selectedRoute = NavItemId.HOME.route,
                        showLabels = true,
                        musicVisualizerEnabled = false,
                        musicVisualizerStyle = MusicVisualizerStyle.BARS,
                        musicVisualizerFrequencyMode = MusicVisualizerFrequencyMode.ADAPTIVE,
                        musicVisualizerMinFrequencyHz = 60,
                        musicVisualizerMaxFrequencyHz = 16_000,
                        onSelected = {},
                        modifier = Modifier.testTag(BOTTOM_BAR_TAG),
                    )
                }
            }
        }

        // Compare pixels so the assertion stays independent of device density and bottom insets.
        // Before the fix both nodes were the same 640 dp height.
        val parentHeight = composeRule.onNodeWithTag(PARENT_TAG).fetchSemanticsNode().size.height
        val bottomBarHeight = composeRule.onNodeWithTag(BOTTOM_BAR_TAG)
            .fetchSemanticsNode()
            .size
            .height
        assertTrue(
            "Bottom bar ($bottomBarHeight px) expanded into parent ($parentHeight px)",
            bottomBarHeight < parentHeight / 2,
        )
    }

    private companion object {
        const val BOTTOM_BAR_TAG = "desk_bottom_bar"
        const val PARENT_TAG = "bottom_bar_parent"
    }
}
