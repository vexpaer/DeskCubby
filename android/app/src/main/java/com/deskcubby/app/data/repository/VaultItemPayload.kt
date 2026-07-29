package com.deskcubby.app.data.repository

import org.json.JSONObject

/**
 * Plaintext representation inside an AES-GCM vault row.
 *
 * The Room entity deliberately stays ciphertext-only. Versioning the encrypted payload lets the
 * app evolve the entry fields without a database migration or a bulk rewrite of existing rows.
 */
internal data class VaultItemPayload(
    val content: String,
    val note: String?,
)

internal fun encodeVaultItemPayload(content: String, note: String?): String =
    JSONObject()
        .put(JSON_VERSION, CURRENT_PAYLOAD_VERSION)
        .put(JSON_CONTENT, content)
        .apply {
            note?.takeUnless(String::isBlank)?.let { put(JSON_NOTE, it) }
        }
        .toString()

internal fun decodeVaultItemPayload(plaintext: String): VaultItemPayload? {
    val json = try {
        JSONObject(plaintext)
    } catch (_: Exception) {
        return null
    }

    return if (json.has(JSON_VERSION)) {
        decodeVersionedPayload(json)
    } else {
        decodeLegacyPayload(json)
    }
}

private fun decodeVersionedPayload(json: JSONObject): VaultItemPayload? {
    val rawVersion = json.opt(JSON_VERSION)
    if (rawVersion !is Number || rawVersion.toDouble() != CURRENT_PAYLOAD_VERSION.toDouble()) {
        return null
    }
    if (!json.has(JSON_CONTENT) || json.isNull(JSON_CONTENT)) return null
    val content = json.opt(JSON_CONTENT) as? String ?: return null
    if (json.has(JSON_NOTE) && !json.isNull(JSON_NOTE) && json.opt(JSON_NOTE) !is String) {
        return null
    }
    val note = (json.opt(JSON_NOTE) as? String)?.takeUnless(String::isBlank)
    return VaultItemPayload(content = content, note = note)
}

/**
 * v1 rows had no version marker and stored `title` plus `content`.
 *
 * When both fields exist, the old title becomes the optional note. A title-only row uses its title
 * as content, so every previously visible character remains available in the title-less UI.
 */
private fun decodeLegacyPayload(json: JSONObject): VaultItemPayload? {
    if (json.has(JSON_LEGACY_TITLE) &&
        !json.isNull(JSON_LEGACY_TITLE) &&
        json.opt(JSON_LEGACY_TITLE) !is String
    ) {
        return null
    }
    if (json.has(JSON_CONTENT) && !json.isNull(JSON_CONTENT) && json.opt(JSON_CONTENT) !is String) {
        return null
    }

    val legacyTitle = json.opt(JSON_LEGACY_TITLE) as? String ?: ""
    val legacyContent = json.opt(JSON_CONTENT) as? String ?: ""
    return if (legacyContent.isNotEmpty()) {
        VaultItemPayload(
            content = legacyContent,
            note = legacyTitle.takeUnless(String::isEmpty),
        )
    } else {
        VaultItemPayload(
            content = legacyTitle,
            note = null,
        )
    }
}

private const val CURRENT_PAYLOAD_VERSION = 2
private const val JSON_VERSION = "version"
private const val JSON_CONTENT = "content"
private const val JSON_NOTE = "note"
private const val JSON_LEGACY_TITLE = "title"
