package com.deskcubby.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.deskcubby.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class UpdatePackage(
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult

    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseName: String,
        val notes: String,
        val htmlUrl: String,
        val publishedAt: String,
        val updatePackage: UpdatePackage?,
    ) : UpdateCheckResult

    data class Failed(
        val message: String,
        val messageEnglish: String = message,
    ) : UpdateCheckResult
}

enum class UpdateDownloadFailure {
    NO_TRUSTED_APK,
    INVALID_DOWNLOAD_URL,
    HTTP_ERROR,
    DOWNLOAD_TOO_LARGE,
    SIZE_MISMATCH,
    INVALID_APK,
    WRONG_APPLICATION,
    VERSION_MISMATCH,
    NOT_NEWER,
    SIGNATURE_MISMATCH,
    TIMEOUT,
    TLS_ERROR,
    NETWORK_ERROR,
    STORAGE_ERROR,
    INSTALL_PERMISSION_SETTINGS_UNAVAILABLE,
    INSTALLER_UNAVAILABLE,
}

sealed interface UpdateDownloadResult {
    data class Downloaded(val update: DownloadedUpdate) : UpdateDownloadResult
    data class Failed(val reason: UpdateDownloadFailure) : UpdateDownloadResult
}

class DownloadedUpdate internal constructor(
    internal val file: File,
    val fileName: String,
    val version: String,
    val sizeBytes: Long,
)

sealed interface UpdateInstallRequest {
    data class LaunchInstaller(val intent: Intent) : UpdateInstallRequest
    data class RequestPermission(val intent: Intent) : UpdateInstallRequest
    data class Failed(val reason: UpdateDownloadFailure) : UpdateInstallRequest
}

