package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncServiceType
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

class DefaultCloudSyncRemoteStoreFactory : CloudSyncRemoteStoreFactory {
    override fun create(
        config: CloudSyncConfig,
        limits: CloudSyncLimits,
        transferBudget: TransferBudget,
    ): CloudSyncRemoteStore {
        val validated = config.validateForSync()
        val http = BoundedHttpClient(
            connectTimeoutMillis = limits.connectTimeoutMillis,
            readTimeoutMillis = limits.readTimeoutMillis,
            userAgent = validated.source.userAgent,
        )
        val transport: ConditionalBlobTransport = when (config.serviceType) {
            CloudSyncServiceType.WEBDAV -> WebDavBlobTransport(
                config = validated,
                http = BudgetedSyncHttpExecutor(
                    delegate = WebDavSyncHttpExecutor(
                        standard = http,
                        propFind = BoundedPropFindHttpClient(
                            connectTimeoutMillis = limits.connectTimeoutMillis,
                            readTimeoutMillis = limits.readTimeoutMillis,
                            writeTimeoutMillis = limits.readTimeoutMillis,
                            callTimeoutMillis = limits.overallTimeoutMillis,
                            userAgent = validated.source.userAgent,
                        ),
                    ),
                    budget = transferBudget,
                ),
            )
            CloudSyncServiceType.S3_COMPATIBLE -> S3BlobTransport(
                validated,
                BudgetedSyncHttpExecutor(http, transferBudget),
            )
        }
        return ManifestRemoteStore(transport, limits)
    }
}

internal class WebDavBlobTransport(
    config: ValidatedCloudSyncConfig,
    private val http: SyncHttpExecutor,
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
        val uri = appendStorageName(collectionUri, storageName)
        val initialHeaders = buildMap {
            authorization?.let { put("Authorization", it) }
            expectedVersion?.let { addReadConditionHeaders(it) }
        }
        var response = executeGet(
            uri = uri,
            headers = initialHeaders,
            maxBytes = maxBytes,
        )
        if (response.status == 404) return null

        var actualHash = sha256(response.body)
        var metadata = response.toBlobMetadata(allowMissingVersion = true)
        if (metadata.version.isBlank()) {
            val unvalidatedContentHash = actualHash
            val properties = readProperties(uri)
                ?: throw missingWebDavValidator(response)
            val propertyVersion = properties.strongEtag
                ?: throw missingWebDavValidator(response)
            val confirmationHeaders = buildMap {
                authorization?.let { put("Authorization", it) }
                addReadConditionHeaders(propertyVersion)
            }
            response = executeGet(
                uri = uri,
                headers = confirmationHeaders,
                maxBytes = maxBytes,
            )
            if (response.status == 404) throw remoteVersionConflict()
            actualHash = sha256(response.body)
            if (actualHash != unvalidatedContentHash) throw remoteVersionConflict()
            metadata = response.withWebDavEtag(properties).toBlobMetadata()
            if (metadata.version != propertyVersion) {
                throw remoteVersionConflict()
            }
        }
        if (expectedVersion != null && metadata.version != requireStrongRemoteVersion(expectedVersion)) {
            throw remoteVersionConflict()
        }
        if (metadata.size >= 0L && metadata.size != response.body.size.toLong()) {
            throw CloudSyncException("WebDAV 返回的文件长度不完整。")
        }
        return BlobRead(
            metadata = metadata.copy(size = response.body.size.toLong()),
            bytes = response.body,
        )
    }

    private suspend fun executeGet(
        uri: URI,
        headers: Map<String, String>,
        maxBytes: Long,
    ): SyncHttpResponse {
        val response = http.execute(
            SyncHttpRequest(
                method = "GET",
                uri = uri,
                headers = headers,
                maxResponseBytes = maxBytes,
            ),
        )
        when (response.status) {
            200, 404 -> Unit
            409, 412 -> throw CloudSyncConflictException()
            else -> throw statusException("WebDAV 读取", response.status)
        }
        return response
    }

    private suspend fun readProperties(uri: URI): WebDavProperties? {
        val headers = buildMap {
            authorization?.let { put("Authorization", it) }
            put("Depth", "0")
            put("Content-Type", "application/xml; charset=utf-8")
        }
        val response = http.execute(
            SyncHttpRequest(
                method = "PROPFIND",
                uri = uri,
                headers = headers,
                body = WEBDAV_PROPERTY_REQUEST,
                maxResponseBytes = MAX_DAV_PROPERTIES_BYTES,
            ),
        )
        when (response.status) {
            200, 207 -> Unit
            404, 409, 412 -> throw remoteVersionConflict()
            405, 501 -> throw CloudSyncException(
                "WebDAV 服务未返回文件验证头，也不支持读取强 ETag 属性，" +
                    "为避免覆盖远端修改，已停止同步。 / The WebDAV service returned no " +
                    "validator header and does not support the strong ETag property; " +
                    "sync was stopped to avoid overwriting remote changes.",
                errorCode = "SYNC_REMOTE_VALIDATION",
            )
            else -> throw statusException("WebDAV 属性读取", response.status)
        }
        return parseWebDavProperties(response.body, uri)
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
                is BlobWriteCondition.MustMatch -> put(
                    "If-Match",
                    requireStrongRemoteVersion(condition.version),
                )
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
        return verifyFallbackWrite(
            storageName = storageName,
            bytes = bytes,
            expectedSha256 = sha256,
            read = ::get,
        )
    }
}

