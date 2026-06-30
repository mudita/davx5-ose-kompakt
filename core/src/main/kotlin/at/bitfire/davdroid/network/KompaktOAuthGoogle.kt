/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import androidx.core.net.toUri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.net.URI
import javax.inject.Inject

class KompaktOAuthGoogle @Inject constructor(
    private val oAuthIntegration: OAuthIntegration
) {

    private val SCOPES = arrayOf(
        "https://www.googleapis.com/auth/calendar",     // CalDAV
        "openid",
        "email"                                         // needed to extract email from ID token
    )

    fun baseUri(googleAccount: String): URI =
        URI("https", "apidata.googleusercontent.com", "/caldav/v2/$googleAccount/user", null)

    private val serviceConfig = AuthorizationServiceConfiguration(
        "https://accounts.google.com/o/oauth2/v2/auth".toUri(),
        "https://oauth2.googleapis.com/token".toUri()
    )

    fun signIn(email: String?, customClientId: String?): AuthorizationRequest =
        AuthorizationRequest.Builder(
            serviceConfig,
            customClientId ?: CLIENT_ID,
            ResponseTypeValues.CODE,
            oAuthIntegration.redirectUri
        )
            .setScopes(*SCOPES)
            .setLoginHint(email)
            .build()

    companion object {
        private const val CLIENT_ID = "1044477190035-8pja9fc38b3tq57ipqqqckpka61f0blr.apps.googleusercontent.com"
    }

}
