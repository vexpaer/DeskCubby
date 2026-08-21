package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.withRequiredSyncDependencies
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal data class ValidatedCloudSyncConfig(
    val source: CloudSyncConfig,
    val endpoint: URI,
    val remotePath: String,
    val scopeFingerprint: String,
)

internal fun CloudSyncConfig.validateForSync(): ValidatedCloudSyncConfig {
    if (id.isBlank() || id.length > MAX_CONFIG_ID_CHARS || id.any(Char::isISOControl)) {
        throw CloudSyncConfigurationException("同步配置 ID 无效。")
    }
    if (name.length > MAX_CONFIG_NAME_CHARS || name.any(Char::isISOControl)) {
        throw CloudSyncConfigurationException("同步配置名称无效。")
    }
    if (!enabled) {
        throw CloudSyncConfigurationException("该同步配置尚未启用。")
    }
    if (selectedContents.isEmpty()) {
        throw CloudSyncConfigurationException("请至少选择一类需要同步的内容。")
    }
    val normalizedSelectedContents = selectedContents.withRequiredSyncDependencies()
    if (userAgent.isBlank() || userAgent.length > MAX_USER_AGENT_CHARS ||
        userAgent.any(Char::isISOControl)
    ) {
        throw CloudSyncConfigurationException("User-Agent 无效或过长。")
    }

    val endpoint = try {
        URI(endpointUrl.trim())
    } catch (error: Exception) {
        throw CloudSyncConfigurationException("云端服务地址无效。")
    }
    val scheme = endpoint.scheme?.lowercase(Locale.ROOT)
    if (scheme != "https" && !(scheme == "http" && allowInsecureHttp)) {
        throw CloudSyncConfigurationException(
            if (scheme == "http") {
                "HTTP 同步默认关闭；仅可信本地服务可在配置中明确允许 HTTP。"
            } else {
                "云端服务地址必须使用 HTTPS。"
            },
        )
    }
    if (!endpoint.isAbsolute || endpoint.host.isNullOrBlank() || endpoint.userInfo != null ||
        endpoint.query != null || endpoint.fragment != null
    ) {
        throw CloudSyncConfigurationException("云端服务地址不能包含账号、查询参数或片段。")
    }

    val normalizedRemotePath = normalizeRemotePath(remotePath)
    when (serviceType) {
        CloudSyncServiceType.WEBDAV -> {
            if (webDavUsername.length > MAX_CREDENTIAL_CHARS ||
                webDavPassword.length > MAX_CREDENTIAL_CHARS
            ) {
                throw CloudSyncConfigurationException("WebDAV 凭据过长。")
            }
        }

        CloudSyncServiceType.S3_COMPATIBLE -> {
            if (s3Bucket.isBlank() || s3Bucket.length > 255 ||
                s3Bucket.any { it == '/' || it == '\\' || it.isISOControl() }
            ) {
                throw CloudSyncConfigurationException("S3 Bucket 名称无效。")
            }
            if (!s3PathStyle && (
                    !S3_VIRTUAL_HOST_BUCKET_REGEX.matches(s3Bucket) ||
                        endpoint.host.orEmpty().contains(':')
                    )
            ) {
                throw CloudSyncConfigurationException(
                    "关闭 Path-Style 时，Bucket 必须是可用作主机名的小写名称。",
                )
            }
            if (!S3_REGION_REGEX.matches(s3Region)) {
                throw CloudSyncConfigurationException("S3 Region 无效。")
            }
            if (s3AccessKey.isBlank() || s3SecretKey.isBlank()) {
                throw CloudSyncConfigurationException("请填写 S3 Access Key 和 Secret Key。")
            }
            if (listOf(s3AccessKey, s3SecretKey, s3SessionToken).any {
                    it.length > MAX_CREDENTIAL_CHARS
                }
            ) {
                throw CloudSyncConfigurationException("S3 凭据过长。")
            }
        }
    }

    val scope = buildString {
        append(serviceType.name)
        append('\n')
        append(endpoint.normalize().toASCIIString())
        append('\n')
        append(normalizedRemotePath)
        append('\n')
        when (serviceType) {
            CloudSyncServiceType.WEBDAV -> append(webDavUsername)
            CloudSyncServiceType.S3_COMPATIBLE -> {
                append(s3Bucket)
                append('\n')
                append(s3Region)
                append('\n')
                append(s3PathStyle)
                append('\n')
                // Only the final scope SHA-256 is persisted. Including the credential identity
                // prevents ancestry from one account being reused after switching accounts.
                append(s3AccessKey)
            }
        }
    }
    return ValidatedCloudSyncConfig(
        source = copy(selectedContents = normalizedSelectedContents),
        endpoint = endpoint,
        remotePath = normalizedRemotePath,
        scopeFingerprint = sha256(scope.toByteArray(StandardCharsets.UTF_8)),
    )
}

internal fun normalizeRemotePath(raw: String): String {
    if (raw.length > MAX_REMOTE_PATH_CHARS || raw.any(Char::isISOControl)) {
        throw CloudSyncConfigurationException("远端目录路径无效。")
    }
    if (raw.contains('\\')) {
        throw CloudSyncConfigurationException("远端目录路径必须使用“/”分隔。")
    }
    val segments = raw.trim().trim('/').split('/').filter(String::isNotEmpty)
    if (segments.any { it == "." || it == ".." }) {
        throw CloudSyncConfigurationException("远端目录路径不能包含 . 或 ..。")
    }
    return segments.joinToString("/")
}

internal fun requireValidSyncKey(key: String): String {
    if (key.isBlank() || key.length > MAX_SYNC_KEY_CHARS || key.startsWith('/') ||
        key.endsWith('/') || key.contains('\\') || key.any(Char::isISOControl)
    ) {
        throw CloudSyncException("同步内容包含无效的相对路径。")
    }
    val segments = key.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) {
        throw CloudSyncException("同步内容包含无效的相对路径。")
    }
    return key
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(Locale.ROOT, it) }

private val S3_REGION_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val S3_VIRTUAL_HOST_BUCKET_REGEX =
    Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")
private const val MAX_CONFIG_ID_CHARS = 128
private const val MAX_CONFIG_NAME_CHARS = 200
private const val MAX_CREDENTIAL_CHARS = 8_192
private const val MAX_REMOTE_PATH_CHARS = 1_024
private const val MAX_SYNC_KEY_CHARS = 2_048
private const val MAX_USER_AGENT_CHARS = 512
