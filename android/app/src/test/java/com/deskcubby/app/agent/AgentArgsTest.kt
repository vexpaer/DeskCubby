package com.deskcubby.app.agent

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentArgsTest {
    @Test
    fun acceptsValidTypedAndBoundedArguments() {
        val args = AgentArgs(
            mapOf(
                "query" to "week",
                "limit" to 20.0,
                "sources" to listOf("diary", "notes"),
                "date" to "2026-08-13",
            ),
        ).only("query", "limit", "sources", "date")

        assertEquals("week", args.string("query", 100))
        assertEquals(20, args.int("limit", 1, 50, 10))
        assertEquals(listOf("diary", "notes"), args.strings("sources", 8, 32))
        assertEquals(LocalDate.of(2026, 8, 13), args.optionalDate("date"))
        assertNull(args.optionalString("missing", 10))
    }

    @Test
    fun rejectsUnknownWrongTypeOutOfRangeAndInvalidDateArguments() {
        assertInvalid { AgentArgs(mapOf("extra" to true)).only() }
        assertInvalid { AgentArgs(mapOf("query" to 1)).string("query", 20) }
        assertInvalid { AgentArgs(mapOf("limit" to 51)).int("limit", 1, 50, 10) }
        assertInvalid { AgentArgs(mapOf("date" to "2026-02-30")).optionalDate("date") }
        assertInvalid { AgentArgs(mapOf("sources" to listOf("diary", ""))).strings("sources", 8, 32) }
    }

    private fun assertInvalid(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        require(error is AgentToolException)
        assertEquals("INVALID_ARGUMENTS", error.code)
    }
}
