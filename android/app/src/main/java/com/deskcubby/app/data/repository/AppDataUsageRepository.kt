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
        val privateDeadline = System.nanoTime() + PRIVATE_TOTAL_TIME_LIMIT_NANOS
        val treeDeadline = System.nanoTime() + TREE_TOTAL_TIME_LIMIT_NANOS
        val measurePrivate: (File) -> FileMeasurement = { target ->
            measurePrivateFileTree(
                target = target,
                maxEntries = MAX_PRIVATE_ENTRIES,
                deadlineNanos = privateDeadline,
            )
        }
        val parent = context.filesDir.parentFile
        val database = listOf(
            context.getDatabasePath("deskcubby.db"),
            context.getDatabasePath("deskcubby.db-wal"),
            context.getDatabasePath("deskcubby.db-shm"),
        ).map(measurePrivate).combined()
        val reader = measurePrivate(File(context.filesDir, ReaderRepository.DIRECTORY_NAME))
        val engagement = measurePrivate(
            File(context.filesDir, EngagementTimeRepository.DIRECTORY_NAME),
        )
        val statistics = listOf(
            File(context.filesDir, "statistics"),
            File(context.filesDir, "usage-device-histories"),
        ).map(measurePrivate).combined()
        val dataStore = measurePrivate(File(context.filesDir, "datastore"))
        val sharedPreferences = parent
            ?.let { measurePrivate(File(it, "shared_prefs")) }
            ?: FileMeasurement.EMPTY
        val settingsMeasurement = listOf(dataStore, sharedPreferences).combined()
        val allFiles = measurePrivate(context.filesDir)
        val knownFiles = listOf(reader, engagement, statistics, dataStore).combined()
        val otherFiles = FileMeasurement(
            bytes = (allFiles.bytes - knownFiles.bytes).coerceAtLeast(0L),
            partial = allFiles.partial || knownFiles.partial,
        )
        val code = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
        }.map(measurePrivate).combined()
        val cache = listOfNotNull(context.cacheDir, context.externalCacheDir)
            .distinctBy(::stablePath)
            .map(measurePrivate)
            .combined()
        val externalApp = context.getExternalFilesDirs(null)
            .filterNotNull()
            .distinctBy(::stablePath)
            .map(measurePrivate)
            .combined()

        val entries = buildList {
            add(code.toEntry("code"))
            add(database.toEntry("database"))
            add(settingsMeasurement.toEntry("settings"))
            add(reader.toEntry("reader"))
            add(engagement.toEntry("engagement"))
            add(statistics.toEntry("statistics"))
            add(otherFiles.toEntry("other_files"))
            add(cache.toEntry("cache"))
            add(externalApp.toEntry("external_app"))
            settings.diaryTreeUri?.let { raw ->
                val measured = measureTree(raw, treeDeadline)
                add(
                    AppDataUsageEntry(
                        key = "diary_tree",
                        bytes = measured.bytes,
                        userOwned = true,
                        partial = measured.partial,
                    ),
                )
            }
            settings.mediaTreeUri?.let { raw ->
                val measured = measureTree(raw, treeDeadline)
                add(
                    AppDataUsageEntry(
                        key = "media_tree",
                        bytes = measured.bytes,
                        userOwned = true,
                        partial = measured.partial,
                    ),
                )
            }
        }
        AppDataUsageSnapshot(entries, System.currentTimeMillis())
    }

    private data class TreeMeasurement(val bytes: Long, val partial: Boolean)

    private fun FileMeasurement.toEntry(key: String): AppDataUsageEntry =
        AppDataUsageEntry(key = key, bytes = bytes, partial = partial)

    private fun Iterable<FileMeasurement>.combined(): FileMeasurement {
        var bytes = 0L
        var partial = false
        for (measurement in this) {
            bytes = saturatedAddBytes(bytes, measurement.bytes)
            partial = partial || measurement.partial
        }
        return FileMeasurement(bytes, partial)
    }

    private fun stablePath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun measureTree(raw: String, deadline: Long): TreeMeasurement {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(raw)) }.getOrNull()
            ?: return TreeMeasurement(0L, true)
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
            val isDirectory = runCatching { current.isDirectory }.getOrElse {
                partial = true
                false
            }
            val isFile = if (isDirectory) false else runCatching { current.isFile }.getOrElse {
                partial = true
                false
            }
            if (isDirectory) {
                val children = runCatching { current.listFiles() }.getOrElse {
                    partial = true
                    emptyArray()
                }
                val remainingCapacity = (MAX_TREE_ENTRIES - count - queue.size).coerceAtLeast(0)
                if (children.size > remainingCapacity) partial = true
                children.take(remainingCapacity).forEach(queue::addLast)
            } else if (isFile) {
                val length = runCatching { current.length() }.getOrElse {
                    partial = true
                    0L
                }
                bytes = saturatedAddBytes(bytes, length.coerceAtLeast(0L))
            } else {
                partial = true
            }
        }
        return TreeMeasurement(bytes, partial)
    }

    companion object {
        private const val MAX_PRIVATE_ENTRIES = 200_000
        private const val MAX_TREE_ENTRIES = 50_000
        private const val PRIVATE_TOTAL_TIME_LIMIT_NANOS = 4_000_000_000L
        private const val TREE_TOTAL_TIME_LIMIT_NANOS = 8_000_000_000L
    }
}

