package com.deskcubby.app.ui.games

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.games.GoGame
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoBoardSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accessibilityClickPublishesAVisibleMoveAcrossAllThreeThemes() {
        val visualStyle = mutableStateOf(VisualStyle.MATERIAL)
        val game = mutableStateOf(GoGame())
        composeRule.setContent {
            DeskCubbyTheme(
                AppSettings(
                    darkMode = DarkMode.LIGHT,
                    visualStyle = visualStyle.value,
                ),
            ) {
                GoBoard(
                    game = game.value,
                    onPlay = { x, y ->
                        val mutableGame = game.value
                        val result = mutableGame.play(x, y)
                        if (result.accepted) game.value = mutableGame.snapshotCopy()
                        result.accepted
                    },
                    modifier = Modifier
                        .size(320.dp)
                        .testTag(BOARD_TAG),
                )
            }
        }

        listOf(
            VisualStyle.MATERIAL,
            VisualStyle.LIQUID_GLASS,
            VisualStyle.ORGANIC_FUTURE,
        ).forEach { style ->
            composeRule.runOnIdle {
                visualStyle.value = style
                game.value = GoGame()
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(BOARD_TAG)
                .assertHasClickAction()
                .performClick()

            composeRule.runOnIdle {
                assertEquals(GoGame.Stone.BLACK, game.value.stoneAt(4, 4))
                assertEquals(GoGame.Stone.WHITE, game.value.currentPlayer)
                assertEquals(1, game.value.turnCount)
            }
        }
    }

    @Test
    fun boardAndNavigationActionsExposeChineseAndEnglishDescriptions() {
        val language = mutableStateOf(AppLanguage.CHINESE)
        composeRule.setContent {
            DeskCubbyTheme(
                AppSettings(
                    darkMode = DarkMode.LIGHT,
                    visualStyle = VisualStyle.MATERIAL,
                    appLanguage = language.value,
                ),
            ) {
                GoBoard(
                    game = GoGame(),
                    onPlay = { _, _ -> true },
                    modifier = Modifier
                        .size(320.dp)
                        .testTag(BOARD_TAG),
                )
            }
        }

        assertBoardSemantics(
            descriptionPart = "9路围棋棋盘，黑子 0，白子 0",
            stateDescription = "黑方落子；已选择第 5 行、第 5 列；当前为空位",
            actionLabels = listOf(
                "选择左侧交叉点",
                "选择右侧交叉点",
                "选择上方交叉点",
                "选择下方交叉点",
            ),
        )

        composeRule.runOnIdle { language.value = AppLanguage.ENGLISH }
        composeRule.waitForIdle()

        assertBoardSemantics(
            descriptionPart = "9 by 9 Go board with 0 black and 0 white stones",
            stateDescription =
                "Black to play；Selected row 5, column 5；The intersection is empty",
            actionLabels = listOf(
                "Select the intersection to the left",
                "Select the intersection to the right",
                "Select the intersection above",
                "Select the intersection below",
            ),
        )
    }

    private fun assertBoardSemantics(
        descriptionPart: String,
        stateDescription: String,
        actionLabels: List<String>,
    ) {
        val semantics = composeRule.onNodeWithTag(BOARD_TAG).fetchSemanticsNode().config
        val descriptions = semantics[SemanticsProperties.ContentDescription]
        assertTrue(descriptions.any { it.contains(descriptionPart) })
        assertEquals(stateDescription, semantics[SemanticsProperties.StateDescription])
        assertEquals(
            actionLabels,
            semantics[SemanticsActions.CustomActions].map { it.label },
        )
    }

    private companion object {
        const val BOARD_TAG = "go_board"
    }
}
