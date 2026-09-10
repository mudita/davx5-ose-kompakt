/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.ui.account.KompaktAccountProgressUseCase
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/** Why a sync request started nothing, or what it started. */
sealed interface KompaktSyncStart {
    data object NoStorage : KompaktSyncStart
    data object NoNetwork : KompaktSyncStart
    /** Nothing the caller asked for can sync: no consent, or switched off. */
    data object NoneEligible : KompaktSyncStart
    /** Everything eligible already had a run in flight, so this request added none. */
    data object AlreadySyncing : KompaktSyncStart
    data class Started(val runs: Map<KompaktSyncService, UUID>) : KompaktSyncStart
}

/**
 * Every precondition a sync request must pass, and the enqueue if it passes them — so "why did nothing
 * sync" has one answer with one home, whether the request came from the screen, another app, or a
 * finished re-authorization.
 *
 * The order is not arbitrary. Configuring a service needs the network, and the switch is only
 * trustworthy once configured, so connectivity has to be established before the authoritative answer
 * exists. What can be ruled out from local state alone is therefore ruled out first, and an account
 * that is already configured gets its real answer there — which is what stops a switched-off account
 * being told it has no internet.
 *
 * **Must not be called on the main thread.**
 */
class KompaktStartSyncUseCase @Inject constructor(
    private val initDefaults: KompaktInitDefaults,
    private val eligibility: KompaktSyncEligibility,
    private val provisioning: KompaktServiceProvisioning,
    private val storage: KompaktStorageAvailability,
    private val network: KompaktNetworkAvailability,
    private val syncWork: KompaktSyncWork,
    private val accountProgress: KompaktAccountProgressUseCase,
    private val logger: Logger
) {

    suspend operator fun invoke(
        account: Account,
        services: Collection<KompaktSyncService> = KompaktSyncService.entries,
        awaitDiscovery: Boolean = true
    ): KompaktSyncStart {
        val requested = services.toSet()
        val consented = eligibility.consented(account) intersect requested
        val switchedOn = eligibility.switchedOn(account) intersect consented
        val undecided = consented.filterNot { initDefaults.isApplied(account, it) }

        if (switchedOn.isEmpty() && undecided.isEmpty())
            return KompaktSyncStart.NoneEligible

        // A manual run carries no storage constraint and would only fail, and nothing may be enqueued
        // that cannot run: a request parked on an unmet constraint reads as a sync in progress for as
        // long as it waits.
        if (storage.isLow())
            return KompaktSyncStart.NoStorage
        if (!network.isAvailable(account))
            return KompaktSyncStart.NoNetwork

        val configured = (switchedOn + undecided).filter { service ->
            configure(account, service, awaitDiscovery)
        }

        // Asked again now that a default exists for everything configured, so an "off" here is the
        // user's choice rather than an unwritten one.
        val toSync = configured intersect eligibility.switchedOn(account)
        if (toSync.isEmpty())
            return KompaktSyncStart.NoneEligible

        val runs = toSync
            // No second job while one is in progress. This also covers a running *periodic* worker,
            // which the one-time unique name cannot see.
            .filterNot { service -> accountProgress(account, service.dataType).first() }
            .associateWith { service -> syncWork.enqueue(account, service, manual = true) }
            .mapNotNull { (service, id) -> id?.let { service to it } }
            .toMap()

        return if (runs.isEmpty())
            KompaktSyncStart.AlreadySyncing
        else
            KompaktSyncStart.Started(runs)
    }

    private suspend fun configure(
        account: Account,
        service: KompaktSyncService,
        awaitDiscovery: Boolean
    ): Boolean =
        try {
            provisioning.ensureRow(account, service).also { ready ->
                if (ready)
                    initDefaults.ensureApplied(account, service, awaitDiscovery)
                else
                    logger.warning("No $service for $account and none discoverable; not syncing it")
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't configure $service for $account", e)
            false
        }

}
