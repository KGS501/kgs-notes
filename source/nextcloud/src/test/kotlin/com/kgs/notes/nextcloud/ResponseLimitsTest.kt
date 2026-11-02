package com.kgs.notes.nextcloud

import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.buffer
import okio.Source
import okio.Timeout

/** A malicious or broken server cannot make the app buffer unbounded responses (#15). */
class ResponseLimitsTest {
    private val json = "application/json".toMediaType()

    /** Endless bytes produced on demand, so the test itself never allocates the payload. */
    private class Endless : Source {
        var produced = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val step = minOf(byteCount, 8192L)
            repeat(step.toInt()) { sink.writeByte(' '.code) }
            produced += step
            return step
        }
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    @Test
    fun `a body within the limit is read completely`() {
        val body = Buffer().writeUtf8("[]").asResponseBody(json, -1)
        assertEquals("[]", body.boundedString(16))
    }

    @Test
    fun `a stream without a length stops one step past the limit instead of exhausting memory`() {
        val endless = Endless()
        val body = endless.buffer().asResponseBody(json, -1)
        val failure = assertFailsWith<NextcloudConnectionException> { body.boundedString(64 * 1024) }
        assertEquals(NextcloudConnectionFailure.INVALID_RESPONSE, failure.failure)
        assert(endless.produced <= 64 * 1024 + 2 * 8192) { "read ${endless.produced} bytes" }
    }

    @Test
    fun `an announced oversized body is rejected before reading`() {
        val endless = Endless()
        val body = endless.buffer().asResponseBody(json, ResponseLimits.NOTES_RESPONSE_BYTES + 1)
        assertFailsWith<NextcloudConnectionException> { body.boundedString(ResponseLimits.NOTES_RESPONSE_BYTES) }
        assertEquals(0L, endless.produced)
    }

    @Test
    fun `a small gzip response expanding past the limit fails the listing as an invalid response`() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTrust.sslSocketFactory(), false); start() }
        try {
            val expanded = Buffer()
            GZIPOutputStream(expanded.outputStream()).use { zip ->
                zip.write('['.code)
                val chunk = ByteArray(1 shl 20) { ' '.code.toByte() }
                repeat((ResponseLimits.NOTES_RESPONSE_BYTES shr 20).toInt() + 1) { zip.write(chunk) }
                zip.write(']'.code)
            }
            assert(expanded.size < 1L shl 20) { "compressed payload is ${expanded.size} bytes" }
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(expanded))
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val api = HttpNextcloudNotesApi(NextcloudCredentials(server.url("/").toString(), "test-user", "test-password"), client)

            val failure = assertFailsWith<NextcloudConnectionException> { api.notes() }

            assertEquals(NextcloudConnectionFailure.INVALID_RESPONSE, failure.failure)
        } finally {
            server.shutdown()
        }
    }
}
