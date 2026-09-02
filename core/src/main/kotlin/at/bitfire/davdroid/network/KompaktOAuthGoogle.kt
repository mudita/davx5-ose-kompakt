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
     * @param dataScopes which data this request asks access to. Callers state their own policy: a new
     * link or a re-authorization asks for both, adding a missing consent asks for just that one.
     * [SIGN_IN_SCOPES] is added on top of whatever is passed here.
     * @param includeGrantedScopes sets Google's `include_granted_scopes` request parameter, so the
     * returned token's granted-scope set is the union of every scope previously granted to this user
     * for this OAuth client plus whatever this request grants — required whenever [dataScopes] is a subset,
     * so applying the result never looks like the other service's consent was revoked.
     */
    fun signIn(
        email: String?,
        customClientId: String?,
        dataScopes: Set<String>,
        includeGrantedScopes: Boolean = false
    ): AuthorizationRequest {
        logger.info("Google OAuth signing-key index: ${KompaktGoogleOAuthClients.clientIndex(context)}")
        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            customClientId ?: KompaktGoogleOAuthClients.clientId(context),
            ResponseTypeValues.CODE,
            oAuthIntegration.redirectUri
        )
            .setScopes(dataScopes + SIGN_IN_SCOPES)
            .setLoginHint(email)
        if (includeGrantedScopes)
            builder.setAdditionalParameters(mapOf("include_granted_scopes" to "true"))
        return builder.build()
    }

    companion object {
        const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"
        const val SCOPE_CONTACTS = "https://www.googleapis.com/auth/carddav"

        /**
         * Identity, not data access: these grant nothing, but the ID token they produce is the only way
         * to learn the account's e-mail on a device with no Google account registered. [signIn] adds
         * them to every request, so no caller can leave them out.
         */
        private val SIGN_IN_SCOPES = setOf("openid", "email")

        /**
         * The data this app can sync — what a first-time link or a re-authorization asks for. Adding a
         * single missing consent deliberately asks for less, so it names its one scope instead.
         */
        val LINK_DATA_SCOPES = setOf(SCOPE_CALENDAR, SCOPE_CONTACTS)

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
