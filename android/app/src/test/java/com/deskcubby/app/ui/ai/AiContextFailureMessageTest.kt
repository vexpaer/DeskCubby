package com.deskcubby.app.ui.ai

import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.repository.AiContextException
import com.deskcubby.app.data.repository.AiContextFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextFailureMessageTest {
    @Test
    fun everyStructuredFailureHasAnEnglishMessage() {
        val han = Regex("[\\u3400-\\u9FFF]")

        AiContextFailure.entries.forEach { failure ->
            val message = aiContextFailureMessage(
                AiContextException(failure),
                AppLanguage.ENGLISH,
            )
            assertTrue(message.isNotBlank())
            assertFalse("English message for $failure contains Chinese: $message", han.containsMatchIn(message))
        }
    }

    @Test
    fun englishModeLocalizesStructuredLimitFailure() {
        val message = aiContextFailureMessage(
            error = AiContextException(
                failure = AiContextFailure.TOTAL_TOO_LARGE,
                measuredBytes = 300 * 1024,
            ),
            language = AppLanguage.ENGLISH,
        )

        assertTrue(message.contains("300.0 KiB"))
        assertTrue(message.contains("256 KiB total limit"))
        assertFalse(message.contains("上下文"))
    }

    @Test
    fun englishModeExplainsUnavailableAndInvalidUtf8Sources() {
        val unavailable = aiContextFailureMessage(
            AiContextException(AiContextFailure.SOURCE_UNAVAILABLE),
            AppLanguage.ENGLISH,
        )
        val invalidUtf8 = aiContextFailureMessage(
            AiContextException(
                failure = AiContextFailure.INVALID_TEXT_ENCODING,
                itemTitle = "Journal",
            ),
            AppLanguage.ENGLISH,
        )

        assertTrue(unavailable.contains("directory permission"))
        assertEquals(
            "“Journal” is not valid UTF-8 text and cannot be imported.",
            invalidUtf8,
        )
    }

    @Test
    fun candidateLoadFailureIsBilingual() {
        assertEquals(
            "Could not load importable context. Please try again.",
            contextCandidateLoadFailureMessage(AppLanguage.ENGLISH),
        )
        assertEquals(
            "无法读取可导入的上下文，请稍后重试。",
            contextCandidateLoadFailureMessage(AppLanguage.CHINESE),
        )
    }
}
