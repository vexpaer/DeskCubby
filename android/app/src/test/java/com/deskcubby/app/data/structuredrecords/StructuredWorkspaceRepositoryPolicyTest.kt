package com.deskcubby.app.data.structuredrecords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredWorkspaceRepositoryPolicyTest {
    @Test
    fun onlyMissingWorkspaceFilesAreInitialized() {
        assertTrue(shouldInitializeStructuredWorkspaceFile(null))
        assertFalse(shouldInitializeStructuredWorkspaceFile(""))
        assertFalse(shouldInitializeStructuredWorkspaceFile("{\"schemaVersion\":1,\"records\":[]}"))
    }

    @Test
    fun starterTemplatesRequireTheirReferencedFields() {
        assertTrue(defaultTemplatesSupportedBy(emptyList()).isEmpty())
        assertEquals(
            DefaultStructuredExamples.TEMPLATES,
            defaultTemplatesSupportedBy(DefaultStructuredExamples.FIELDS),
        )
    }

    @Test
    fun partialFieldSetOnlyEnablesCompatibleStarterTemplates() {
        val fields = listOf(DefaultStructuredExamples.FIELDS.first())
        assertEquals(
            listOf("r_word_today"),
            defaultTemplatesSupportedBy(fields).map { it.id },
        )
    }

    @Test
    fun everyDefaultFieldSubsetNeverReturnsTemplateWithMissingBinding() {
        val allFields = DefaultStructuredExamples.FIELDS
        repeat(1 shl allFields.size) { mask ->
            val fields = allFields.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val fieldIds = fields.mapTo(hashSetOf()) { it.id }
            assertTrue(
                defaultTemplatesSupportedBy(fields).all { template ->
                    template.segments.filterIsInstance<StructuredRecordSegment.Field>()
                        .all { it.fieldId in fieldIds }
                },
            )
        }
    }
}
