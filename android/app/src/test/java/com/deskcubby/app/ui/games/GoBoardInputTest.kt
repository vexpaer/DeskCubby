package com.deskcubby.app.ui.games

import com.deskcubby.app.games.GoGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBoardInputTest {
    @Test
    fun `every drawn intersection maps back to the same move`() {
        GoGame.SUPPORTED_SIZES.forEach { boardSize ->
            val geometry = requireNotNull(goBoardGeometry(360f, 360f, boardSize))
            repeat(boardSize) { y ->
                repeat(boardSize) { x ->
                    assertEquals(
                        GoGame.Point(x, y),
                        goIntersectionForTap(
                            tapX = geometry.originX + x * geometry.spacing,
                            tapY = geometry.originY + y * geometry.spacing,
                            boardWidth = 360f,
                            boardHeight = 360f,
                            boardSize = boardSize,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `space between grid lines snaps continuously to its nearest intersection`() {
        val geometry = requireNotNull(goBoardGeometry(360f, 360f, 19))

        val upperLeft = goIntersectionForTap(
            tapX = geometry.originX + geometry.spacing * 0.49f,
            tapY = geometry.originY + geometry.spacing * 0.49f,
            boardWidth = 360f,
            boardHeight = 360f,
            boardSize = 19,
        )
        val lowerRight = goIntersectionForTap(
            tapX = geometry.originX + geometry.spacing * 0.51f,
            tapY = geometry.originY + geometry.spacing * 0.51f,
            boardWidth = 360f,
            boardHeight = 360f,
            boardSize = 19,
        )

        assertEquals(GoGame.Point(0, 0), upperLeft)
        assertEquals(GoGame.Point(1, 1), lowerRight)
    }

    @Test
    fun `outer decoration is ignored but edge intersections keep half-cell targets`() {
        val geometry = requireNotNull(goBoardGeometry(360f, 360f, 9))
        val justInside = geometry.originX - geometry.spacing * 0.49f
        val justOutside = geometry.originX - geometry.spacing * 0.51f

        assertEquals(
            GoGame.Point(0, 4),
            goIntersectionForTap(
                tapX = justInside,
                tapY = geometry.originY + geometry.spacing * 4f,
                boardWidth = 360f,
                boardHeight = 360f,
                boardSize = 9,
            ),
        )
        assertNull(
            goIntersectionForTap(
                tapX = justOutside,
                tapY = geometry.originY + geometry.spacing * 4f,
                boardWidth = 360f,
                boardHeight = 360f,
                boardSize = 9,
            ),
        )
    }

    @Test
    fun `non-square bounds center the same geometry for drawing and input`() {
        val geometry = requireNotNull(goBoardGeometry(420f, 300f, 9))

        assertTrue(geometry.originX > geometry.originY)
        assertEquals(60f, geometry.originX - geometry.originY, 0.001f)
        assertEquals(
            GoGame.Point(4, 4),
            goIntersectionForTap(
                tapX = 210f,
                tapY = 150f,
                boardWidth = 420f,
                boardHeight = 300f,
                boardSize = 9,
            ),
        )
    }

    @Test
    fun `invalid or non-finite bounds cannot create a move`() {
        assertNull(goBoardGeometry(0f, 360f, 9))
        assertNull(goBoardGeometry(Float.NaN, 360f, 9))
        assertNull(goBoardGeometry(360f, 360f, 1))
        assertNull(
            goIntersectionForTap(
                tapX = Float.POSITIVE_INFINITY,
                tapY = 10f,
                boardWidth = 360f,
                boardHeight = 360f,
                boardSize = 9,
            ),
        )
    }
}
