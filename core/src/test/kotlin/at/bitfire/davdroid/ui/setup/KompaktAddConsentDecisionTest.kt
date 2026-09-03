/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.Context
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.network.OAuthIntegration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktAddConsentDecisionTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val oAuthGoogle = KompaktOAuthGoogle(context, OAuthIntegration(context), Logger.getGlobal())

    private fun authStateGranting(vararg scopes: String): AuthState {
        val request = oAuthGoogle.signIn(
            email = null,
            customClientId = "test.apps.googleusercontent.com"
        )
        val response = AuthorizationResponse.Builder(request).setScopes(*scopes).build()
        return AuthState(response, null)
    }

    @Test
    fun `same account, requested scope granted`() {
        val outcome = classifyAddConsentResult(
            targetAccountName = "someone@gmail.com",
            requestedType = Service.TYPE_CARDDAV,
            previouslyGranted = emptySet(),
            grantedEmail = "someone@gmail.com",
            authState = authStateGranting(KompaktOAuthGoogle.SCOPE_CONTACTS, "openid", "email")
        )

        assertEquals(AddConsentOutcome.Granted(setOf(Service.TYPE_CARDDAV)), outcome)
    }

    @Test
    fun `same account, case-insensitive email still matches`() {
        val outcome = classifyAddConsentResult(
            targetAccountName = "Someone@Gmail.com",
            requestedType = Service.TYPE_CALDAV,
            previouslyGranted = emptySet(),
            grantedEmail = "someone@gmail.com",
            authState = authStateGranting(KompaktOAuthGoogle.SCOPE_CALENDAR, "openid", "email")
        )

        assertEquals(AddConsentOutcome.Granted(setOf(Service.TYPE_CALDAV)), outcome)
    }

    @Test
    fun `different account authorized is a mismatch, not applied`() {
        val outcome = classifyAddConsentResult(
            targetAccountName = "someone@gmail.com",
            requestedType = Service.TYPE_CARDDAV,
            previouslyGranted = emptySet(),
            grantedEmail = "someone.else@gmail.com",
            authState = authStateGranting(KompaktOAuthGoogle.SCOPE_CONTACTS, "openid", "email")
        )

        assertEquals(AddConsentOutcome.AccountMismatch, outcome)
    }

    @Test
    fun `no email in the response is treated as the same account`() {
        // Google's response doesn't always echo the id-token email back separately; absence isn't a mismatch
        val outcome = classifyAddConsentResult(
            targetAccountName = "someone@gmail.com",
            requestedType = Service.TYPE_CARDDAV,
            previouslyGranted = emptySet(),
            grantedEmail = null,
            authState = authStateGranting(KompaktOAuthGoogle.SCOPE_CONTACTS, "openid", "email")
        )

        assertEquals(AddConsentOutcome.Granted(setOf(Service.TYPE_CARDDAV)), outcome)
    }

    @Test
    fun `neither data scope granted is NotGranted`() {
        val outcome = classifyAddConsentResult(
            targetAccountName = "someone@gmail.com",
            requestedType = Service.TYPE_CARDDAV,
            previouslyGranted = emptySet(),
            grantedEmail = "someone@gmail.com",
            authState = authStateGranting("openid", "email")
        )

        assertEquals(AddConsentOutcome.NotGranted, outcome)
    }

    @Test
    fun `a non-empty grant that's missing the requested scope is NotGranted`() {
        // requested Contacts, but only Calendar came back — a wrong/partial grant must not read as success
        val outcome = classifyAddConsentResult(
            targetAccountName = "someone@gmail.com",
            requestedType = Service.TYPE_CARDDAV,
            previouslyGranted = emptySet(),
            grantedEmail = "someone@gmail.com",
            authState = authStateGranting(KompaktOAuthGoogle.SCOPE_CALENDAR, "openid", "email")
        )

        assertEquals(AddConsentOutcome.NotGranted, outcome)
    }

    @Test
    fun `a previously-granted service missing from the new grant is NotGranted`() {
        // Calendar was already granted; a token that comes back without its scope must not read as a
        // successful Contacts grant — consent is read from the persisted scope set, so it would
        // otherwise make Calendar look freshly unconsented.
        val outcome = classifyAddConsentResult(
            targetAccountName = "someone@gmail.com",
            requestedType = Service.TYPE_CARDDAV,
            previouslyGranted = setOf(Service.TYPE_CALDAV),
            grantedEmail = "someone@gmail.com",
            authState = authStateGranting(KompaktOAuthGoogle.SCOPE_CONTACTS, "openid", "email")
        )

        assertEquals(AddConsentOutcome.NotGranted, outcome)
    }

    @Test
    fun `requested scope granted alongside a previously-granted one is Granted`() {
        val outcome = classifyAddConsentResult(
            targetAccountName = "someone@gmail.com",
            requestedType = Service.TYPE_CARDDAV,
            previouslyGranted = setOf(Service.TYPE_CALDAV),
            grantedEmail = "someone@gmail.com",
            authState = authStateGranting(KompaktOAuthGoogle.SCOPE_CALENDAR, KompaktOAuthGoogle.SCOPE_CONTACTS, "openid", "email")
        )

        assertEquals(AddConsentOutcome.Granted(setOf(Service.TYPE_CALDAV, Service.TYPE_CARDDAV)), outcome)
    }

}
