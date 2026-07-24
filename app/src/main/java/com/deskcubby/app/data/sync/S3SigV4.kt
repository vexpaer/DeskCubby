package com.deskcubby.app.data.sync

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs S3-compatible HTTP requests with AWS Signature Version 4.
 *
 * The signer keeps the secret access key private and does not include it in returned values,
 * exceptions, or [toString]. The returned headers can be applied directly to the HTTP request
 * whose method, URI, headers, and payload were supplied to [sign].
 */
class S3SigV4(
    accessKeyId: String,
    secretAccessKey: String,
    region: String,
    service: String = "s3",
    sessionToken: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val accessKeyId = accessKeyId.also {
        require(it.isNotBlank()) { "Access key ID is required" }
        require('/' !in it && ',' !in it) { "Access key ID contains an unsupported character" }
    }
    private val secretAccessKeyBytes = secretAccessKey.toByteArray(StandardCharsets.UTF_8).also {
        require(it.isNotEmpty()) { "Secret access key is required" }
    }
    private val region = normalizeScopePart(region, "Region")
    private val service = normalizeScopePart(service, "Service")
    private val sessionToken = sessionToken?.takeIf(String::isNotBlank)

    /**
     * Produces the SigV4 headers for a single request.
     *
     * [payloadSha256] may be supplied when the caller has already hashed a streaming payload.
     * Otherwise the hash is calculated from [payload]. The special S3 value
     * `UNSIGNED-PAYLOAD` is also accepted. Supplied `host`, `authorization`, `x-amz-date`,
     * `x-amz-content-sha256`, and (when configured) `x-amz-security-token` headers are replaced
     * with values controlled by this signer.
     */
    fun sign(
        method: String,
        uri: URI,
        headers: Map<String, String> = emptyMap(),
        payload: ByteArray = byteArrayOf(),
        payloadSha256: String? = null,
    ): SignedRequest {
        val canonicalMethod = normalizeMethod(method)
        val timestamp = AMZ_DATE_FORMATTER.format(clock.instant())
        val date = timestamp.substring(0, 8)
        val normalizedPayloadHash = normalizePayloadHash(payloadSha256, payload)
        val normalizedHeaders = normalizeHeaders(headers)

        normalizedHeaders["host"] = mutableListOf(canonicalHost(uri))
        normalizedHeaders["x-amz-date"] = mutableListOf(timestamp)
        normalizedHeaders["x-amz-content-sha256"] = mutableListOf(normalizedPayloadHash)
        if (sessionToken != null) {
            normalizedHeaders["x-amz-security-token"] = mutableListOf(sessionToken)
        }

        val flattenedHeaders = normalizedHeaders.mapValuesTo(linkedMapOf()) { (_, values) ->
            values.joinToString(",")
        }
        val signedHeaders = flattenedHeaders.keys.joinToString(";")
        val canonicalHeaders = flattenedHeaders.entries.joinToString(
            separator = "\n",
            postfix = "\n",
        ) { (name, value) -> "$name:$value" }
        val canonicalRequest = buildString {
            append(canonicalMethod)
            append('\n')
            append(canonicalUri(uri))
            append('\n')
            append(canonicalQuery(uri))
            append('\n')
            append(canonicalHeaders)
            append('\n')
            append(signedHeaders)
            append('\n')
            append(normalizedPayloadHash)
        }
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
        val credentialScope = "$date/$region/$service/$TERMINATOR"
        val stringToSign = "$ALGORITHM\n$timestamp\n$credentialScope\n$canonicalRequestHash"
        val signingKey = deriveSigningKey(date)
        val signature = try {
            hmacSha256(signingKey, stringToSign).toHex()
        } finally {
            signingKey.fill(0)
        }
        val authorization = "$ALGORITHM Credential=$accessKeyId/$credentialScope," +
            "SignedHeaders=$signedHeaders,Signature=$signature"

        val resultHeaders = LinkedHashMap<String, String>(flattenedHeaders.size + 1)
        resultHeaders.putAll(flattenedHeaders)
        resultHeaders["Authorization"] = authorization
        return SignedRequest(
            headers = Collections.unmodifiableMap(resultHeaders),
            payloadSha256 = normalizedPayloadHash,
            signedHeaders = signedHeaders,
            canonicalRequestHash = canonicalRequestHash,
            timestamp = timestamp,
        )
    }

    override fun toString(): String =
        "S3SigV4(region=$region, service=$service, hasSessionToken=${sessionToken != null})"

    private fun deriveSigningKey(date: String): ByteArray {
        var key = ByteArray(AWS4_PREFIX.size + secretAccessKeyBytes.size)
        AWS4_PREFIX.copyInto(key)
        secretAccessKeyBytes.copyInto(key, destinationOffset = AWS4_PREFIX.size)
        try {
            key = replaceKey(key, hmacSha256(key, date))
            key = replaceKey(key, hmacSha256(key, region))
            key = replaceKey(key, hmacSha256(key, service))
            key = replaceKey(key, hmacSha256(key, TERMINATOR))
            return key
        } catch (error: Throwable) {
            key.fill(0)
            throw error
        }
    }

    private fun replaceKey(previous: ByteArray, next: ByteArray): ByteArray {
        previous.fill(0)
        return next
    }

    private fun normalizeHeaders(headers: Map<String, String>): TreeMap<String, MutableList<String>> {
        val normalized = TreeMap<String, MutableList<String>>()
        headers.forEach { (rawName, rawValue) ->
            val name = rawName.trim().lowercase(Locale.ROOT)
            require(name.isNotEmpty() && name.all(::isHeaderNameCharacter)) {
                "Request contains an invalid header name"
            }
            if (name != "authorization") {
                normalized.getOrPut(name, ::mutableListOf).add(
                    rawValue.trim().replace(HEADER_WHITESPACE, " "),
                )
            }
        }
        return normalized
    }

    private fun normalizePayloadHash(payloadSha256: String?, payload: ByteArray): String {
        if (payloadSha256 == null) return sha256Hex(payload)
        if (payloadSha256 == UNSIGNED_PAYLOAD) return payloadSha256
        require(PAYLOAD_HASH.matches(payloadSha256)) {
            "Payload SHA-256 must be 64 hexadecimal characters or UNSIGNED-PAYLOAD"
        }
        return payloadSha256.lowercase(Locale.ROOT)
    }

    private fun deriveDefaultPort(scheme: String): Int? = when (scheme.lowercase(Locale.ROOT)) {
        "http" -> 80
        "https" -> 443
        else -> null
    }

    private fun canonicalHost(uri: URI): String {
        require(uri.isAbsolute) { "Request URI must be absolute" }
        require(uri.rawUserInfo == null) { "Request URI must not contain user information" }
        val scheme = uri.scheme ?: throw IllegalArgumentException("Request URI requires a scheme")
        val rawHost = uri.host ?: throw IllegalArgumentException("Request URI requires a valid host")
        val host = if (rawHost.indexOf(':') >= 0) {
            "[${rawHost.trim('[', ']').lowercase(Locale.ROOT)}]"
        } else {
            IDN.toASCII(rawHost).lowercase(Locale.ROOT)
        }
        val port = uri.port
        return if (port >= 0 && port != deriveDefaultPort(scheme)) "$host:$port" else host
    }

    private fun normalizeMethod(method: String): String {
        val normalized = method.trim().uppercase(Locale.ROOT)
        require(normalized.isNotEmpty() && normalized.all(::isHeaderNameCharacter)) {
            "HTTP method is invalid"
        }
        return normalized
    }

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    class SignedRequest internal constructor(
        val headers: Map<String, String>,
        val payloadSha256: String,
        val signedHeaders: String,
        val canonicalRequestHash: String,
        val timestamp: String,
    ) {
        override fun toString(): String =
            "S3SigV4.SignedRequest(timestamp=$timestamp, payloadSha256=$payloadSha256, " +
                "signedHeaders=$signedHeaders, canonicalRequestHash=$canonicalRequestHash, " +
                "headers=${headers.keys})"
    }

    companion object {
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val TERMINATOR = "aws4_request"
        private val AWS4_PREFIX = "AWS4".toByteArray(StandardCharsets.US_ASCII)
        private val HEADER_WHITESPACE = Regex("[\\t\\n\\r ]+")
        private val PAYLOAD_HASH = Regex("[0-9A-Fa-f]{64}")
        private val AMZ_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        private val HEX = "0123456789ABCDEF".toCharArray()

        fun sha256Hex(payload: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(payload).toHex()

        internal fun canonicalUri(uri: URI): String {
            val rawPath = uri.rawPath
            if (rawPath.isNullOrEmpty()) return "/"

            return buildString(rawPath.length) {
                var segmentStart = 0
                rawPath.forEachIndexed { index, character ->
                    if (character == '/') {
                        append(awsEncode(percentDecode(rawPath.substring(segmentStart, index))))
                        append('/')
                        segmentStart = index + 1
                    }
                }
                append(awsEncode(percentDecode(rawPath.substring(segmentStart))))
            }.ifEmpty { "/" }
        }

        internal fun canonicalQuery(uri: URI): String {
            val rawQuery = uri.rawQuery
            if (rawQuery.isNullOrEmpty()) return ""

            val parameters = mutableListOf<Pair<String, String>>()
            var parameterStart = 0
            while (parameterStart <= rawQuery.length) {
                val separator = rawQuery.indexOf('&', parameterStart).let {
                    if (it < 0) rawQuery.length else it
                }
                val parameter = rawQuery.substring(parameterStart, separator)
                val equals = parameter.indexOf('=')
                val rawName = if (equals < 0) parameter else parameter.substring(0, equals)
                val rawValue = if (equals < 0) "" else parameter.substring(equals + 1)
                parameters += awsEncode(percentDecode(rawName)) to
                    awsEncode(percentDecode(rawValue))
                if (separator == rawQuery.length) break
                parameterStart = separator + 1
            }
            return parameters.sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                .joinToString("&") { (name, value) -> "$name=$value" }
        }

        private fun normalizeScopePart(value: String, label: String): String {
            val normalized = value.trim().lowercase(Locale.ROOT)
            require(normalized.isNotEmpty() && '/' !in normalized) { "$label is invalid" }
            return normalized
        }

        private fun percentDecode(value: String): ByteArray {
            val output = ByteArrayOutputStream(value.length)
            var index = 0
            while (index < value.length) {
                if (
                    value[index] == '%' &&
                    index + 2 < value.length &&
                    value[index + 1].digitToIntOrNull(16) != null &&
                    value[index + 2].digitToIntOrNull(16) != null
                ) {
                    val high = checkNotNull(value[index + 1].digitToIntOrNull(16))
                    val low = checkNotNull(value[index + 2].digitToIntOrNull(16))
                    output.write((high shl 4) or low)
                    index += 3
                } else {
                    val codePoint = value.codePointAt(index)
                    val encoded = String(Character.toChars(codePoint))
                        .toByteArray(StandardCharsets.UTF_8)
                    output.write(encoded)
                    index += Character.charCount(codePoint)
                }
            }
            return output.toByteArray()
        }

        private fun awsEncode(value: ByteArray): String = buildString(value.size) {
            value.forEach { signedByte ->
                val byte = signedByte.toInt() and 0xff
                if (isUnreserved(byte)) {
                    append(byte.toChar())
                } else {
                    append('%')
                    append(HEX[byte ushr 4])
                    append(HEX[byte and 0x0f])
                }
            }
        }

        private fun isUnreserved(byte: Int): Boolean =
            byte in 'A'.code..'Z'.code ||
                byte in 'a'.code..'z'.code ||
                byte in '0'.code..'9'.code ||
                byte == '-'.code ||
                byte == '.'.code ||
                byte == '_'.code ||
                byte == '~'.code

        private fun isHeaderNameCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character in "!#$%&'*+-.^_`|~"

        private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }
}
