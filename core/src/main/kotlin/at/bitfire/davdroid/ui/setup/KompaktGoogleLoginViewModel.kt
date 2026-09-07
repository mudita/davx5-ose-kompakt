/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.AccountManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bitfire.davdroid.R
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.network.OAuthIntegration
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.synctools.util.trimToNull
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import java.util.logging.Level
import java.util.logging.Logger

@HiltViewModel(assistedFactory = KompaktGoogleLoginViewModel.Factory::class)
class KompaktGoogleLoginViewModel @AssistedInject constructor(
    @Assisted val initialLoginInfo: LoginInfo,
    private val authService: AuthorizationService,
    @ApplicationContext val context: Context,
    private val logger: Logger,
    private val oAuthGoogle: KompaktOAuthGoogle,
    private val oAuthIntegration: OAuthIntegration
): ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(loginInfo: LoginInfo): KompaktGoogleLoginViewModel
    }

    override fun onCleared() {
        authService.dispose()
    }


    data class UiState(
        val email: String = "",
        val customClientId: String = "",
        val error: String? = null,

        /** login info (set after successful login) */
        val result: LoginInfo? = null
    ) {
        val canContinue = email.isNotEmpty()
        val emailWithDomain = if (email.contains("@")) email else "$email@gmail.com"
    }

    var uiState by mutableStateOf(UiState())
        private set

    init {
        uiState = uiState.copy(
            email = initialLoginInfo.credentials?.username ?: findGoogleAccount() ?: "",
            error = null,
            result = null
        )
    }

    fun authorizationContract() = KompaktOAuthWebViewActivity.Contract()

    fun signIn() =
        oAuthGoogle.signIn(
            email = uiState.emailWithDomain,
            customClientId = uiState.customClientId.trimToNull()
        )

    fun signInFailed() {
        uiState = uiState.copy(error = context.getString(R.string.install_browser))
    }

    fun authenticate(authResponse: AuthorizationResponse) {
        viewModelScope.launch {
            try {
                val authState = oAuthIntegration.authenticate(authService, authResponse)
                val credentials = Credentials(authState = authState)

                // Extract email from ID token (returned because openid+email scopes are requested).
                // This is the only reliable way to get the email on Kompakt where the Android
                // AccountManager has no Google account registered.
                val email = authState.lastTokenResponse?.idToken
                    ?.let { KompaktOAuthGoogle.parseEmailFromIdToken(it) }
                    ?: uiState.emailWithDomain

                uiState = uiState.copy(
                    result = LoginInfo(
                        baseUri = oAuthGoogle.baseUri(email),
                        credentials = credentials,
                        suggestedAccountName = email
                    )
                )
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Google authentication failed", e)
                uiState = uiState.copy(error = e.message)
            }
        }
    }

    fun authCodeFailed() {
        uiState = uiState.copy(error = context.getString(R.string.login_oauth_couldnt_obtain_auth_code))
    }

    fun resetResult() {
        uiState = uiState.copy(result = null)
    }

    fun clearError() {
        uiState = uiState.copy(error = null)
    }

    private fun findGoogleAccount(): String? {
        val accountManager = AccountManager.get(context)
        return accountManager
            .getAccountsByType("com.google")
            .map { it.name }
            .firstOrNull()
    }

}