internal class S3BlobTransport(
    config: ValidatedCloudSyncConfig,
    private val http: SyncHttpExecutor,
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
            expectedVersion?.let { put("If-Match", requireStrongRemoteVersion(it)) }
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
        val version = resolveReadVersion(response, expectedVersion)
        val metadata = response.toBlobMetadataWithVersion(version)
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
                is BlobWriteCondition.MustMatch -> put(
                    "If-Match",
                    requireStrongRemoteVersion(condition.version),
                )
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
        val trustedVersion = response.s3EntityTagResolution().trustedVersion
        if (trustedVersion != null) {
            return response.toBlobMetadataWithVersion(trustedVersion)
                .copy(size = bytes.size.toLong())
        }
        return verifyFallbackWrite(
            storageName = storageName,
            bytes = bytes,
            expectedSha256 = sha256,
            read = ::get,
        )
    }

    /**
     * S3-compatible gateways commonly remove ETag quotes, duplicate proxy headers, omit ETag, or
     * do not implement conditional GET consistently. Keep a bounded, header-safe version token for
     * best-effort If-Match requests without probing whether the provider enforces that condition.
     * The manifest and payload SHA-256 checks remain the source of content integrity.
     */
    private fun resolveReadVersion(
        response: SyncHttpResponse,
        expectedVersion: String?,
    ): String {
        val expected = expectedVersion?.let(::requireStrongRemoteVersion)
        // ManifestRemoteStore still verifies payload SHA-256 and the decoded manifest entry set.
        // Returning the requested token avoids rejecting providers that ignore If-Match.
        if (expected != null) return expected
        val resolution = response.s3EntityTagResolution()
        resolution.trustedVersion?.let { return it }
        return resolution.compatibleVersions.firstOrNull() ?: response.body.s3SinglePartEtag()
    }
}

internal data class S3EntityTagResolution(
    val trustedVersion: String?,
    val compatibleVersions: List<String>,
)

