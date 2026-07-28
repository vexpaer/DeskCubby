package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.DiaryEditorDocument
import com.deskcubby.app.data.model.DiaryTrashItem
import com.deskcubby.app.data.model.ImportedMedia
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.MealCategory
import com.deskcubby.app.ui.diary.filter.MealCalendarExportLayout
import com.deskcubby.app.ui.diary.filter.isDateInMealExportRange
import com.deskcubby.app.ui.diary.filter.mealCalendarExportLayout
import com.deskcubby.app.ui.diary.filter.mealPhotoFilterMatrix
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONObject

class ExternalFileConflictException(
    val diskDocument: DiaryEditorDocument,
) : IllegalStateException("日记已被其他应用修改")

internal class DiaryTextLimitExceededException(
    val maxBytes: Int,
) : IOException("Diary text exceeds the $maxBytes-byte read limit")

internal class DiaryTextInvalidUtf8Exception(
    cause: Throwable,
) : IOException("Diary text is not valid UTF-8", cause)

data class MealCalendarPhoto(
    val uri: Uri,
    val caption: String,
    val category: MealCategory,
    val diaryUri: Uri,
    val markdown: String,
    val energyKj: Int? = null,
    /** Lower-cased media file name; the key into the media metadata JSON. */
    val fileName: String = "",
    val locationName: String? = null,
)

/** One entry of the `dc-media.json` sidecar kept in the media directory. */
data class MediaMetaEntry(
    val energyKj: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val place: String? = null,
)

data class MealCalendarDay(
    val dateIso: String,
    val photos: List<MealCalendarPhoto>,
) { val totalEnergyKj: Int? get() = photos.mapNotNull { it.energyKj }.takeIf(List<Int>::isNotEmpty)?.sum() }

data class DiaryPreviewMedia(
    val uri: Uri?,
    val locationName: String? = null,
)

data class MealCalendarExportResult(
    val width: Int,
    val height: Int,
    val dayCount: Int,
    val photoCount: Int,
)

enum class DiaryCloudSyncArea {
    DIARY,
    MEDIA,
}

data class DiaryCloudSyncFile(
    val area: DiaryCloudSyncArea,
    val name: String,
    val uri: String,
    val mimeType: String,
    val size: Long,
    val lastModifiedMillis: Long,
    val sha256: String,
)

sealed interface DiaryCloudSyncWriteResult {
    data class Applied(val file: DiaryCloudSyncFile) : DiaryCloudSyncWriteResult
    data class ConflictCopy(
        val existing: DiaryCloudSyncFile?,
        val copy: DiaryCloudSyncFile,
    ) : DiaryCloudSyncWriteResult
}

