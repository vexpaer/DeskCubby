package com.deskcubby.app.ui.components

import com.deskcubby.app.ui.theme.AppTypography
import com.deskcubby.app.ui.theme.GlassTypography
import com.deskcubby.app.ui.theme.withTabularFigures
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskMotionComponentsTest {
    @Test
    fun `count up duration grows with magnitude but stays bounded`() {
        assertEquals(520, countUpDurationMillis(0))
        assertTrue(countUpDurationMillis(10) < countUpDurationMillis(1_000))
        assertEquals(1_200, countUpDurationMillis(Int.MAX_VALUE))
        assertEquals(1_200, countUpDurationMillis(Int.MIN_VALUE + 1))
        assertTrue(countUpDurationMillis(-50) in 520..1_200)
    }

    @Test
    fun `style typographies keep a clear display hierarchy`() {
        assertEquals(FontFamily.Serif, AppTypography.displayLarge.fontFamily)
        assertEquals(FontFamily.Serif, AppTypography.headlineSmall.fontFamily)
        assertTrue(AppTypography.displayLarge.fontSize > AppTypography.headlineLarge.fontSize)
        assertTrue(GlassTypography.displayLarge.fontSize > GlassTypography.headlineLarge.fontSize)
        assertTrue(GlassTypography.displayLarge.letterSpacing.value < 0f)
    }

    @Test
    fun `tabular figures are enabled for counters`() {
        assertEquals("tnum", AppTypography.displaySmall.withTabularFigures().fontFeatureSettings)
    }
}
