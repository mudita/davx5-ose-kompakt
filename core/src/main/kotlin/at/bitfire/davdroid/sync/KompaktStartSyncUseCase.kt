/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import java.util.UUID
import javax.inject.Inject

class KompaktStartSyncUseCase @Inject constructor(
    private val initDefaults: KompaktInitDefaults,
    private val eligibility: KompaktSyncEligibility,
    private val syncWork: KompaktSyncWork
) {

    // Defaults first: that is what writes the interval eligibility then reads, so on a fresh account
    // the order decides between syncing and silently doing nothing.
    suspend operator fun invoke(
        account: Account,
        services: Collection<KompaktSyncService> = KompaktSyncService.entries,
        awaitDiscovery: Boolean = true
    ): Map<KompaktSyncService, UUID?> {
        for (service in services)
            initDefaults.ensureApplied(account, service, awaitDiscovery)

        val requested = services.toSet()
        return eligibility.enabledServices(account)
            .filter { it in requested }
            .associateWith { service -> syncWork.enqueue(account, service, manual = true) }
    }

}