@Singleton
class DiaryFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexDao: DiaryIndexDao,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val writeMutex = Mutex()
    private val mediaMutex = Mutex()

    suspend fun scan(settings: AppSettings): List<DiaryDocument> = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext emptyList()
        val documents = root.listFiles()
            .asSequence()
            .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
            .map { document ->
                val content = readText(document.uri)
                val date = extractDate(
                    name = document.name.orEmpty(),
                    modified = document.lastModified(),
                    fileNamePattern = settings.fileNamePattern,
                )
                val title = markdownStem(document.name.orEmpty())
                DiaryDocument(
                    uri = document.uri.toString(),
                    name = document.name.orEmpty(),
                    title = title,
                    dateIso = date.toString(),
                    monthKey = "%04d.%02d".format(Locale.ROOT, date.year, date.monthValue),
                    lastModified = document.lastModified(),
                    size = document.length(),
                    wordCount = DiaryTextUtils.wordCount(content),
                ) to DiaryTextUtils.sha256(content.toByteArray())
            }
            .toList()

        indexDao.replaceAfterSuccessfulScan(
            documents.map { (item, hash) ->
                DiaryIndexEntity(
                    uri = item.uri,
                    name = item.name,
                    title = item.title,
                    dateIso = item.dateIso,
                    monthKey = item.monthKey,
                    lastModified = item.lastModified,
                    size = item.size,
                    wordCount = item.wordCount,
                    sha256 = hash,
                    indexedAt = System.currentTimeMillis(),
                )
            },
        )
        documents.map { it.first }.sortedWith(compareByDescending<DiaryDocument> { it.dateIso }.thenByDescending { it.name })
    }

    /**
     * Builds the meal photo wall directly from the current Markdown files. A media-directory
     * lookup table is created once per scan so resolving many photos does not repeatedly query
     * the Storage Access Framework provider. Unreadable or malformed individual diary files are
     * ignored; a broken file should not hide valid meal photos from every other day.
     */
    suspend fun scanMealCalendar(settings: AppSettings): List<MealCalendarDay> = withContext(Dispatchers.IO) {
        val diaryRoot = settings.diaryTreeUri?.let(::tree) ?: return@withContext emptyList()
        val mediaSnapshot = settings.mediaTreeUri?.let(::tree)?.let { root ->
            mediaMutex.withLock { snapshotMediaDirectoryUnlocked(root) }
        } ?: MediaDirectorySnapshot()
        val mediaByName = mediaSnapshot.byName
        val mediaMetaEntries = mediaSnapshot.metaEntries

        val photosByDate = linkedMapOf<String, MutableList<MealCalendarPhoto>>()
        val diaries = diaryRoot.listFiles()
            .asSequence()
            .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
            .map { document ->
                val name = document.name.orEmpty()
                Triple(
                    document,
                    name,
                    extractDate(name, document.lastModified(), settings.fileNamePattern),
                )
            }
            .sortedWith(
                compareByDescending<Triple<DocumentFile, String, LocalDate>> { it.third }
                    .thenByDescending { it.second },
            )

        for ((diary, _, diaryDate) in diaries) {
            currentCoroutineContext().ensureActive()
            val content = try {
                readText(diary.uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            val dateIso = diaryDate.toString()
            for (match in MARKDOWN_IMAGE_REGEX.findAll(content)) {
                currentCoroutineContext().ensureActive()
                val caption = match.groupValues[1].trim()
                val target = match.groupValues[2].ifBlank { match.groupValues[3] }
                val category = mealCategoryFromCaption(caption)
                    ?: mealCategoryFromFileName(target)
                    ?: continue
                val mediaUri = resolveMealMediaUri(target, mediaByName) ?: continue
                val metaKey = decodedTargetFileName(target)?.lowercase(Locale.ROOT).orEmpty()
                val meta = mediaMetaEntries[metaKey]
                photosByDate.getOrPut(dateIso) { mutableListOf() }
                    .add(
                        MealCalendarPhoto(
                            uri = mediaUri, caption = caption, category = category,
                            diaryUri = diary.uri, markdown = match.value,
                            // The JSON sidecar wins; captions written by older releases
                            // (e.g. "午餐-800kJ") remain readable as a fallback.
                            energyKj = meta?.energyKj ?: energyFromCaption(caption),
                            fileName = metaKey,
                            locationName = meta?.let(::mediaMetaDisplayLocation),
                        ),
                    )
            }
        }

        photosByDate
            .map { (dateIso, photos) ->
                MealCalendarDay(
                    dateIso = dateIso,
                    photos = photos.sortedBy { it.category.sortOrder },
                )
            }
            .sortedByDescending(MealCalendarDay::dateIso)
    }

    /**
     * Renders an inclusive day range of the current meal-category selection to one bounded PNG.
     *
     * The bitmap dimensions are calculated and checked before allocation. Source photos are
     * sampled to their card size and a corrupt individual image is rendered as a placeholder.
     * The PNG is first written to app cache, decoded for dimension validation, then copied to the
     * CreateDocument URI and read back for an exact byte-count and SHA-256 check.
     */
    suspend fun exportMealCalendarPng(
        destinationUri: Uri,
        settings: AppSettings,
        startInclusive: LocalDate,
        endInclusive: LocalDate,
        categories: Set<MealCategory>,
    ): MealCalendarExportResult = withContext(Dispatchers.IO) {
        var committed = false
        try {
            require(!startInclusive.isAfter(endInclusive)) { "开始日期不能晚于结束日期" }
            require(categories.isNotEmpty()) { "请至少选择一个餐别" }
            val selectedDays = scanMealCalendar(settings).mapNotNull { day ->
                currentCoroutineContext().ensureActive()
                val date = runCatching { LocalDate.parse(day.dateIso) }.getOrNull()
                    ?: return@mapNotNull null
                if (!isDateInMealExportRange(date, startInclusive, endInclusive)) {
                    return@mapNotNull null
                }
                day.photos.filter { it.category in categories }
                    .takeIf(List<MealCalendarPhoto>::isNotEmpty)
                    ?.let { day.copy(photos = it) }
            }
            require(selectedDays.isNotEmpty()) {
                "所选日期和餐别下没有可导出的饮食照片"
            }

            // This pure preflight rejects excessive height/pixel count before createBitmap().
            val layout = mealCalendarExportLayout(
                photoCounts = selectedDays.map { it.photos.size },
                imageMaxHeight = settings.mealCalendarImageMaxHeightDp,
                showCaptions = settings.mealCalendarShowCaptions,
                photosPerRow = settings.mealCalendarPhotosPerRow,
            )
            currentCoroutineContext().ensureActive()
            val bitmap = try {
                Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                error("没有足够内存生成这张长图，请缩短日期范围")
            }

            var cachedPng: File? = null
            try {
                renderMealCalendarExport(
                    bitmap = bitmap,
                    layout = layout,
                    days = selectedDays,
                    settings = settings,
                    startInclusive = startInclusive,
                    endInclusive = endInclusive,
                    categories = categories,
                )
                currentCoroutineContext().ensureActive()
                cachedPng = writeAndValidateMealExportCache(bitmap, layout)
                // The remaining SAF copy and read-back verification only need the cache file.
                // Release the potentially ~45 MiB export bitmap before entering provider I/O.
                bitmap.recycle()
                copyMealExportToDocumentAndVerify(cachedPng, destinationUri)
                committed = true
                MealCalendarExportResult(
                    width = layout.width,
                    height = layout.height,
                    dayCount = selectedDays.size,
                    photoCount = selectedDays.sumOf { it.photos.size },
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { cachedPng?.delete() }
                }
            }
        } catch (error: Throwable) {
            if (!committed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    deleteCreatedExportDocument(destinationUri)
                }
            }
            throw error
        }
    }

    private suspend fun renderMealCalendarExport(
        bitmap: Bitmap,
        layout: MealCalendarExportLayout,
        days: List<MealCalendarDay>,
        settings: AppSettings,
        startInclusive: LocalDate,
        endInclusive: LocalDate,
        categories: Set<MealCategory>,
    ) {
        val canvas = Canvas(bitmap)
        val backgroundColor = Color.rgb(248, 248, 246)
        val cardColor = Color.WHITE
        val textColor = Color.rgb(35, 39, 42)
        val mutedTextColor = Color.rgb(95, 99, 104)
        val primaryColor = settings.themeColorArgb.withOpaqueAlpha()
        val secondaryColor = settings.themeSecondaryColorsArgb.firstOrNull()
            ?.withOpaqueAlpha()
            ?: primaryColor
        val primaryTextColor = contrastTextColor(primaryColor)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryTextColor
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        val headerDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryTextColor
            textSize = 21f
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val energyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondaryColor
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 21f
        }
        val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mutedTextColor
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardColor
            style = Paint.Style.FILL
        }
        val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val normalizedFilter = settings.mealPhotoFilter.normalized()
        if (normalizedFilter.enabled && normalizedFilter.hasVisibleAdjustment()) {
            photoPaint.colorFilter = ColorMatrixColorFilter(
                ColorMatrix(mealPhotoFilterMatrix(normalizedFilter)),
            )
        }

        canvas.drawColor(backgroundColor)
        val padding = MealCalendarExportLayout.CONTENT_PADDING.toFloat()
        val headerBottom = padding + MealCalendarExportLayout.HEADER_HEIGHT - 12f
        canvas.drawRoundRect(
            RectF(padding, padding, layout.width - padding, headerBottom),
            24f,
            24f,
            headerPaint,
        )
        val isEnglish = settings.appLanguage == AppLanguage.ENGLISH
        canvas.drawText(
            if (isEnglish) "Meal calendar" else "吃历",
            padding + 24f,
            padding + 44f,
            titlePaint,
        )
        canvas.drawText(
            "$startInclusive  —  $endInclusive",
            padding + 24f,
            padding + 73f,
            headerDetailPaint,
        )
        val categoryText = categories.sortedBy(MealCategory::sortOrder).joinToString(
            separator = if (isEnglish) " · " else "、",
        ) { category -> if (isEnglish) category.englishLabel else category.chineseLabel }
        drawEllipsizedText(
            canvas = canvas,
            text = categoryText,
            x = padding + 24f,
            baseline = padding + 100f,
            maxWidth = layout.width - padding * 2f - 48f,
            paint = headerDetailPaint,
        )

        var y = padding + MealCalendarExportLayout.HEADER_HEIGHT
        val availableWidth = layout.width - MealCalendarExportLayout.CONTENT_PADDING * 2
        days.forEachIndexed { dayIndex, day ->
            currentCoroutineContext().ensureActive()
            canvas.drawText(
                day.dateIso,
                padding,
                y + 35f,
                datePaint,
            )
            day.totalEnergyKj?.let { energy ->
                val dateWidth = datePaint.measureText(day.dateIso)
                canvas.drawText(
                    " · $energy kJ",
                    padding + dateWidth + 8f,
                    y + 35f,
                    energyPaint,
                )
            }
            y += MealCalendarExportLayout.DAY_HEADER_HEIGHT

            var photoOffset = 0
            val rowSizes = layout.rowsPerDay[dayIndex]
            rowSizes.forEachIndexed { rowIndex, rowSize ->
                val totalCellGaps = MealCalendarExportLayout.CELL_GAP * (rowSize - 1)
                val cellWidth = (availableWidth - totalCellGaps).toFloat() / rowSize.toFloat()
                repeat(rowSize) { column ->
                    currentCoroutineContext().ensureActive()
                    val photo = day.photos[photoOffset++]
                    val left = padding +
                        column * (cellWidth + MealCalendarExportLayout.CELL_GAP)
                    val cardRect = RectF(
                        left,
                        y,
                        left + cellWidth,
                        y + layout.cardHeight,
                    )
                    canvas.drawRoundRect(cardRect, 14f, 14f, cardPaint)
                    val imageRect = RectF(
                        cardRect.left,
                        cardRect.top,
                        cardRect.right,
                        cardRect.top + layout.imageHeight,
                    )
                    val source = decodeMealExportBitmap(
                        uri = photo.uri,
                        targetWidth = cellWidth.roundToInt().coerceAtLeast(1),
                        targetHeight = layout.imageHeight,
                    )
                    if (source == null) {
                        val placeholder = if (isEnglish) "Image unavailable" else "图片损坏"
                        val oldColor = cardPaint.color
                        cardPaint.color = Color.rgb(226, 229, 232)
                        canvas.drawRect(imageRect, cardPaint)
                        cardPaint.color = oldColor
                        canvas.drawText(
                            placeholder,
                            imageRect.centerX(),
                            imageRect.centerY() - (placeholderPaint.ascent() + placeholderPaint.descent()) / 2f,
                            placeholderPaint,
                        )
                    } else {
                        try {
                            canvas.drawBitmap(
                                source,
                                centerCropSourceRect(source.width, source.height, imageRect),
                                imageRect,
                                photoPaint,
                            )
                        } finally {
                            source.recycle()
                        }
                    }
                    if (layout.captionHeight > 0) {
                        val caption = photo.caption.ifBlank {
                            if (isEnglish) photo.category.englishLabel else photo.category.chineseLabel
                        }
                        drawEllipsizedText(
                            canvas = canvas,
                            text = caption,
                            x = cardRect.left + 10f,
                            baseline = imageRect.bottom + 29f,
                            maxWidth = cardRect.width() - 20f,
                            paint = captionPaint,
                        )
                    }
                }
                y += layout.cardHeight
                if (rowIndex != rowSizes.lastIndex) {
                    y += MealCalendarExportLayout.ROW_GAP
                }
            }
            y += MealCalendarExportLayout.DAY_GAP
        }
    }

    private suspend fun decodeMealExportBitmap(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取图片" }
                BitmapFactory.decodeStream(input, null, bounds)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = imageSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                CompressedImageSize(targetWidth, targetHeight),
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        currentCoroutineContext().ensureActive()
        var decoded: Bitmap? = try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取图片" }
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
        decoded ?: return null
        return try {
            currentCoroutineContext().ensureActive()
            val oriented = applyExifOrientation(decoded, readExifOrientation(uri))
            if (oriented !== decoded) decoded.recycle()
            decoded = null
            oriented
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        } finally {
            decoded?.recycle()
        }
    }

    private fun centerCropSourceRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        destination: RectF,
    ): Rect {
        val sourceAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val destinationAspect = destination.width() / destination.height()
        return if (sourceAspect > destinationAspect) {
            val cropWidth = (bitmapHeight * destinationAspect).roundToInt().coerceAtLeast(1)
            val left = ((bitmapWidth - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + cropWidth).coerceAtMost(bitmapWidth), bitmapHeight)
        } else {
            val cropHeight = (bitmapWidth / destinationAspect).roundToInt().coerceAtLeast(1)
            val top = ((bitmapHeight - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, bitmapWidth, (top + cropHeight).coerceAtMost(bitmapHeight))
        }
    }

    private fun drawEllipsizedText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        maxWidth: Float,
        paint: Paint,
    ) {
        if (paint.measureText(text) <= maxWidth) {
            canvas.drawText(text, x, baseline, paint)
            return
        }
        val ellipsis = "…"
        val available = (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(0f)
        var end = paint.breakText(text, true, available, null).coerceIn(0, text.length)
        if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1])) end--
        canvas.drawText(text.take(end) + ellipsis, x, baseline, paint)
    }

    private fun writeAndValidateMealExportCache(
        bitmap: Bitmap,
        layout: MealCalendarExportLayout,
    ): File {
        val directory = File(context.cacheDir, "meal-calendar-exports").apply {
            check(exists() || mkdirs()) { "无法创建吃历导出缓存" }
        }
        deleteStaleMealExportCaches(directory)
        val file = File.createTempFile(
            MEAL_EXPORT_CACHE_PREFIX,
            MEAL_EXPORT_CACHE_SUFFIX,
            directory,
        )
        try {
            FileOutputStream(file).use { raw ->
                val output = raw.buffered()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法生成 PNG" }
                output.flush()
                raw.fd.sync()
            }
            check(file.length() > 0L) { "生成的 PNG 为空" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
            check(bounds.outWidth == layout.width && bounds.outHeight == layout.height) {
                "PNG 缓存校验失败"
            }
            check(bounds.outMimeType.equals("image/png", ignoreCase = true)) {
                "PNG 缓存格式校验失败"
            }
            val validationBitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampledValidationSize(layout.width, layout.height)
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            ) ?: error("PNG 缓存无法完整解码")
            try {
                check(validationBitmap.width > 0 && validationBitmap.height > 0) {
                    "PNG 缓存像素校验失败"
                }
            } finally {
                validationBitmap.recycle()
            }
            return file
        } catch (_: OutOfMemoryError) {
            runCatching { file.delete() }
            error("没有足够内存校验这张长图，请缩短日期范围")
        } catch (error: Exception) {
            runCatching { file.delete() }
            throw error
        }
    }

    private fun deleteStaleMealExportCaches(directory: File) {
        val staleBefore = System.currentTimeMillis() - MEAL_EXPORT_CACHE_MAX_AGE_MS
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return
        directory.listFiles().orEmpty().forEach { candidate ->
            val ownedTemporaryPng =
                candidate.isFile &&
                    candidate.name.startsWith(MEAL_EXPORT_CACHE_PREFIX) &&
                    candidate.name.endsWith(MEAL_EXPORT_CACHE_SUFFIX, ignoreCase = true) &&
                    runCatching { candidate.canonicalFile.parentFile == canonicalDirectory }
                        .getOrDefault(false)
            if (ownedTemporaryPng && candidate.lastModified() <= staleBefore) {
                runCatching { candidate.delete() }
            }
        }
    }

    private fun sampledValidationSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (
            width / sampleSize > MEAL_EXPORT_VALIDATION_MAX_EDGE ||
            height / sampleSize > MEAL_EXPORT_VALIDATION_MAX_EDGE
        ) {
            if (sampleSize > Int.MAX_VALUE / 2) return sampleSize
            sampleSize *= 2
        }
        return sampleSize
    }

    private suspend fun copyMealExportToDocumentAndVerify(source: File, destination: Uri) {
        val expected = sha256AndSize(source)
        val stream = runCatching { resolver.openOutputStream(destination, "rwt") }.getOrNull()
            ?: resolver.openOutputStream(destination, "wt")
        stream.use { output ->
            requireNotNull(output) { "无法写入导出文件" }
            FileInputStream(source).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
        }
        val actual = sha256AndSize(destination, expected.first)
        check(actual == expected) { "导出文件写入后的校验失败" }
    }

    private fun deleteCreatedExportDocument(destination: Uri) {
        val deleted = runCatching {
            if (DocumentsContract.isDocumentUri(context, destination)) {
                DocumentsContract.deleteDocument(resolver, destination)
            } else {
                false
            }
        }.getOrDefault(false)
        if (!deleted) runCatching { resolver.delete(destination, null, null) }
    }

    private suspend fun sha256AndSize(file: File): Pair<Long, String> =
        FileInputStream(file).use { input -> sha256AndSize(input = input, maxBytes = file.length()) }

    private suspend fun sha256AndSize(uri: Uri, expectedMaxBytes: Long): Pair<Long, String> =
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法回读导出文件" }
            sha256AndSize(input = input, maxBytes = expectedMaxBytes)
        }

    private suspend fun sha256AndSize(
        input: java.io.InputStream,
        maxBytes: Long,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            currentCoroutineContext().ensureActive()
            if (count < 0) break
            total += count
            check(total <= maxBytes) { "导出文件写入后的大小校验失败" }
            digest.update(buffer, 0, count)
        }
        return total to digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    suspend fun load(uri: String): DiaryEditorDocument = withContext(Dispatchers.IO) {
        load(Uri.parse(uri))
    }

    /**
     * Reads diary text for bounded consumers such as AI context import.
     *
     * The Room index size is deliberately not consulted: SAF providers may report an unknown
     * length and the index may be stale after an external edit. The stream itself is capped
     * before bytes are retained, and malformed UTF-8 is rejected instead of replacement-decoded.
     */
    internal suspend fun readDiaryTextBounded(uri: String, maxBytes: Int): String =
        withContext(Dispatchers.IO) {
            require(maxBytes > 0) { "maxBytes must be positive" }
            val parsed = Uri.parse(uri)
            val input = resolver.openInputStream(parsed)
                ?: throw IOException("Unable to open diary text")
            input.use { stream -> stream.readUtf8Bounded(maxBytes) }
        }

    /**
     * Records the estimated energy in the media-directory JSON sidecar instead of
     * rewriting the Markdown caption. Old captions like "午餐-800kJ" stay untouched
     * and continue to work as a read-only fallback.
     */
    suspend fun setMealPhotoEnergy(
        photo: MealCalendarPhoto,
        energyKj: Int,
        settings: AppSettings,
    ) = mediaMutex.withLock {
        withContext(Dispatchers.IO) {
            val root = settings.mediaTreeUri?.let(::tree) ?: error("请先在设置中选择媒体目录")
            val key = photo.fileName.takeIf(String::isNotBlank)
                ?: error("无法确定图片文件名，热量未记录")
            updateMediaMetaEntryUnlocked(root, key) { entry -> entry.copy(energyKj = energyKj) }
        }
    }

    private fun mediaMetaFile(root: DocumentFile): DocumentFile? {
        val files = root.listFiles()
        return files.firstOrNull {
            it.isFile && it.name.equals(MEDIA_META_FILE_NAME, ignoreCase = true)
        } ?: files.firstOrNull {
            it.isFile && it.name.equals(LEGACY_MEDIA_META_FILE_NAME, ignoreCase = true)
        }
    }

    private fun currentMediaMetaFile(root: DocumentFile): DocumentFile? = root.listFiles()
        .firstOrNull { it.isFile && it.name.equals(MEDIA_META_FILE_NAME, ignoreCase = true) }

    private data class MediaDirectorySnapshot(
        val byName: Map<String, Uri> = emptyMap(),
        val metaEntries: Map<String, MediaMetaEntry> = emptyMap(),
    )

    /** Caller must hold [mediaMutex]. */
    private fun snapshotMediaDirectoryUnlocked(root: DocumentFile): MediaDirectorySnapshot {
        val files = root.listFiles()
        val byName = files.asSequence()
            .filter(DocumentFile::isFile)
            .mapNotNull { file ->
                file.name?.let { name -> name.lowercase(Locale.ROOT) to file.uri }
            }
            .toMap()
        val metaFile = files.firstOrNull {
            it.isFile && it.name.equals(MEDIA_META_FILE_NAME, ignoreCase = true)
        } ?: files.firstOrNull {
            it.isFile && it.name.equals(LEGACY_MEDIA_META_FILE_NAME, ignoreCase = true)
        }
        val metaEntries = metaFile?.let { file ->
            try {
                parseMediaMeta(readText(file.uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
        }.orEmpty()
        return MediaDirectorySnapshot(byName = byName, metaEntries = metaEntries)
    }

    private fun readMediaMetaEntries(root: DocumentFile): Map<String, MediaMetaEntry> {
        val file = mediaMetaFile(root) ?: return emptyMap()
        val raw = runCatching { readText(file.uri) }.getOrNull() ?: return emptyMap()
        return parseMediaMeta(raw)
    }

    private fun parseMediaMeta(raw: String): Map<String, MediaMetaEntry> = runCatching {
        val entriesJson = JSONObject(raw).optJSONObject("entries") ?: return@runCatching emptyMap()
        buildMap {
            entriesJson.keys().forEach { key ->
                val item = entriesJson.optJSONObject(key) ?: return@forEach
                put(
                    key.lowercase(Locale.ROOT),
                    MediaMetaEntry(
                        energyKj = if (item.has("energyKj")) item.optInt("energyKj") else null,
                        latitude = if (item.has("lat")) item.optDouble("lat") else null,
                        longitude = if (item.has("lng")) item.optDouble("lng") else null,
                        place = if (item.has("place") && !item.isNull("place")) {
                            item.optString("place").trim().takeIf(String::isNotBlank)
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun encodeMediaMeta(entries: Map<String, MediaMetaEntry>): String {
        val entriesJson = JSONObject()
        entries.toSortedMap().forEach { (key, entry) ->
            val item = JSONObject()
            entry.energyKj?.let { item.put("energyKj", it) }
            entry.latitude?.takeIf(Double::isFinite)?.let { item.put("lat", it) }
            entry.longitude?.takeIf(Double::isFinite)?.let { item.put("lng", it) }
            entry.place?.takeIf(String::isNotBlank)?.let { item.put("place", it) }
            if (item.length() > 0) entriesJson.put(key, item)
        }
        return JSONObject().put("version", 1).put("entries", entriesJson).toString(2)
    }

    /** Caller must hold [mediaMutex]. Read-modify-write with read-back verification. */
    private fun updateMediaMetaEntryUnlocked(
        root: DocumentFile,
        key: String,
        transform: (MediaMetaEntry) -> MediaMetaEntry,
    ) {
        val normalizedKey = key.lowercase(Locale.ROOT)
        val current = readMediaMetaEntries(root).toMutableMap()
        current[normalizedKey] = transform(current[normalizedKey] ?: MediaMetaEntry())
        val encoded = encodeMediaMeta(current)
        // Read the legacy long filename when present, but always write future updates to
        // the shorter name. The legacy file is kept as a recoverable compatibility copy.
        val target = currentMediaMetaFile(root)
            ?: root.createFile("application/json", MEDIA_META_FILE_NAME)
            ?: error("无法在媒体目录创建 $MEDIA_META_FILE_NAME")
        writeText(target.uri, encoded)
        check(readText(target.uri) == encoded) { "媒体信息 JSON 写入后的校验失败" }
    }

    private fun readPhotoLatLong(sourceUri: Uri): DoubleArray? {
        val candidates = buildList {
            // MediaStore redacts EXIF location on API 29+ unless the original is requested
            // (needs ACCESS_MEDIA_LOCATION; best-effort when denied).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                sourceUri.authority == MediaStore.AUTHORITY
            ) {
                runCatching { MediaStore.setRequireOriginal(sourceUri) }.getOrNull()?.let(::add)
            }
            add(sourceUri)
        }
        candidates.forEach { uri ->
            val latLong = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val output = FloatArray(2)
                    if (ExifInterface(input).getLatLong(output)) {
                        doubleArrayOf(output[0].toDouble(), output[1].toDouble())
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (latLong != null) return latLong
        }
        return null
    }

    private fun geocodePlaceName(latitude: Double, longitude: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return@runCatching null
        @Suppress("DEPRECATION")
        val address = Geocoder(context, Locale.getDefault())
            .getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
            ?: return@runCatching null
        listOfNotNull(address.adminArea, address.locality, address.subLocality, address.thoroughfare)
            .distinct()
            .joinToString("")
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun Int.withOpaqueAlpha(): Int =
        if (Color.alpha(this) == 0) this or Color.BLACK else this

    private fun contrastTextColor(background: Int): Int {
        val luminance = (
            0.2126 * Color.red(background) +
                0.7152 * Color.green(background) +
                0.0722 * Color.blue(background)
            ) / 255.0
        return if (luminance > 0.56) Color.rgb(28, 31, 34) else Color.WHITE
    }

    suspend fun enterToday(
        settings: AppSettings,
        today: LocalDate = LocalDate.now(),
    ): DiaryEditorDocument = writeMutex.withLock {
        enterTodayUnlocked(settings, today)
    }

    private suspend fun enterTodayUnlocked(
        settings: AppSettings,
        today: LocalDate,
    ): DiaryEditorDocument =
        withContext(Dispatchers.IO) {
            val root = settings.diaryTreeUri?.let(::tree)
                ?: error("请先在设置中选择日记目录")
            val baseName = formatDate(today, settings.fileNamePattern, "yyyy-MM-dd '日记'")
            val fileName = DiaryTextUtils.sanitizeFileName(baseName.removeSuffix(".md")) + ".md"
            val existing = root.listFiles().firstOrNull { it.name.equals(fileName, ignoreCase = true) }
            if (existing != null) return@withContext load(existing.uri)

            val title = baseName.removeSuffix(".md")
            val dateText = today.toString()
            val content = settings.markdownTemplate
                .replace("{title}", title)
                .replace("{date}", dateText)
            val created = root.createFile("text/markdown", fileName)
                ?: error("无法在所选目录中创建日记")
            try {
                writeText(created.uri, content)
                load(created.uri)
            } catch (error: Exception) {
                val committed = runCatching { readText(created.uri) == content }.getOrDefault(false)
                if (committed && error !is CancellationException) return@withContext load(created.uri)
                if (!committed) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { created.delete() }
                    }
                }
                throw error
            }
        }

    suspend fun create(settings: AppSettings, title: String, date: LocalDate = LocalDate.now()): DiaryEditorDocument =
        withContext(Dispatchers.IO) {
            val root = settings.diaryTreeUri?.let(::tree) ?: error("请先选择日记目录")
            val dateText = date.toString()
            val safeTitle = DiaryTextUtils.sanitizeFileName(title.ifBlank { "新日记" })
            var candidate = "$dateText $safeTitle.md"
            var sequence = 2
            while (root.findFile(candidate) != null) {
                candidate = "$dateText $safeTitle ($sequence).md"
                sequence++
            }
            val file = root.createFile("text/markdown", candidate) ?: error("创建日记失败")
            val content = settings.markdownTemplate
                .replace("{title}", title.ifBlank { "新日记" })
                .replace("{date}", dateText)
            writeText(file.uri, content)
            load(file.uri)
        }

    suspend fun save(
        uri: String,
        content: String,
        expectedSha256: String,
        force: Boolean = false,
    ): DiaryEditorDocument = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val target = Uri.parse(uri)
            val onDisk = load(target)
            if (!force && onDisk.sha256 != expectedSha256) throw ExternalFileConflictException(onDisk)
            writeText(target, content)
            load(target)
        }
    }

    suspend fun rename(uri: String, newFileName: String, settings: AppSettings): DiaryEditorDocument =
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val root = settings.diaryTreeUri?.let(::tree)
                    ?: error("请先在设置中选择日记目录")
                val sourceUri = Uri.parse(uri)
                val source = DocumentFile.fromSingleUri(context, sourceUri)
                    ?: error("找不到要重命名的日记文件")
                val currentName = source.name ?: error("无法读取当前日记文件名")
                val targetName = DiaryTextUtils.normalizeMarkdownFileName(newFileName)

                if (currentName == targetName) {
                    return@withContext load(sourceUri)
                }

                val duplicate = root.listFiles().firstOrNull { candidate ->
                    candidate.uri != sourceUri && candidate.name.equals(targetName, ignoreCase = true)
                }
                require(duplicate == null) { "目录中已存在同名日记：$targetName" }

                var directFailure: Throwable? = null
                val renamedUri = try {
                    DocumentsContract.renameDocument(resolver, sourceUri, targetName)
                } catch (error: Exception) {
                    directFailure = error
                    null
                } ?: run {
                    val fallbackSucceeded = runCatching { source.renameTo(targetName) }
                        .onFailure { directFailure = it }
                        .getOrDefault(false)
                    if (!fallbackSucceeded) {
                        throw IllegalStateException(
                            "重命名失败，存储服务拒绝了文件名：$targetName",
                            directFailure,
                        )
                    }
                    root.listFiles().firstOrNull { it.name == targetName }?.uri ?: source.uri
                }

                // Some providers return a new document URI after rename, while others retain the
                // original URI. Resolve the document from both places before reporting success.
                val renamedFile = DocumentFile.fromSingleUri(context, renamedUri)
                    ?.takeIf { it.exists() && it.name.equals(targetName, ignoreCase = true) }
                    ?: root.listFiles().firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    ?: error("文件可能已重命名，但存储服务没有返回可访问的新文件")
                val renamedDocument = load(renamedFile.uri)
                updateIndexAfterRename(uri, renamedDocument)
                renamedDocument
            }
        }

    private suspend fun updateIndexAfterRename(oldUri: String, document: DiaryEditorDocument) {
        val date = extractDate(document.name, document.lastModified)
        val renamedIndex = DiaryIndexEntity(
            uri = document.uri,
            name = document.name,
            title = markdownStem(document.name),
            dateIso = date.toString(),
            monthKey = "%04d.%02d".format(Locale.ROOT, date.year, date.monthValue),
            lastModified = document.lastModified,
            size = document.size,
            wordCount = DiaryTextUtils.wordCount(document.content),
            sha256 = document.sha256,
            indexedAt = System.currentTimeMillis(),
        )
        val preserved = indexDao.getAll().filterNot { it.uri == oldUri || it.uri == document.uri }
        indexDao.replaceAfterSuccessfulScan(preserved + renamedIndex)
    }

    suspend fun delete(uri: String, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext false
        val document = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return@withContext false
        val originalName = document.name ?: return@withContext false
        val trashRoot = root.findFile(TRASH_DIRECTORY) ?: root.createDirectory(TRASH_DIRECTORY)
            ?: error("无法创建日记回收站目录")
        val storedName = "${System.currentTimeMillis()}__$originalName"
        val backup = trashRoot.createFile("application/octet-stream", storedName)
            ?: error("无法在回收站中创建备份")
        runCatching {
            val bytes = readBytes(document.uri)
            resolver.openOutputStream(backup.uri, "w").use { output ->
                requireNotNull(output) { "无法写入日记回收站" }
                output.write(bytes)
            }
            require(readBytes(backup.uri).contentEquals(bytes)) { "回收站备份校验失败" }
            require(document.delete()) { "原日记无法删除，文件已保持不变" }
            true
        }.onFailure { backup.delete() }.getOrThrow()
    }

    suspend fun scanTrash(settings: AppSettings): List<DiaryTrashItem> = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext emptyList()
        val currentTrash = root.findFile(TRASH_DIRECTORY)?.listFiles().orEmpty()
            .filter { it.isFile }
            .map { file ->
                val storedName = file.name.orEmpty()
                DiaryTrashItem(
                    uri = file.uri.toString(),
                    originalName = storedName.substringAfter("__", storedName),
                    deletedAt = storedName.substringBefore("__").toLongOrNull() ?: file.lastModified(),
                )
            }
        val legacyTrash = root.listFiles()
            .filter { it.isFile && it.name?.endsWith(".$TRASH_SUFFIX", ignoreCase = true) == true }
            .map { file ->
                DiaryTrashItem(
                    uri = file.uri.toString(),
                    originalName = file.name.orEmpty().removeSuffix(".$TRASH_SUFFIX"),
                    deletedAt = file.lastModified(),
                )
            }
        (currentTrash + legacyTrash).sortedByDescending { it.deletedAt }
    }

    suspend fun restore(uri: String, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext false
        val document = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return@withContext false
        val storedName = document.name.orEmpty()
        val legacy = storedName.endsWith(".$TRASH_SUFFIX", ignoreCase = true)
        val original = if (legacy) storedName.removeSuffix(".$TRASH_SUFFIX") else storedName.substringAfter("__", storedName)
        var candidate = original
        var sequence = 2
        while (root.listFiles().any { it.name.equals(candidate, ignoreCase = true) }) {
            val extension = original.substringAfterLast('.', "md")
            val stem = original.removeSuffix(".$extension")
            candidate = "$stem (恢复 $sequence).$extension"
            sequence++
        }
        if (legacy) {
            document.renameTo(candidate)
        } else {
            val restored = root.createFile("text/markdown", candidate) ?: return@withContext false
            runCatching {
                val bytes = readBytes(document.uri)
                resolver.openOutputStream(restored.uri, "w").use { output ->
                    requireNotNull(output) { "无法恢复日记" }
                    output.write(bytes)
                }
                require(readBytes(restored.uri).contentEquals(bytes)) { "恢复后的日记校验失败" }
                require(document.delete()) { "日记已恢复，但回收站副本无法移除" }
                true
            }.onFailure { restored.delete() }.getOrThrow()
        }
    }

    suspend fun permanentlyDelete(uri: String): Boolean = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return@withContext false
        val name = document.name.orEmpty()
        val isTrash = name.endsWith(".$TRASH_SUFFIX", ignoreCase = true) ||
            name.substringBefore("__").toLongOrNull() != null
        if (!isTrash) return@withContext false
        document.delete()
    }

    suspend fun importImage(
        sourceUri: Uri,
        category: String?,
        settings: AppSettings,
        date: LocalDate = LocalDate.now(),
    ): ImportedMedia = mediaMutex.withLock {
        var created: DocumentFile? = null
        var compressedFile: File? = null
        try {
            withContext(Dispatchers.IO) {
                val root = settings.mediaTreeUri?.let(::tree) ?: error("请先在设置中选择媒体目录")
                val sourceMime = resolver.getType(sourceUri) ?: "image/jpeg"
                val sourceExtension = inferExtension(sourceUri, sourceMime)
                val shouldCompress = settings.mealImageCompressionEnabled && !category.isNullOrBlank()
                compressedFile = if (shouldCompress && isCompressibleImage(sourceMime, sourceExtension)) {
                    compressMealImageToCache(sourceUri, settings.mealImageCompressionQuality)
                } else {
                    null
                }
                val mime = if (compressedFile != null) "image/jpeg" else sourceMime
                val extension = if (compressedFile != null) "jpg" else sourceExtension
                val categoryText = category?.takeIf(String::isNotBlank) ?: "图片"
                val dateText = date.toString()
                val fileName = DiaryTextUtils.nextMediaFileName(
                    pattern = settings.imageNamePattern,
                    dateText = dateText,
                    category = categoryText,
                    extension = extension,
                    existingNames = root.listFiles().mapNotNull { it.name },
                )

                val destination = root.createFile(mime, fileName) ?: error("无法创建媒体文件")
                created = destination
                val inputStream = compressedFile?.inputStream() ?: resolver.openInputStream(sourceUri)
                inputStream.use { input ->
                    requireNotNull(input) { "无法读取所选图片" }
                    resolver.openOutputStream(destination.uri, "w").use { output ->
                        requireNotNull(output) { "无法写入媒体目录" }
                        input.copyTo(output)
                        output.flush()
                    }
                }

                val actualName = destination.name ?: fileName
                if (settings.saveOriginalToGallery) {
                    // Best-effort: a gallery failure must never fail or roll back the SAF write.
                    runCatching {
                        saveOriginalToGallery(
                            sourceUri = sourceUri,
                            mime = sourceMime,
                            displayName = actualName.substringBeforeLast('.') + "." + sourceExtension,
                        )
                    }
                }
                if (settings.photoLocationEnabled) {
                    // Best-effort EXIF location capture into the media JSON sidecar.
                    // mediaMutex is already held here.
                    runCatching {
                        readPhotoLatLong(sourceUri)?.let { latLong ->
                            updateMediaMetaEntryUnlocked(root, actualName) { entry ->
                                entry.copy(
                                    latitude = latLong[0],
                                    longitude = latLong[1],
                                    place = geocodePlaceName(latLong[0], latLong[1]) ?: entry.place,
                                )
                            }
                        }
                    }
                }
                ImportedMedia(
                    documentUri = destination.uri.toString(),
                    fileName = actualName,
                    markdown = "![$categoryText](<${actualName.replace(">", "%3E")}>)",
                )
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { created?.delete() }
            }
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { compressedFile?.delete() }
            }
        }
    }

    /**
     * Copies the untouched source image into the system gallery (MediaStore.Images).
     * On API 29+ this needs no permission; on API 26-28 it relies on
     * WRITE_EXTERNAL_STORAGE being granted and otherwise fails, which callers treat
     * as best-effort.
     */
    private fun saveOriginalToGallery(sourceUri: Uri, mime: String, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/DeskCubby",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val itemUri = resolver.insert(collection, values) ?: error("无法写入系统相册")
        try {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                resolver.openOutputStream(itemUri, "w").use { output ->
                    requireNotNull(output) { "无法写入系统相册" }
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
        } catch (error: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw error
        }
    }

    suspend fun appendImageToToday(
        sourceUri: Uri,
        category: String,
        settings: AppSettings,
        date: LocalDate = LocalDate.now(),
    ): ImportedMedia {
        val media = importImage(sourceUri, category, settings, date)
        var diaryUri: Uri? = null
        var originalContent: String? = null
        var updatedContent: String? = null
        return try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val document = enterTodayUnlocked(settings, date)
                    diaryUri = Uri.parse(document.uri)
                    originalContent = document.content
                    val lineBreak = if (
                        document.content.isEmpty() ||
                        document.content.endsWith('\n') ||
                        document.content.endsWith('\r')
                    ) {
                        ""
                    } else {
                        DiaryTextUtils.preferredLineEnding(document.content)
                    }
                    updatedContent = document.content + lineBreak + media.markdown
                    writeText(requireNotNull(diaryUri), requireNotNull(updatedContent))
                    check(readText(requireNotNull(diaryUri)) == updatedContent) {
                        "图片写入今日日记后的校验失败"
                    }
                    media
                }
            }
        } catch (error: Exception) {
            var committed = false
            var safeToDeleteMedia = diaryUri == null
            withContext(NonCancellable + Dispatchers.IO) {
                val target = diaryUri
                val desired = updatedContent
                val original = originalContent
                if (target != null && desired != null && original != null) {
                    val diskContent = runCatching { readText(target) }.getOrNull()
                    if (diskContent == desired) {
                        committed = true
                    } else {
                        val rollback = runCatching {
                            writeText(target, original)
                            check(readText(target) == original) { "今日日记原文恢复校验失败" }
                        }
                        safeToDeleteMedia = rollback.isSuccess
                        rollback.exceptionOrNull()?.let(error::addSuppressed)
                    }
                }
                if (safeToDeleteMedia) {
                    runCatching {
                        DocumentFile.fromSingleUri(context, Uri.parse(media.documentUri))?.delete()
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
            }
            if (committed && error !is CancellationException) return media
            throw error
        }
    }

    /** Appends one exact, standalone text line to today's Markdown diary. */
    suspend fun appendTextToToday(
        text: String,
        settings: AppSettings,
        date: LocalDate = LocalDate.now(),
    ): DiaryEditorDocument = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val line = text.trim().replace('\r', ' ').replace('\n', ' ')
            require(line.isNotBlank()) { "日常记录不能为空" }
            val document = enterTodayUnlocked(settings, date)
            val uri = Uri.parse(document.uri)
            val separator = when {
                document.content.isEmpty() || document.content.endsWith('\n') || document.content.endsWith('\r') -> ""
                else -> DiaryTextUtils.preferredLineEnding(document.content)
            }
            val updated = document.content + separator + line
            val immediatelyBeforeWrite = load(uri)
            if (immediatelyBeforeWrite.sha256 != document.sha256) {
                throw ExternalFileConflictException(immediatelyBeforeWrite)
            }
            try {
                writeText(uri, updated)
                check(readText(uri) == updated) { "日常记录写入今日日记后的校验失败" }
                load(uri)
            } catch (error: Exception) {
                val committed = runCatching { readText(uri) == updated }.getOrDefault(false)
                if (committed && error !is CancellationException) return@withContext load(uri)
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        writeText(uri, document.content)
                        check(readText(uri) == document.content) { "今日日记原文恢复校验失败" }
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }

    suspend fun resolveMedia(markdownTarget: String, settings: AppSettings): Uri? =
        resolveDiaryPreviewMedia(listOf(markdownTarget), settings)[markdownTarget]?.uri

    /**
     * Resolves every Markdown image in one media-directory snapshot. File names and sidecar keys
     * are matched case-insensitively, and the sidecar is read while holding [mediaMutex] so a
     * concurrent metadata update cannot expose a half-old/half-new preview.
     */
    suspend fun resolveDiaryPreviewMedia(
        markdownTargets: Collection<String>,
        settings: AppSettings,
    ): Map<String, DiaryPreviewMedia> = withContext(Dispatchers.IO) {
        val targets = markdownTargets.distinct()
        if (targets.isEmpty()) return@withContext emptyMap()
        val snapshot = settings.mediaTreeUri?.let(::tree)?.let { root ->
            mediaMutex.withLock { snapshotMediaDirectoryUnlocked(root) }
        } ?: MediaDirectorySnapshot()
        buildMap {
            targets.forEach { target ->
                currentCoroutineContext().ensureActive()
                val fileName = decodedTargetFileName(target)?.lowercase(Locale.ROOT)
                val uri = resolveMealMediaUri(target, snapshot.byName)
                val location = fileName
                    ?.let(snapshot.metaEntries::get)
                    ?.let(::mediaMetaDisplayLocation)
                put(target, DiaryPreviewMedia(uri = uri, locationName = location))
            }
        }
    }

    /**
     * Produces bounded, hash-verified SAF snapshots for cloud sync. Only direct children are
     * included because the diary and media features themselves use these directories as flat
     * stores. No content URI is converted to a filesystem path.
     */
    suspend fun snapshotForCloudSync(
        settings: AppSettings,
        areas: Set<DiaryCloudSyncArea>,
        maxObjectBytes: Long,
        maxObjects: Int,
    ): List<DiaryCloudSyncFile> = withContext(Dispatchers.IO) {
        require(maxObjectBytes > 0L && maxObjects > 0) { "同步大小限制无效" }
        val result = mutableListOf<DiaryCloudSyncFile>()
        areas.forEach { area ->
            val rawTree = when (area) {
                DiaryCloudSyncArea.DIARY -> settings.diaryTreeUri
                DiaryCloudSyncArea.MEDIA -> settings.mediaTreeUri
            } ?: return@forEach
            val root = tree(rawTree)
            root.listFiles()
                .asSequence()
                .filter(DocumentFile::isFile)
                .filter { file ->
                    area != DiaryCloudSyncArea.DIARY ||
                        file.name?.endsWith(".md", ignoreCase = true) == true
                }
                .sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
                .forEach { file ->
                    currentCoroutineContext().ensureActive()
                    if (result.size >= maxObjects) error("同步文件数量超过上限")
                    val name = requireCloudSyncFileName(file.name)
                    val bytes = readBytesBounded(file.uri, maxObjectBytes)
                    result += DiaryCloudSyncFile(
                        area = area,
                        name = name,
                        uri = file.uri.toString(),
                        mimeType = file.type ?: mimeTypeForCloudSync(name, area),
                        size = bytes.size.toLong(),
                        lastModifiedMillis = file.lastModified().coerceAtLeast(0L),
                        sha256 = DiaryTextUtils.sha256(bytes),
                    )
                }
        }
        result
    }

    suspend fun readForCloudSync(
        file: DiaryCloudSyncFile,
        maxObjectBytes: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        val bytes = readBytesBounded(Uri.parse(file.uri), maxObjectBytes)
        if (bytes.size.toLong() != file.size || DiaryTextUtils.sha256(bytes) != file.sha256) {
            error("本地文件在同步读取期间发生变化")
        }
        bytes
    }

    /**
     * Applies a remote object conservatively. A replacement is first written and read back from a
     * pending document; the old bytes then receive a verified recovery copy before the existing
     * document is overwritten. If the expected local hash no longer matches, a deterministic
     * remote-conflict copy is created instead.
     */
    suspend fun writeFromCloudSync(
        settings: AppSettings,
        area: DiaryCloudSyncArea,
        name: String,
        bytes: ByteArray,
        expectedSha256: String,
        expectedLocalSha256: String?,
        maxObjectBytes: Long,
    ): DiaryCloudSyncWriteResult {
        require(bytes.size.toLong() <= maxObjectBytes) { "远端文件超过单文件同步上限" }
        require(DiaryTextUtils.sha256(bytes) == expectedSha256) { "远端文件校验失败" }
        val validName = requireCloudSyncFileName(name)
        if (area == DiaryCloudSyncArea.DIARY) {
            require(validName.endsWith(".md", ignoreCase = true)) { "远端日记不是 Markdown 文件" }
        }
        val mutex = if (area == DiaryCloudSyncArea.MEDIA) mediaMutex else writeMutex
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val rawTree = when (area) {
                    DiaryCloudSyncArea.DIARY -> settings.diaryTreeUri
                    DiaryCloudSyncArea.MEDIA -> settings.mediaTreeUri
                } ?: error(if (area == DiaryCloudSyncArea.DIARY) "请先选择日记目录" else "请先选择媒体目录")
                val root = tree(rawTree)
                val target = root.listFiles().firstOrNull {
                    it.isFile && it.name == validName
                }
                val current = target?.let {
                    snapshotCloudSyncDocument(area, it, maxObjectBytes)
                }
                if (current?.sha256 != expectedLocalSha256) {
                    val copy = writeCloudConflictCopy(
                        root = root,
                        area = area,
                        originalName = validName,
                        bytes = bytes,
                        hash = expectedSha256,
                        maxObjectBytes = maxObjectBytes,
                    )
                    return@withContext DiaryCloudSyncWriteResult.ConflictCopy(current, copy)
                }
                if (target == null) {
                    val created = createAndVerifyCloudSyncDocument(
                        root = root,
                        area = area,
                        name = validName,
                        bytes = bytes,
                        expectedHash = expectedSha256,
                        maxObjectBytes = maxObjectBytes,
                    )
                    return@withContext DiaryCloudSyncWriteResult.Applied(created)
                }

                val mime = target.type ?: mimeTypeForCloudSync(validName, area)
                val pendingName = uniqueCloudSyncName(
                    root,
                    cloudSyncSiblingName(validName, ".sync-pending-${expectedSha256.take(8)}"),
                )
                val pending = root.createFile(mime, pendingName)
                    ?: error("无法创建同步临时文件")
                try {
                    ensureExactDocumentName(pending, pendingName)
                    writeBytes(pending.uri, bytes)
                    verifyCloudSyncBytes(pending.uri, bytes.size.toLong(), expectedSha256, maxObjectBytes)

                    // Recheck immediately before committing so an external edit after the initial
                    // scan cannot be silently overwritten.
                    val originalBytes = readBytesBounded(target.uri, maxObjectBytes)
                    val originalHash = DiaryTextUtils.sha256(originalBytes)
                    if (originalHash != expectedLocalSha256) {
                        val copy = writeCloudConflictCopy(
                            root, area, validName, bytes, expectedSha256, maxObjectBytes,
                        )
                        return@withContext DiaryCloudSyncWriteResult.ConflictCopy(
                            snapshotCloudSyncDocument(area, target, maxObjectBytes),
                            copy,
                        )
                    }
                    val recoveryName = uniqueCloudSyncName(
                        root,
                        cloudSyncSiblingName(validName, ".sync-previous-${originalHash.take(8)}"),
                    )
                    val recovery = root.createFile(mime, recoveryName)
                        ?: error("无法创建同步恢复副本")
                    ensureExactDocumentName(recovery, recoveryName)
                    writeBytes(recovery.uri, originalBytes)
                    verifyCloudSyncBytes(
                        recovery.uri,
                        originalBytes.size.toLong(),
                        originalHash,
                        maxObjectBytes,
                    )
                    try {
                        writeBytes(target.uri, bytes)
                        verifyCloudSyncBytes(
                            target.uri,
                            bytes.size.toLong(),
                            expectedSha256,
                            maxObjectBytes,
                        )
                    } catch (error: Exception) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching {
                                writeBytes(target.uri, originalBytes)
                                verifyCloudSyncBytes(
                                    target.uri,
                                    originalBytes.size.toLong(),
                                    originalHash,
                                    maxObjectBytes,
                                )
                            }.exceptionOrNull()?.let(error::addSuppressed)
                        }
                        throw error
                    }
                    runCatching { recovery.delete() }
                    DiaryCloudSyncWriteResult.Applied(
                        snapshotCloudSyncDocument(area, target, maxObjectBytes),
                    )
                } finally {
                    runCatching { pending.delete() }
                }
            }
        }
    }

    fun hasPersistedAccess(uri: String?): Boolean {
        if (uri == null) return false
        return resolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission && it.isWritePermission
        }
    }

    private fun tree(raw: String): DocumentFile =
        DocumentFile.fromTreeUri(context, Uri.parse(raw)) ?: error("目录授权已失效，请重新选择")

    private fun load(uri: Uri): DiaryEditorDocument {
        val document = DocumentFile.fromSingleUri(context, uri) ?: error("日记文件不存在")
        val bytes = readBytes(uri)
        return DiaryEditorDocument(
            uri = uri.toString(),
            name = document.name.orEmpty(),
            content = bytes.toString(Charsets.UTF_8),
            lastModified = document.lastModified(),
            size = document.length(),
            sha256 = DiaryTextUtils.sha256(bytes),
        )
    }

    private fun readText(uri: Uri): String = readBytes(uri).toString(Charsets.UTF_8)

    private fun readBytes(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文件" }
            input.copyTo(output)
        }
        return output.toByteArray()
    }

    private suspend fun readBytesBounded(uri: Uri, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文件" }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "文件超过单文件同步上限" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun writeText(uri: Uri, content: String) {
        val stream = resolver.openOutputStream(uri, "rwt") ?: resolver.openOutputStream(uri, "wt")
        stream.use { output ->
            requireNotNull(output) { "无法写入文件" }
            output.write(content.toByteArray())
            output.flush()
        }
    }

    private suspend fun writeBytes(uri: Uri, bytes: ByteArray) {
        val stream = runCatching { resolver.openOutputStream(uri, "rwt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "wt")
        stream.use { output ->
            requireNotNull(output) { "无法写入文件" }
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(DEFAULT_BUFFER_SIZE, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
            }
            output.flush()
        }
    }

    private suspend fun snapshotCloudSyncDocument(
        area: DiaryCloudSyncArea,
        document: DocumentFile,
        maxObjectBytes: Long,
    ): DiaryCloudSyncFile {
        val name = requireCloudSyncFileName(document.name)
        val bytes = readBytesBounded(document.uri, maxObjectBytes)
        return DiaryCloudSyncFile(
            area = area,
            name = name,
            uri = document.uri.toString(),
            mimeType = document.type ?: mimeTypeForCloudSync(name, area),
            size = bytes.size.toLong(),
            lastModifiedMillis = document.lastModified().coerceAtLeast(0L),
            sha256 = DiaryTextUtils.sha256(bytes),
        )
    }

    private suspend fun createAndVerifyCloudSyncDocument(
        root: DocumentFile,
        area: DiaryCloudSyncArea,
        name: String,
        bytes: ByteArray,
        expectedHash: String,
        maxObjectBytes: Long,
    ): DiaryCloudSyncFile {
        val created = root.createFile(mimeTypeForCloudSync(name, area), name)
            ?: error("无法创建同步文件")
        try {
            ensureExactDocumentName(created, name)
            writeBytes(created.uri, bytes)
            verifyCloudSyncBytes(created.uri, bytes.size.toLong(), expectedHash, maxObjectBytes)
            return snapshotCloudSyncDocument(area, created, maxObjectBytes)
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { created.delete() }
            }
            throw error
        }
    }

    private suspend fun writeCloudConflictCopy(
        root: DocumentFile,
        area: DiaryCloudSyncArea,
        originalName: String,
        bytes: ByteArray,
        hash: String,
        maxObjectBytes: Long,
    ): DiaryCloudSyncFile {
        val preferred = cloudSyncSiblingName(originalName, ".remote-conflict-${hash.take(8)}")
        root.listFiles().firstOrNull { it.isFile && it.name == preferred }?.let { existing ->
            val snapshot = snapshotCloudSyncDocument(area, existing, maxObjectBytes)
            if (snapshot.sha256 == hash) return snapshot
        }
        val name = uniqueCloudSyncName(root, preferred)
        return createAndVerifyCloudSyncDocument(
            root, area, name, bytes, hash, maxObjectBytes,
        )
    }

    private suspend fun verifyCloudSyncBytes(
        uri: Uri,
        expectedSize: Long,
        expectedHash: String,
        maxObjectBytes: Long,
    ) {
        val verified = readBytesBounded(uri, maxObjectBytes)
        check(
            verified.size.toLong() == expectedSize &&
                DiaryTextUtils.sha256(verified) == expectedHash,
        ) { "同步文件写入后的校验失败" }
    }

    private fun ensureExactDocumentName(document: DocumentFile, expectedName: String) {
        if (document.name != expectedName) {
            runCatching { document.delete() }
            error("存储服务更改了同步文件名")
        }
    }

    private fun uniqueCloudSyncName(root: DocumentFile, preferred: String): String {
        if (root.findFile(preferred) == null) return preferred
        val dot = preferred.lastIndexOf('.').takeIf { it > 0 } ?: preferred.length
        val stem = preferred.substring(0, dot)
        val extension = preferred.substring(dot)
        var sequence = 2
        while (sequence <= 10_000) {
            val candidate = "$stem ($sequence)$extension"
            if (root.findFile(candidate) == null) return candidate
            sequence++
        }
        error("无法为同步副本生成文件名")
    }

    private fun cloudSyncSiblingName(originalName: String, suffix: String): String {
        val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: originalName.length
        return originalName.substring(0, dot) + suffix + originalName.substring(dot)
    }

    private fun requireCloudSyncFileName(name: String?): String {
        val value = name?.takeIf(String::isNotBlank) ?: error("存储服务返回了空文件名")
        require(
            value.length <= 255 && value != "." && value != ".." &&
                '/' !in value && '\\' !in value && value.none(Char::isISOControl),
        ) { "同步文件名无效" }
        return value
    }

    private fun mimeTypeForCloudSync(name: String, area: DiaryCloudSyncArea): String {
        if (area == DiaryCloudSyncArea.DIARY) return "text/markdown"
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun isCompressibleImage(mime: String, extension: String): Boolean {
        val normalizedMime = mime.lowercase(Locale.ROOT)
        val normalizedExtension = extension.lowercase(Locale.ROOT)
        return normalizedMime in COMPRESSIBLE_IMAGE_MIMES ||
            normalizedExtension in COMPRESSIBLE_IMAGE_EXTENSIONS
    }

    private fun compressMealImageToCache(sourceUri: Uri, quality: Int): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }.getOrElse { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val target = compressedImageSize(bounds.outWidth, bounds.outHeight, COMPRESSED_IMAGE_MAX_EDGE_PX)
        val options = BitmapFactory.Options().apply {
            inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                BitmapFactory.decodeStream(input, null, options)
            }
        }.getOrNull() ?: return null

        var bitmap = decoded
        var tempFile: File? = null
        return try {
            // Scale before applying EXIF rotation so a large source and a same-sized rotated copy
            // are never held at the same time. This keeps peak memory bounded on camera photos.
            val scaledTarget = compressedImageSize(bitmap.width, bitmap.height, COMPRESSED_IMAGE_MAX_EDGE_PX)
            if (bitmap.width != scaledTarget.width || bitmap.height != scaledTarget.height) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    scaledTarget.width,
                    scaledTarget.height,
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            val oriented = applyExifOrientation(bitmap, readExifOrientation(sourceUri))
            if (oriented !== bitmap) {
                bitmap.recycle()
                bitmap = oriented
            }

            if (bitmap.hasAlpha()) {
                val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(flattened).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                bitmap.recycle()
                bitmap = flattened
            }

            val directory = File(context.cacheDir, "meal-image-compression").apply {
                check(exists() || mkdirs()) { "无法创建图片压缩缓存" }
            }
            val compressed = File.createTempFile("meal-", ".jpg", directory)
            tempFile = compressed
            compressed.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), output)) {
                    "无法压缩饮食图片"
                }
                output.flush()
            }

            val sourceSize = sourceByteSize(sourceUri)
            if (compressed.length() <= 0L || (sourceSize > 0L && compressed.length() >= sourceSize)) {
                compressed.delete()
                null
            } else {
                compressed
            }
        } catch (_: OutOfMemoryError) {
            tempFile?.delete()
            null
        } catch (_: Exception) {
            tempFile?.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun readExifOrientation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun sourceByteSize(uri: Uri): Long = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length } ?: -1L
    }.getOrDefault(-1L)

    private fun inferExtension(uri: Uri, mime: String): String {
        val byMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (!byMime.isNullOrBlank()) return byMime.lowercase(Locale.ROOT)
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return displayName?.substringAfterLast('.', "jpg")?.lowercase(Locale.ROOT) ?: "jpg"
    }

    private fun mealCategoryFromCaption(caption: String): MealCategory? {
        val normalized = caption.trim().lowercase(Locale.ROOT).replace(WHITESPACE_REGEX, " ")
        return MealCategory.entries.firstOrNull { category ->
            normalized.removeSuffixEnergy() == category.chineseLabel ||
                normalized.removeSuffixEnergy() == category.englishLabel.lowercase(Locale.ROOT)
        } ?: MealCategory.LATE_SNACK.takeIf { normalized == "late-night snack" }
    }

    private fun energyFromCaption(caption: String): Int? = ENERGY_SUFFIX_REGEX.find(caption)?.groupValues?.get(1)?.toIntOrNull()
    private fun String.removeSuffixEnergy(): String = replace(ENERGY_SUFFIX_REGEX, "").trimEnd('-', ' ')

    private fun mealCategoryFromFileName(target: String): MealCategory? {
        val fileName = decodedTargetFileName(target)?.lowercase(Locale.ROOT) ?: return null
        return MealCategory.entries.firstOrNull { category ->
            fileName.contains(category.chineseLabel) ||
                Regex(
                    "(?:^|[^a-z])" + when (category) {
                        MealCategory.LATE_SNACK -> "late[ _-]+(?:night[ _-]+)?snack"
                        else -> Regex.escape(category.englishLabel.lowercase(Locale.ROOT))
                    } + "(?:[^a-z]|$)",
                ).containsMatchIn(fileName)
        }
    }

    private fun resolveMealMediaUri(target: String, mediaByName: Map<String, Uri>): Uri? {
        val cleaned = target.trim().trim('<', '>')
        if (cleaned.isBlank()) return null
        // Parse explicit URIs before decoding. Decoding a complete SAF URI corrupts encoded
        // document IDs such as %3A and %2F.
        val directUri = runCatching { Uri.parse(cleaned) }.getOrNull()
        if (directUri?.scheme?.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) == true ||
            directUri?.scheme?.equals(ContentResolver.SCHEME_FILE, ignoreCase = true) == true ||
            directUri?.scheme?.equals(ContentResolver.SCHEME_ANDROID_RESOURCE, ignoreCase = true) == true
        ) {
            return directUri
        }

        val fileName = decodedTargetFileName(cleaned)
            ?: return null
        return mediaByName[fileName.lowercase(Locale.ROOT)]
    }

    private fun decodedTargetFileName(target: String): String? = runCatching {
        Uri.decode(
            target
                .trim()
                .trim('<', '>')
                .replace('\\', '/')
                .substringAfterLast('/'),
        )
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun extractDate(
        name: String,
        modified: Long,
        fileNamePattern: String? = null,
    ): LocalDate {
        fileNamePattern?.takeIf(String::isNotBlank)?.let { pattern ->
            runCatching {
                LocalDate.parse(
                    markdownStem(name),
                    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()),
                )
            }.getOrNull()?.let { return it }
        }
        DATE_REGEX.find(name)?.value?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()?.let { return it }
        }
        val instant = if (modified > 0) Instant.ofEpochMilli(modified) else Instant.now()
        return instant.atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun markdownStem(name: String): String =
        if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name

    private fun formatDate(date: LocalDate, pattern: String, fallback: String): String = try {
        date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    } catch (_: IllegalArgumentException) {
        date.format(DateTimeFormatter.ofPattern(fallback, Locale.getDefault()))
    } catch (_: DateTimeParseException) {
        date.toString()
    }

    companion object {
        private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val MARKDOWN_IMAGE_REGEX = Regex(
            """!\[([^\]\r\n]*)]\(\s*(?:<([^>\r\n]+)>|([^\s)\r\n]+))(?:\s+(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|\([^\)\r\n]*\)))?\s*\)""",
        )
        private val ENERGY_SUFFIX_REGEX = Regex("[-–—]\\s*(\\d+)\\s*kJ\\s*$", RegexOption.IGNORE_CASE)
        private const val MEDIA_META_FILE_NAME = "dc-media.json"
        private const val LEGACY_MEDIA_META_FILE_NAME = "deskcubby-media.json"
        private const val MEAL_EXPORT_CACHE_PREFIX = "meal-calendar-"
        private const val MEAL_EXPORT_CACHE_SUFFIX = ".png"
        private const val MEAL_EXPORT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val MEAL_EXPORT_VALIDATION_MAX_EDGE = 256
        private val COMPRESSIBLE_IMAGE_MIMES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/heic",
            "image/heif",
            "image/webp",
            "image/avif",
        )
        private val COMPRESSIBLE_IMAGE_EXTENSIONS = setOf(
            "jpg",
            "jpeg",
            "png",
            "heic",
            "heif",
            "webp",
            "avif",
        )
        private const val COMPRESSED_IMAGE_MAX_EDGE_PX = 2_560
        private const val TRASH_SUFFIX = "deskcubby-trash"
        private const val TRASH_DIRECTORY = ".DeskCubby Trash"
    }
}

