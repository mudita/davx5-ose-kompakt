/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import android.content.Context
import at.bitfire.davdroid.db.Service
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
class KompaktGrantedServicesTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val oAuthGoogle = KompaktOAuthGoogle(context, OAuthIntegration(context), Logger.getGlobal())

    @Test
    fun `both data scopes granted`() {
        assertEquals(
            setOf(Service.TYPE_CALDAV, Service.TYPE_CARDDAV),
            KompaktGrantedServices.fromScopes(
                setOf(
                    KompaktOAuthGoogle.SCOPE_CALENDAR,
                    KompaktOAuthGoogle.SCOPE_CONTACTS,
                    "openid",
                    "email"
                )
            )
        )
    }

    @Test
    fun `only calendar granted`() {
        assertEquals(
            setOf(Service.TYPE_CALDAV),
            KompaktGrantedServices.fromScopes(setOf(KompaktOAuthGoogle.SCOPE_CALENDAR, "openid", "email"))
        )
    }

    @Test
    fun `only contacts granted`() {
        assertEquals(
            setOf(Service.TYPE_CARDDAV),
            KompaktGrantedServices.fromScopes(setOf(KompaktOAuthGoogle.SCOPE_CONTACTS, "openid", "email"))
        )
    }

    @Test
    fun `sign-in scopes alone grant no service`() {
        assertEquals(
            emptySet<String>(),
            KompaktGrantedServices.fromScopes(setOf("openid", "email"))
        )
    }

    @Test
    fun `absent scope set grants no service`() {
        assertEquals(emptySet<String>(), KompaktGrantedServices.fromScopes(null))
    }

    @Test
    fun `fromAuthState resolves granted services across a real AuthState JSON round trip`() {
        // pass a customClientId so the request does not depend on the app's signing certificate
        val request = oAuthGoogle.signIn(email = null, customClientId = "test.apps.googleusercontent.com")
        val response = AuthorizationResponse.Builder(request)
            .setScopes(KompaktOAuthGoogle.SCOPE_CALENDAR, KompaktOAuthGoogle.SCOPE_CONTACTS)
            .build()
        val authState = AuthState(response, null)

        val roundTripped = AuthState.jsonDeserialize(authState.jsonSerializeString())

        assertEquals(
            setOf(Service.TYPE_CALDAV, Service.TYPE_CARDDAV),
            KompaktGrantedServices.fromAuthState(roundTripped)
        )
    }

}
