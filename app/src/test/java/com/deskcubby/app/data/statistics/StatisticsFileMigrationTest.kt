package com.deskcubby.app.data.statistics

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatisticsFileMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy root file moves into excluded statistics directory`() {
        val filesDir = temporaryFolder.newFolder("files")
        val json = "valid:root-history"
        File(filesDir, USAGE_STATISTICS_FILE_NAME).writeText(json)

        val target = prepareTestFile(filesDir)

        assertEquals(File(filesDir, "statistics/$USAGE_STATISTICS_FILE_NAME"), target)
        assertEquals(json, target.readText())
        assertFalse(File(filesDir, USAGE_STATISTICS_FILE_NAME).exists())
        assertRecoveryContains(filesDir, json)
    }

    @Test
    fun `valid AtomicFile backup wins over base and both versions are retained`() {
        val filesDir = temporaryFolder.newFolder("backup-files")
        val baseJson = "valid:new-uncommitted-history"
        val backupJson = "valid:last-known-good-history"
        File(filesDir, USAGE_STATISTICS_FILE_NAME).writeText(baseJson)
        File(filesDir, "$USAGE_STATISTICS_FILE_NAME.bak").writeText(backupJson)

        val target = prepareTestFile(filesDir)

        assertEquals(backupJson, target.readText())
        assertRecoveryContains(filesDir, baseJson)
        assertRecoveryContains(filesDir, backupJson)
    }

    @Test
    fun `lone valid AtomicFile new sidecar is recovered`() {
        val filesDir = temporaryFolder.newFolder("new-files")
        val json = "valid:completed-new-sidecar"
        File(filesDir, "$USAGE_STATISTICS_FILE_NAME.new").writeText(json)

        val target = prepareTestFile(filesDir)

        assertEquals(json, target.readText())
        assertFalse(File(filesDir, "$USAGE_STATISTICS_FILE_NAME.new").exists())
        assertRecoveryContains(filesDir, json)
    }

    @Test
    fun `existing valid private target stays authoritative without dropping legacy data`() {
        val filesDir = temporaryFolder.newFolder("target-files")
        val statisticsDir = File(filesDir, STATISTICS_DIRECTORY_NAME).apply {
            assertTrue(mkdirs())
        }
        val targetJson = "valid:current-private-history"
        val legacyJson = "valid:older-root-history"
        File(statisticsDir, USAGE_STATISTICS_FILE_NAME).writeText(targetJson)
        File(filesDir, USAGE_STATISTICS_FILE_NAME).writeText(legacyJson)

        val target = prepareTestFile(filesDir)

        assertEquals(targetJson, target.readText())
        assertFalse(File(filesDir, USAGE_STATISTICS_FILE_NAME).exists())
        assertRecoveryContains(filesDir, legacyJson)
    }

    @Test
    fun `malformed legacy bytes are copied so a later update cannot erase them`() {
        val filesDir = temporaryFolder.newFolder("malformed-files")
        val malformed = "{not valid statistics json"
        File(filesDir, STEP_STATISTICS_FILE_NAME).writeText(malformed)

        val target = prepareStatisticsFile(
            filesDir = filesDir,
            fileName = STEP_STATISTICS_FILE_NAME,
            validator = ::validateTestStatistics,
        )

        assertEquals(malformed, target.readText())
        assertTrue(
            runCatching {
                validateTestStatistics(target.readBytes())
            }.isFailure,
        )
        assertRecoveryContains(filesDir, malformed)
    }

    private fun prepareTestFile(filesDir: File): File =
        prepareStatisticsFile(
            filesDir = filesDir,
            fileName = USAGE_STATISTICS_FILE_NAME,
            validator = ::validateTestStatistics,
        )

    private fun assertRecoveryContains(filesDir: File, expected: String) {
        val recovery = File(filesDir, "$STATISTICS_DIRECTORY_NAME/recovery")
        assertTrue(
            recovery.listFiles().orEmpty().any { file ->
                file.isFile && file.readText() == expected
            },
        )
    }

    private fun validateTestStatistics(bytes: ByteArray) {
        require(bytes.toString(StandardCharsets.UTF_8).startsWith("valid:"))
    }
}