/** Parses the common S3/proxy ETag variants without weakening WebDAV's strict parser. */
internal fun SyncHttpResponse.s3EntityTagResolution(): S3EntityTagResolution {
    val rawValues = headers["etag"].orEmpty()
    if (rawValues.size > MAX_S3_ETAG_CANDIDATES) return emptyS3EntityTagResolution()
    val splitValues = runCatching { rawValues.flatMap(::splitS3EntityTagHeader) }
        .getOrElse { return emptyS3EntityTagResolution() }
    if (splitValues.size > MAX_S3_ETAG_CANDIDATES) return emptyS3EntityTagResolution()
    val parsed = splitValues.mapNotNull { raw ->
        runCatching { parseS3EntityTagCandidate(raw) }.getOrNull()
    }
    val candidates = parsed.map(S3EntityTagCandidate::version).distinct()
    if (candidates.size > MAX_S3_ETAG_CANDIDATES) return emptyS3EntityTagResolution()
    // Only one already-quoted field value is trusted as the direct server version. Repaired,
    // repeated, or proxy-split values remain bounded compatibility candidates.
    val trusted = parsed.singleOrNull()
        ?.takeIf(S3EntityTagCandidate::trusted)
        ?.version
    return S3EntityTagResolution(
        trustedVersion = trusted,
        compatibleVersions = candidates,
    )
}

private fun emptyS3EntityTagResolution(): S3EntityTagResolution =
    S3EntityTagResolution(trustedVersion = null, compatibleVersions = emptyList())

private data class S3EntityTagCandidate(
    val version: String,
    val trusted: Boolean,
)

private fun parseS3EntityTagCandidate(raw: String): S3EntityTagCandidate {
    val value = raw.trim()
    if (value.isEmpty() || value.length > MAX_ETAG_CHARS || value.any(Char::isISOControl)) {
        throw invalidEntityTag()
    }
    if (value.startsWith("W/", ignoreCase = true)) {
        val repaired = parseStrongEntityTag(value.substring(2)) ?: throw invalidEntityTag()
        return S3EntityTagCandidate(repaired, trusted = false)
    }
    if (value.startsWith('"')) {
        val strong = parseStrongEntityTag(value) ?: throw invalidEntityTag()
        return S3EntityTagCandidate(strong, trusted = true)
    }
    if (value.any { it.code !in 0x21..0x7e || it == '"' || it == ',' }) {
        throw invalidEntityTag()
    }
    val repaired = parseStrongEntityTag("\"$value\"") ?: throw invalidEntityTag()
    return S3EntityTagCandidate(repaired, trusted = false)
}

