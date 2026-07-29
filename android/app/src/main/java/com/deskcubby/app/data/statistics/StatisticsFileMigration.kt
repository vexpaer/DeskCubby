package com.deskcubby.app.data.statistics

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Moves the two statistics stores from the old filesDir root into the
 * auto-backup-excluded statistics directory.
 *
 * AtomicFile can leave either a legacy `.bak` file or a modern `.new` file
 * after an interrupted write. We validate every candidate before choosing
 * one, prefer the last known-good AtomicFile backup, and recover a lone valid
 * `.new` file rather than silently starting over. Every displaced candidate
 * is retained under statistics/recovery before its old path is removed.
 */
internal fun prepareStatisticsFile(
    filesDir: File,
    fileName: String,
    validator: (ByteArray) -> Unit,
): File {
    require(fileName == File(fileName).name && fileName.isNotBlank()) {
        "Statistics file name must not contain a path."
    }

    val statisticsDir = File(filesDir, STATISTICS_DIRECTORY_NAME)
    if (!statisticsDir.isDirectory && !statisticsDir.mkdirs() && !statisticsDir.isDirectory) {
        throw IOException("Unable to create the private statistics directory.")
    }

    val target = File(statisticsDir, fileName)
    val targetBackup = File(target.path + ATOMIC_BACKUP_SUFFIX)
    val targetNew = File(target.path + ATOMIC_NEW_SUFFIX)
    val migrationOld = File(statisticsDir, ".$fileName.migration-old")
    val legacy = File(filesDir, fileName)
    val legacyBackup = File(legacy.path + ATOMIC_BACKUP_SUFFIX)
    val legacyNew = File(legacy.path + ATOMIC_NEW_SUFFIX)

    val targetCandidates = listOf(
        StatisticsFileCandidate(targetBackup, "target-bak"),
        StatisticsFileCandidate(target, "target-base"),
        StatisticsFileCandidate(migrationOld, "target-rollback"),
        StatisticsFileCandidate(targetNew, "target-new"),
    )
    val legacyCandidates = listOf(
        StatisticsFileCandidate(legacyBackup, "legacy-bak"),
        StatisticsFileCandidate(legacy, "legacy-base"),
        StatisticsFileCandidate(legacyNew, "legacy-new"),
    )
    val candidates = targetCandidates + legacyCandidates
    val existing = candidates.filter { it.file.isFile }
    if (existing.isEmpty()) return target

    val selected = existing.firstOrNull { candidate ->
        candidate.file.length() <= MAX_STATISTICS_JSON_BYTES &&
            runCatching {
                validator(candidate.file.readBytes())
            }.isSuccess
    } ?: existing.first()

    val targetSidecarsExist = targetCandidates
        .filterNot { it.file == target }
        .any { it.file.exists() }
    if (selected.file != target || targetSidecarsExist) {
        installStatisticsCandidate(
            selected = selected.file,
            target = target,
            rollback = migrationOld,
            validator = validator,
            selectedWasValid = selected.file.length() <= MAX_STATISTICS_JSON_BYTES &&
                runCatching { validator(selected.file.readBytes()) }.isSuccess,
        )
    }

    // The committed target is now authoritative. Retain every old root file
    // and every no-longer-needed sidecar inside the excluded statistics tree.
    val recoveryDir = File(statisticsDir, STATISTICS_RECOVERY_DIRECTORY_NAME)
    (legacyCandidates + targetCandidates.filterNot { it.file == target }).forEach { candidate ->
        preserveAndRemoveCandidate(
            candidate = candidate,
            recoveryDir = recoveryDir,
            fileName = fileName,
        )
    }
    return target
}

private data class StatisticsFileCandidate(
    val file: File,
    val label: String,
)

private data class FileFingerprint(
    val size: Long,
    val sha256: String,
)

