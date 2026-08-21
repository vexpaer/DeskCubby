package com.deskcubby.app.data.structuredrecords

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredRecordDraftTest {
    private val count = StructuredField("f_count", "俯卧撑次数", StructuredFieldType.NUMBER)
    private val food = StructuredField("f_food", "午饭内容", StructuredFieldType.WORD)
    private val fields = listOf(count, food).associateBy { it.id }
    private val template = StructuredRecordTemplate(
        id = "r_test",
        name = "测试",
        segments = listOf(
            StructuredRecordSegment.Text("跑完步做了 "),
            StructuredRecordSegment.Field(count.id),
            StructuredRecordSegment.Text(" 个，午饭吃了 "),
            StructuredRecordSegment.Field(food.id),
            StructuredRecordSegment.Text("。"),
        ),
    )

    @Test
    fun pureTextTemplateIsImmediatelyReady() {
        val draft = createStructuredRecordDraft(
            StructuredRecordTemplate(
                id = "r_text",
                name = "纯文本",
                segments = listOf(StructuredRecordSegment.Text("今天状态不错。")),
            ),
            emptyMap(),
            LocalTime.NOON,
        )

        assertTrue(draft.fields.isEmpty())
        assertTrue(isStructuredDraftReady(draft))
        assertEquals(emptyList<String>(), structuredDraftValues(draft))
    }

    @Test
    fun ordinaryTextEditShiftsFieldRangesWithoutUnbinding() {
        val draft = createStructuredRecordDraft(template, fields, LocalTime.NOON)
        val edited = applyStructuredDraftEdit(draft, "今天" + draft.text)!!
        assertEquals(draft.fields[0].start + 2, edited.fields[0].start)
        assertEquals(count.id, edited.fields[0].fieldId)
        assertEquals(food.id, edited.fields[1].fieldId)
    }

    @Test
    fun editingOnePlaceholderKeepsOtherBinding() {
        val draft = createStructuredRecordDraft(template, fields, LocalTime.NOON)
        val first = draft.fields.first()
        val candidate = draft.text.replaceRange(first.start, first.endExclusive, "30")
        val edited = applyStructuredDraftEdit(draft, candidate)!!
        assertEquals("30", edited.fields.first().value)
        assertEquals(food.id, edited.fields.last().fieldId)
    }

    @Test
    fun clearingWholeFieldIsRejectedInsteadOfLeavingInvisibleBinding() {
        val draft = createStructuredRecordDraft(template, fields, LocalTime.NOON)
        val first = draft.fields.first()
        val candidate = draft.text.removeRange(first.start, first.endExclusive)
        assertNull(applyStructuredDraftEdit(draft, candidate))
    }

    @Test
    fun templateDraftCanRemoveWholeFieldBinding() {
        val draft = createStructuredTemplateDraft(template, fields)
        val first = draft.fields.first()
        val second = draft.fields.last()
        val removedLength = first.endExclusive - first.start
        val edited = applyStructuredDraftEdit(
            draft,
            draft.text.removeRange(first.start, first.endExclusive),
        )!!

        assertEquals(1, edited.fields.size)
        assertEquals(food.id, edited.fields.single().fieldId)
        assertEquals(second.start - removedLength, edited.fields.single().start)
        assertTrue(
            structuredDraftToSegments(edited).none { segment ->
                segment is StructuredRecordSegment.Field && segment.fieldId == count.id
            },
        )
    }

    @Test
    fun blankAssistedReplacementKeepsVisibleBinding() {
        val draft = createStructuredRecordDraft(template, fields, LocalTime.NOON)
        val unchanged = replaceStructuredDraftField(draft, draft.fields.first().occurrenceIndex, "   ")

        assertSame(draft, unchanged)
        assertTrue(unchanged.fields.first().endExclusive > unchanged.fields.first().start)
    }

    @Test
    fun editAcrossMultipleFieldsIsRejected() {
        val draft = createStructuredRecordDraft(template, fields, LocalTime.NOON)
        val candidate = draft.text.replaceRange(draft.fields.first().start, draft.fields.last().endExclusive, "x")
        assertNull(applyStructuredDraftEdit(draft, candidate))
    }

    @Test
    fun editedBodyRoundTripsIntoSegments() {
        var draft = createStructuredRecordDraft(template, fields, LocalTime.NOON)
        draft = applyStructuredDraftEdit(draft, "今天" + draft.text)!!
        draft = replaceStructuredDraftField(draft, draft.fields[0].occurrenceIndex, "30")
        draft = replaceStructuredDraftField(draft, draft.fields[1].occurrenceIndex, "牛肉饭")
        val segments = structuredDraftToSegments(draft)
        assertTrue((segments.first() as StructuredRecordSegment.Text).value.startsWith("今天"))
        assertEquals(listOf("30", "牛肉饭"), structuredDraftValues(draft))
    }
}
