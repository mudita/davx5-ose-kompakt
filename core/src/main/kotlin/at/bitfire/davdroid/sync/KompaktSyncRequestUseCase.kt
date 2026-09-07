/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import java.util.logging.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

class KompaktSyncRequestUseCase @Inject constructor(
    private val accountRepository: AccountRepository,
    private val syncStatsRepository: DavSyncStatsRepository,
    private val startSync: KompaktStartSyncUseCase
) {

    companion object {
        /** Minimum time that must elapse after a service's own last successful sync before another request for it is honored. */
        val SYNC_THROTTLE_MS = 15.minutes.inWholeMilliseconds
    }

    private val logger = Logger.getLogger(javaClass.name)

    suspend operator fun invoke(requested: Collection<KompaktSyncService>) {
        val allowed = requested.filterNot { it.isThrottled() }
        if (allowed.isEmpty())
            return

        for (account in accountRepository.getAll())
            // No discovery wait: a receiver has no lifecycle to block on. A service whose toggle is
            // off, whose consent is missing or which is not configured is skipped, so this may
            // enqueue nothing at all.
            startSync(account, allowed, awaitDiscovery = false)
    }

    // Per service, so a calendar sync a minute ago cannot swallow a contacts request.
    private suspend fun KompaktSyncService.isThrottled(): Boolean {
        val lastSync = syncStatsRepository.getLastSyncTime(dataType) ?: return false
        val sinceLastSync = System.currentTimeMillis() - lastSync
        if (sinceLastSync >= SYNC_THROTTLE_MS)
            return false

        logger.info("Ignoring the $name sync request: its last successful sync was $sinceLastSync ms ago (< $SYNC_THROTTLE_MS ms)")
        return true
    }

}
