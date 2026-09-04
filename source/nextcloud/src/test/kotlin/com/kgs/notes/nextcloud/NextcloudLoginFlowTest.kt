package com.kgs.notes.nextcloud

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class NextcloudLoginFlowTest {
    @Test
    fun `a stock HTTPS server starts Login Flow v2 without credentials`() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        try {
            val loginUrl = server.url("/login/v2/flow/browser-token")
            val pollUrl = server.url("/login/v2/poll")
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "poll": {"token": "one-time-token", "endpoint": "$pollUrl"},
                      "login": "$loginUrl"
                    }
                    """.trimIndent(),
                ),
            )
            val flow = HttpNextcloudLoginFlow(
                client = OkHttpClient.Builder()
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                    .build(),
            )

            val challenge = flow.begin(server.url("/cloud/").toString())

            assertEquals(loginUrl.toString(), challenge.loginUrl)
            assertEquals(pollUrl.toString(), challenge.pollUrl)
            assertEquals("one-time-token", challenge.token)
            assertEquals("/cloud/index.php/login/v2", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        } finally {
            server.shutdown()
        }
    }
}