internal data class FileMeasurement(
    val bytes: Long,
    val partial: Boolean,
) {
    companion object {
        val EMPTY = FileMeasurement(bytes = 0L, partial = false)
    }
}

/** Bounded, symlink-safe private-file traversal kept separate for deterministic JVM tests. */
internal fun measurePrivateFileTree(
    target: File,
    maxEntries: Int,
    deadlineNanos: Long = Long.MAX_VALUE,
    nanoTime: () -> Long = System::nanoTime,
): FileMeasurement {
    require(maxEntries > 0)
    val exists = runCatching { target.exists() }.getOrElse {
        return FileMeasurement(bytes = 0L, partial = true)
    }
    if (!exists) return FileMeasurement.EMPTY
    if (runCatching { Files.isSymbolicLink(target.toPath()) }.getOrElse { true }) {
        return FileMeasurement(bytes = 0L, partial = true)
    }
    val targetIsFile = runCatching { target.isFile }.getOrElse {
        return FileMeasurement(bytes = 0L, partial = true)
    }
    if (targetIsFile) {
        return runCatching { FileMeasurement(target.length().coerceAtLeast(0L), partial = false) }
            .getOrElse { FileMeasurement(bytes = 0L, partial = true) }
    }

    var total = 0L
    var partial = false
    val queue = ArrayDeque<File>()
    queue.add(target)
    var count = 0
    while (queue.isNotEmpty()) {
        if (count >= maxEntries || nanoTime() >= deadlineNanos) {
            partial = true
            break
        }
        val current = queue.removeFirst()
        count++
        if (runCatching { Files.isSymbolicLink(current.toPath()) }.getOrElse { true }) {
            partial = true
            continue
        }
        val isFile = runCatching { current.isFile }.getOrElse {
            partial = true
            false
        }
        if (isFile) {
            val length = runCatching { current.length() }.getOrElse {
                partial = true
                0L
            }
            total = saturatedAddBytes(total, length.coerceAtLeast(0L))
        } else {
            val children = runCatching { current.listFiles() }.getOrNull()
            if (children == null) {
                partial = true
                continue
            }
            val remainingCapacity = (maxEntries - count - queue.size).coerceAtLeast(0)
            if (children.size > remainingCapacity) partial = true
            children.take(remainingCapacity).forEach(queue::addLast)
        }
    }
    return FileMeasurement(total, partial)
}

internal fun saturatedAddBytes(first: Long, second: Long): Long =
    if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