internal suspend fun InputStream.readUtf8Bounded(maxBytes: Int): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
        currentCoroutineContext().ensureActive()
        if (count < 0) break
        if (count > maxBytes - total) {
            throw DiaryTextLimitExceededException(maxBytes)
        }
        output.write(buffer, 0, count)
        total += count
    }
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
    } catch (error: CharacterCodingException) {
        throw DiaryTextInvalidUtf8Exception(error)
    }
}

internal fun mediaMetaDisplayLocation(entry: MediaMetaEntry): String? {
    entry.place?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    val latitude = entry.latitude
        ?.takeIf { it.isFinite() && it in -90.0..90.0 }
        ?: return null
    val longitude = entry.longitude
        ?.takeIf { it.isFinite() && it in -180.0..180.0 }
        ?: return null
    return "%.4f, %.4f".format(Locale.ROOT, latitude, longitude)
}

internal data class CompressedImageSize(val width: Int, val height: Int)

internal fun compressedImageSize(
    width: Int,
    height: Int,
    maxEdge: Int = 2_560,
): CompressedImageSize {
    require(width > 0 && height > 0 && maxEdge > 0)
    val longestEdge = max(width, height)
    if (longestEdge <= maxEdge) return CompressedImageSize(width, height)
    val scale = maxEdge.toDouble() / longestEdge.toDouble()
    return CompressedImageSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

internal fun imageSampleSize(
    width: Int,
    height: Int,
    target: CompressedImageSize,
): Int {
    require(width > 0 && height > 0 && target.width > 0 && target.height > 0)
    // BitmapFactory samples most efficiently in powers of two. Allowing the decoded edge to be
    // at most 20% above the output target avoids 40-100 MiB intermediate bitmaps; landing a little
    // below the target is preferable to risking an OOM on common 12-48 MP camera photos.
    val targetEdge = max(target.width, target.height)
    val decodedEdgeLimit = (targetEdge * 1.2).roundToInt().coerceAtLeast(targetEdge)
    var sample = 1
    while (max(width, height) / sample > decodedEdgeLimit && sample <= Int.MAX_VALUE / 2) {
        sample *= 2
    }
    return sample
}
