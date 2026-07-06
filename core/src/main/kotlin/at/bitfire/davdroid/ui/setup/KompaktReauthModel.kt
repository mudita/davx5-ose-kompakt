/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Handles the result of the re-authorization OAuth flow for an existing account.
 *
 * If the user re-authorized the **same** Google account, the fresh OAuth [net.openid.appauth.AuthState]
 * is applied **in place** (via [AccountSettings.updateAuthState]) — preserving the account, its calendars
 * and any local changes still pending upload (dirty/deleted rows) — then a sync is enqueued so those
 * changes upload.
 *
 * If the user signed in with a **different** Google account, [switchAccount] unlinks the old account and
 * links the new one instead.
 */
@HiltViewModel
class KompaktReauthModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val syncWorkerManager: SyncWorkerManager,
    private val accountRepository: AccountRepository,
    private val resourceFinderFactory: DavResourceFinder.Factory,
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
                    // the user signed in with a different Google account: switch accounts
                    switchAccount(oldAccount = account, loginInfo = loginInfo)

                else -> try {
                    accountSettingsFactory.create(account).updateAuthState(authState)
                    // token is valid again: clear the persistent "needs re-auth" flag
                    AccountManager.get(context).setUserData(account, AccountSettings.KEY_NEEDS_REAUTH, null)
                    syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Couldn't store re-authorized credentials for $account", e)
                }
            }
            _done.value = true
        }
    }

    /**
     * The user completed OAuth with a different Google account than [oldAccount]. Switch accounts:
     * detect the new account's CalDAV configuration, delete [oldAccount], then create the new account.
     * Creating the account auto-enqueues collection discovery and enables auto-sync (same as a normal
     * link), and the reactive accounts screen swaps to the new account on its own.
     *
     * Ordering is detect → delete → create: the old account is never torn down without a valid config
     * for the new one, keeping the single-account invariant at every step. On any failure the switch is
     * abandoned (logged); at worst the user lands on the empty "Link account" screen and can retry.
     */
    private suspend fun switchAccount(oldAccount: Account, loginInfo: LoginInfo) {
        val email = loginInfo.suggestedAccountName ?: return
        val baseUri = loginInfo.baseUri
        if (baseUri == null) {
            logger.warning("Re-auth with different account ($email) has no baseUri; keeping ${oldAccount.name}")
            return
        }
        try {
            val config = runInterruptible {
                resourceFinderFactory.create(baseUri, loginInfo.credentials).findInitialConfiguration()
            }
            val calDav = config.calDAV
            if (calDav == null) {
                logger.warning("Couldn't detect CalDAV config for $email; keeping ${oldAccount.name}")
                return
            }
            val accountName = calDav.emails.firstOrNull() ?: email

            accountRepository.delete(oldAccount.name)
            val created = accountRepository.createBlocking(
                accountName,
                loginInfo.credentials,
                config,
                loginInfo.suggestedGroupMethod,
                loginInfo.preconfigurationUrl
            )
            if (created == null)
                logger.warning("Switched away from ${oldAccount.name} but couldn't create $accountName")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't switch account ${oldAccount.name} → $email", e)
        }
    }

}
