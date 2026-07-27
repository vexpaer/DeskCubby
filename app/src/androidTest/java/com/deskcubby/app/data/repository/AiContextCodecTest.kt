package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiContextCodecTest {
    @Test
    fun roundTripUsesOnlyProviderIndependentFields() {
        val snapshot = AiContextSnapshot(
            listOf(
                AiContextItem(
                    source = AiContextSource.THOUGHT,
                    title = "计划",
                    date = "2026-07-27 12:00",
                    attribution = "工作",
                    content = "完成 AI 上下文测试",
                ),
            ),
        )

        val encoded = AiContextCodec.encode(snapshot)
        val decoded = AiContextCodec.decode(encoded)
        val root = JSONObject(encoded)
        val item = root.getJSONArray("items").getJSONObject(0)

        assertEquals(snapshot, decoded)
        assertEquals(
            setOf("source", "title", "date", "attribution", "content"),
            item.keys().asSequence().toSet(),
        )
        assertFalse(encoded.contains("content://"))
        assertFalse(encoded.contains("sha256", ignoreCase = true))
        assertFalse(item.has("id"))
        assertTrue(root.getString("instruction").contains("untrusted reference data"))
    }

    @Test
    fun rejectsMoreThanFiftyItemsWithoutTruncating() {
        val items = List(AiContextCodec.MAX_ITEMS + 1) { index ->
            AiContextItem(
                source = AiContextSource.DATE_RECORD,
                title = "日期 $index",
                date = "2026-07-27",
            )
        }

        val error = assertThrows(AiContextException::class.java) {
            AiContextCodec.encode(AiContextSnapshot(items))
        }

        assertEquals(AiContextFailure.TOO_MANY_ITEMS, error.failure)
        assertEquals(AiContextCodec.MAX_ITEMS + 1, error.itemCount)
    }

    @Test
    fun rejectsOversizedSingleItemWithoutTruncating() {
        val original = "字".repeat(AiContextCodec.MAX_ITEM_BYTES)
        val item = AiContextItem(
            source = AiContextSource.DIARY,
            title = "超长日记",
            content = original,
        )

        val error = assertThrows(AiContextException::class.java) {
            AiContextCodec.encode(AiContextSnapshot(listOf(item)))
        }

        assertEquals(AiContextFailure.ITEM_TOO_LARGE, error.failure)
        assertEquals("超长日记", error.itemTitle)
        assertTrue(error.measuredBytes!! > AiContextCodec.MAX_ITEM_BYTES)
        assertEquals(original, item.content)
    }

    @Test
    fun rejectsOversizedTotalEvenWhenEveryItemFits() {
        val items = List(5) { index ->
            AiContextItem(
                source = AiContextSource.DIARY,
                title = "日记 $index",
                content = "x".repeat(60 * 1024),
            )
        }

        val error = assertThrows(AiContextException::class.java) {
            AiContextCodec.encode(AiContextSnapshot(items))
        }

        assertEquals(AiContextFailure.TOTAL_TOO_LARGE, error.failure)
        assertTrue(error.measuredBytes!! > AiContextCodec.MAX_TOTAL_BYTES)
    }
}
