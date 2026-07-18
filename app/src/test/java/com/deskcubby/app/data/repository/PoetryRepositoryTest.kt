package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PoetryRepositoryTest {
    @Test
    fun formatsPoemSource() {
        assertEquals("— 李商隐《夜雨寄北》", PoetryRepository.formatSource("夜雨寄北", "李商隐"))
        assertEquals("— 《无题》", PoetryRepository.formatSource("无题", ""))
    }
}
