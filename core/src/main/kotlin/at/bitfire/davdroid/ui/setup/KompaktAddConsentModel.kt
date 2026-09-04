/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.network.KompaktGrantedServices
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.network.OAuthIntegration
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.KompaktSyncService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Drives the Kompakt "add a missing Calendar/Contacts consent" flow for an already-linked [account]:
 * re-runs Google authorization, applies the grant **in place** on success, and creates the one
 * [at.bitfire.davdroid.db.Service] row [service] was missing. Every scope is requested, not just the
 * missing one — see [KompaktOAuthGoogle.signIn].
 *
 * Unlike [KompaktReauthModel], a different Google account authorizing here is never a switch — see
 * [classifyAddConsentResult] — and unlike a first-time link, discovery for the newly-granted service
 * runs silently in the background; there is no detect-resources UI for this flow.
 */
@HiltViewModel(assistedFactory = KompaktAddConsentModel.Factory::class)
class KompaktAddConsentModel @AssistedInject constructor(
    @Assisted private val account: Account,
    @Assisted private val service: KompaktSyncService,
    private val accountRepository: AccountRepository,
    private val authService: AuthorizationService,
    private val initDefaults: KompaktInitDefaults,
    private val kompaktAccountSettings: KompaktAccountSettings,
    private val oAuthGoogle: KompaktOAuthGoogle,
    private val oAuthIntegration: OAuthIntegration,
    private val resourceFinderFactory: DavResourceFinder.Factory,
    private val serviceRepository: DavServiceRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(account: Account, service: KompaktSyncService): KompaktAddConsentModel
    }

    sealed interface AddConsentState {
        /** Running the Google OAuth step — the initial state, before the result is known. */
        data object Authenticating : AddConsentState

        /** OAuth succeeded; writing the token and creating the missing service. */
        data object Applying : AddConsentState

        /** The missing consent is granted and applied; the screen may close. */
        data object Granted : AddConsentState

        /**
         * Authorization came back without the scope this flow asked for — nothing was granted to apply,
         * so no error surfaces and the screen just closes with the toggle still off. Cancelling the
         * WebView is not this: it's indistinguishable from any other authorization failure and reports
         * [Failed], the same as a link or a re-authorization does.
         */
        data object Denied : AddConsentState

        /**
         * Something the user can retry: a different Google account, a request or apply step that threw,
         * or discovery that found no server for the newly granted service.
         */
        data object Failed : AddConsentState
    }

    private val _state = MutableStateFlow<AddConsentState>(AddConsentState.Authenticating)
    val state: StateFlow<AddConsentState> = _state.asStateFlow()

    override fun onCleared() {
        authService.dispose()
    }

    fun authorizationContract() = KompaktOAuthWebViewActivity.Contract()

    fun signIn(): AuthorizationRequest =
        oAuthGoogle.signIn(
            email = account.name,
            customClientId = null
        )

    fun signInFailed() {
        _state.value = AddConsentState.Failed
    }

    fun authenticate(authResponse: AuthorizationResponse) {
        viewModelScope.launch(ioDispatcher) {
            _state.value = AddConsentState.Applying
            try {
                val authState = oAuthIntegration.authenticate(authService, authResponse)
                val email = authState.lastTokenResponse?.idToken?.let(KompaktOAuthGoogle::parseEmailFromIdToken)

                when (classifyAddConsentResult(account.name, service.serviceType, readCurrentGrantedServices(), email, authState)) {
                    is AddConsentOutcome.Granted -> apply(authState)
                    AddConsentOutcome.AccountMismatch -> {
                        logger.warning("Additional consent was granted for a different Google account; not applying")
                        _state.value = AddConsentState.Failed
                    }
                    AddConsentOutcome.NotGranted -> {
                        logger.warning("Additional authorization did not grant $service for $account")
                        _state.value = AddConsentState.Denied
                    }
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't apply additional consent for $account", e)
                _state.value = AddConsentState.Failed
            }
        }
    }

    private suspend fun apply(authState: AuthState) {
        val existingService = serviceRepository.getByAccountAndType(account.name, service.serviceType)

        val serviceId = if (existingService != null) {
            existingService.id
        } else {
            val discovered = discoverService(authState)
            if (discovered == null) {
                logger.warning("Discovery found no $service for $account; leaving the grant unrecorded so it can be retried")
                _state.value = AddConsentState.Failed
                return
            }
            accountRepository.addServiceBlocking(account.name, service, discovered)
        }

        // Through KompaktAccountSettings rather than AccountSettings directly: its change signal is what
        // moves the linked-account switches, and a write around it leaves them showing the old consent.
        kompaktAccountSettings.updateAuthState(account, authState)
        // The token just obtained carries every scope, so an account parked in the auth-error state is
        // usable again. This write is also what makes KompaktAuthStateReplicator announce the change to
        // the other apps on the device.
        kompaktAccountSettings.setReauthNeeded(account, false)

        // The same primitive every other entry point uses for selection and the Kompakt interval. For a
        // newly discovered service its collections are already persisted above, so this never waits on
        // NOT_READY — and unlike a bare interval write, it also selects a primary calendar when this call
        // is the one that grants Calendar consent, which nothing else in this flow does.
        initDefaults.maybeApply(account, service, serviceId)

        _state.value = AddConsentState.Granted
    }

    private suspend fun discoverService(authState: AuthState): DavResourceFinder.Configuration.ServiceInfo? {
        val credentials = Credentials(authState = authState)
        // Thread interruption is how DavResourceFinder notices cancellation; without runInterruptible,
        // cancelling this coroutine leaves the OkHttp calls running to their own timeouts.
        val config = runInterruptible {
            resourceFinderFactory
                .create(oAuthGoogle.baseUri(account.name), credentials)
                .findInitialConfiguration()
        }
        return when (service) {
            KompaktSyncService.CALENDAR -> config.calDAV
            KompaktSyncService.CONTACTS -> config.cardDAV
        }
    }

    private fun readCurrentGrantedServices(): Set<String> =
        KompaktGrantedServices.fromAuthState(kompaktAccountSettings.getAuthState(account))

    /** Retries after [AddConsentState.Failed]: restarts the OAuth step. */
    fun retry() {
        _state.value = AddConsentState.Authenticating
    }

}
