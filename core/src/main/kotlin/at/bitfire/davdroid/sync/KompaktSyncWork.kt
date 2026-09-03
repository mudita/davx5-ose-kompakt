/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import androidx.work.WorkManager
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface KompaktSyncWork {

    suspend fun enqueue(account: Account, service: KompaktSyncService, manual: Boolean = true): UUID?

    suspend fun cancel(account: Account, service: KompaktSyncService)

}

@Singleton
class KompaktSyncWorkImpl @Inject constructor(
    private val syncWorkerManager: SyncWorkerManager,
    private val workManager: WorkManager
) : KompaktSyncWork {

    override suspend fun enqueue(account: Account, service: KompaktSyncService, manual: Boolean) =
        syncWorkerManager.enqueueOneTimeReturningId(account, service.dataType, manual = manual)

    override suspend fun cancel(account: Account, service: KompaktSyncService) {
        workManager.cancelUniqueWork(OneTimeSyncWorker.workerName(account, service.dataType))
    }

}

@Module
@InstallIn(SingletonComponent::class)
interface KompaktSyncWorkModule {

    @Binds
    fun kompaktSyncWork(impl: KompaktSyncWorkImpl): KompaktSyncWork

}
