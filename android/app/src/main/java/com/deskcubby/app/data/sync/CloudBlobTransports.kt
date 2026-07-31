package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncServiceType
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

class DefaultCloudSyncRemoteStoreFactory : CloudSyncRemoteStoreFactory {
    override fun create(
        config: CloudSyncConfig,
        limits: CloudSyncLimits,
    ): CloudSyncRemoteStore {
        val validated = config.validateForSync()
        val http = BoundedHttpClient(
            connectTimeoutMillis = limits.connectTimeoutMillis,
            readTimeoutMillis = limits.readTimeoutMillis,
            userAgent = validated.source.userAgent,
        )
        val transport: ConditionalBlobTransport = when (config.serviceType) {
            CloudSyncServiceType.WEBDAV -> WebDavBlobTransport(validated, http)
            CloudSyncServiceType.S3_COMPATIBLE -> S3BlobTransport(validated, http)
        }
        return ManifestRemoteStore(transport, limits)
    }
}

private class WebDavBlobTransport(
    config: ValidatedCloudSyncConfig,
    private val http: BoundedHttpClient,
) : ConditionalBlobTransport {
    private val collectionUri = buildCollectionUri(
        endpoint = config.endpoint,
        pathSegments = config.remotePath.split('/').filter(String::isNotBlank),
    )
    private val authorization = if (
        config.source.webDavUsername.isNotEmpty() || config.source.webDavPassword.isNotEmpty()
    ) {
        val value = "${config.source.webDavUsername}:${config.source.webDavPassword}"
        "Basic " + Base64.getEncoder()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    } else {
        null
    }

    override suspend fun get(
        storageName: String,
        maxBytes: Long,
        expectedVersion: String?,
    ): BlobRead? {
        val headers = buildMap {
            authorization?.let { put("Authorization", it) }
            expectedVersion?.let { put("If-Match", it) }
        }
        val response = http.execute(
            SyncHttpRequest(
                method = "GET",
                uri = appendStorageName(collectionUri, storageName),
                headers = headers,
                maxResponseBytes = maxBytes,
            ),
        )
        when (response.status) {
            404 -> return null
            409, 412 -> throw CloudSyncConflictException()
            200 -> Unit
            else -> throw statusException("WebDAV 读取", response.status)
        }
        val metadata = response.toBlobMetadata()
        if (expectedVersion != null && metadata.version != expectedVersion) {
            throw CloudSyncConflictException()
        }
        if (metadata.size >= 0L && metadata.size != response.body.size.toLong()) {
            throw CloudSyncException("WebDAV 返回的文件长度不完整。")
        }
        return BlobRead(
            metadata = metadata.copy(size = response.body.size.toLong()),
            bytes = response.body,
        )
    }

    override suspend fun put(
        storageName: String,
        bytes: ByteArray,
        sha256: String,
        condition: BlobWriteCondition,
    ): BlobMetadata {
        val uri = appendStorageName(collectionUri, storageName)
        val headers = buildMap {
            authorization?.let { put("Authorization", it) }
            put("Content-Type", "application/octet-stream")
            put("X-DeskCubby-Sha256", sha256)
            when (condition) {
                BlobWriteCondition.MustNotExist -> put("If-None-Match", "*")
                is BlobWriteCondition.MustMatch -> put("If-Match", condition.version)
            }
        }
        val response = http.execute(
            SyncHttpRequest(
                method = "PUT",
                uri = uri,
                headers = headers,
                body = bytes,
                maxResponseBytes = MAX_ERROR_BYTES,
            ),
        )
        when (response.status) {
            200, 201, 204 -> Unit
            409, 412 -> throw CloudSyncConflictException()
            else -> throw statusException("WebDAV 写入", response.status)
        }
        val returned = response.toBlobMetadata(allowMissingVersion = true)
        if (returned.version.isNotBlank()) {
            return returned.copy(size = bytes.size.toLong())
        }
        // If PUT omitted ETag, read the exact committed bytes and obtain their ETag together.
        // HEAD alone is insufficient: a competing writer could win between PUT and HEAD.
        val verified = get(storageName, bytes.size.toLong())
            ?: throw CloudSyncConflictException()
        if (verified.bytes.size != bytes.size || sha256(verified.bytes) != sha256) {
            throw CloudSyncConflictException()
        }
        return verified.metadata
    }
}

