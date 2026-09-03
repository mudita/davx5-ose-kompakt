/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.network.KompaktGrantedServices
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktStartSyncUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Drives the Kompakt re-authorization flow for an existing account (the re-auth target) as an explicit
 * [ReauthState] the hosting screen renders.
 *
 * After OAuth completes ([apply]):
 *  - **Same account** — the fresh OAuth [net.openid.appauth.AuthState] is applied **in place** (via
 *    [KompaktAccountSettings.updateAuthState]), preserving the account and its pending local changes, and a sync
 *    is enqueued → [ReauthState.Refreshed]. The account is never unlinked on this path. If the
 *    re-authorization granted neither the Calendar nor the Contacts scope, the token is **not** applied
 *    and the flow reports [ReauthState.Failed] instead.
 *  - **Different account** — this model creates nothing; it moves to [ReauthState.SwitchingToNewAccount]
 *    so the screen can link the new account via the normal `KompaktLoginScreen` pipeline. Once that
 *    succeeds the screen calls [completeSwitch], which removes the old account
 *    ([ReauthState.RemovingOldAccount]) and finishes ([ReauthState.Done]).
 */
@HiltViewModel
class KompaktReauthModel @Inject constructor(
    private val kompaktAccountSettings: KompaktAccountSettings,
    private val startSyncUseCase: KompaktStartSyncUseCase,
    private val accountRepository: AccountRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger
) : ViewModel() {

    sealed interface ReauthState {
        /** Running the Google OAuth flow — the initial state, before the result is known. */
        data object Authenticating : ReauthState

        /** Same account re-authorized: token refreshed in place; the screen may close. */
        data object Refreshed : ReauthState

        /**
         * Re-authorization completed, but the granted authorization carries neither the Calendar nor the
         * Contacts scope (the user continued without granting either), so the account still can't sync.
         */
        data object Failed : ReauthState

        /** A different Google account was authorized: the screen links it via the normal pipeline. */
        data class SwitchingToNewAccount(val loginInfo: LoginInfo) : ReauthState

        /** The new account is linked; removing the old one. */
        data object RemovingOldAccount : ReauthState

        /**
         * Switch finished; the screen may close. [switched] is `true` only when the old account was
         * actually removed (a clean switch → the caller reports success and shows "Account linked");
         * `false` when its removal failed, so the caller must not report the switch as a success.
         */
        data class Done(val switched: Boolean) : ReauthState
    }

    private val _state = MutableStateFlow<ReauthState>(ReauthState.Authenticating)
    val state: StateFlow<ReauthState> = _state.asStateFlow()

    /**
     * Classifies the completed OAuth [loginInfo] against the re-auth target [account]: same identity →
     * refresh in place ([ReauthState.Refreshed]); different identity → [ReauthState.SwitchingToNewAccount]
     * (leaving [account] untouched here).
     */
    fun apply(account: Account, loginInfo: LoginInfo) {
        viewModelScope.launch(ioDispatcher) {
            val authState = loginInfo.credentials?.authState
            val email = loginInfo.suggestedAccountName
            when {
                authState == null -> {
                    logger.warning("Re-auth produced no auth state; not applying")
                    _state.value = ReauthState.Refreshed
                }

                email != null && !email.equals(account.name, ignoreCase = true) ->
                    _state.value = ReauthState.SwitchingToNewAccount(loginInfo)

                KompaktGrantedServices.fromAuthState(authState).isEmpty() -> {
                    logger.warning("Re-authorization granted neither Calendar nor Contacts; not applying")
                    _state.value = ReauthState.Failed
                }

                else -> {
                    try {
                        kompaktAccountSettings.updateAuthState(account, authState)
                        // Clearing the flag is what publishes the change, via KompaktAuthStateReplicator.
                        kompaktAccountSettings.setReauthNeeded(account, false)
                        startSyncUseCase(account)
                    } catch (e: Exception) {
                        logger.log(Level.WARNING, "Couldn't store re-authorized credentials for $account", e)
                    }
                    _state.value = ReauthState.Refreshed
                }
            }
        }
    }

    /**
     * Retries after a [ReauthState.Failed] result: restarts the OAuth step so the user can re-login/grant necessary scopes
     */
    fun retry() {
        _state.value = ReauthState.Authenticating
    }

    /**
     * Removes the old [oldAccount] once the new account is linked, then moves to [ReauthState.Done].
     * Runs the delete in [viewModelScope] (not the caller's composition scope) so it isn't cancelled if
     * the screen leaves composition mid-delete.
     */
    fun completeSwitch(oldAccount: Account) {
        _state.value = ReauthState.RemovingOldAccount
        viewModelScope.launch(ioDispatcher) {
            val removed = accountRepository.delete(oldAccount.name)
            if (!removed)
                // both accounts now remain; don't report a clean switch (see [ReauthState.Done.switched])
                logger.severe("Couldn't unlink old account ${oldAccount.name} after switch; both accounts remain")
            _state.value = ReauthState.Done(switched = removed)
        }
    }
}
