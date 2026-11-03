/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.log

import net.openid.appauth.AuthorizationException
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktSafeLogTest {

    private val secret = "1//refresh-SECRET"

    /** What AppAuth does when a token response is truncated: wraps the JSONException as cause. */
    private fun truncatedTokenResponseException(): AuthorizationException {
        val jsonException = try {
            JSONObject("""{"access_token":"ya29.x","refresh_token":"$secret""")
            throw AssertionError("expected JSONException")
        } catch (e: JSONException) {
            e
        }
        return AuthorizationException.fromTemplate(
            AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR,
            jsonException
        )
    }

    @Test
    fun `JSONException message contains the raw response`() {
        // documents the threat this helper guards against
        assertTrue(truncatedTokenResponseException().cause!!.message!!.contains(secret))
    }

    @Test
    fun `AuthorizationException with JSON cause doesn't leak the response`() {
        val ex = truncatedTokenResponseException()
        val description = ex.kompaktSafeDescription()

        assertFalse(description.contains(secret))
        assertEquals(
            "AuthorizationException(type=${ex.type}, code=${ex.code}, error=${ex.error}) caused by org.json.JSONException",
            description
        )
    }

    @Test
    fun `OAuth error code is kept`() {
        val ex = AuthorizationException.TokenRequestErrors.INVALID_GRANT
        assertTrue(ex.kompaktSafeDescription().contains("error=invalid_grant"))
    }

    @Test
    fun `non-standard OAuth error from server is not included`() {
        val ex = AuthorizationException.fromOAuthTemplate(
            AuthorizationException.TokenRequestErrors.OTHER, "leak:$secret", "description $secret", null
        )
        val description = ex.kompaktSafeDescription()

        assertFalse(description.contains(secret))
        assertTrue(description.contains("error=(non-standard)"))
    }

    @Test
    fun `HTTP log line - user info and query are removed`() {
        // OkHttp percent-encodes the user info, so a "/" in the password appears as %2F
        val encodedSecret = secret.replace("/", "%2F")
        assertEquals(
            "--> GET https://***@dav.example.com/cal/?*** (12ms)",
            kompaktSanitizeHttpLogLine("--> GET https://alice:$encodedSecret@dav.example.com/cal/?token=$encodedSecret&x=1 (12ms)")
        )
        assertEquals(
            "Location: http://host:8080/path?***",
            kompaktSanitizeHttpLogLine("Location: http://host:8080/path?code=$secret#frag")
        )
    }

    @Test
    fun `HTTP log line - relative, protocol-relative and fragment URLs are sanitized`() {
        val cases = mapOf(
            "Location: /callback?token=$secret" to "Location: /callback?***",
            "Location: //host/callback?token=$secret" to "Location: //host/callback?***",
            "Location: //alice:pw@host/callback" to "Location: //***@host/callback",
            "Location: https://host/callback#access_token=$secret" to "Location: https://host/callback#***",
            "<d:href>/dav/cal?token=$secret</d:href>" to "<d:href>/dav/cal?***</d:href>",
            "Link: <https://host/next?page=$secret>; rel=\"next\"" to "Link: <https://host/next?***>; rel=\"next\""
        )
        for ((input, expected) in cases) {
            val sanitized = kompaktSanitizeHttpLogLine(input)
            assertEquals(expected, sanitized)
            assertEquals("idempotent", sanitized, kompaktSanitizeHttpLogLine(sanitized))
        }
    }

    @Test
    fun `filter sanitizes message, parameters and exception chain`() {
        val record = java.util.logging.LogRecord(java.util.logging.Level.INFO, "Checking user-given URL: https://alice:pw@host/dav?token=$secret")
        record.parameters = arrayOf(java.net.URI("https://host/dav#$secret"), null)
        record.thrown = RuntimeException(
            "wrapper",
            java.net.URISyntaxException("https://alice:pw@host/x?token=$secret", "Illegal character")
        )

        assertTrue(KompaktSanitizingLogFilter.isLoggable(record))

        assertEquals("Checking user-given URL: https://***@host/dav?***", record.message)
        assertEquals("https://host/dav#***", record.parameters[0])
        assertEquals(null, record.parameters[1])

        val trace = android.util.Log.getStackTraceString(record.thrown)
        assertFalse(trace.contains(secret))
        assertFalse(trace.contains("alice:pw"))
        assertTrue(trace.startsWith("java.lang.RuntimeException: wrapper"))
        assertTrue(trace.contains("Caused by: java.net.URISyntaxException: Illegal character: https://***@host/x?***"))
    }

    @Test
    fun `HTTP log line - plain URLs and other lines are unchanged`() {
        val lines = listOf(
            "<-- 207 Multi-Status https://apidata.googleusercontent.com/caldav/v2/a%40b.com/events/ (85ms)",
            "Content-Type: application/xml; charset=utf-8",
            "<d:href>/caldav/v2/user@example.com/events/</d:href>",
            "mailto:user@example.com"
        )
        for (line in lines)
            assertEquals(line, kompaktSanitizeHttpLogLine(line))
    }

    @Test
    fun `plain exception message is not included`() {
        val description = RuntimeException("token=$secret", IllegalStateException(secret)).kompaktSafeDescription()

        assertFalse(description.contains(secret))
        assertEquals("java.lang.RuntimeException caused by java.lang.IllegalStateException", description)
    }

}