private class S3BlobTransport(
    config: ValidatedCloudSyncConfig,
    private val http: BoundedHttpClient,
) : ConditionalBlobTransport {
    private val config = config.source
    private val collectionUri = buildS3CollectionUri(
        endpoint = config.endpoint,
        bucket = this.config.s3Bucket,
        remotePath = config.remotePath,
        pathStyle = this.config.s3PathStyle,
    )
    private val signer = S3SigV4(
        accessKeyId = this.config.s3AccessKey,
        secretAccessKey = this.config.s3SecretKey,
        region = this.config.s3Region,
        sessionToken = this.config.s3SessionToken.takeIf(String::isNotBlank),
    )

    override suspend fun get(
        storageName: String,
        maxBytes: Long,
        expectedVersion: String?,
    ): BlobRead? {
        val uri = appendStorageName(collectionUri, storageName)
        val unsignedHeaders = buildMap {
            expectedVersion?.let { put("If-Match", it) }
        }
        val signed = signer.sign("GET", uri, unsignedHeaders)
        val response = http.execute(
            SyncHttpRequest(
                method = "GET",
                uri = uri,
                headers = signed.headers.withoutSyntheticHost(),
                maxResponseBytes = maxBytes,
            ),
        )
        when (response.status) {
            404 -> return null
            409, 412 -> throw CloudSyncConflictException()
            200 -> Unit
            else -> throw s3StatusException("S3 读取", response.status, response.body)
        }
        val metadata = response.toBlobMetadata()
        if (expectedVersion != null && metadata.version != expectedVersion) {
            throw CloudSyncConflictException()
        }
        return BlobRead(
            metadata = metadata.copy(size = response.body.size.toLong()),
            bytes = response.body,
        )
    }

    override suspend fun put(
        storageName: String,
        bytes: ByteArray,
        sha256: String,
        condition: BlobWriteCondition,
    ): BlobMetadata {
        val uri = appendStorageName(collectionUri, storageName)
        val unsignedHeaders = buildMap {
            put("Content-Type", "application/octet-stream")
            put("x-amz-meta-deskcubby-sha256", sha256)
            when (condition) {
                BlobWriteCondition.MustNotExist -> put("If-None-Match", "*")
                is BlobWriteCondition.MustMatch -> put("If-Match", condition.version)
            }
        }
        val signed = signer.sign(
            method = "PUT",
            uri = uri,
            headers = unsignedHeaders,
            payload = bytes,
            payloadSha256 = sha256,
        )
        val response = http.execute(
            SyncHttpRequest(
                method = "PUT",
                uri = uri,
                headers = signed.headers.withoutSyntheticHost(),
                body = bytes,
                maxResponseBytes = MAX_ERROR_BYTES,
            ),
        )
        when (response.status) {
            200, 201, 204 -> Unit
            409, 412 -> throw CloudSyncConflictException()
            else -> throw s3StatusException("S3 写入", response.status, response.body)
        }
        val metadata = response.toBlobMetadata(allowMissingVersion = true)
        if (metadata.version.isNotBlank()) {
            return metadata.copy(size = bytes.size.toLong())
        }
        val verified = get(storageName, bytes.size.toLong())
            ?: throw CloudSyncConflictException()
        if (verified.bytes.size != bytes.size || sha256(verified.bytes) != sha256) {
            throw CloudSyncConflictException()
        }
        return verified.metadata
    }
}

private fun SyncHttpResponse.toBlobMetadata(
    allowMissingVersion: Boolean = false,
): BlobMetadata {
    val version = firstHeader("etag").orEmpty()
    if (!allowMissingVersion && version.isBlank()) {
        throw CloudSyncException("云端服务未提供 ETag，无法执行安全的条件同步。")
    }
    if (version.length > MAX_ETAG_CHARS || version.any { it == '\r' || it == '\n' }) {
        throw CloudSyncException("云端服务返回了无效的 ETag。")
    }
    if (version.startsWith("W/", ignoreCase = true)) {
        throw CloudSyncException("云端服务仅提供弱 ETag，无法执行安全的条件同步。")
    }
    val size = firstHeader("content-length")?.toLongOrNull() ?: -1L
    val lastModified = firstHeader("last-modified")?.let { raw ->
        runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    } ?: System.currentTimeMillis()
    return BlobMetadata(
        version = version,
        size = size,
        lastModifiedMillis = lastModified.coerceAtLeast(0L),
    )
}

private fun statusException(action: String, status: Int): CloudSyncException {
    val detail = when (status) {
        301, 302, 303, 307, 308 -> "服务发生重定向，请在配置中填写最终地址"
        401, 403 -> "认证失败或没有访问权限"
        404 -> "远端目录不存在；WebDAV 目录需预先创建"
        405, 501 -> "服务不支持所需的条件 GET/PUT"
        411, 413 -> "服务拒绝了文件大小"
        429 -> "请求过于频繁"
        in 500..599 -> "云端服务暂时不可用"
        else -> "服务返回状态 $status"
    }
    return CloudSyncException(
        "$action 失败：$detail。",
        errorCode = "HTTP_$status",
    )
}

