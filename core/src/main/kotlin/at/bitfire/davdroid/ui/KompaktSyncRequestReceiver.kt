/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.bitfire.davdroid.sync.KompaktSyncRequestUseCase
import at.bitfire.davdroid.sync.KompaktSyncService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Lets another app on the device request a sync of the linked account(s).
 *
 * Invoked by an explicit broadcast with action [ACTION_REQUEST_SYNC] targeted at this app's package. The
 * receiver is exported but guarded by the signature-level permission `at.bitfire.davdroid.mudita.permission.TRIGGER_SYNC`
 * (declared in the app-ose manifest), so only apps signed with the same key may request a sync.
 *
 * [EXTRA_SYNC_SERVICES] says which services to sync; a request that names none syncs calendar only. Each
 * requested service is throttled against its own last successful sync, so a request may enqueue nothing
 * at all.
 *
 * See `docs/app-integration.md` for the caller contract.
 */
@AndroidEntryPoint
class KompaktSyncRequestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REQUEST_SYNC = "at.bitfire.davdroid.mudita.action.REQUEST_SYNC"

        /** Optional `String[]` of lowercased [KompaktSyncService] names. Absent: calendar only. */
        const val EXTRA_SYNC_SERVICES = "sync_services"
    }

    @Inject
    lateinit var requestSync: KompaktSyncRequestUseCase

    private val logger = Logger.getLogger(javaClass.name)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REQUEST_SYNC)
            return

        // An unqualified request carries this broadcast's original scope: it served one integrator,
        // the calendar app, and calendar data was all that was in scope.
        val requested = intent.requestedServices() ?: listOf(KompaktSyncService.CALENDAR)

        // The use case does some blocking WorkManager calls → do the work off the main thread.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                requestSync(requested)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun Intent.requestedServices(): List<KompaktSyncService>? {
        val names = getStringArrayExtra(EXTRA_SYNC_SERVICES)

        val services = names?.associate { raw -> raw to KompaktSyncService.fromRequestName(raw) }

        val unknown = services?.filterValues { it == null }?.keys
        if (!unknown.isNullOrEmpty())
            logger.warning("Ignoring unknown $EXTRA_SYNC_SERVICES values: $unknown")

        return services?.values?.filterNotNull()?.distinct()
    }

}