private fun splitS3EntityTagHeader(raw: String): List<String> {
    if (raw.length > MAX_ETAG_CHARS * MAX_S3_ETAG_CANDIDATES || raw.any(Char::isISOControl)) {
        throw invalidEntityTag()
    }
    val values = mutableListOf<String>()
    var start = 0
    var quoted = false
    raw.forEachIndexed { index, character ->
        when (character) {
            '"' -> quoted = !quoted
            ',' -> if (!quoted) {
                values += raw.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    if (quoted) throw invalidEntityTag()
    values += raw.substring(start).trim()
    return values
}

/**
 * Builds the conventional single-part S3 ETag compatibility token. MD5 is not used for content
 * integrity or authentication; payload and manifest bytes remain bound by SHA-256.
 */
private fun ByteArray.s3SinglePartEtag(): String {
    val digest = MessageDigest.getInstance("MD5").digest(this)
    return digest.joinToString(separator = "", prefix = "\"", postfix = "\"") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
}

private fun SyncHttpResponse.toBlobMetadataWithVersion(version: String): BlobMetadata {
    val size = firstHeader("content-length")?.toLongOrNull() ?: -1L
    val parsedLastModified = parseLastModifiedMillis(firstHeader("last-modified").orEmpty())
    return BlobMetadata(
        version = requireStrongRemoteVersion(version),
        size = size,
        lastModifiedMillis = (parsedLastModified ?: 0L).coerceAtLeast(0L),
    )
}

internal data class WebDavProperties(
    val strongEtag: String?,
)

internal fun parseWebDavProperties(
    bytes: ByteArray,
    targetUri: URI,
): WebDavProperties? {
    if (bytes.isEmpty() || bytes.size.toLong() > MAX_DAV_PROPERTIES_BYTES) {
        throw invalidWebDavProperties()
    }
    if (containsForbiddenXmlDeclaration(bytes)) {
        throw CloudSyncException(
            "WebDAV 属性响应包含禁止的 DTD/实体声明，已停止同步。 / " +
                "The WebDAV property response contains a forbidden DTD/entity declaration; " +
                "sync was stopped.",
            errorCode = "SYNC_REMOTE_VALIDATION",
        )
    }
    try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ ->
                throw SAXException("External XML entities are disabled")
            }
        }
        val document = ByteArrayInputStream(bytes).use(builder::parse)
        val root = document.documentElement ?: throw invalidWebDavProperties()
        if (root.localNameLowercase() != "multistatus") throw invalidWebDavProperties()

        var foundTarget = false
        var strongEtag: String? = null
        root.childElements("response").forEach { response ->
            val href = response.firstChildText("href") ?: return@forEach
            if (!webDavHrefMatchesTarget(href, targetUri)) return@forEach
            foundTarget = true
            response.childElements("propstat").forEach { propstat ->
                val status = propstat.firstChildText("status").orEmpty()
                if (!SUCCESSFUL_DAV_STATUS.containsMatchIn(status)) return@forEach
                propstat.childElements("prop").forEach { prop ->
                    prop.childElements().forEach { property ->
                        when (property.localNameLowercase()) {
                            "getetag" -> strongEtag = mergeDavProperty(
                                current = strongEtag,
                                candidate = parseStrongEntityTag(property.textContent.orEmpty()),
                            )
                        }
                    }
                }
            }
        }
        if (!foundTarget) return null
        return WebDavProperties(strongEtag)
    } catch (error: CloudSyncException) {
        throw error
    } catch (error: Exception) {
        throw invalidWebDavProperties(error)
    }
}

private fun SyncHttpResponse.withWebDavEtag(
    properties: WebDavProperties,
): SyncHttpResponse {
    val merged = headers.toMutableMap()
    if (strongEtagOrNull() == null) {
        properties.strongEtag?.let { merged["etag"] = listOf(it) }
    }
    return SyncHttpResponse(status = status, headers = merged, body = body)
}

private fun <T> mergeDavProperty(current: T?, candidate: T?): T? {
    if (candidate == null) return current
    if (current != null && current != candidate) throw invalidWebDavProperties()
    return candidate
}

private fun Element.childElements(localName: String? = null): List<Element> = buildList {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && (localName == null || child.localNameLowercase() == localName)) {
            add(child)
        }
    }
}

private fun Element.firstChildText(localName: String): String? =
    childElements(localName).firstOrNull()?.textContent?.trim()

private fun Node.localNameLowercase(): String =
    (localName ?: nodeName.substringAfter(':')).lowercase(Locale.ROOT)

private fun webDavHrefMatchesTarget(rawHref: String, targetUri: URI): Boolean {
    if (rawHref.isBlank() || rawHref.length > MAX_DAV_HREF_CHARS || rawHref.any(Char::isISOControl)) {
        return false
    }
    val resolved = runCatching {
        val href = URI(rawHref.trim())
        if (href.isAbsolute) href else targetUri.resolve(href)
    }.getOrNull() ?: return false
    if (resolved.userInfo != null || resolved.query != null || resolved.fragment != null) return false
    return resolved.scheme.equals(targetUri.scheme, ignoreCase = true) &&
        resolved.host.equals(targetUri.host, ignoreCase = true) &&
        effectivePort(resolved) == effectivePort(targetUri) &&
        resolved.path == targetUri.path
}

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("http", ignoreCase = true) -> 80
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> -1
}

