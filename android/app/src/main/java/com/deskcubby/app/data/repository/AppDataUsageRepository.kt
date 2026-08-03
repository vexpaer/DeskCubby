package com.deskcubby.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.statistics.EngagementTimeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppDataUsageEntry(
    val key: String,
    val bytes: Long,
    val userOwned: Boolean = false,
    val partial: Boolean = false,
)

data class AppDataUsageSnapshot(
    val entries: List<AppDataUsageEntry>,
    val calculatedAt: Long,
)

@Singleton
class AppDataUsageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun calculate(settings: AppSettings): AppDataUsageSnapshot = withContext(Dispatchers.IO) {
        val parent = context.filesDir.parentFile
        val databaseBytes = listOf(
            context.getDatabasePath("deskcubby.db"),
            context.getDatabasePath("deskcubby.db-wal"),
            context.getDatabasePath("deskcubby.db-shm"),
        ).sumOf(::safeSize)
        val readerBytes = safeSize(File(context.filesDir, ReaderRepository.DIRECTORY_NAME))
        val engagementBytes = safeSize(File(context.filesDir, EngagementTimeRepository.DIRECTORY_NAME))
        val statisticsBytes = listOf(
            File(context.filesDir, "statistics"),
            File(context.filesDir, "usage-device-histories"),
        ).sumOf(::safeSize)
        val dataStoreBytes = safeSize(File(context.filesDir, "datastore"))
        val sharedPreferencesBytes = parent?.let { safeSize(File(it, "shared_prefs")) } ?: 0L
        val allFilesBytes = safeSize(context.filesDir)
        val knownFilesBytes = readerBytes + engagementBytes + statisticsBytes + dataStoreBytes
        val otherFilesBytes = (allFilesBytes - knownFilesBytes).coerceAtLeast(0L)
        val codeBytes = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
        }.sumOf(::safeSize)
        val cacheBytes = safeSize(context.cacheDir) + context.externalCacheDir?.let(::safeSize).orZero()
        val externalAppBytes = context.getExternalFilesDirs(null)
            .filterNotNull()
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .sumOf(::safeSize)

        val entries = buildList {
            add(AppDataUsageEntry("code", codeBytes))
            add(AppDataUsageEntry("database", databaseBytes))
            add(AppDataUsageEntry("settings", dataStoreBytes + sharedPreferencesBytes))
            add(AppDataUsageEntry("reader", readerBytes))
            add(AppDataUsageEntry("engagement", engagementBytes))
            add(AppDataUsageEntry("statistics", statisticsBytes))
            add(AppDataUsageEntry("other_files", otherFilesBytes))
            add(AppDataUsageEntry("cache", cacheBytes))
            add(AppDataUsageEntry("external_app", externalAppBytes))
            settings.diaryTreeUri?.let { raw ->
                val measured = measureTree(raw)
                add(AppDataUsageEntry("diary_tree", measured.bytes, userOwned = true, partial = measured.partial))
            }
            settings.mediaTreeUri?.let { raw ->
                val measured = measureTree(raw)
                add(AppDataUsageEntry("media_tree", measured.bytes, userOwned = true, partial = measured.partial))
            }
        }
        AppDataUsageSnapshot(entries, System.currentTimeMillis())
    }

    private data class TreeMeasurement(val bytes: Long, val partial: Boolean)

    private fun measureTree(raw: String): TreeMeasurement {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(raw)) }.getOrNull()
            ?: return TreeMeasurement(0L, true)
        val deadline = System.nanoTime() + TREE_TIME_LIMIT_NANOS
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)
        var bytes = 0L
        var count = 0
        var partial = false
        while (queue.isNotEmpty()) {
            if (count >= MAX_TREE_ENTRIES || System.nanoTime() >= deadline) {
                partial = true
                break
            }
            val current = queue.removeFirst()
            count++
            if (current.isDirectory) {
                val children = runCatching { current.listFiles() }.getOrElse {
                    partial = true
                    emptyArray()
                }
                children.forEach(queue::addLast)
            } else if (current.isFile) {
                bytes = saturatedAdd(bytes, current.length().coerceAtLeast(0L))
            }
        }
        return TreeMeasurement(bytes, partial)
    }

    private fun safeSize(target: File): Long {
        if (!target.exists()) return 0L
        if (runCatching { Files.isSymbolicLink(target.toPath()) }.getOrDefault(false)) return 0L
        if (target.isFile) return target.length().coerceAtLeast(0L)
        var total = 0L
        val queue = ArrayDeque<File>()
        queue.add(target)
        var count = 0
        while (queue.isNotEmpty() && count < MAX_PRIVATE_ENTRIES) {
            val current = queue.removeFirst()
            count++
            if (runCatching { Files.isSymbolicLink(current.toPath()) }.getOrDefault(false)) continue
            if (current.isFile) {
                total = saturatedAdd(total, current.length().coerceAtLeast(0L))
            } else {
                current.listFiles()?.forEach(queue::addLast)
            }
        }
        return total
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    private fun Long?.orZero(): Long = this ?: 0L

    companion object {
        private const val MAX_PRIVATE_ENTRIES = 200_000
        private const val MAX_TREE_ENTRIES = 50_000
        private const val TREE_TIME_LIMIT_NANOS = 8_000_000_000L
    }
}
