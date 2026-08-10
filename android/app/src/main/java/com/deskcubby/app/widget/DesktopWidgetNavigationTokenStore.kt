package com.deskcubby.app.widget

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Process-private, one-shot handoff from a non-exported widget proxy to exported MainActivity. */
internal object DesktopWidgetNavigationTokenStore {
    private val diaryUris = ConcurrentHashMap<String, String>()
    private val configIds = ConcurrentHashMap<String, String>()

    fun issueDiaryToken(uri: String): String = UUID.randomUUID().toString().also { token ->
        diaryUris[token] = uri
    }

    fun consumeDiaryUri(token: String?): String? =
        token?.takeIf { it.length == UUID_TOKEN_LENGTH }?.let(diaryUris::remove)

    fun issueConfigToken(configId: String): String = UUID.randomUUID().toString().also { token ->
        configIds[token] = configId
    }

    fun consumeConfigId(token: String?): String? =
        token?.takeIf { it.length == UUID_TOKEN_LENGTH }?.let(configIds::remove)

    fun discardConfigToken(token: String) {
        configIds.remove(token)
    }

    internal fun clearForTest() {
        diaryUris.clear()
        configIds.clear()
    }

    private const val UUID_TOKEN_LENGTH = 36
}
