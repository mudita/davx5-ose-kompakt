/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import io.mockk.every
import io.mockk.mockk
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.logging.Logger
import javax.inject.Provider

class OAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Formats [millis] as an RFC 1123 HTTP `Date` header value (always GMT), as a real server would send. */
    private fun httpDate(millis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(millis))

    /** A client whose OAuthInterceptor has a cached, still-valid token (so no real AppAuth refresh happens). */
    private fun clientWithCachedToken(): OkHttpClient {
        val authState = mockk<AuthState>(relaxed = true) {
            every { isAuthorized } returns true
            every { accessToken } returns "cached-token"
            every { needsTokenRefresh } returns false
        }
        val interceptor = OAuthInterceptor(
            readAuthState = { authState },
            writeAuthState = { },
            authServiceProvider = Provider { mockk<AuthorizationService>(relaxed = true) },
            logger = Logger.getGlobal()
        )
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun executeRequest(client: OkHttpClient): Response =
        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

    @Test
    fun `401 with far-off server clock is reported as clock skew`() {
        // server clock 2 h behind the device clock → well over the 10-minute threshold
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Date", httpDate(System.currentTimeMillis() - 2 * 60 * 60 * 1000L))
        )
        try {
            executeRequest(clientWithCachedToken()).use { }
            fail("expected KompaktClockSkewException")
        } catch (_: KompaktClockSkewException) {
            // expected
        }
    }

    @Test
    fun `401 with correct server clock passes through as a normal auth failure`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Date", httpDate(System.currentTimeMillis()))
        )
        executeRequest(clientWithCachedToken()).use { response ->
            assertEquals(401, response.code)
        }
    }

    @Test
    fun `non-401 response passes through even with a skewed clock`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Date", httpDate(System.currentTimeMillis() - 2 * 60 * 60 * 1000L))
        )
        executeRequest(clientWithCachedToken()).use { response ->
            assertEquals(200, response.code)
        }
    }
}
