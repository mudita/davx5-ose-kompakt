/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.startup

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import at.bitfire.davdroid.BuildConfig
import at.bitfire.davdroid.di.qualifier.ApplicationScope
import at.bitfire.davdroid.di.qualifier.DefaultDispatcher
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.startup.StartupPlugin.Companion.PRIORITY_DEFAULT
import at.bitfire.davdroid.ui.KompaktAuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Kompakt: publishes the re-auth flag to other (same-signed) apps whenever it changes, so no writer
 * has to remember to announce it. See [KompaktAuthState] for the contract.
 *
 * A transition written before this starts collecting is not published. That stays within the
 * contract, which makes [at.bitfire.davdroid.ui.KompaktAuthStateProvider] the source of truth and
 * the broadcast a convenience, so a consumer that missed one still reads the state on its next query.
 */
class KompaktAuthStateReplicator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val kompaktAccountSettings: KompaktAccountSettings,
    @ApplicationScope private val scope: CoroutineScope,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : StartupPlugin {

    // Collecting in onAppCreateAsync would never return, and CoreApp awaits each plugin's async phase
    // in turn, so whichever plugin sorted after this one would never run at all.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onAppCreate() {
        scope.launch {
            accountRepository.getAllFlow()
                // OnAccountsUpdateListener fires for every account on the device, and rebuilding the
                // merge below drops any change that arrives while it is being rebuilt.
                .distinctUntilChanged()
                .flatMapLatest { accounts ->
                    accounts
                        .map { account ->
                            // emitInitial would announce a change once per subscription — on every app
                            // start, and again whenever the accounts set rebuilds the merge.
                            kompaktAccountSettings.observeReauthNeeded(account, emitInitial = false)
                                .map { account to it }
                        }
                        .merge()
                }
                .flowOn(defaultDispatcher)
                .collect { (account, needsReauth) ->
                    publish(account, needsReauth)
                }
        }
    }

    override fun priority() = PRIORITY_DEFAULT

    override suspend fun onAppCreateAsync() {
    }

    override fun priorityAsync() = PRIORITY_DEFAULT

    private fun publish(account: Account, needsReauth: Boolean) {
        if (BuildConfig.DEBUG)
            Log.i(KompaktAuthState.ACTION_AUTH_STATE_CHANGED, "Notifying auth state change: account=${account.name}, needsReauth=$needsReauth")

        val intent = Intent(KompaktAuthState.ACTION_AUTH_STATE_CHANGED).apply {
            putExtra(KompaktAuthState.EXTRA_ACCOUNT_NAME, account.name)
            putExtra(KompaktAuthState.EXTRA_NEEDS_REAUTH, if (needsReauth) 1 else 0)
        }
        context.sendBroadcast(intent, KompaktAuthState.PERMISSION)

        context.contentResolver.notifyChange(KompaktAuthState.CONTENT_URI, null)
    }

}
