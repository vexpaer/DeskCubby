package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PoetryRepositoryParsingTest {
    @Test
    fun parsesCompleteOriginWithoutUsingOnlyTheSentenceExcerpt() {
        val poem = PoetryRepository.parseSentence(
            """
            {
              "status": "success",
              "data": {
                "content": "何当共剪西窗烛，却话巴山夜雨时。",
                "origin": {
                  "title": "夜雨寄北",
                  "author": "李商隐",
                  "dynasty": "唐",
                  "content": [
                    "君问归期未有期，巴山夜雨涨秋池。",
                    "何当共剪西窗烛，却话巴山夜雨时。"
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            "君问归期未有期，巴山夜雨涨秋池。\n何当共剪西窗烛，却话巴山夜雨时。",
            poem.fullContent,
        )
        assertEquals("— 李商隐《夜雨寄北》", poem.source)
        assertEquals("唐", poem.dynasty)
    }
}
