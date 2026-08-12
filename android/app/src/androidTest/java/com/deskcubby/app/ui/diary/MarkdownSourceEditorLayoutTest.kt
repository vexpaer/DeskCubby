package com.deskcubby.app.ui.diary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MarkdownSourceEditorLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ordinaryMarkdownKeepsFullEditorWidthWhenMediaControlsExist() {
        composeRule.setContent {
            DeskCubbyTheme(
                AppSettings(
                    darkMode = DarkMode.LIGHT,
                    visualStyle = VisualStyle.MATERIAL,
                ),
            ) {
                var value by remember {
                    mutableStateOf(
                        TextFieldValue(
                            "This ordinary journal paragraph is intentionally long enough to " +
                                "measure the complete writing plane without a media gutter.\n" +
                                "![meal](breakfast.jpg)",
                        ),
                    )
                }
                Box(Modifier.size(width = 360.dp, height = 480.dp)) {
                    MarkdownSourceEditor(
                        value = value,
                        onValueChange = { value = it },
                        onMoveMediaLine = { _, _ -> },
                        onDeleteMedia = {},
                    )
                }
            }
        }

        val node = composeRule.onNodeWithTag(
            MARKDOWN_SOURCE_TEXT_FIELD_TEST_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val obtained = node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("Text layout semantics were unavailable", obtained == true && layouts.isNotEmpty())

        val textWidth = layouts.last().size.width
        assertTrue(
            "Text width ($textWidth px) still reserves a global media gutter in ${node.size.width} px",
            textWidth > node.size.width * 0.8f,
        )
    }
}