internal data class ReleaseAssetMetadata(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

private class UpdateCheckException(
    message: String,
    val failure: UpdateDownloadFailure? = null,
) : IOException(message)

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val downloadMutex = Mutex()

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        try {
            val root = JSONObject(fetchLatestReleaseBody())
            val tagName = root.stringOrEmpty("tag_name")
            if (tagName.isBlank()) {
                return@withContext UpdateCheckResult.Failed(
                    "更新信息缺少版本号。",
                    "The release information has no version number.",
                )
            }
            val htmlUrl = root.stringOrEmpty("html_url")
            if (!isTrustedReleasePageUrl(htmlUrl)) {
                return@withContext UpdateCheckResult.Failed(
                    "更新信息中的发布链接不可信，已忽略。",
                    "The release link is not trusted and was ignored.",
                )
            }
            val latestVersion = stripVersionPrefix(tagName)
            if (compareVersions(latestVersion, currentVersion) > 0) {
                val assets = root.optJSONArray("assets").toAssetMetadata()
                UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseName = root.stringOrEmpty("name").ifBlank { tagName },
                    notes = root.stringOrEmpty("body"),
                    htmlUrl = htmlUrl,
                    publishedAt = root.stringOrEmpty("published_at"),
                    updatePackage = selectTrustedApkAsset(assets, latestVersion),
                )
            } else {
                UpdateCheckResult.UpToDate(currentVersion)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            val message = readableCheckMessage(error)
            UpdateCheckResult.Failed(message.first, message.second)
        } catch (error: JSONException) {
            UpdateCheckResult.Failed(
                "无法解析更新信息。",
                "The release information could not be parsed.",
            )
        }
    }

    suspend fun downloadUpdate(
        available: UpdateCheckResult.UpdateAvailable,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): UpdateDownloadResult = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val updatePackage = available.updatePackage
                ?: return@withContext UpdateDownloadResult.Failed(
                    UpdateDownloadFailure.NO_TRUSTED_APK,
                )
            if (!isTrustedApkInitialUrl(updatePackage.downloadUrl)) {
                return@withContext UpdateDownloadResult.Failed(
                    UpdateDownloadFailure.INVALID_DOWNLOAD_URL,
                )
            }
            if (updatePackage.sizeBytes !in 1L..MAX_APK_BYTES) {
                return@withContext UpdateDownloadResult.Failed(
                    UpdateDownloadFailure.DOWNLOAD_TOO_LARGE,
                )
            }

            val (target, partial) = try {
                val updatesDirectory = File(context.cacheDir, UPDATE_CACHE_DIRECTORY)
                if ((!updatesDirectory.exists() && !updatesDirectory.mkdirs()) ||
                    !updatesDirectory.isDirectory
                ) {
                    return@withContext UpdateDownloadResult.Failed(
                        UpdateDownloadFailure.STORAGE_ERROR,
                    )
                }
                val safeVersion = available.latestVersion.replace(Regex("[^0-9A-Za-z._-]"), "_")
                    .take(MAX_VERSION_FILE_CHARS)
                    .ifBlank { "update" }
                val destination = File(updatesDirectory, "DeskCubby-$safeVersion.apk")
                val inProgress = File(updatesDirectory, "${destination.name}.part")
                if (!cleanupUpdateCache(updatesDirectory, destination)) {
                    return@withContext UpdateDownloadResult.Failed(
                        UpdateDownloadFailure.STORAGE_ERROR,
                    )
                }
                if (destination.exists()) {
                    val cachedIsValid = destination.isFile &&
                        destination.length() == updatePackage.sizeBytes &&
                        validateApk(destination, available.latestVersion) == null
                    if (cachedIsValid) {
                        onProgress(updatePackage.sizeBytes, updatePackage.sizeBytes)
                        return@withContext UpdateDownloadResult.Downloaded(
                            DownloadedUpdate(
                                file = destination,
                                fileName = updatePackage.fileName,
                                version = available.latestVersion,
                                sizeBytes = updatePackage.sizeBytes,
                            ),
                        )
                    }
                    if (!destination.delete() && destination.exists()) {
                        return@withContext UpdateDownloadResult.Failed(
                            UpdateDownloadFailure.STORAGE_ERROR,
                        )
                    }
                }
                destination to inProgress
            } catch (_: IOException) {
                return@withContext UpdateDownloadResult.Failed(UpdateDownloadFailure.STORAGE_ERROR)
            } catch (_: SecurityException) {
                return@withContext UpdateDownloadResult.Failed(UpdateDownloadFailure.STORAGE_ERROR)
            }

            val deadlineMillis = SystemClock.elapsedRealtime() + DOWNLOAD_TOTAL_TIMEOUT_MILLIS
            try {
                val bytes = downloadApk(
                    initialUrl = updatePackage.downloadUrl,
                    destination = partial,
                    expectedSize = updatePackage.sizeBytes,
                    deadlineMillis = deadlineMillis,
                    onProgress = onProgress,
                )
                currentCoroutineContext().ensureActive()
                throwIfDownloadDeadlineExceeded(deadlineMillis)
                if (target.exists() && !target.delete()) {
                    throw UpdateCheckException(
                        "Unable to replace cached update",
                        UpdateDownloadFailure.STORAGE_ERROR,
                    )
                }
                if (!partial.renameTo(target)) {
                    throw UpdateCheckException(
                        "Unable to commit cached update",
                        UpdateDownloadFailure.STORAGE_ERROR,
                    )
                }
                when (val validation = validateApk(target, available.latestVersion)) {
                    null -> UpdateDownloadResult.Downloaded(
                        DownloadedUpdate(
                            file = target,
                            fileName = updatePackage.fileName,
                            version = available.latestVersion,
                            sizeBytes = bytes,
                        ),
                    )
                    else -> {
                        target.delete()
                        UpdateDownloadResult.Failed(validation)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: UpdateCheckException) {
                UpdateDownloadResult.Failed(
                    error.failure ?: UpdateDownloadFailure.NETWORK_ERROR,
                )
            } catch (error: IOException) {
                UpdateDownloadResult.Failed(classifyUpdateDownloadIOException(error))
            } catch (_: SecurityException) {
                UpdateDownloadResult.Failed(UpdateDownloadFailure.STORAGE_ERROR)
            } finally {
                if (partial.exists()) {
                    runCatching { partial.delete() }
                }
            }
        }
    }

    suspend fun prepareInstall(update: DownloadedUpdate): UpdateInstallRequest =
        withContext(Dispatchers.IO) {
            if (!update.file.isFile || update.file.length() != update.sizeBytes) {
                return@withContext UpdateInstallRequest.Failed(
                    UpdateDownloadFailure.INVALID_APK,
                )
            }
            validateApk(update.file, update.version)?.let { failure ->
                update.file.delete()
                return@withContext UpdateInstallRequest.Failed(failure)
            }
            val canInstallPackages = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                true
            } else {
                try {
                    context.packageManager.canRequestPackageInstalls()
                } catch (_: RuntimeException) {
                    return@withContext UpdateInstallRequest.Failed(
                        UpdateDownloadFailure.INSTALLER_UNAVAILABLE,
                    )
                }
            }
            if (!canInstallPackages) {
                val permissionIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
                return@withContext UpdateInstallRequest.RequestPermission(permissionIntent)
            }
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    update.file,
                )
                val installerIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                UpdateInstallRequest.LaunchInstaller(installerIntent)
            } catch (_: RuntimeException) {
                UpdateInstallRequest.Failed(UpdateDownloadFailure.INSTALLER_UNAVAILABLE)
            }
        }

    private suspend fun fetchLatestReleaseBody(): String {
        var currentUrl = URL(LATEST_RELEASE_URL)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            requireHttps(currentUrl, "Update checks")
            val connection = openGetConnection(
                url = currentUrl,
                accept = "application/vnd.github+json",
                readTimeout = CHECK_TIMEOUT_MILLIS,
            )
            try {
                val status = connection.responseCode
                when {
                    status in REDIRECT_CODES -> {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw UpdateCheckException("Too many update-check redirects")
                        }
                        val resolved = resolveRedirect(connection, currentUrl)
                        if (!isAllowedCheckRedirect(currentUrl, resolved)) {
                            throw UpdateCheckException("Untrusted update-check redirect")
                        }
                        currentUrl = resolved
                        return@repeat
                    }
                    status == HttpURLConnection.HTTP_NOT_FOUND -> {
                        throw UpdateCheckException("No releases")
                    }
                    status != HttpURLConnection.HTTP_OK -> {
                        throw UpdateCheckException("Update check failed (HTTP $status)")
                    }
                    else -> {
                        return connection.inputStream.use {
                            readLimited(it, MAX_RESPONSE_BYTES)
                        }.toString(Charsets.UTF_8)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateCheckException("Too many update-check redirects")
    }

    private suspend fun downloadApk(
        initialUrl: String,
        destination: File,
        expectedSize: Long,
        deadlineMillis: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var currentUrl = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            throwIfDownloadDeadlineExceeded(deadlineMillis)
            requireHttps(
                currentUrl,
                "Update downloads",
                UpdateDownloadFailure.INVALID_DOWNLOAD_URL,
            )
            val connection = openGetConnection(
                url = currentUrl,
                accept = APK_MIME_TYPE,
                connectTimeout = remainingDownloadTimeoutMillis(
                    deadlineMillis,
                    CONNECT_TIMEOUT_MILLIS,
                ),
                readTimeout = remainingDownloadTimeoutMillis(
                    deadlineMillis,
                    DOWNLOAD_READ_TIMEOUT_MILLIS,
                ),
            )
            try {
                val status = connection.cancellableIo { connection.responseCode }
                throwIfDownloadDeadlineExceeded(deadlineMillis)
                when {
                    status in REDIRECT_CODES -> {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw UpdateCheckException(
                                "Too many APK redirects",
                                UpdateDownloadFailure.INVALID_DOWNLOAD_URL,
                            )
                        }
                        val resolved = resolveRedirect(connection, currentUrl)
                        if (!isAllowedApkRedirect(currentUrl, resolved)) {
                            throw UpdateCheckException(
                                "Untrusted APK redirect",
                                UpdateDownloadFailure.INVALID_DOWNLOAD_URL,
                            )
                        }
                        currentUrl = resolved
                        return@repeat
                    }
                    status != HttpURLConnection.HTTP_OK -> {
                        throw UpdateCheckException(
                            "APK download failed (HTTP $status)",
                            UpdateDownloadFailure.HTTP_ERROR,
                        )
                    }
                    else -> {
                        val contentLength = connection.contentLengthLong
                        if (contentLength > MAX_APK_BYTES ||
                            (contentLength >= 0L && contentLength != expectedSize)
                        ) {
                            throw UpdateCheckException(
                                "Unexpected APK size",
                                if (contentLength > MAX_APK_BYTES) {
                                    UpdateDownloadFailure.DOWNLOAD_TOO_LARGE
                                } else {
                                    UpdateDownloadFailure.SIZE_MISMATCH
                                },
                            )
                        }
                        return writeDownload(
                            connection = connection,
                            input = connection.cancellableIo { connection.inputStream },
                            destination = destination,
                            expectedSize = expectedSize,
                            deadlineMillis = deadlineMillis,
                            onProgress = onProgress,
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateCheckException(
            "Too many APK redirects",
            UpdateDownloadFailure.INVALID_DOWNLOAD_URL,
        )
    }

    private suspend fun writeDownload(
        connection: HttpURLConnection,
        input: InputStream,
        destination: File,
        expectedSize: Long,
        deadlineMillis: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var total = 0L
        withStorageOutput(destination) { output ->
            input.use { source ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    connection.readTimeout = remainingDownloadTimeoutMillis(
                        deadlineMillis,
                        DOWNLOAD_READ_TIMEOUT_MILLIS,
                    )
                    val count = connection.cancellableIo { source.read(buffer) }
                    throwIfDownloadDeadlineExceeded(deadlineMillis)
                    if (count < 0) break
                    total += count
                    if (total > MAX_APK_BYTES || total > expectedSize) {
                        throw UpdateCheckException(
                            "APK exceeded the declared size",
                            if (total > MAX_APK_BYTES) {
                                UpdateDownloadFailure.DOWNLOAD_TOO_LARGE
                            } else {
                                UpdateDownloadFailure.SIZE_MISMATCH
                            },
                        )
                    }
                    try {
                        output.write(buffer, 0, count)
                    } catch (_: IOException) {
                        throw storageWriteException()
                    } catch (_: SecurityException) {
                        throw storageWriteException()
                    }
                    onProgress(total, expectedSize)
                }
            }
            currentCoroutineContext().ensureActive()
            throwIfDownloadDeadlineExceeded(deadlineMillis)
            try {
                output.fd.sync()
            } catch (_: IOException) {
                throw storageWriteException()
            } catch (_: SecurityException) {
                throw storageWriteException()
            }
        }
        if (total != expectedSize) {
            throw UpdateCheckException(
                "APK size did not match release metadata",
                UpdateDownloadFailure.SIZE_MISMATCH,
            )
        }
        return total
    }

    private suspend fun <T> HttpURLConnection.cancellableIo(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { disconnect() }
            try {
                val value = block()
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(value))
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private suspend fun <T> withStorageOutput(
        destination: File,
        block: suspend (FileOutputStream) -> T,
    ): T {
        val output = try {
            FileOutputStream(destination)
        } catch (_: IOException) {
            throw storageWriteException()
        } catch (_: SecurityException) {
            throw storageWriteException()
        }
        var primaryFailure: Throwable? = null
        try {
            return block(output)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                output.close()
            } catch (closeError: IOException) {
                val failure = primaryFailure
                if (failure == null) {
                    throw storageWriteException()
                }
                failure.addSuppressed(closeError)
            } catch (closeError: SecurityException) {
                val failure = primaryFailure
                if (failure == null) {
                    throw storageWriteException()
                }
                failure.addSuppressed(closeError)
            }
        }
    }

    private fun storageWriteException(): UpdateCheckException = UpdateCheckException(
        "Unable to save APK",
        UpdateDownloadFailure.STORAGE_ERROR,
    )

    private fun remainingDownloadTimeoutMillis(
        deadlineMillis: Long,
        maximumMillis: Int,
    ): Int {
        val timeout = boundedDeadlineTimeoutMillis(
            nowMillis = SystemClock.elapsedRealtime(),
            deadlineMillis = deadlineMillis,
            maximumMillis = maximumMillis,
        )
        if (timeout == 0) {
            throw UpdateCheckException(
                "APK download exceeded its total deadline",
                UpdateDownloadFailure.TIMEOUT,
            )
        }
        return timeout
    }

    private fun throwIfDownloadDeadlineExceeded(deadlineMillis: Long) {
        if (SystemClock.elapsedRealtime() >= deadlineMillis) {
            throw UpdateCheckException(
                "APK download exceeded its total deadline",
                UpdateDownloadFailure.TIMEOUT,
            )
        }
    }

    private fun validateApk(file: File, expectedVersion: String): UpdateDownloadFailure? = try {
        validateApkUnsafe(file, expectedVersion)
    } catch (_: RuntimeException) {
        UpdateDownloadFailure.INVALID_APK
    }

    private fun validateApkUnsafe(
        file: File,
        expectedVersion: String,
    ): UpdateDownloadFailure? {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return UpdateDownloadFailure.INVALID_APK
        if (archiveInfo.packageName != context.packageName) {
            return UpdateDownloadFailure.WRONG_APPLICATION
        }
        if (stripVersionPrefix(archiveInfo.versionName.orEmpty()) !=
            stripVersionPrefix(expectedVersion)
        ) {
            return UpdateDownloadFailure.VERSION_MISMATCH
        }
        if (packageVersionCode(archiveInfo) <= BuildConfig.VERSION_CODE.toLong()) {
            return UpdateDownloadFailure.NOT_NEWER
        }
        val installedInfo = try {
            packageManager.getPackageInfo(context.packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return UpdateDownloadFailure.SIGNATURE_MISMATCH
        }
        if (!hasCompatibleSigner(installedInfo, archiveInfo)) {
            return UpdateDownloadFailure.SIGNATURE_MISMATCH
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun hasCompatibleSigner(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installedSigningInfo = installed.signingInfo ?: return false
            val archiveSigningInfo = archive.signingInfo ?: return false
            return areUpdateSignerSetsCompatible(
                installedCurrent = installedSigningInfo.apkContentsSigners
                    .orEmpty()
                    .map { it.toByteArray() },
                installedHasMultipleSigners = installedSigningInfo.hasMultipleSigners(),
                archiveCurrent = archiveSigningInfo.apkContentsSigners
                    .orEmpty()
                    .map { it.toByteArray() },
                archiveHasMultipleSigners = archiveSigningInfo.hasMultipleSigners(),
                archiveHistory = archiveSigningInfo.signingCertificateHistory
                    .orEmpty()
                    .map { it.toByteArray() },
            )
        }
        val installedSignatures = installed.signatures.orEmpty()
        val archiveSignatures = archive.signatures.orEmpty()
        return installedSignatures.isNotEmpty() &&
            installedSignatures.size == archiveSignatures.size &&
            installedSignatures.all { installedSignature ->
                archiveSignatures.any { archiveSignature ->
                    installedSignature.toByteArray().contentEquals(archiveSignature.toByteArray())
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private fun openGetConnection(
        url: URL,
        accept: String,
        readTimeout: Int,
        connectTimeout: Int = CONNECT_TIMEOUT_MILLIS,
    ): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = false
        this.connectTimeout = connectTimeout
        this.readTimeout = readTimeout
        setRequestProperty("User-Agent", "DeskCubby Android/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", accept)
        setRequestProperty("Accept-Encoding", "identity")
    }

    private fun resolveRedirect(connection: URLConnection, source: URL): URL {
        val location = connection.getHeaderField("Location")
            ?.takeIf(String::isNotBlank)
            ?: throw UpdateCheckException("Redirect is missing a location")
        return URL(source, location)
    }

    private fun requireHttps(
        url: URL,
        operation: String,
        failure: UpdateDownloadFailure? = null,
    ) {
        if (!url.protocol.equals("https", ignoreCase = true) ||
            url.userInfo != null ||
            (url.port != -1 && url.port != 443)
        ) {
            throw UpdateCheckException("$operation require HTTPS", failure)
        }
    }

    private suspend fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) {
                throw UpdateCheckException("Update response exceeded its size limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Returns "" when the key is missing or holds JSON null (Android optString would give "null"). */
    private fun JSONObject.stringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key).trim()

    private fun JSONArray?.toAssetMetadata(): List<ReleaseAssetMetadata> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(length(), MAX_RELEASE_ASSETS)) {
                val item = optJSONObject(index) ?: continue
                add(
                    ReleaseAssetMetadata(
                        name = item.stringOrEmpty("name"),
                        downloadUrl = item.stringOrEmpty("browser_download_url"),
                        sizeBytes = item.optLong("size", -1L),
                    ),
                )
            }
        }
    }

    private fun readableCheckMessage(error: IOException): Pair<String, String> = when (error) {
        is UpdateCheckException -> when {
            error.message == "No releases" ->
                "尚无发布版本。" to "No releases are available yet."
            HTTP_STATUS_PATTERN.find(error.message.orEmpty()) != null -> {
                val status = HTTP_STATUS_PATTERN.find(error.message.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
                "更新检查失败（HTTP $status）。" to
                    "The update check failed (HTTP $status)."
            }
            else ->
                "更新服务器返回了不可信或无效的信息。" to
                    "The update server returned invalid or untrusted information."
        }
        is java.net.SocketTimeoutException ->
            "更新检查超时，请稍后重试。" to "The update check timed out. Please try again later."
        is java.net.UnknownHostException ->
            "无法连接更新服务器，请检查网络。" to
                "The update server could not be reached. Check your connection."
        is SSLException ->
            "更新检查的 HTTPS 证书验证失败。" to
                "HTTPS certificate verification failed during the update check."
        else ->
            "更新检查网络请求失败，请检查网络连接。" to
                "The update check failed. Check your network connection."
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/vexpaer/DeskCubby/releases/latest"
        const val UPDATE_CACHE_DIRECTORY = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val MAX_REDIRECTS = 4
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val CHECK_TIMEOUT_MILLIS = 10_000
        const val DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000
        const val DOWNLOAD_TOTAL_TIMEOUT_MILLIS = 15L * 60L * 1_000L
        const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
        const val MAX_APK_BYTES = 256L * 1024L * 1024L
        const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
        const val MAX_RELEASE_ASSETS = 100
        const val MAX_VERSION_FILE_CHARS = 48
        val HTTP_STATUS_PATTERN = Regex("HTTP (\\d{3})")
        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}

internal fun cleanupUpdateCache(directory: File, keepApk: File?): Boolean {
    val keepPath = keepApk?.absolutePath
    val entries = directory.listFiles() ?: return false
    var cleaned = true
    entries
        .asSequence()
        .filter(File::isFile)
        .filter { entry ->
            entry.name.endsWith(".part", ignoreCase = true) ||
                (
                    entry.name.endsWith(".apk", ignoreCase = true) &&
                        entry.absolutePath != keepPath
                    )
        }
        .forEach { entry ->
            if (!entry.delete() && entry.exists()) cleaned = false
        }
    return cleaned
}

internal fun boundedDeadlineTimeoutMillis(
    nowMillis: Long,
    deadlineMillis: Long,
    maximumMillis: Int,
): Int {
    require(maximumMillis > 0)
    val remaining = deadlineMillis - nowMillis
    if (remaining <= 0L) return 0
    return minOf(remaining, maximumMillis.toLong()).coerceAtLeast(1L).toInt()
}

internal fun areUpdateSignerSetsCompatible(
    installedCurrent: List<ByteArray>,
    installedHasMultipleSigners: Boolean,
    archiveCurrent: List<ByteArray>,
    archiveHasMultipleSigners: Boolean,
    archiveHistory: List<ByteArray>,
): Boolean {
    if (installedCurrent.isEmpty() || archiveCurrent.isEmpty()) return false
    if (installedHasMultipleSigners || archiveHasMultipleSigners) {
        return installedHasMultipleSigners &&
            archiveHasMultipleSigners &&
            installedCurrent.hasSameCertificateSetAs(archiveCurrent)
    }
    if (installedCurrent.size != 1 || archiveCurrent.size != 1) return false
    return archiveHistory.any { candidate ->
        installedCurrent.single().contentEquals(candidate)
    }
}

internal fun classifyUpdateDownloadIOException(error: IOException): UpdateDownloadFailure =
    when (error) {
        is java.net.SocketTimeoutException -> UpdateDownloadFailure.TIMEOUT
        is SSLException -> UpdateDownloadFailure.TLS_ERROR
        else -> UpdateDownloadFailure.NETWORK_ERROR
    }

private fun List<ByteArray>.hasSameCertificateSetAs(other: List<ByteArray>): Boolean {
    if (size != other.size) return false
    val unmatched = other.toMutableList()
    for (certificate in this) {
        val index = unmatched.indexOfFirst { candidate -> certificate.contentEquals(candidate) }
        if (index < 0) return false
        unmatched.removeAt(index)
    }
    return unmatched.isEmpty()
}

internal fun selectTrustedApkAsset(
    assets: List<ReleaseAssetMetadata>,
    version: String,
): UpdatePackage? {
    val normalizedVersion = stripVersionPrefix(version)
    val expectedNames = listOf(
        "DeskCubby-$normalizedVersion.apk",
        "DeskCubby-v$normalizedVersion.apk",
        "DeskCubby.apk",
    )
    expectedNames.forEach { expectedName ->
        val matches = assets.filter { asset ->
            asset.name.equals(expectedName, ignoreCase = true) &&
                asset.name.length <= 128 &&
                asset.sizeBytes in 1L..256L * 1024L * 1024L &&
                isTrustedApkInitialUrl(asset.downloadUrl)
        }
        if (matches.size == 1) {
            val match = matches.single()
            return UpdatePackage(
                fileName = match.name,
                downloadUrl = match.downloadUrl,
                sizeBytes = match.sizeBytes,
            )
        }
        if (matches.size > 1) return null
    }
    return null
}

internal fun isTrustedReleasePageUrl(value: String): Boolean = runCatching {
    val url = URL(value)
    isStrictHttps(url) &&
        url.host.equals("github.com", ignoreCase = true) &&
        url.path.startsWith("/vexpaer/DeskCubby/releases/tag/")
}.getOrDefault(false)

internal fun isTrustedApkInitialUrl(value: String): Boolean = runCatching {
    val url = URL(value)
    isStrictHttps(url) &&
        url.host.equals("github.com", ignoreCase = true) &&
        url.path.startsWith("/vexpaer/DeskCubby/releases/download/") &&
        url.path.endsWith(".apk", ignoreCase = true)
}.getOrDefault(false)

internal fun isAllowedCheckRedirect(source: URL, target: URL): Boolean =
    isStrictHttps(source) &&
        isStrictHttps(target) &&
        source.host.equals("api.github.com", ignoreCase = true) &&
        target.host.equals("api.github.com", ignoreCase = true)

internal fun isAllowedApkRedirect(source: URL, target: URL): Boolean {
    if (!isStrictHttps(source) || !isStrictHttps(target)) return false
    val sourceHost = source.host.lowercase()
    val targetHost = target.host.lowercase()
    if (sourceHost == "github.com") {
        return targetHost in TRUSTED_APK_DOWNLOAD_HOSTS
    }
    return sourceHost in TRUSTED_APK_DOWNLOAD_HOSTS &&
        targetHost in TRUSTED_APK_DOWNLOAD_HOSTS
}

private fun isStrictHttps(url: URL): Boolean =
    url.protocol.equals("https", ignoreCase = true) &&
        url.userInfo == null &&
        (url.port == -1 || url.port == 443)

private val TRUSTED_APK_DOWNLOAD_HOSTS = setOf(
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

/**
 * Compares two dotted version strings numerically per segment. A leading "v"/"V"
 * is ignored and non-numeric segments count as 0, so "v1.2" > "1.1.9" and
 * "1.0" == "1.0.0". Returns a negative value, zero, or a positive value when
 * [a] is lower than, equal to, or higher than [b].
 */
internal fun compareVersions(a: String, b: String): Int {
    val segmentsA = versionSegments(a)
    val segmentsB = versionSegments(b)
    val length = maxOf(segmentsA.size, segmentsB.size)
    for (index in 0 until length) {
        val valueA = segmentsA.getOrElse(index) { 0L }
        val valueB = segmentsB.getOrElse(index) { 0L }
        if (valueA != valueB) return if (valueA < valueB) -1 else 1
    }
    return 0
}

private fun versionSegments(version: String): List<Long> = stripVersionPrefix(version)
    .split('.')
    .map { segment -> segment.trim().toLongOrNull() ?: 0L }

private fun stripVersionPrefix(version: String): String {
    val trimmed = version.trim()
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed.substring(1) else trimmed
}
