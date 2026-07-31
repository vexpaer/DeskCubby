package com.deskcubby.app.ui.poetry

import org.junit.Assert.assertEquals
import org.junit.Test

class PoetryTypographyTest {
    @Test
    fun wrapsSevenCharactersWithTrailingPunctuation() {
        assertEquals(
            "两个黄鹂鸣翠柳，\n一行白鹭上青天。",
            wrapSevenCharacterVerse("两个黄鹂鸣翠柳，一行白鹭上青天。"),
        )
    }

    @Test
    fun preservesManualLinesAndShortVerses() {
        assertEquals(
            "床前明月光\n疑是地上霜",
            wrapSevenCharacterVerse("床前明月光\n疑是地上霜"),
        )
    }

    @Test
    fun wrapsLongLineWithoutPunctuation() {
        assertEquals(
            "abcdefg\nhijklmn\no",
            wrapSevenCharacterVerse("abcdefghijklmno"),
        )
    }

    @Test
    fun onlyRecognizesPoemsMadeOfSevenCharacterClauses() {
        assertEquals(
            true,
            isSevenCharacterPoem("两个黄鹂鸣翠柳，一行白鹭上青天。"),
        )
        assertEquals(
            false,
            isSevenCharacterPoem("山中何事？松花酿酒，春水煎茶。"),
        )
        assertEquals("望月怀远", poetryTitleFromSource("— 张九龄《望月怀远》"))
    }
}
