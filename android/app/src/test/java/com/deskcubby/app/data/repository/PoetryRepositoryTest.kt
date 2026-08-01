package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoetryRepositoryTest {
    @Test
    fun formatsPoemSource() {
        assertEquals("— 李商隐《夜雨寄北》", PoetryRepository.formatSource("夜雨寄北", "李商隐"))
        assertEquals("— 《无题》", PoetryRepository.formatSource("无题", ""))
        assertEquals(
            "夜雨寄北",
            PoetryRepository.titleFromFormattedSource("— 李商隐《夜雨寄北》"),
        )
        assertEquals("", PoetryRepository.titleFromFormattedSource("— 今日诗词"))
    }

    @Test
    fun matchingLegacyVerseExpandsFromTheSameCachedPoem() {
        val stored = "何当共剪西窗烛，却话巴山夜雨时。"
        val resolution = PoetryRepository.resolveSavedContentForEdit(
            storedContent = stored,
            storedSource = "李商隐《夜雨寄北》",
            cached = DailyPoem(
                content = stored,
                source = "— 李商隐《夜雨寄北》",
                fullContent =
                    "君问归期未有期，巴山夜雨涨秋池。\n何当共剪西窗烛，却话巴山夜雨时。",
            ),
        )

        assertEquals(PoemEditContentStatus.EXPANDED_FROM_DAILY_CACHE, resolution.status)
        assertEquals(
            "君问归期未有期，巴山夜雨涨秋池。\n何当共剪西窗烛，却话巴山夜雨时。",
            resolution.content,
        )
    }

    @Test
    fun oldCacheWithoutFullPoemSafelyKeepsStoredContent() {
        val stored = "山中何事？松花酿酒，春水煎茶。"
        val resolution = PoetryRepository.resolveSavedContentForEdit(
            storedContent = stored,
            storedSource = "— 张可久《人月圆·山中书事》",
            cached = DailyPoem(
                content = stored,
                source = "— 张可久《人月圆·山中书事》",
                fullContent = "",
            ),
        )

        assertEquals(PoemEditContentStatus.LEGACY_CACHE_WITHOUT_FULL_CONTENT, resolution.status)
        assertEquals(stored, resolution.content)
    }

    @Test
    fun unrelatedOrCorruptCacheNeverReplacesStoredPoem() {
        val stored = "床前明月光，疑是地上霜。"
        val unrelated = PoetryRepository.resolveSavedContentForEdit(
            storedContent = stored,
            storedSource = "李白《静夜思》",
            cached = DailyPoem(
                content = stored,
                source = "杜甫《春望》",
                fullContent = "国破山河在，城春草木深。",
            ),
        )
        val corrupt = PoetryRepository.resolveSavedContentForEdit(
            storedContent = stored,
            storedSource = "李白《静夜思》",
            cached = DailyPoem(
                content = stored,
                source = "李白《静夜思》",
                fullContent = "并不包含已保存诗句",
            ),
        )

        assertEquals(PoemEditContentStatus.STORED_CONTENT, unrelated.status)
        assertEquals(stored, unrelated.content)
        assertEquals(PoemEditContentStatus.STORED_CONTENT, corrupt.status)
        assertEquals(stored, corrupt.content)
    }

    @Test
    fun oversizedCachedFullPoemIsNotSilentlyTruncatedOrSubstituted() {
        val stored = "一句"
        val resolution = PoetryRepository.resolveSavedContentForEdit(
            storedContent = stored,
            storedSource = "作者《标题》",
            cached = DailyPoem(
                content = stored,
                source = "作者《标题》",
                fullContent = stored + "长".repeat(4_000),
            ),
        )

        assertEquals(PoemEditContentStatus.CACHED_FULL_CONTENT_TOO_LONG, resolution.status)
        assertEquals(stored, resolution.content)
    }

    @Test
    fun refreshSelectionPrefersANovelPoemAndNeverFallsBackToARepeat() {
        val first = DailyPoem("第一句", "作者《第一首》", title = "第一首")
        val second = DailyPoem("第二句", "作者《第二首》", title = "第二首")

        assertEquals(
            second,
            PoetryRepository.chooseFreshPoem(
                candidates = listOf(first, second),
                current = first,
                recentFingerprints = listOf(PoetryRepository.poemFingerprint(first)),
            ),
        )
        assertNull(
            PoetryRepository.chooseFreshPoem(
                candidates = listOf(first),
                current = first,
                recentFingerprints = listOf(PoetryRepository.poemFingerprint(first)),
            ),
        )
    }
}
