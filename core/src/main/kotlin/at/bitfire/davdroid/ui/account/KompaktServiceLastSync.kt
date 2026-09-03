/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.sync.KompaktSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

class KompaktServiceLastSync @Inject constructor(
    private val serviceRepository: DavServiceRepository,
    private val syncStatsRepository: DavSyncStatsRepository
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observe(account: Account, service: KompaktSyncService): Flow<Reported<Long?>> =
        serviceRepository.getServiceFlow(account.name, service.serviceType)
            .flatMapLatest { serviceRow ->
                if (serviceRow == null)
                    flowOf<Reported<Long?>>(Reported.Value(null))
                else
                    syncStatsRepository.lastSyncFlow(serviceRow.id, service.dataType)
                        .map<Long?, Reported<Long?>> { Reported.Value(it) }
            }
            .onStart { emit(Reported.Pending) }
            .distinctUntilChanged()

}
