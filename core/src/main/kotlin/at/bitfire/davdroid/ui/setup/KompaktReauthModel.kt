/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
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
 * Applies a freshly obtained Google OAuth token to an EXISTING account, **in place**.
 *
 * Unlike deleting + re-creating the account, this preserves the account, its calendars and any local
 * changes that are still pending upload (dirty/deleted rows): only the stored OAuth [net.openid.appauth.AuthState]
 * is replaced (via [AccountSettings.updateAuthState]), then a sync is enqueued so the pending changes upload.
 */
@HiltViewModel
class KompaktReauthModel @Inject constructor(
    private val accountSettingsFactory: AccountSettings.Factory,
    private val syncWorkerManager: SyncWorkerManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger
) : ViewModel() {

    private val _done = MutableStateFlow(false)
    /** `true` once the re-auth result has been processed and the screen may finish. */
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /**
     * Persists the new credentials to [account] (if they belong to the same Google identity) and
     * triggers a sync. Always sets [done] when finished so the hosting screen can close.
     */
    fun apply(account: Account, loginInfo: LoginInfo) {
        viewModelScope.launch(ioDispatcher) {
            val authState = loginInfo.credentials?.authState
            val email = loginInfo.suggestedAccountName
            when {
                authState == null ->
                    logger.warning("Re-auth produced no auth state; not applying")

                email != null && !email.equals(account.name, ignoreCase = true) ->
                    // a token for a different Google account would break this account – ignore it
                    logger.warning("Re-auth account mismatch ($email vs ${account.name}); not applying")

                else -> try {
                    accountSettingsFactory.create(account).updateAuthState(authState)
                    syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Couldn't store re-authorized credentials for $account", e)
                }
            }
            _done.value = true
        }
    }

}
