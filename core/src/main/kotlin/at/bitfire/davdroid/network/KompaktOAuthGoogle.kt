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

    fun baseUri(googleAccount: String): URI =
        URI("https", "apidata.googleusercontent.com", "/caldav/v2/$googleAccount/user", null)

    private val serviceConfig = AuthorizationServiceConfiguration(
        "https://accounts.google.com/o/oauth2/v2/auth".toUri(),
        "https://oauth2.googleapis.com/token".toUri()
    )

    /**
     * @param scopes defaults to Calendar + Contacts + `openid`/`email` (a full new-link or re-auth
     * request). [KompaktAddConsentModel][at.bitfire.davdroid.ui.setup.KompaktAddConsentModel] passes a
     * single data scope here to request only the missing permission.
     * @param includeGrantedScopes sets Google's `include_granted_scopes` request parameter, so the
     * returned token's granted-scope set is the union of every scope previously granted to this user
     * for this OAuth client plus whatever this request grants — required whenever [scopes] is a subset,
     * so applying the result never looks like the other service's consent was revoked.
     */
    fun signIn(
        email: String?,
        customClientId: String?,
        scopes: Set<String> = setOf(SCOPE_CALENDAR, SCOPE_CONTACTS, "openid", "email"),
        includeGrantedScopes: Boolean = false
    ): AuthorizationRequest {
        logger.info("Google OAuth signing-key index: ${KompaktGoogleOAuthClients.clientIndex(context)}")
        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            customClientId ?: KompaktGoogleOAuthClients.clientId(context),
            ResponseTypeValues.CODE,
            oAuthIntegration.redirectUri
        )
            .setScopes(scopes)
            .setLoginHint(email)
        if (includeGrantedScopes)
            builder.setAdditionalParameters(mapOf("include_granted_scopes" to "true"))
        return builder.build()
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
