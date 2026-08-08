package com.deskcubby.app.data.sync

import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.repository.ReaderProgressRecord
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Bounded, URI-free cloud payload for cross-device reader progress.
 *
 * The payload intentionally contains no title, document URI, cover URI, file name, or book text.
 * A full-file SHA-256 fingerprint is the only book identifier shared with the configured service.
 */
object ReaderProgressJsonCodec {
    const val FORMAT_VERSION = 1
    const val MAX_RECORDS = 500
    const val MAX_JSON_BYTES = 512 * 1024

    private const val MAX_TEXT_PAGES = 50_000
    private const val MAX_TEXT_PARAGRAPHS = 250_000
    private const val MAX_PDF_PAGES = 20_000

    private val fingerprintRegex = Regex("[0-9a-f]{64}")
    private val rootKeys = setOf("version", "records")
    private val recordKeys = setOf(
        "fingerprint",
        "type",
        "textPageIndex",
        "textParagraphIndex",
        "pdfPageIndex",
        "totalPages",
        "updatedAt",
    )

    fun encode(records: List<ReaderProgressRecord>): ByteArray {
        require(records.size <= MAX_RECORDS) { "Too many reader progress records" }
        val normalized = records.map(::validateAndNormalize)
            .sortedWith(compareBy(ReaderProgressRecord::fingerprint, ReaderProgressRecord::type))
        require(normalized.map { it.fingerprint to it.type }.distinct().size == normalized.size) {
            "Duplicate reader progress record"
        }
        val root = JSONObject()
            .put("version", FORMAT_VERSION)
            .put(
                "records",
                JSONArray().apply {
                    normalized.forEach { record ->
                        put(
                            JSONObject()
                                .put("fingerprint", record.fingerprint)
                                .put("type", record.type.name)
                                .put("textPageIndex", record.textPageIndex)
                                .put("textParagraphIndex", record.textParagraphIndex)
                                .put("pdfPageIndex", record.pdfPageIndex)
                                .put("totalPages", record.totalPages)
                                .put("updatedAt", record.updatedAt),
                        )
                    }
                },
            )
        return root.toString().toByteArray(StandardCharsets.UTF_8).also { bytes ->
            require(bytes.size <= MAX_JSON_BYTES) { "Reader progress JSON is too large" }
        }
    }

    fun decode(bytes: ByteArray): List<ReaderProgressRecord> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_JSON_BYTES) {
            "Reader progress JSON size is invalid"
        }
        val raw = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val tokener = JSONTokener(raw)
        val root = tokener.nextValue() as? JSONObject
            ?: throw IllegalArgumentException("Reader progress JSON root must be an object")
        require(tokener.nextClean() == '\u0000') {
            "Reader progress JSON contains trailing content"
        }
        require(root.keys().asSequence().toSet() == rootKeys) {
            "Reader progress JSON contains unexpected fields"
        }
        requireInteger(root, "version", FORMAT_VERSION.toLong(), FORMAT_VERSION.toLong())
        val values = root.get("records") as? JSONArray
            ?: throw IllegalArgumentException("records must be an array")
        require(values.length() <= MAX_RECORDS) { "Too many reader progress records" }
        val decoded = buildList(values.length()) {
            for (index in 0 until values.length()) {
                val item = values.get(index) as? JSONObject
                    ?: throw IllegalArgumentException("Reader progress record must be an object")
                require(item.keys().asSequence().toSet() == recordKeys) {
                    "Reader progress record contains unexpected fields"
                }
                val fingerprint = item.get("fingerprint") as? String
                    ?: throw IllegalArgumentException("Reader progress fingerprint must be a string")
                require(fingerprintRegex.matches(fingerprint)) {
                    "Reader progress fingerprint is invalid"
                }
                val rawType = item.get("type") as? String
                    ?: throw IllegalArgumentException("Reader progress type must be a string")
                val type = runCatching {
                    ReaderBookType.valueOf(rawType)
                }.getOrElse { throw IllegalArgumentException("Reader progress type is invalid") }
                add(
                    ReaderProgressRecord(
                        fingerprint = fingerprint,
                        type = type,
                        textPageIndex = requireInteger(
                            item,
                            "textPageIndex",
                            -1L,
                            (MAX_TEXT_PAGES - 1).toLong(),
                        ).toInt(),
                        textParagraphIndex = requireInteger(
                            item,
                            "textParagraphIndex",
                            0L,
                            (MAX_TEXT_PARAGRAPHS - 1).toLong(),
                        ).toInt(),
                        pdfPageIndex = requireInteger(
                            item,
                            "pdfPageIndex",
                            0L,
                            (MAX_PDF_PAGES - 1).toLong(),
                        ).toInt(),
                        totalPages = requireInteger(
                            item,
                            "totalPages",
                            0L,
                            if (type == ReaderBookType.PDF) {
                                MAX_PDF_PAGES.toLong()
                            } else {
                                MAX_TEXT_PAGES.toLong()
                            },
                        ).toInt(),
                        updatedAt = requireInteger(item, "updatedAt", 0L, Long.MAX_VALUE),
                    ),
                )
            }
        }.sortedWith(compareBy(ReaderProgressRecord::fingerprint, ReaderProgressRecord::type))
        require(decoded.map { it.fingerprint to it.type }.distinct().size == decoded.size) {
            "Duplicate reader progress record"
        }
        return decoded
    }

    private fun validateAndNormalize(record: ReaderProgressRecord): ReaderProgressRecord {
        val fingerprint = record.fingerprint.lowercase(Locale.ROOT)
        require(fingerprintRegex.matches(fingerprint)) { "Reader progress fingerprint is invalid" }
        require(record.textPageIndex in -1 until MAX_TEXT_PAGES)
        require(record.textParagraphIndex in 0 until MAX_TEXT_PARAGRAPHS)
        require(record.pdfPageIndex in 0 until MAX_PDF_PAGES)
        val maximumTotalPages = if (record.type == ReaderBookType.PDF) {
            MAX_PDF_PAGES
        } else {
            MAX_TEXT_PAGES
        }
        require(record.totalPages in 0..maximumTotalPages)
        require(record.updatedAt >= 0L)
        return record.copy(fingerprint = fingerprint)
    }

    private fun requireInteger(
        source: JSONObject,
        key: String,
        minimum: Long,
        maximum: Long,
    ): Long {
        val raw = source.get(key)
        require(raw is Int || raw is Long) { "$key must be an integer" }
        return (raw as Number).toLong().also { value ->
            require(value in minimum..maximum) { "$key is out of range" }
        }
    }
}