private fun s3StatusException(
    action: String,
    status: Int,
    responseBody: ByteArray,
): CloudSyncException {
    val providerCode = extractS3ErrorCode(responseBody)
    val detail = when (status) {
        301, 302, 303, 307, 308 -> "服务发生重定向，请检查接入点和 Path-Style"
        400 -> "请求签名、接入点、Region 或 Path-Style 不匹配"
        401, 403 -> "认证失败或没有访问权限"
        404 -> "Bucket、接入点或远端对象不存在"
        405, 501 -> "服务不支持所需的条件 GET/PUT"
        409, 412 -> "云端对象在同步期间发生变化"
        411, 413 -> "服务拒绝了文件大小"
        429 -> "请求过于频繁"
        in 500..599 -> "云端服务暂时不可用"
        else -> "服务返回状态 $status"
    }
    val safeProviderCode = providerCode?.takeIf(S3_ERROR_CODE::matches)
    val code = buildString {
        append("S3")
        safeProviderCode?.let { append('_').append(it) }
        append("_HTTP_").append(status)
    }
    val providerDetail = safeProviderCode?.let { "（服务代码：$it）" }.orEmpty()
    return CloudSyncException(
        "$action 失败：$detail$providerDetail。",
        errorCode = code,
    )
}

internal fun extractS3ErrorCode(responseBody: ByteArray): String? {
    if (responseBody.isEmpty() || responseBody.size > MAX_ERROR_BYTES) return null
    val text = responseBody.toString(StandardCharsets.UTF_8)
    return S3_ERROR_XML.find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(S3_ERROR_CODE::matches)
}

internal fun buildS3CollectionUri(
    endpoint: URI,
    bucket: String,
    remotePath: String,
    pathStyle: Boolean,
): URI {
    if (pathStyle) {
        return buildCollectionUri(
            endpoint = endpoint,
            pathSegments = buildList {
                add(bucket)
                addAll(remotePath.split('/').filter(String::isNotBlank))
            },
        )
    }
    val endpointHost = endpoint.host
    val virtualHost = if (endpointHost.startsWith("$bucket.", ignoreCase = true)) {
        endpointHost
    } else {
        "$bucket.$endpointHost"
    }
    val authority = if (endpoint.port == -1) virtualHost else "$virtualHost:${endpoint.port}"
    val virtualEndpoint = URI(
        "${endpoint.scheme}://$authority${endpoint.rawPath.orEmpty().ifEmpty { "/" }}",
    )
    return buildCollectionUri(
        endpoint = virtualEndpoint,
        pathSegments = remotePath.split('/').filter(String::isNotBlank),
    )
}

private fun buildCollectionUri(
    endpoint: URI,
    pathSegments: List<String>,
): URI {
    val basePath = endpoint.rawPath.orEmpty().trimEnd('/')
    val suffix = pathSegments.joinToString("/") { encodePathSegment(it) }
    val rawPath = buildString {
        append(if (basePath.isEmpty()) "" else basePath)
        append('/')
        if (suffix.isNotEmpty()) {
            append(suffix)
            append('/')
        }
    }
    return URI("${endpoint.scheme}://${endpoint.rawAuthority}$rawPath")
}

private fun appendStorageName(collection: URI, storageName: String): URI {
    if (!STORAGE_NAME.matches(storageName)) throw CloudSyncException("远端对象名称无效。")
    return URI(collection.toASCIIString() + storageName)
}

private fun encodePathSegment(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    return buildString(bytes.size) {
        bytes.forEach { byte ->
            val valueInt = byte.toInt() and 0xff
            val character = valueInt.toChar()
            if (character.isAsciiUnreserved()) {
                append(character)
            } else {
                append('%')
                append(HEX[valueInt ushr 4])
                append(HEX[valueInt and 0x0f])
            }
        }
    }
}

private fun Char.isAsciiUnreserved(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
        this == '-' || this == '.' || this == '_' || this == '~'

private fun Map<String, String>.withoutSyntheticHost(): Map<String, String> =
    filterKeys { !it.equals("host", ignoreCase = true) }

private val STORAGE_NAME = Regex("[.A-Za-z0-9_-]{1,200}")
private val S3_ERROR_XML =
    Regex("""<(?:[A-Za-z0-9_-]+:)?Code>\s*([A-Za-z0-9._-]{1,128})\s*</(?:[A-Za-z0-9_-]+:)?Code>""")
private val S3_ERROR_CODE = Regex("[A-Za-z0-9._-]{1,128}")
private val HEX = "0123456789ABCDEF".toCharArray()
private const val MAX_ETAG_CHARS = 4_096
private const val MAX_ERROR_BYTES = 64L * 1024