private fun containsForbiddenXmlDeclaration(bytes: ByteArray): Boolean {
    // Removing NUL also catches the common UTF-16/UTF-32 encodings of XML declarations.
    val ascii = buildString(bytes.size) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (value != 0 && value < 128) append(value.toChar().lowercaseChar())
        }
    }
    return "<!doctype" in ascii || "<!entity" in ascii
}

private fun invalidWebDavProperties(cause: Throwable? = null): CloudSyncException =
    CloudSyncException(
        "WebDAV 属性响应无效，已停止同步。 / " +
            "The WebDAV property response is invalid; sync was stopped.",
        cause = cause,
        errorCode = "SYNC_REMOTE_VALIDATION",
    )

private fun missingWebDavValidator(response: SyncHttpResponse): CloudSyncException {
    val weak = response.firstHeader("etag").orEmpty().trim()
        .startsWith("W/", ignoreCase = true)
    val message = if (weak) {
        "WebDAV 服务仅提供弱 ETag，GET 与 PROPFIND 均未提供单个合法强 ETag，" +
            "为避免覆盖远端修改，已停止同步。 / The WebDAV service exposes only a weak " +
            "ETag, and neither GET nor PROPFIND supplies one valid strong ETag; " +
            "sync was stopped to avoid overwriting remote changes."
    } else {
        "WebDAV 服务的 GET 与 PROPFIND 均未提供单个合法强 ETag，" +
            "为避免覆盖远端修改，已停止同步。 / Neither GET nor PROPFIND supplies one " +
            "valid strong ETag; sync was stopped to avoid overwriting remote changes."
    }
    return CloudSyncException(message, errorCode = "SYNC_REMOTE_VALIDATION")
}

internal fun SyncHttpResponse.toBlobMetadata(
    allowMissingVersion: Boolean = false,
): BlobMetadata {
    val returnedEtag = firstHeader("etag").orEmpty().trim()
    val strongEtag = strongEtagOrNull().orEmpty()
    val size = firstHeader("content-length")?.toLongOrNull() ?: -1L
    val parsedLastModified = parseLastModifiedMillis(firstHeader("last-modified").orEmpty())
    val version = strongEtag
    if (!allowMissingVersion && version.isBlank()) {
        val reason = if (returnedEtag.startsWith("W/", ignoreCase = true)) {
            "云端服务仅提供弱 ETag，无法执行安全条件同步。 / " +
                "The cloud service exposes only a weak ETag, so safe conditional sync is unavailable."
        } else {
            "云端服务未提供单个合法强 ETag，无法执行安全条件同步。 / " +
                "The cloud service exposes no single valid strong ETag, so safe conditional sync is unavailable."
        }
        throw CloudSyncException(reason, errorCode = "SYNC_REMOTE_VALIDATION")
    }
    val lastModified = parsedLastModified ?: 0L
    return BlobMetadata(
        version = version,
        size = size,
        lastModifiedMillis = lastModified.coerceAtLeast(0L),
    )
}

private fun SyncHttpResponse.strongEtagOrNull(): String? {
    val values = headers["etag"].orEmpty()
    if (values.size > 1) throw invalidEntityTag()
    return values.singleOrNull()?.let(::parseStrongEntityTag)
}

private fun parseStrongEntityTag(raw: String): String? {
    val value = raw.orEmpty().trim()
    if (value.isEmpty()) return null
    if (value.length > MAX_ETAG_CHARS || value.any(Char::isISOControl)) {
        throw invalidEntityTag()
    }
    val opaqueTag = if (value.startsWith("W/", ignoreCase = true)) {
        value.substring(2)
    } else {
        value
    }
    if (opaqueTag.length < 2 || opaqueTag.first() != '"' || opaqueTag.last() != '"' ||
        opaqueTag.substring(1, opaqueTag.lastIndex).any { character ->
            // RFC 9110 entity-tag = DQUOTE *etagc DQUOTE. Be conservative about obs-text
            // because header decoding differs across providers; visible ASCII is sufficient for
            // real-world WebDAV validators and rejects lists such as `"one", "two"`.
            character.code !in 0x21..0x7e || character == '"'
        }
    ) {
        throw invalidEntityTag()
    }
    // Weak entity-tags are syntactically valid but cannot protect a conditional write.
    return value.takeUnless { it.startsWith("W/", ignoreCase = true) }
}

