/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface KompaktSyncWork {

    suspend fun enqueue(account: Account, service: KompaktSyncService, manual: Boolean = true): UUID?

    suspend fun cancel(account: Account, service: KompaktSyncService)

    fun enqueueRefreshCollections(serviceId: Long)

    /**
     * [enqueueRefreshCollections], or joins a refresh already pending, and answers whether it succeeded.
     * Waits as long as the run takes: a caller with a time budget bounds it.
     */
    suspend fun refreshCollectionsAndWait(serviceId: Long): Boolean

}

@Singleton
class KompaktSyncWorkImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncWorkerManager: SyncWorkerManager,
    private val workManager: WorkManager
) : KompaktSyncWork {

    override suspend fun enqueue(account: Account, service: KompaktSyncService, manual: Boolean) =
        syncWorkerManager.enqueueOneTimeReturningId(account, service.dataType, manual = manual)

    override suspend fun cancel(account: Account, service: KompaktSyncService) {
        workManager.cancelUniqueWork(OneTimeSyncWorker.workerName(account, service.dataType))
    }

    override fun enqueueRefreshCollections(serviceId: Long) {
        RefreshCollectionsWorker.enqueue(context, serviceId)
    }

    override suspend fun refreshCollectionsAndWait(serviceId: Long): Boolean {
        val (name, operation) = RefreshCollectionsWorker.enqueue(context, serviceId)
        // Until the enqueue lands, the unique name still lists the previous run, which already finished.
        operation.await()
        return workManager.getWorkInfosForUniqueWorkFlow(name)
            .first { runs -> runs.isNotEmpty() && runs.all { it.state.isFinished } }
            .any { it.state == WorkInfo.State.SUCCEEDED }
    }

}

@Module
@InstallIn(SingletonComponent::class)
interface KompaktSyncWorkModule {

    @Binds
    fun kompaktSyncWork(impl: KompaktSyncWorkImpl): KompaktSyncWork

}
