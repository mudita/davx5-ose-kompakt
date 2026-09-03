/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

/**
 * Guards the security-critical property that the Google OAuth authorization request uses PKCE (S256),
 * and the requested scope set (Calendar and Contacts, alongside `openid`/`email`).
 *
 * The Kompakt login runs in an embedded WebView and captures the redirect in-process, but the last line
 * of defence against a leaked authorization code is PKCE: without the `code_verifier` (which never leaves
 * the app) an intercepted code cannot be exchanged for tokens. If someone ever disables PKCE (e.g.
 * `AuthorizationRequest.Builder.setCodeVerifier(null)` in [KompaktOAuthGoogle.signIn]) this test fails.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktOAuthGoogleTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val oAuthGoogle = KompaktOAuthGoogle(context, OAuthIntegration(context), Logger.getGlobal())

    @Test
    fun `signIn request uses PKCE with S256`() {
        // pass a customClientId so the request does not depend on the app's signing certificate
        val request = oAuthGoogle.signIn(
            email = null,
            customClientId = "test.apps.googleusercontent.com"
        )

        assertNotNull("code_verifier must be set (PKCE)", request.codeVerifier)
        assertNotNull("code_challenge must be set (PKCE)", request.codeVerifierChallenge)
        assertEquals("S256", request.codeVerifierChallengeMethod)
    }

    @Test
    fun `every request asks for both data scopes and the sign-in scopes`() {
        // Asserted against literals, not the constants the set is built from — comparing it to
        // SCOPE_CONTACTS would still pass if Contacts were dropped, silently taking Contacts sync out
        // of every link. Equality also pins that nothing else creeps into the request.
        val request = oAuthGoogle.signIn(
            email = null,
            customClientId = "test.apps.googleusercontent.com"
        )

        assertEquals(
            setOf(
                "https://www.googleapis.com/auth/calendar",
                "https://www.googleapis.com/auth/carddav",
                "openid",
                "email"
            ),
            request.scopeSet
        )
    }



}