private fun installStatisticsCandidate(
    selected: File,
    target: File,
    rollback: File,
    validator: (ByteArray) -> Unit,
    selectedWasValid: Boolean,
) {
    val targetDirectory = requireNotNull(target.parentFile) {
        "Statistics migration target must have a parent directory."
    }
    val temporary = File.createTempFile(".${target.name}-migration-", ".tmp", targetDirectory)
    try {
        val expected = copyFileWithSync(selected, temporary)
        if (rollback.exists()) {
            preserveAndRemoveCandidate(
                candidate = StatisticsFileCandidate(rollback, "target-rollback"),
                recoveryDir = File(targetDirectory, STATISTICS_RECOVERY_DIRECTORY_NAME),
                fileName = target.name,
            )
        }
        if (target.exists() && !target.renameTo(rollback)) {
            throw IOException("Unable to prepare the statistics migration rollback.")
        }
        if (!temporary.renameTo(target)) {
            if (rollback.exists() && !target.exists()) rollback.renameTo(target)
            throw IOException("Unable to commit the statistics file migration.")
        }

        val actual = fingerprint(target)
        if (actual != expected) {
            val failedTarget = uniqueFile(
                directory = targetDirectory,
                preferredName = ".${target.name}.migration-failed",
            )
            target.renameTo(failedTarget)
            if (rollback.exists()) rollback.renameTo(target)
            throw IOException("Statistics file migration verification failed.")
        }
        if (selectedWasValid) {
            try {
                validator(target.readBytes())
            } catch (error: Exception) {
                val failedTarget = uniqueFile(
                    directory = targetDirectory,
                    preferredName = ".${target.name}.migration-failed",
                )
                target.renameTo(failedTarget)
                if (rollback.exists()) rollback.renameTo(target)
                throw IOException("Migrated statistics JSON failed validation.", error)
            }
        }
        preserveAndRemoveCandidate(
            candidate = StatisticsFileCandidate(rollback, "target-rollback"),
            recoveryDir = File(targetDirectory, STATISTICS_RECOVERY_DIRECTORY_NAME),
            fileName = target.name,
        )
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

private fun preserveAndRemoveCandidate(
    candidate: StatisticsFileCandidate,
    recoveryDir: File,
    fileName: String,
) {
    val source = candidate.file
    if (!source.isFile) return
    if (!recoveryDir.isDirectory && !recoveryDir.mkdirs() && !recoveryDir.isDirectory) return

    val sourceFingerprint = runCatching { fingerprint(source) }.getOrNull() ?: return
    val archive = File(
        recoveryDir,
        "$fileName.${candidate.label}.${sourceFingerprint.sha256.take(16)}.preserved",
    )
    if (archive.isFile && runCatching { fingerprint(archive) }.getOrNull() == sourceFingerprint) {
        source.delete()
        return
    }
    if (archive.exists()) return
    if (source.renameTo(archive)) return

    val copied = runCatching { copyFileWithSync(source, archive) }.getOrNull()
    if (copied == sourceFingerprint && runCatching { fingerprint(archive) }.getOrNull() == copied) {
        source.delete()
    } else {
        archive.delete()
    }
}

private fun copyFileWithSync(source: File, target: File): FileFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    FileInputStream(source).use { input ->
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                size += read
            }
            output.fd.sync()
        }
    }
    return FileFingerprint(size, digest.digest().toHex())
}

private fun fingerprint(file: File): FileFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            size += read
        }
    }
    return FileFingerprint(size, digest.digest().toHex())
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(radix = 16).padStart(2, '0')
}

private fun uniqueFile(directory: File, preferredName: String): File {
    val preferred = File(directory, preferredName)
    if (!preferred.exists()) return preferred
    var suffix = 1
    while (true) {
        val candidate = File(directory, "$preferredName.$suffix")
        if (!candidate.exists()) return candidate
        suffix += 1
    }
}

internal const val STATISTICS_DIRECTORY_NAME = "statistics"
private const val STATISTICS_RECOVERY_DIRECTORY_NAME = "recovery"
private const val ATOMIC_BACKUP_SUFFIX = ".bak"
private const val ATOMIC_NEW_SUFFIX = ".new"