private fun invalidEntityTag(): CloudSyncException = CloudSyncException(
    "云端服务返回了无效或不可安全使用的 ETag，已停止同步。 / " +
        "The cloud service returned an invalid or unsafe ETag; sync was stopped.",
    errorCode = "SYNC_REMOTE_VALIDATION",
)

private fun parseLastModifiedMillis(raw: String): Long? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.length > MAX_LAST_MODIFIED_CHARS || value.any { it == '\r' || it == '\n' }) {
        throw CloudSyncException(
            "云端服务返回了无效的 Last-Modified，已停止同步。 / " +
                "The cloud service returned an invalid Last-Modified value; sync was stopped.",
            errorCode = "SYNC_REMOTE_VALIDATION",
        )
    }
    return runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.takeIf { it >= 0L }
}

private fun requireStrongRemoteVersion(version: String): String {
    val strong = parseStrongEntityTag(version)
    if (strong == null) {
        throw CloudSyncException(
            "云端版本不是单个合法强 ETag，已停止写入。 / " +
                "The remote version is not one valid strong ETag; the write was stopped.",
            errorCode = "SYNC_REMOTE_VALIDATION",
        )
    }
    return strong
}

private fun MutableMap<String, String>.addReadConditionHeaders(version: String) {
    put("If-Match", requireStrongRemoteVersion(version))
}

private suspend fun verifyFallbackWrite(
    storageName: String,
    bytes: ByteArray,
    expectedSha256: String,
    read: suspend (String, Long, String?) -> BlobRead?,
): BlobMetadata {
    val maxBytes = verificationReadLimit(bytes)
    val first = read(storageName, maxBytes, null) ?: throw remoteVersionConflict()
    if (first.bytes.size != bytes.size || sha256(first.bytes) != expectedSha256) {
        throw remoteVersionConflict()
    }
    return first.metadata
}

private fun verificationReadLimit(bytes: ByteArray): Long =
    maxOf(1L, bytes.size.toLong())

private fun remoteVersionConflict(): CloudSyncConflictException = CloudSyncConflictException(
    "云端对象在安全校验期间发生变化，未继续覆盖；请重新同步。 / " +
        "The remote object changed during safety verification and was not overwritten; sync again.",
)

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
private val SUCCESSFUL_DAV_STATUS = Regex("(?:^|\\s)200(?:\\s|$)")
private val S3_ERROR_XML =
    Regex("""<(?:[A-Za-z0-9_-]+:)?Code>\s*([A-Za-z0-9._-]{1,128})\s*</(?:[A-Za-z0-9_-]+:)?Code>""")
private val S3_ERROR_CODE = Regex("[A-Za-z0-9._-]{1,128}")
private val HEX = "0123456789ABCDEF".toCharArray()
private val WEBDAV_PROPERTY_REQUEST = """
    <?xml version="1.0" encoding="utf-8"?>
    <D:propfind xmlns:D="DAV:">
      <D:prop>
        <D:getetag/>
      </D:prop>
    </D:propfind>
""".trimIndent().toByteArray(StandardCharsets.UTF_8)
private const val MAX_ETAG_CHARS = 4_096
private const val MAX_S3_ETAG_CANDIDATES = 8
private const val MAX_LAST_MODIFIED_CHARS = 128
private const val MAX_ERROR_BYTES = 64L * 1024
private const val MAX_DAV_PROPERTIES_BYTES = 64L * 1024
private const val MAX_DAV_HREF_CHARS = 4_096
