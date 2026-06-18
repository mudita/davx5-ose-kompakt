/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lets another app on the device trigger a **manual** sync of the linked account(s).
 *
 * Invoked by an explicit broadcast with action [ACTION_SYNC_NOW] targeted at this app's package. The
 * receiver is exported but guarded by the signature-level permission `com.davx5.ose.permission.TRIGGER_SYNC`
 * (declared in the app-ose manifest), so only apps signed with the same key may request a sync.
 *
 * See `docs/kompakt-integration.md` for the caller contract.
 */
@AndroidEntryPoint
class KompaktSyncRequestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SYNC_NOW = "com.davx5.ose.action.SYNC_NOW"
    }

    @Inject
    lateinit var accountRepository: AccountRepository

    @Inject
    lateinit var syncWorkerManager: SyncWorkerManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC_NOW)
            return

        // enqueueOneTime() does some blocking WorkManager calls → do the work off the main thread.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                for (account in accountRepository.getAll())
                    syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)
            } finally {
                pendingResult.finish()
            }
        }
    }

}
