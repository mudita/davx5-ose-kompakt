/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.accounts.Account
import at.bitfire.davdroid.db.AppDatabase
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.sync.KompaktSyncFailure
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.ui.account.Reported
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

class KompaktSyncOutcomeRepository @Inject constructor(
    db: AppDatabase,
    private val serviceRepository: DavServiceRepository
) {

    private val dao = db.kompaktSyncOutcomeDao()

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
        dao.insertOrReplace(
            KompaktSyncOutcome(
                id = 0,
                serviceId = serviceRow.id,
                dataType = service.dataType.name,
                at = System.currentTimeMillis(),
                succeeded = cause == null,
                cause = cause?.name,
                trigger = if (manual) TRIGGER_MANUAL else TRIGGER_AUTOMATIC,
                detail = detail
            )
        )
    }

    suspend fun get(account: Account, service: KompaktSyncService): KompaktSyncOutcome? {
        val serviceRow = serviceRepository.getByAccountAndType(account.name, service.serviceType) ?: return null
        return dao.get(serviceRow.id, service.dataType.name)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observe(account: Account, service: KompaktSyncService): Flow<Reported<KompaktSyncOutcome?>> =
        serviceRepository.getServiceFlow(account.name, service.serviceType)
            .flatMapLatest { serviceRow ->
                if (serviceRow == null)
                    flowOf<Reported<KompaktSyncOutcome?>>(Reported.Value(null))
                else
                    dao.observe(serviceRow.id, service.dataType.name)
                        .map<KompaktSyncOutcome?, Reported<KompaktSyncOutcome?>> { Reported.Value(it) }
            }
            .onStart { emit(Reported.Pending) }
            .distinctUntilChanged()

    companion object {
        const val TRIGGER_MANUAL = "MANUAL"
        const val TRIGGER_AUTOMATIC = "AUTOMATIC"
    }

}
