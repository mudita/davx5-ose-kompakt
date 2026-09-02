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
            customClientId = "test.apps.googleusercontent.com",
            dataScopes = KompaktOAuthGoogle.LINK_DATA_SCOPES
        )

        assertNotNull("code_verifier must be set (PKCE)", request.codeVerifier)
        assertNotNull("code_challenge must be set (PKCE)", request.codeVerifierChallenge)
        assertEquals("S256", request.codeVerifierChallengeMethod)
    }

    @Test
    fun `a link asks for both data scopes`() {
        // The policy a first-time link and a re-authorization both use. Asserted against literals, not
        // against the constants it's built from — comparing it to SCOPE_CONTACTS would still pass if
        // Contacts were dropped, silently taking Contacts sync out of every new link.
        assertEquals(
            setOf(
                "https://www.googleapis.com/auth/calendar",
                "https://www.googleapis.com/auth/carddav"
            ),
            KompaktOAuthGoogle.LINK_DATA_SCOPES
        )
    }

    @Test
    fun `signIn adds the sign-in scopes and nothing else`() {
        // A caller can't forget openid/email, and can't accidentally regain a data scope it left out:
        // one data scope in, exactly that scope plus the sign-in scopes out.
        val request = oAuthGoogle.signIn(
            email = null,
            customClientId = "test.apps.googleusercontent.com",
            dataScopes = setOf(KompaktOAuthGoogle.SCOPE_CONTACTS)
        )

        assertEquals(
            setOf(KompaktOAuthGoogle.SCOPE_CONTACTS, "openid", "email"),
            request.scopeSet
        )
    }

    @Test
    fun `signIn with includeGrantedScopes sets Google's incremental-auth parameter`() {
        val request = oAuthGoogle.signIn(
            email = null,
            customClientId = "test.apps.googleusercontent.com",
            dataScopes = setOf(KompaktOAuthGoogle.SCOPE_CONTACTS),
            includeGrantedScopes = true
        )

        assertEquals("true", request.additionalParameters?.get("include_granted_scopes"))
    }

    @Test
    fun `signIn without includeGrantedScopes sets no incremental-auth parameter`() {
        val request = oAuthGoogle.signIn(
            email = null,
            customClientId = "test.apps.googleusercontent.com",
            dataScopes = KompaktOAuthGoogle.LINK_DATA_SCOPES
        )

        assertEquals(null, request.additionalParameters?.get("include_granted_scopes"))
    }

}
