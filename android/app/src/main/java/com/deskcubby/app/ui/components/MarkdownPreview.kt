package com.deskcubby.app.ui.components

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.normalizeMarkdownHeadingSizes
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

data class MarkdownResolvedMedia(
    val model: Any,
    val locationName: String? = null,
)

/**
 * Shared CommonMark reading view for diaries and Obsidian-compatible notes.
 *
 * CommonMark first produces semantic HTML; Android's span parser then preserves block and inline
 * formatting. Private-use markers around headings let us replace platform-relative H1-H6 sizes
 * with the six explicit user settings without flattening the rest of the document to plain text.
 */
@Composable
fun MarkdownPreview(
    content: String,
    headingSizesSp: List<Float>,
    maxWidthDp: Int,
    maxHeightDp: Int,
    mediaScopeKey: Any?,
    resolveMediaBatch: suspend (Collection<String>) -> Map<String, MarkdownResolvedMedia>,
    modifier: Modifier = Modifier,
    onEditCaption: ((fullMarkdown: String, caption: String) -> Unit)? = null,
    onDeleteMedia: ((target: String) -> Unit)? = null,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    val parts = remember(content) { splitMarkdownPreviewParts(content) }
    val mediaTargets = remember(parts) {
        parts.filterIsInstance<MarkdownPreviewPart.Image>()
            .map(MarkdownPreviewPart.Image::target)
            .distinct()
    }
    val resolvedMedia by produceState<Map<String, MarkdownResolvedMedia>>(
        initialValue = emptyMap(),
        mediaTargets,
        mediaScopeKey,
    ) {
        value = try {
            resolveMediaBatch(mediaTargets)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(parts, key = MarkdownPreviewPart::stableKey) { part ->
            when (part) {
                is MarkdownPreviewPart.Text -> MarkdownFormattedText(
                    markdown = part.markdown,
                    headingSizesSp = headingSizesSp,
                )

                is MarkdownPreviewPart.Image -> {
                    val media = resolvedMedia[part.target]
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        role = PanelRole.MEDIA,
                        padding = PaddingValues(10.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (media == null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 112.dp)
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.BrokenImage,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        tr("无法找到媒体", "Media could not be found"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        part.target,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = media.model,
                                    contentDescription = part.caption,
                                    modifier = Modifier
                                        .widthIn(max = maxWidthDp.dp)
                                        .fillMaxWidth()
                                        .heightIn(max = maxHeightDp.dp)
                                        .then(
                                            if (organic) Modifier.clip(visuals.mediaShape)
                                            else Modifier,
                                        ),
                                )
                            }
                            media?.locationName?.let { location ->
                                val locationDescription = tr(
                                    "拍摄地点：$location",
                                    "Photo location: $location",
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .clearAndSetSemantics {
                                            contentDescription = locationDescription
                                        },
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.Place,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        location,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (onEditCaption != null && part.captionEditable) {
                                    TextButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            onEditCaption(part.fullMarkdown, part.caption)
                                        },
                                    ) {
                                        Text(
                                            part.caption.ifBlank {
                                                tr(
                                                    "点击添加图片说明",
                                                    "Tap to add a caption",
                                                )
                                            },
                                        )
                                    }
                                } else {
                                    Text(
                                        part.caption.ifBlank { part.target },
                                        modifier = Modifier.weight(1f).padding(10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (onDeleteMedia != null) {
                                    IconButton(onClick = { onDeleteMedia(part.target) }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = tr("删除媒体", "Delete media"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownFormattedText(
    markdown: String,
    headingSizesSp: List<Float>,
) {
    val parser = remember { Parser.builder().build() }
    val renderer = remember { HtmlRenderer.builder().build() }
    val normalizedSizes = remember(headingSizesSp) {
        normalizeMarkdownHeadingSizes(headingSizesSp)
    }
    val scaledDensity = LocalDensity.current.run { density * fontScale }
    val spanned = remember(markdown, normalizedSizes, scaledDensity) {
        val html = renderer.render(parser.parse(markdown))
        markdownHtmlToSpanned(html, normalizedSizes, scaledDensity)
    }
    if (spanned.isEmpty()) return

    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val baseTextSize = MaterialTheme.typography.bodyLarge.fontSize.value
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                includeFontPadding = false
                setLineSpacing(0f, 1.12f)
            }
        },
        update = { view ->
            view.text = spanned
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            view.textSize = baseTextSize
        },
    )
}

internal fun markdownHtmlToSpanned(
    html: String,
    headingSizesSp: List<Float>,
    scaledDensity: Float = 1f,
): Spanned {
    val sizes = normalizeMarkdownHeadingSizes(headingSizesSp)
    val markedHtml = HEADING_OPEN_REGEX.replace(html) { match ->
        "${match.value}$HEADING_START${match.groupValues[1]}$HEADING_LEVEL_END"
    }.let { value ->
        HEADING_CLOSE_REGEX.replace(value) { "$HEADING_END${it.value}" }
    }
    val builder = SpannableStringBuilder(
        HtmlCompat.fromHtml(markedHtml, HtmlCompat.FROM_HTML_MODE_LEGACY),
    )

    while (true) {
        val start = builder.indexOf(HEADING_START)
        if (start < 0) break
        val levelEnd = builder.indexOf(HEADING_LEVEL_END, start + 1)
        val end = builder.indexOf(HEADING_END, (levelEnd + 1).coerceAtLeast(start + 1))
        if (levelEnd < 0 || end < 0) {
            builder.delete(start, start + 1)
            continue
        }
        val level = builder.substring(start + 1, levelEnd).toIntOrNull()?.coerceIn(1, 6)
        val contentStart = levelEnd + 1
        val contentEnd = end
        builder.delete(end, end + 1)
        builder.delete(start, contentStart)
        if (level != null) {
            val adjustedEnd = contentEnd - (contentStart - start)
            if (adjustedEnd > start) {
                builder.setSpan(
                    AbsoluteSizeSpan(
                        (sizes[level - 1] * scaledDensity).roundToInt(),
                        false,
                    ),
                    start,
                    adjustedEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    adjustedEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    builder.getSpans(0, builder.length, URLSpan::class.java).forEach { span ->
        if (!isSafeMarkdownLink(span.url)) builder.removeSpan(span)
    }
    return builder
}

internal fun isSafeMarkdownLink(raw: String): Boolean = runCatching {
    when (URI(raw.trim()).scheme?.lowercase()) {
        "http", "https", "mailto" -> true
        else -> false
    }
}.getOrDefault(false)

private sealed interface MarkdownPreviewPart {
    val stableKey: String

    data class Text(val markdown: String, override val stableKey: String) : MarkdownPreviewPart

    data class Image(
        val fullMarkdown: String,
        val caption: String,
        val target: String,
        val captionEditable: Boolean,
        override val stableKey: String,
    ) : MarkdownPreviewPart
}

private fun splitMarkdownPreviewParts(content: String): List<MarkdownPreviewPart> = buildList {
    var cursor = 0
    var partIndex = 0
    MARKDOWN_IMAGE_REGEX.findAll(content).forEach { match ->
        if (match.range.first > cursor) {
            add(
                MarkdownPreviewPart.Text(
                    markdown = content.substring(cursor, match.range.first),
                    stableKey = "text-${partIndex++}-${match.range.first}",
                ),
            )
        }
        val standardTarget = match.groupValues[2].ifBlank { match.groupValues[3] }
        val isStandard = standardTarget.isNotBlank()
        val target = if (isStandard) standardTarget else match.groupValues[4]
        val caption = if (isStandard) match.groupValues[1] else match.groupValues[5]
            .ifBlank { target.substringAfterLast('/').substringBeforeLast('.') }
        add(
            MarkdownPreviewPart.Image(
                fullMarkdown = match.value,
                caption = caption,
                target = target.trim(),
                captionEditable = isStandard,
                stableKey = "image-${partIndex++}-${match.range.first}",
            ),
        )
        cursor = match.range.last + 1
    }
    if (cursor < content.length) {
        add(
            MarkdownPreviewPart.Text(
                markdown = content.substring(cursor),
                stableKey = "text-${partIndex}-${cursor}",
            ),
        )
    }
    if (isEmpty() && content.isNotEmpty()) {
        add(MarkdownPreviewPart.Text(content, "text-0"))
    }
}

private const val HEADING_START = '\uE000'
private const val HEADING_LEVEL_END = '\uE001'
private const val HEADING_END = '\uE002'
private val HEADING_OPEN_REGEX = Regex("<h([1-6])(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val HEADING_CLOSE_REGEX = Regex("</h[1-6]>", RegexOption.IGNORE_CASE)
private val MARKDOWN_IMAGE_REGEX = Regex(
    """!\[([^]\r\n]*)]\(\s*(?:<([^>\r\n]+)>|([^\s)\r\n]+))(?:\s+(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|\([^\)\r\n]*\)))?\s*\)|!\[\[([^]|\r\n]+)(?:\|([^]\r\n]+))?]]""",
)
