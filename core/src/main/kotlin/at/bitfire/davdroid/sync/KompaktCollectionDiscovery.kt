/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import kotlinx.coroutines.withTimeoutOrNull
import java.util.logging.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fetches the collections of a service's row from Google.
 *
 * The fetch authenticates with the token the account holds when it runs, so it must not start before
 * the token that grants the service is stored: Google refuses one on the old token, and upstream's
 * refresh treats that refusal as success, leaving the row empty.
 */
class KompaktCollectionDiscovery @Inject constructor(
    private val serviceRepository: DavServiceRepository,
    private val collectionRepository: DavCollectionRepository,
    private val syncWork: KompaktSyncWork,
    private val logger: Logger
) {

    companion object {
        private const val DISCOVERY_WAIT_MS = 30_000L
    }

    /**
     * Fetches the collections of a row that has none, and is `true` only when it just did.
     *
     * Nothing refreshes a row after it is created, so an empty one stays empty until this runs. With
     * [await] `false` the fetch is only started.
     */
    suspend fun ensure(account: Account, service: KompaktSyncService, await: Boolean = true): Boolean {
        val row = serviceRepository.getByAccountAndType(account.name, service.serviceType) ?: return false
        if (collectionRepository.getByService(row.id).isNotEmpty())
            return false

        logger.info("$service of $account has no collections; fetching them")
        if (!await) {
            syncWork.enqueueRefreshCollections(row.id)
            return false
        }
        val refreshed = withTimeoutOrNull(DISCOVERY_WAIT_MS.milliseconds) {
            syncWork.refreshCollectionsAndWait(row.id)
        } == true
        // A refresh Google refused still succeeds, having found nothing.
        return refreshed && collectionRepository.getByService(row.id).isNotEmpty()
    }

}
