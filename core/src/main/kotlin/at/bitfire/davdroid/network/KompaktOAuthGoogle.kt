/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.net.URI
import java.util.logging.Logger
import javax.inject.Inject

class KompaktOAuthGoogle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val oAuthIntegration: OAuthIntegration,
    private val logger: Logger
) {

    private val SCOPES = arrayOf(
        SCOPE_CALENDAR,                                 // CalDAV
        SCOPE_CONTACTS,                                 // CardDAV
        "openid",
        "email"                                         // needed to extract email from ID token
    )

    fun baseUri(googleAccount: String): URI =
        URI("https", "apidata.googleusercontent.com", "/caldav/v2/$googleAccount/user", null)

    private val serviceConfig = AuthorizationServiceConfiguration(
        "https://accounts.google.com/o/oauth2/v2/auth".toUri(),
        "https://oauth2.googleapis.com/token".toUri()
    )

    /**
     * Always asks for every scope. Asking only for a missing one would be pointless: Google's consent
     * screen already omits what this user has granted before, so a request for both shows just the one
     * that's new (verified on a Kompakt).
     */
    fun signIn(email: String?, customClientId: String?): AuthorizationRequest {
        logger.info("Google OAuth signing-key index: ${KompaktGoogleOAuthClients.clientIndex(context)}")
        return AuthorizationRequest.Builder(
            serviceConfig,
            customClientId ?: KompaktGoogleOAuthClients.clientId(context),
            ResponseTypeValues.CODE,
            oAuthIntegration.redirectUri
        )
            .setScopes(*SCOPES)
            .setLoginHint(email)
            .build()
    }

    companion object {
        const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"
        const val SCOPE_CONTACTS = "https://www.googleapis.com/auth/carddav"

        /** Extracts the `email` claim from an OIDC ID token's JWT payload, or `null` if absent/unparseable. */
        fun parseEmailFromIdToken(idToken: String): String? = try {
            val payloadBase64 = idToken.split(".").getOrNull(1) ?: return null
            val payloadBytes = android.util.Base64.decode(
                payloadBase64,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val payloadJson = org.json.JSONObject(String(payloadBytes, Charsets.UTF_8))
            payloadJson.optString("email").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
