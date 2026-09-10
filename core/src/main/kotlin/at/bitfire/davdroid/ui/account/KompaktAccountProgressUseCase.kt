/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.commonTag
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// Reads only WorkManager, unlike AccountProgressUseCase. Collection discovery is left out because on
// Kompakt it has a screen of its own, and the sync framework's pending flag because it is raised while
// sync-ability is being enabled and dropped again once the request becomes a worker — a source that
// retracts makes the row claim a sync and then take the claim back.
class KompaktAccountProgressUseCase @Inject constructor(
    private val workManager: WorkManager
) {

    // One query, so that the one-time run's queued-to-running transition is one emission of one flow.
    // Asking separately whether anything is queued and whether anything is running answers neither for
    // as long as "no longer queued" arrives before "now running".
    operator fun invoke(account: Account, dataType: SyncDataType): Flow<Boolean> {
        val oneTimeTag = OneTimeSyncWorker.workerName(account, dataType)
        val query = WorkQuery.Builder
            .fromStates(listOf(WorkInfo.State.BLOCKED, WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
            .addTags(listOf(commonTag(account, dataType)))
            .build()

        return workManager.getWorkInfosFlow(query)
            .map { workInfos -> workInfos.any { syncing(it, oneTimeTag) } }
            .distinctUntilChanged()
    }

    // A periodic worker is always enqueued, so only its run counts; a one-time one exists only because a
    // sync was asked for, so it counts from the moment it is queued. Except when a constraint stopped
    // it: WorkManager returns an interrupted run to ENQUEUED with its tags intact.
    private fun syncing(workInfo: WorkInfo, oneTimeTag: String): Boolean =
        workInfo.state == WorkInfo.State.RUNNING ||
            (oneTimeTag in workInfo.tags && workInfo.stopReason == WorkInfo.STOP_REASON_NOT_STOPPED)

}
