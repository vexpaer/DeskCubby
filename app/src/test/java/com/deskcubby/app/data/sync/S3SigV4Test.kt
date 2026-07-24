package com.deskcubby.app.data.sync

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S3SigV4Test {
    @Test
    fun `matches the official AWS S3 GET object signature`() {
        val signer = officialExampleSigner()

        val signed = signer.sign(
            method = "GET",
            uri = URI("https://examplebucket.s3.amazonaws.com/test.txt"),
            headers = mapOf("Range" to "bytes=0-9"),
        )

        assertEquals(
            "7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972",
            signed.canonicalRequestHash,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 " +
                "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date," +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed.headers["Authorization"],
        )
    }

    @Test
    fun `matches the official AWS S3 list objects query signature`() {
        val signer = officialExampleSigner()

        val signed = signer.sign(
            method = "GET",
            uri = URI("https://examplebucket.s3.amazonaws.com/?prefix=J&max-keys=2"),
        )

        assertEquals(
            "df57d21db20da04d7fa30298dd4488ba3a2b47ca3a489c74750e0f1e7df1b9b7",
            signed.canonicalRequestHash,
        )
        assertTrue(
            signed.headers.getValue("Authorization").endsWith(
                "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            ),
        )
    }

    @Test
    fun `canonicalizes S3 paths and duplicate query parameters without path normalization`() {
        val uri = URI(
            "https://storage.example.test:9443/a//b/../snow-%E9%9B%AA/a%2Fb" +
                "?z=last&space=a%20b&plus=a+b&dup=2&dup=1&slash=%2f&empty",
        )

        assertEquals(
            "/a//b/../snow-%E9%9B%AA/a%2Fb",
            S3SigV4.canonicalUri(uri),
        )
        assertEquals(
            "dup=1&dup=2&empty=&plus=a%2Bb&slash=%2F&space=a%20b&z=last",
            S3SigV4.canonicalQuery(uri),
        )
    }

    @Test
    fun `hashes payload and canonicalizes headers`() {
        val signer = S3SigV4(
            accessKeyId = "key-id",
            secretAccessKey = "secret-value",
            region = "us-east-1",
            clock = FIXED_CLOCK,
        )
        val payload = "Welcome to Amazon S3.".toByteArray(StandardCharsets.UTF_8)

        val signed = signer.sign(
            method = "put",
            uri = URI("https://EXAMPLE.test:443/test\$file.text"),
            headers = linkedMapOf(
                " Content-Type " to "  text/plain \t;  charset=utf-8 ",
                "X-Custom" to " first  value ",
                "x-custom" to "\tsecond\r\n value ",
                "Host" to "incorrect.example",
                "Authorization" to "must-be-replaced",
            ),
            payload = payload,
        )

        assertEquals(
            "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072",
            signed.payloadSha256,
        )
        assertEquals("example.test", signed.headers["host"])
        assertEquals("text/plain ; charset=utf-8", signed.headers["content-type"])
        assertEquals("first value,second value", signed.headers["x-custom"])
        assertEquals(
            "content-type;host;x-amz-content-sha256;x-amz-date;x-custom",
            signed.signedHeaders,
        )
        assertFalse(signed.headers.getValue("Authorization").contains("must-be-replaced"))
    }

    @Test
    fun `adds and signs a session token`() {
        val signer = S3SigV4(
            accessKeyId = "temporary-key",
            secretAccessKey = "temporary-secret",
            region = "auto",
            sessionToken = "temporary-session-token",
            clock = FIXED_CLOCK,
        )

        val signed = signer.sign(
            method = "HEAD",
            uri = URI("http://127.0.0.1:9000/bucket/object"),
            payloadSha256 = S3SigV4.UNSIGNED_PAYLOAD,
        )

        assertEquals("127.0.0.1:9000", signed.headers["host"])
        assertEquals("temporary-session-token", signed.headers["x-amz-security-token"])
        assertEquals(S3SigV4.UNSIGNED_PAYLOAD, signed.headers["x-amz-content-sha256"])
        assertTrue(signed.signedHeaders.contains("x-amz-security-token"))
        assertTrue(
            signed.headers.getValue("Authorization")
                .contains("/20130524/auto/s3/aws4_request"),
        )
    }

    @Test
    fun `rejects malformed precomputed payload hashes without revealing credentials`() {
        val secret = "do-not-leak-this-secret"
        val signer = S3SigV4(
            accessKeyId = "key-id",
            secretAccessKey = secret,
            region = "us-east-1",
            clock = FIXED_CLOCK,
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            signer.sign(
                method = "PUT",
                uri = URI("https://example.test/object"),
                payloadSha256 = "not-a-sha256",
            )
        }

        assertFalse(signer.toString().contains(secret))
        assertFalse(failure.message.orEmpty().contains(secret))
    }

    private fun officialExampleSigner() = S3SigV4(
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = "us-east-1",
        clock = FIXED_CLOCK,
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2013-05-24T00:00:00Z"),
            ZoneOffset.UTC,
        )
    }
}
