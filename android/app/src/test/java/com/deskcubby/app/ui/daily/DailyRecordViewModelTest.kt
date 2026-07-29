package com.deskcubby.app.ui.daily

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyRecordViewModelTest {
    @Test
    fun selectTappedPlaceholderSelectsTheWholeXxToken() {
        val previous = TextFieldValue("喝水 xx 杯")
        val selected = selectTappedPlaceholder(previous, previous.copy(selection = TextRange(4)))
        assertEquals(TextRange(3, 5), selected.selection)
    }

    @Test
    fun selectTappedPlaceholderDoesNotAlterActualTextEdits() {
        val previous = TextFieldValue("喝水 xx 杯", selection = TextRange(3, 5))
        val edited = TextFieldValue("喝水 8 杯", selection = TextRange(4))
        assertEquals(edited, selectTappedPlaceholder(previous, edited))
    }
}
