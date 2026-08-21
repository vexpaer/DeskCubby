package com.deskcubby.app.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryOriginalStaging(
    val path: String,
    val mimeType: String,
    val displayName: String,
)

object GalleryOriginalStager {
    private const val DIRECTORY = "media-staging"
    private const val MAX_ORPHAN_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    suspend fun stage(
        context: Context,
        sourceUri: Uri,
        displayNameHint: String,
    ): GalleryOriginalStaging = withContext(Dispatchers.IO) {
        cleanupOrphans(context)
        val resolver = context.contentResolver
        val mime = resolver.getType(sourceUri)?.takeIf(String::isNotBlank) ?: "image/jpeg"
        val extension = mime.substringAfterLast('/', "jpg")
            .lowercase()
            .filter(Char::isLetterOrDigit)
            .take(8)
            .ifBlank { "jpg" }
        val displayBase = displayNameHint.substringBeforeLast('.').trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim('.', ' ')
            .take(100)
            .ifBlank { "image" }
        val displayName = "$displayBase.$extension"
        val directory = File(context.filesDir, DIRECTORY).apply {
            check(exists() || mkdirs()) { "无法创建原图暂存目录" }
        }
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取相机原图" }
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(target.isFile && target.length() > 0L) { "原图暂存失败" }
            GalleryOriginalStaging(target.absolutePath, mime, displayName)
        } catch (error: Exception) {
            runCatching { target.delete() }
            throw error
        }
    }

    /**
     * Enqueues the gallery copy and suspends until WorkManager reports the enqueue Operation as
     * complete. Callers can therefore release transient camera content or finish an Activity only
     * after WorkManager has durably accepted a request that references app-owned staging bytes.
     */
    suspend fun enqueue(context: Context, staging: GalleryOriginalStaging): Unit = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<GalleryOriginalCopyWorker>()
            .setInputData(
                Data.Builder()
                    .putString(GalleryOriginalCopyWorker.KEY_PATH, staging.path)
                    .putString(GalleryOriginalCopyWorker.KEY_MIME, staging.mimeType)
                    .putString(GalleryOriginalCopyWorker.KEY_DISPLAY_NAME, staging.displayName)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request).await()
        Unit
    }

    fun discard(staging: GalleryOriginalStaging?) {
        staging ?: return
        runCatching { File(staging.path).delete() }
    }

    fun cleanupOrphans(context: Context, now: Long = System.currentTimeMillis()) {
        val directory = File(context.filesDir, DIRECTORY)
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() > 0L && now - file.lastModified() >= MAX_ORPHAN_AGE_MS) {
                runCatching { file.delete() }
            }
        }
    }
}

class GalleryOriginalCopyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_PATH) ?: return@withContext Result.failure()
        val mime = inputData.getString(KEY_MIME)?.takeIf(String::isNotBlank) ?: "image/jpeg"
        val displayName = inputData.getString(KEY_DISPLAY_NAME)?.takeIf(String::isNotBlank)
            ?: File(path).name
        val source = File(path)
        if (!source.isFile || source.length() <= 0L) return@withContext Result.failure()

        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DeskCubby")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val itemUri = try {
            resolver.insert(collection, values)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext Result.retry()

        try {
            FileInputStream(source).use { input ->
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
            source.delete()
            Result.success()
        } catch (cancelled: CancellationException) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw cancelled
        } catch (_: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            Result.retry()
        }
    }

    companion object {
        const val KEY_PATH = "staging_path"
        const val KEY_MIME = "mime_type"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}