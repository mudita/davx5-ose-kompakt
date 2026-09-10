/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.KompaktSyncOutcomeRepository
import at.bitfire.davdroid.ui.account.Reported
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/**
 * The stored outcome of one service's last sync, addressed by account rather than by service row id —
 * the one place that resolves the row, so neither the worker that writes nor the screen that reads has
 * to know how.
 */
class KompaktServiceSyncOutcome @Inject constructor(
    private val serviceRepository: DavServiceRepository,
    private val outcomes: KompaktSyncOutcomeRepository
) {

    /**
     * Records how the last sync attempt for [service] ended. A `null` [cause] means it succeeded.
     *
     * Does nothing when the service row is gone: an account unlinked during a sync has already
     * cascaded it away, and the insert would then fail the foreign key inside the worker.
     */
    suspend fun record(
        account: Account,
        service: KompaktSyncService,
        cause: KompaktSyncFailure?,
        manual: Boolean,
        detail: String? = null
    ) {
        val serviceRow = serviceRepository.getByAccountAndType(account.name, service.serviceType) ?: return
        outcomes.record(
            serviceId = serviceRow.id,
            dataType = service.dataType,
            succeeded = cause == null,
            cause = cause?.name,
            trigger = if (manual) TRIGGER_MANUAL else TRIGGER_AUTOMATIC,
            detail = detail
        )
    }

    suspend fun get(account: Account, service: KompaktSyncService): KompaktSyncOutcome? {
        val serviceRow = serviceRepository.getByAccountAndType(account.name, service.serviceType) ?: return null
        return outcomes.get(serviceRow.id, service.dataType)
    }

    /**
     * [Reported.Pending] until the row has been read, so the cell withholds its status rather than
     * painting a tick from `syncstats` and repainting to the alert icon a frame later.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observe(account: Account, service: KompaktSyncService): Flow<Reported<KompaktSyncOutcome?>> =
        serviceRepository.getServiceFlow(account.name, service.serviceType)
            .flatMapLatest { serviceRow ->
                if (serviceRow == null)
                    flowOf<Reported<KompaktSyncOutcome?>>(Reported.Value(null))
                else
                    outcomes.observe(serviceRow.id, service.dataType)
                        .map<KompaktSyncOutcome?, Reported<KompaktSyncOutcome?>> { Reported.Value(it) }
            }
            .onStart { emit(Reported.Pending) }
            .distinctUntilChanged()

    companion object {
        const val TRIGGER_MANUAL = "MANUAL"
        const val TRIGGER_AUTOMATIC = "AUTOMATIC"
    }

}
