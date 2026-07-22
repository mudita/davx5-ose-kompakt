/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.logging.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * Lets another app on the device request a sync of the linked account(s).
 *
 * Invoked by an explicit broadcast with action [ACTION_REQUEST_SYNC] targeted at this app's package. The
 * receiver is exported but guarded by the signature-level permission `at.bitfire.davdroid.mudita.permission.TRIGGER_SYNC`
 * (declared in the app-ose manifest), so only apps signed with the same key may request a sync.
 *
 * The request is **throttled**: a sync is only enqueued if the last successful sync finished at least
 * [SYNC_THROTTLE_MS] ago (or there has never been a successful sync). Otherwise the broadcast is a no-op.
 *
 * See `docs/kompakt-integration.md` for the caller contract.
 */
@AndroidEntryPoint
class KompaktSyncRequestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REQUEST_SYNC = "at.bitfire.davdroid.mudita.action.REQUEST_SYNC"

        /** Minimum time that must elapse after the last successful sync before another request is honored. */
        val SYNC_THROTTLE_MS = 15.minutes.inWholeMilliseconds
    }

    @Inject
    lateinit var accountRepository: AccountRepository

    @Inject
    lateinit var syncWorkerManager: SyncWorkerManager

    @Inject
    lateinit var syncStatsRepository: DavSyncStatsRepository

    @Inject
    lateinit var initDefaults: KompaktInitDefaults

    private val logger = Logger.getLogger(javaClass.name)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REQUEST_SYNC)
            return

        // enqueueOneTime() does some blocking WorkManager calls → do the work off the main thread.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val lastSync = syncStatsRepository.getLastSyncTime()
                val sinceLastSync = lastSync?.let { System.currentTimeMillis() - it }
                if (sinceLastSync != null && sinceLastSync < SYNC_THROTTLE_MS) {
                    logger.info("Ignoring sync request: last successful sync was ${sinceLastSync} ms ago (< $SYNC_THROTTLE_MS ms)")
                    return@launch
                }

                for (account in accountRepository.getAll()) {
                    // A freshly linked account has no calendar selected for sync until the Kompakt init
                    // defaults run; if they haven't yet (e.g. the Linked Account screen wasn't open long
                    // enough after linking), select the primary calendar first so this sync actually
                    // syncs instead of no-op'ing over an empty selection (SHP-1070).
                    initDefaults.ensureApplied(account)
                    syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

}
