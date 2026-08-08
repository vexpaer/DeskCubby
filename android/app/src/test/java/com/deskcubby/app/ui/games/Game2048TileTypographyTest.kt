package com.deskcubby.app.ui.games

import org.junit.Assert.assertTrue
import org.junit.Test

class Game2048TileTypographyTest {

    @Test
    fun `font shrinks as tile values gain digits`() {
        val fourDigits = tileFontSize(value = 8_192, boardSize = 4, tileWidthDp = 96f)
        val fiveDigits = tileFontSize(value = 16_384, boardSize = 4, tileWidthDp = 96f)
        val sixDigits = tileFontSize(value = 131_072, boardSize = 4, tileWidthDp = 96f)

        assertTrue(fiveDigits < fourDigits)
        assertTrue(sixDigits < fiveDigits)
    }

    @Test
    fun `long values fit the estimated single line width on every board size`() {
        for (boardSize in 4..6) {
            val tileWidth = when (boardSize) {
                4 -> 96f
                5 -> 74f
                else -> 60f
            }
            val value = 1_048_576
            val digits = value.toString().length
            val fontSize = tileFontSize(value, boardSize, tileWidth)

            assertTrue(fontSize >= 9)
            assertTrue(fontSize * digits * 0.6f <= tileWidth * 0.82f + 1f)
        }
    }
}
