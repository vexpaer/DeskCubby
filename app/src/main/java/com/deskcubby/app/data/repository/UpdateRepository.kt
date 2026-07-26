package com.deskcubby.app.data.repository

import com.deskcubby.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult

    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseName: String,
        val notes: String,
        val htmlUrl: String,
        val publishedAt: String,
    ) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}

private class UpdateCheckException(message: String) : IOException(message)

@Singleton
class UpdateRepository @Inject constructor() {

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        try {
            val root = JSONObject(fetchLatestReleaseBody())
            val tagName = root.stringOrEmpty("tag_name")
            if (tagName.isBlank()) {
                return@withContext UpdateCheckResult.Failed("更新信息缺少版本号。")
            }
            val htmlUrl = root.stringOrEmpty("html_url")
            if (!htmlUrl.startsWith(TRUSTED_RELEASE_URL_PREFIX)) {
                return@withContext UpdateCheckResult.Failed("更新信息中的发布链接不可信，已忽略。")
            }
            val latestVersion = stripVersionPrefix(tagName)
            if (compareVersions(latestVersion, currentVersion) > 0) {
                UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseName = root.stringOrEmpty("name").ifBlank { tagName },
                    notes = root.stringOrEmpty("body"),
                    htmlUrl = htmlUrl,
                    publishedAt = root.stringOrEmpty("published_at"),
                )
            } else {
                UpdateCheckResult.UpToDate(currentVersion)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            UpdateCheckResult.Failed(readableMessage(error))
        } catch (error: JSONException) {
            UpdateCheckResult.Failed("无法解析更新信息。")
        }
    }

    private fun fetchLatestReleaseBody(): String {
        var currentUrl = LATEST_RELEASE_URL
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val url = URL(currentUrl)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw UpdateCheckException("更新检查仅支持 HTTPS 地址。")
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("User-Agent", "DeskCubby Android")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val status = connection.responseCode
                when {
                    status in REDIRECT_CODES -> {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw UpdateCheckException("更新检查重定向次数过多。")
                        }
                        val location = connection.getHeaderField("Location")
                            ?.takeIf(String::isNotBlank)
                            ?: throw UpdateCheckException("更新检查重定向缺少目标地址。")
                        val resolved = URL(url, location)
                        if (!resolved.protocol.equals("https", ignoreCase = true)) {
                            throw UpdateCheckException("更新检查不允许重定向到非 HTTPS 地址。")
                        }
                        currentUrl = resolved.toString()
                        return@repeat
                    }
                    status == HttpURLConnection.HTTP_NOT_FOUND -> {
                        throw UpdateCheckException("尚无发布版本 / No releases yet")
                    }
                    status != HttpURLConnection.HTTP_OK -> {
                        throw UpdateCheckException("更新检查失败（HTTP $status）。")
                    }
                    else -> {
                        return connection.inputStream.use(::readLimited).toString(Charsets.UTF_8)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateCheckException("更新检查重定向次数过多。")
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw UpdateCheckException("更新信息超过 1 MiB 上限。")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Returns "" when the key is missing or holds JSON null (Android optString would give "null"). */
    private fun JSONObject.stringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key).trim()

    private fun readableMessage(error: IOException): String = when (error) {
        is UpdateCheckException -> error.message ?: "更新检查失败。"
        is java.net.SocketTimeoutException -> "更新检查超时，请稍后重试。"
        is java.net.UnknownHostException -> "无法连接更新服务器，请检查网络。"
        is javax.net.ssl.SSLException -> "更新检查的 HTTPS 证书验证失败。"
        else -> "更新检查网络请求失败，请检查网络连接。"
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/vexpaer/DeskCubby/releases/latest"
        const val TRUSTED_RELEASE_URL_PREFIX = "https://github.com/"
        const val MAX_REDIRECTS = 3
        const val TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}

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
