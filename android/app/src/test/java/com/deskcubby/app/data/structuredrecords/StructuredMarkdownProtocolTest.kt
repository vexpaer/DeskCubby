package com.deskcubby.app.data.structuredrecords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredMarkdownProtocolTest {

    /** The design's canonical sleep example. */
    @Test
    fun parseSleepExample() {
        val content = "睡觉：[<!--dc:f_system_sleep_time-->02:37<!--dc:/f_system_sleep_time-->]"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(1, occurrences.size)
        assertEquals("f_system_sleep_time", occurrences[0].fieldId)
        assertEquals("02:37", occurrences[0].rawValue)
    }

    @Test
    fun roundTripValue() {
        val content = "做了 <!--dc:f_pushups-->20<!--dc:/f_pushups--> 个俯卧撑。"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        val replaced = StructuredMarkdownProtocol.replaceValue(content, occurrences[0], "30")
        val reparsed = StructuredMarkdownProtocol.parse(replaced)
        assertEquals("30", reparsed[0].rawValue)
        // Other prose untouched.
        assertTrue(replaced.startsWith("做了 "))
        assertTrue(replaced.endsWith(" 个俯卧撑。"))
    }

    @Test
    fun multiFieldSameLine() {
        val content = "甲：<!--dc:f_a-->1<!--dc:/f_a--> 乙：<!--dc:f_b-->2<!--dc:/f_b-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(2, occurrences.size)
        assertEquals(listOf("f_a", "f_b"), occurrences.map { it.fieldId })
    }

    @Test
    fun sameFieldMultipleTimesKeepsOrder() {
        val content = "<!--dc:f_w-->一<!--dc:/f_w-->x<!--dc:f_w-->二<!--dc:/f_w-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(2, occurrences.size)
        assertEquals(listOf("一", "二"), occurrences.map { it.rawValue })
        assertEquals(listOf(0, 1), occurrences.map { it.orderInFile })
    }

    @Test
    fun unknownFieldIdStillParses() {
        val content = "未知：<!--dc:f_missing_abc-->值<!--dc:/f_missing_abc-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(1, occurrences.size)
        assertEquals("f_missing_abc", occurrences[0].fieldId)
    }

    @Test
    fun corruptEndMarkerDoesNotDeleteProse() {
        // Missing close marker: the value must stay in the document and no occurrence is produced.
        val content = "正文文字<!--dc:f_x-->未闭合"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(0, occurrences.size)
        assertEquals(content, StructuredMarkdownProtocol.stripMarkers(content))
    }

    @Test
    fun mismatchedEndMarkerIsIgnored() {
        val content = "<!--dc:f_x-->值<!--dc:/f_y-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertTrue(occurrences.isEmpty())
    }

    @Test
    fun nestedSameFieldIsRejected() {
        val content = "<!--dc:f_x--><!--dc:f_x-->内<!--dc:/f_x--><!--dc:/f_x-->"
        // The outer marker has a nested same-field open before its close, so it must not be parsed
        // as one spanning value; only the innermost well-formed marker is produced.
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(1, occurrences.size)
        assertEquals("内", occurrences[0].rawValue)
    }

    @Test
    fun supportsChineseEmojiSpacesAndPunctuation() {
        val content = "今天做的事很多：<!--dc:f_word_today-->今天终于把功能做完了，心情不错😀 继续加油！<!--dc:/f_word_today-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(1, occurrences.size)
        assertEquals("今天终于把功能做完了，心情不错😀 继续加油！", occurrences[0].rawValue)
    }

    @Test
    fun stripMarkersLeavesVisibleText() {
        val content = "午饭：[<!--dc:f_lunch_time-->12:36<!--dc:/f_lunch_time-->]"
        assertEquals("午饭：[12:36]", StructuredMarkdownProtocol.stripMarkers(content))
    }

    @Test
    fun parseIgnoresMarkersInsideFencedCodeBlock() {
        val content = buildString {
            append("正文：<!--dc:f_a-->真实<!--dc:/f_a-->\n")
            append("```markdown\n")
            append("示例 <!--dc:f_a-->假数据<!--dc:/f_a-->\n")
            append("```\n")
            append("尾部：<!--dc:f_b-->也是真的<!--dc:/f_b-->")
        }
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(listOf("f_a", "f_b"), occurrences.map { it.fieldId })
        assertEquals(listOf("真实", "也是真的"), occurrences.map { it.rawValue })
        // stripMarkers must not touch the code block between the fences.
        val stripped = StructuredMarkdownProtocol.stripMarkers(content)
        assertTrue(stripped.contains("示例 <!--dc:f_a-->假数据<!--dc:/f_a-->"))
        assertTrue(stripped.contains("正文：真实"))
    }

    @Test
    fun parseIgnoresMarkersInsideTildeFence() {
        val content = "~~~\n<!--dc:f_x-->1<!--dc:/f_x-->\n~~~\n正文：<!--dc:f_x-->2<!--dc:/f_x-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(1, occurrences.size)
        assertEquals("2", occurrences[0].rawValue)
    }

    @Test
    fun parseIgnoresMarkersInsideIndentedFence() {
        // Both delimiters are indented (1-3 spaces); the block must still be treated as fenced.
        val content = "   ```\n   <!--dc:f_x-->1<!--dc:/f_x-->\n   ```\n正文：<!--dc:f_x-->2<!--dc:/f_x-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(1, occurrences.size)
        assertEquals("2", occurrences[0].rawValue)
    }

    @Test
    fun parseIgnoresMarkersInsideDeeplyIndentedFence() {
        // Even a 4+ space-indented ``` fence pair protects its contents from being indexed.
        val content = "    ```\n    <!--dc:f_x-->1<!--dc:/f_x-->\n    ```\na<!--dc:f_x-->2<!--dc:/f_x-->"
        val occurrences = StructuredMarkdownProtocol.parse(content)
        assertEquals(1, occurrences.size)
        assertEquals("2", occurrences[0].rawValue)
    }

    @Test
    fun buildRecordText() {
        val text = StructuredMarkdownProtocol.buildRecordText(
            listOf(
                StructuredRecordSegment.Text("做了 "),
                StructuredRecordSegment.Field("f_pushups"),
                StructuredRecordSegment.Text(" 个俯卧撑"),
            ),
            listOf("20"),
        )
        assertEquals("做了 <!--dc:f_pushups-->20<!--dc:/f_pushups--> 个俯卧撑", text)
    }
}
