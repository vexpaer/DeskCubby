package com.deskcubby.app.data.repository

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `selects exact versioned DeskCubby APK`() {
        val selected = selectTrustedApkAsset(
            assets = listOf(
                asset("notes.txt", 20),
                asset("DeskCubby-0.3.2.apk", 18_000_000),
                asset("DeskCubby-0.3.2.sha256", 64),
            ),
            version = "0.3.2",
        )

        assertEquals("DeskCubby-0.3.2.apk", selected?.fileName)
        assertEquals(18_000_000L, selected?.sizeBytes)
    }

    @Test
    fun `prefers versioned APK over generic APK`() {
        val selected = selectTrustedApkAsset(
            assets = listOf(
                asset("DeskCubby.apk", 17_000_000),
                asset("DeskCubby-0.3.2.apk", 18_000_000),
            ),
            version = "v0.3.2",
        )

        assertEquals("DeskCubby-0.3.2.apk", selected?.fileName)
    }

    @Test
    fun `rejects duplicate same-priority APK assets`() {
        val duplicate = asset("DeskCubby-0.3.2.apk", 18_000_000)

        assertNull(
            selectTrustedApkAsset(
                assets = listOf(duplicate, duplicate),
                version = "0.3.2",
            ),
        )
    }

    @Test
    fun `rejects APK from another host or above size limit`() {
        val wrongHost = ReleaseAssetMetadata(
            name = "DeskCubby-0.3.2.apk",
            downloadUrl = "https://example.com/DeskCubby-0.3.2.apk",
            sizeBytes = 18_000_000,
        )
        val tooLarge = asset("DeskCubby-0.3.2.apk", 256L * 1024L * 1024L + 1L)

        assertNull(selectTrustedApkAsset(listOf(wrongHost), "0.3.2"))
        assertNull(selectTrustedApkAsset(listOf(tooLarge), "0.3.2"))
    }

    @Test
    fun `release page and initial APK URLs require exact repository`() {
        assertTrue(
            isTrustedReleasePageUrl(
                "https://github.com/vexpaer/DeskCubby/releases/tag/v0.3.2",
            ),
        )
        assertFalse(
            isTrustedReleasePageUrl(
                "https://github.com/another/DeskCubby/releases/tag/v0.3.2",
            ),
        )
        assertTrue(
            isTrustedApkInitialUrl(
                "https://github.com/vexpaer/DeskCubby/releases/download/v0.3.2/DeskCubby-0.3.2.apk",
            ),
        )
        assertFalse(
            isTrustedApkInitialUrl(
                "http://github.com/vexpaer/DeskCubby/releases/download/v0.3.2/DeskCubby-0.3.2.apk",
            ),
        )
    }

    @Test
    fun `update API redirects stay on API host`() {
        val source = URL("https://api.github.com/repos/vexpaer/DeskCubby/releases/latest")

        assertTrue(
            isAllowedCheckRedirect(
                source,
                URL("https://api.github.com/repositories/123/releases/latest"),
            ),
        )
        assertFalse(
            isAllowedCheckRedirect(
                source,
                URL("https://github.com/vexpaer/DeskCubby/releases/latest"),
            ),
        )
    }

    @Test
    fun `APK redirect only enters and stays within known GitHub asset hosts`() {
        val release = URL(
            "https://github.com/vexpaer/DeskCubby/releases/download/v0.3.2/DeskCubby-0.3.2.apk",
        )
        val asset = URL("https://release-assets.githubusercontent.com/github-production-release-asset")

        assertTrue(isAllowedApkRedirect(release, asset))
        assertTrue(
            isAllowedApkRedirect(
                asset,
                URL("https://objects.githubusercontent.com/github-production-release-asset"),
            ),
        )
        assertFalse(isAllowedApkRedirect(release, URL("https://example.com/update.apk")))
        assertFalse(isAllowedApkRedirect(asset, URL("http://release-assets.githubusercontent.com/a")))
    }

    @Test
    fun `numeric version comparison remains monotonic`() {
        assertTrue(compareVersions("0.3.10", "0.3.2") > 0)
        assertEquals(0, compareVersions("v1.0", "1.0.0"))
        assertTrue(compareVersions("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun `cache cleanup removes old APKs and partials but keeps target for validation`() {
        val directory = temporaryFolder.newFolder("updates")
        val keep = File(directory, "DeskCubby-0.3.2.apk").apply { writeText("verified") }
        val old = File(directory, "DeskCubby-0.3.1.apk").apply { writeText("old") }
        val partial = File(directory, "DeskCubby-0.3.2.apk.part").apply { writeText("partial") }
        val unrelated = File(directory, "readme.txt").apply { writeText("keep") }

        assertTrue(cleanupUpdateCache(directory, keep))
        assertTrue(keep.isFile)
        assertFalse(old.exists())
        assertFalse(partial.exists())
        assertTrue(unrelated.isFile)
    }

    @Test
    fun `deadline timeout is capped and reaches zero monotonically`() {
        assertEquals(
            30_000,
            boundedDeadlineTimeoutMillis(
                nowMillis = 1_000L,
                deadlineMillis = 901_000L,
                maximumMillis = 30_000,
            ),
        )
        assertEquals(
            750,
            boundedDeadlineTimeoutMillis(
                nowMillis = 10_000L,
                deadlineMillis = 10_750L,
                maximumMillis = 30_000,
            ),
        )
        assertEquals(
            0,
            boundedDeadlineTimeoutMillis(
                nowMillis = 10_750L,
                deadlineMillis = 10_750L,
                maximumMillis = 30_000,
            ),
        )
        assertEquals(
            0,
            boundedDeadlineTimeoutMillis(
                nowMillis = 11_000L,
                deadlineMillis = 10_750L,
                maximumMillis = 30_000,
            ),
        )
    }

    @Test
    fun `multi signer updates require the same complete signer set`() {
        val signerA = byteArrayOf(1)
        val signerB = byteArrayOf(2)

        assertTrue(
            areUpdateSignerSetsCompatible(
                installedCurrent = listOf(signerA, signerB),
                installedHasMultipleSigners = true,
                archiveCurrent = listOf(signerB, signerA),
                archiveHasMultipleSigners = true,
                archiveHistory = emptyList(),
            ),
        )
        assertFalse(
            areUpdateSignerSetsCompatible(
                installedCurrent = listOf(signerA, signerB),
                installedHasMultipleSigners = true,
                archiveCurrent = listOf(signerA),
                archiveHasMultipleSigners = false,
                archiveHistory = listOf(signerA, signerB),
            ),
        )
        assertFalse(
            areUpdateSignerSetsCompatible(
                installedCurrent = listOf(signerA, signerB),
                installedHasMultipleSigners = true,
                archiveCurrent = listOf(signerA, byteArrayOf(3)),
                archiveHasMultipleSigners = true,
                archiveHistory = emptyList(),
            ),
        )
    }

    @Test
    fun `single signer rotation accepts installed signer only from archive history`() {
        val oldSigner = byteArrayOf(1)
        val newSigner = byteArrayOf(2)

        assertTrue(
            areUpdateSignerSetsCompatible(
                installedCurrent = listOf(oldSigner),
                installedHasMultipleSigners = false,
                archiveCurrent = listOf(newSigner),
                archiveHasMultipleSigners = false,
                archiveHistory = listOf(oldSigner, newSigner),
            ),
        )
        assertFalse(
            areUpdateSignerSetsCompatible(
                installedCurrent = listOf(oldSigner),
                installedHasMultipleSigners = false,
                archiveCurrent = listOf(newSigner),
                archiveHasMultipleSigners = false,
                archiveHistory = listOf(newSigner),
            ),
        )
    }

    @Test
    fun `download source failures preserve timeout TLS and network categories`() {
        assertEquals(
            UpdateDownloadFailure.TIMEOUT,
            classifyUpdateDownloadIOException(SocketTimeoutException()),
        )
        assertEquals(
            UpdateDownloadFailure.TLS_ERROR,
            classifyUpdateDownloadIOException(SSLException("certificate")),
        )
        assertEquals(
            UpdateDownloadFailure.NETWORK_ERROR,
            classifyUpdateDownloadIOException(IOException("connection reset")),
        )
    }

    private fun asset(name: String, size: Long): ReleaseAssetMetadata =
        ReleaseAssetMetadata(
            name = name,
            downloadUrl =
                "https://github.com/vexpaer/DeskCubby/releases/download/v0.3.2/$name",
            sizeBytes = size,
        )
}
