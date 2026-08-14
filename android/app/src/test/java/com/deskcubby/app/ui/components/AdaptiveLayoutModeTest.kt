package com.deskcubby.app.ui.components

import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutModeTest {

    private fun info(width: Float, height: Float) = WindowInfo(
        widthDp = width.dp,
        heightDp = height.dp,
        isLandscape = width > height,
        smallestWidthDp = minOf(width, height).dp,
    )

    @Test
    fun smallPhoneIsCompact() {
        assertEquals(LayoutMode.COMPACT, resolveLayoutMode(info(411f, 914f)))
        assertEquals(LayoutMode.COMPACT, resolveLayoutMode(info(500f, 900f)))
    }

    @Test
    fun widePhoneUnderExpandedIsMedium() {
        assertEquals(LayoutMode.MEDIUM, resolveLayoutMode(info(790f, 400f)))
    }

    @Test
    fun largeLandscapeTabletIsExpanded() {
        assertEquals(LayoutMode.EXPANDED, resolveLayoutMode(info(1024f, 768f)))
        assertEquals(LayoutMode.EXPANDED, resolveLayoutMode(info(1440f, 900f)))
    }

    @Test
    fun portraitTabletNeverForcesThreePanes() {
        assertEquals(LayoutMode.MEDIUM, resolveLayoutMode(info(900f, 1200f)))
    }
}
