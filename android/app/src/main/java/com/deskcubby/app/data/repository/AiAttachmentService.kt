package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legere.pdfiumandroid.PdfiumCore
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class AiAttachmentKind {
    IMAGE,
    DOCUMENT,
}

data class AiChatAttachment(
    val uri: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val kind: AiAttachmentKind,
    /** Frozen, bounded text. All of it remains untrusted when sent to a model. */
    val extractedText: String? = null,
    val permissionOwnedByChat: Boolean = false,
)

@Singleton
class AiAttachmentService @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val pdfMutex = Mutex()

    suspend fun prepare(uriValue: String): AiChatAttachment = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: throw AiChatException(
                AiChatFailure.CONFIGURATION,
                "只能选择系统文件选择器中的图片或文档。",
            )
        val resolver = context.contentResolver
        val metadata = queryMetadata(uri)
        val mimeType = resolver.getType(uri)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: mimeFromName(metadata.first)
            ?: throw AiChatException(AiChatFailure.CONFIGURATION, "无法识别附件类型。")
        if (mimeType.startsWith("image/")) {
            val bytes = readBounded(uri, MAX_IMAGE_ATTACHMENT_BYTES)
            return@withContext AiChatAttachment(
                uri = uri.toString(),
                mimeType = mimeType,
                displayName = metadata.first,
                sizeBytes = bytes.size.toLong(),
                kind = AiAttachmentKind.IMAGE,
            )
        }
        val text = when {
            mimeType == "application/pdf" -> extractPdf(uri)
            mimeType == DOCX_MIME -> extractDocx(uri)
            mimeType.startsWith("text/") || mimeType in TEXT_DOCUMENT_MIME_TYPES -> {
                decodeText(readBounded(uri, MAX_DOCUMENT_SOURCE_BYTES), mimeType)
            }
            else -> throw AiChatException(
                AiChatFailure.CONFIGURATION,
                "当前仅支持图片、PDF、DOCX、TXT、Markdown、HTML、JSON、CSV 和 XML 文档。",
            )
        }.trim().take(MAX_EXTRACTED_DOCUMENT_CHARS)
        if (text.isBlank()) {
            throw AiChatException(
                AiChatFailure.CONFIGURATION,
                "文档中没有可读取的文字，扫描版 PDF 请先进行 OCR。",
            )
        }
        AiChatAttachment(
            uri = uri.toString(),
            mimeType = mimeType,
            displayName = metadata.first,
            sizeBytes = metadata.second.coerceAtLeast(0L),
            kind = AiAttachmentKind.DOCUMENT,
            extractedText = text,
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        var name = "attachment"
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex).trim().take(MAX_DISPLAY_NAME_CHARS)
                            .ifBlank { "attachment" }
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size > MAX_ATTACHMENT_BYTES) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "附件超过 8 MiB 上限。")
        }
        return name to size
    }

    private fun readBounded(uri: Uri, maximumBytes: Int): ByteArray {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: Exception) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "无法读取所选附件。", error)
        } ?: throw AiChatException(AiChatFailure.CONFIGURATION, "无法读取所选附件。")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximumBytes) {
                    throw AiChatException(AiChatFailure.CONFIGURATION, "附件超过允许的大小上限。")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private suspend fun extractPdf(uri: Uri): String = pdfMutex.withLock {
        coroutineContext.ensureActive()
        var descriptor: android.os.ParcelFileDescriptor? = null
        var document: io.legere.pdfiumandroid.PdfDocument? = null
        try {
            descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw AiChatException(AiChatFailure.CONFIGURATION, "无法打开 PDF 文档。")
            document = PdfiumCore().newDocument(descriptor)
            val pageCount = document.getPageCount()
            require(pageCount in 1..MAX_PDF_PAGES) { "PDF page count is invalid" }
            buildString {
                for (pageIndex in 0 until minOf(pageCount, MAX_PDF_TEXT_PAGES)) {
                    coroutineContext.ensureActive()
                    if (length >= MAX_EXTRACTED_DOCUMENT_CHARS) break
                    val page = document.openPage(pageIndex)
                    try {
                        val textPage = page.openTextPage()
                        try {
                            val remaining = MAX_EXTRACTED_DOCUMENT_CHARS - length
                            val count = textPage.textPageCountChars().coerceIn(0, remaining)
                            if (count > 0) {
                                if (isNotEmpty()) append("\n\n")
                                append(textPage.textPageGetText(0, count).orEmpty())
                            }
                        } finally {
                            textPage.close()
                        }
                    } finally {
                        page.close()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiChatException) {
            throw error
        } catch (error: Exception) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "无法提取 PDF 文字。", error)
        } catch (error: LinkageError) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "当前设备无法读取 PDF 文字。", error)
        } finally {
            runCatching { document?.close() ?: descriptor?.close() }
        }
    }

    private fun extractDocx(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw AiChatException(AiChatFailure.CONFIGURATION, "无法打开 DOCX 文档。")
        val xml = input.use inputUse@{ stream ->
            ZipInputStream(stream).use zipUse@{ zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    if (entries > MAX_DOCX_ENTRIES) {
                        throw AiChatException(AiChatFailure.CONFIGURATION, "DOCX 文件结构过大。")
                    }
                    if (entry.name == "word/document.xml") {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_DOCX_XML_BYTES) {
                                throw AiChatException(AiChatFailure.CONFIGURATION, "DOCX 正文超过上限。")
                            }
                            output.write(buffer, 0, count)
                        }
                        return@zipUse output.toString(StandardCharsets.UTF_8.name())
                    }
                }
                ""
            }
        }
        val readableXml = xml
            .replace(Regex("<w:(tab|br)[^>]*/>"), "\n")
            .replace(Regex("</w:p>"), "\n")
        return Html.fromHtml(readableXml, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun decodeText(bytes: ByteArray, mimeType: String): String {
        val decoded = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
        return if (mimeType == "text/html") {
            Html.fromHtml(
                decoded.replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " "),
                Html.FROM_HTML_MODE_LEGACY,
            ).toString()
        } else {
            decoded
        }
    }

    private fun mimeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> DOCX_MIME
        "txt", "md", "markdown" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        else -> null
    }

    private companion object {
        const val MAX_IMAGE_ATTACHMENT_BYTES = 8 * 1024 * 1024
        const val MAX_DOCUMENT_SOURCE_BYTES = 8 * 1024 * 1024
        const val MAX_ATTACHMENT_BYTES = MAX_DOCUMENT_SOURCE_BYTES
        const val MAX_EXTRACTED_DOCUMENT_CHARS = 256 * 1024
        const val MAX_DISPLAY_NAME_CHARS = 240
        const val MAX_PDF_PAGES = 20_000
        const val MAX_PDF_TEXT_PAGES = 200
        const val MAX_DOCX_ENTRIES = 4_096
        const val MAX_DOCX_XML_BYTES = 2 * 1024 * 1024
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val TEXT_DOCUMENT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/x-yaml",
            "application/yaml",
        )
    }
}
