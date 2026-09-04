/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync.worker

import android.accounts.Account
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkRequest
import at.bitfire.davdroid.push.PushNotificationManager
import at.bitfire.davdroid.sync.ResyncType
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.TasksAppManager
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.INPUT_ACCOUNT_NAME
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.INPUT_ACCOUNT_TYPE
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.INPUT_DATA_TYPE
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.INPUT_MANUAL
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.INPUT_RESYNC
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.INPUT_UPLOAD
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.RESYNC_ENTRIES
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.RESYNC_LIST
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.commonTag
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.inject.Inject

/**
 * For building and managing synchronization workers (both one-time and periodic).
 *
 * One-time sync workers can be enqueued. Periodic sync workers can be enabled and disabled.
 */
class SyncWorkerManager @Inject constructor(
    @ApplicationContext val context: Context,
    val logger: Logger,
    val pushNotificationManager: Lazy<PushNotificationManager>,
    val tasksAppManager: Lazy<TasksAppManager>
) {

    // one-time sync workers

    /**
     * Builds a one-time sync worker for a specific account and authority.
     *
     * Arguments: see [enqueueOneTime]
     *
     * @return one-time sync work request for the given arguments
     */
    fun buildOneTime(
        account: Account,
        dataType: SyncDataType,
        manual: Boolean = false,
        resync: ResyncType? = null,
        fromUpload: Boolean = false
    ): OneTimeWorkRequest {
        // worker arguments
        val argumentsBuilder = Data.Builder()
            .putString(INPUT_DATA_TYPE, dataType.toString())
            .putString(INPUT_ACCOUNT_NAME, account.name)
            .putString(INPUT_ACCOUNT_TYPE, account.type)

        if (manual)
            argumentsBuilder.putBoolean(INPUT_MANUAL, true)

        when (resync) {
            ResyncType.RESYNC_ENTRIES -> argumentsBuilder.putInt(INPUT_RESYNC, RESYNC_ENTRIES)
            ResyncType.RESYNC_LIST -> argumentsBuilder.putInt(INPUT_RESYNC, RESYNC_LIST)
            else -> { /* no explicit re-synchronization */ }
        }

        argumentsBuilder.putBoolean(INPUT_UPLOAD, fromUpload)

        // build work request
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)   // require a network connection
            // Kompakt: automatic (non-manual) one-time syncs wait while the system reports critically low
            // storage and auto-run when it recovers. Manual syncs are pre-checked in the UI instead.
            .apply { if (!manual) setRequiresStorageNotLow(true) }
            .build()
        return OneTimeWorkRequestBuilder<OneTimeSyncWorker>()
            .addTag(OneTimeSyncWorker.workerName(account, dataType))
            .addTag(commonTag(account, dataType))
            .setInputData(argumentsBuilder.build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,   // 30 sec
                TimeUnit.MILLISECONDS
            )
            .setConstraints(constraints)

            /* OneTimeSyncWorker is started by user or sync framework when there are local changes.
            In both cases, synchronization should be done as soon as possible, so we set expedited. */
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)

            // build work request
            .build()
    }

    /**
     * Requests immediate synchronization of an account with a specific authority.
     *
     * If there is no currently running one-time sync, the sync is enqueued normally.
     *
     * If there is a currently running one-time sync, another sync is appended to make sure
     * a complete sync is run. This method makes however sure that there's only _one_
     * further sync in the queue.
     *
     * @param account       account to sync
     * @param dataType      type of data to synchronize
     * @param manual        user-initiated sync (ignores network checks)
     * @param resync        whether to request (full) re-synchronization (`null` for normal sync)
     * @param fromUpload    whether this sync is initiated by a local change
     * @param fromPush      whether this sync is initiated by a push notification
     *
     * @return existing or newly created worker name
     */
    fun enqueueOneTime(
        account: Account,
        dataType: SyncDataType,
        manual: Boolean = false,
        resync: ResyncType? = null,
        fromUpload: Boolean = false,
        fromPush: Boolean = false
    ): String {
        enqueueOneTimeReturningId(account, dataType, manual, resync, fromUpload, fromPush)
        return OneTimeSyncWorker.workerName(account, dataType)
    }

    /**
     * Like [enqueueOneTime], but returns the id of the run that will execute — the freshly enqueued
     * request, or an already-pending one — so a caller can observe that specific run.
     */
    fun enqueueOneTimeReturningId(
        account: Account,
        dataType: SyncDataType,
        manual: Boolean = false,
        resync: ResyncType? = null,
        fromUpload: Boolean = false,
        fromPush: Boolean = false
    ): UUID? {
        logger.info("Enqueueing unique worker for account=$account, dataType=$dataType, manual=$manual, resync=$resync, fromUpload=$fromUpload, fromPush=$fromPush")

        val name = OneTimeSyncWorker.workerName(account, dataType)
        val request = buildOneTime(
            account = account,
            dataType = dataType,
            manual = manual,
            resync = resync,
            fromUpload = fromUpload
        )

        if (fromPush)
            pushNotificationManager.get().notify(account, dataType)

        // Append at most one further run: if one is already pending, track it instead of adding more.
        val workManager = WorkManager.getInstance(context)
        synchronized(SyncWorkerManager::class.java) {
            val currentWork = workManager.getWorkInfosForUniqueWork(name).get()
            val pending = currentWork.firstOrNull {
                it.state in setOf(WorkInfo.State.BLOCKED, WorkInfo.State.ENQUEUED)
            }
            return if (pending == null) {
                val op = workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                op.result   // for synchronization: wait until work is actually enqueued
                request.id
            } else {
                logger.fine("Another one-time sync already waiting, not adding more of $name")
                pending.id
            }
        }
    }

    /**
     * Requests immediate synchronization of an account with all applicable
     * authorities (contacts, calendars, …).
     *
     * Arguments: see [enqueueOneTime]
     *
     * @return the id of each authority's run that will execute, so a caller can observe a specific run
     */
    fun enqueueOneTimeAllAuthorities(
        account: Account,
        manual: Boolean = false,
        resync: ResyncType? = null,
        fromUpload: Boolean = false,
        fromPush: Boolean = false
    ): Map<SyncDataType, UUID?> =
        SyncDataType.entries.associateWith { dataType ->
            enqueueOneTimeReturningId(account, dataType, manual, resync, fromUpload, fromPush)
        }


    // periodic sync workers

    /**
     * Builds a periodic sync worker for a specific account and authority.
     *
     * Arguments: see [enablePeriodic]
     *
     * @return periodic sync work request for the given arguments
     */
    fun buildPeriodic(
        account: Account,
        dataType: SyncDataType,
        interval: Long,
        syncWifiOnly: Boolean,
        delayFirstRunBy: Long = 0
    ): PeriodicWorkRequest {
        val arguments = Data.Builder()
            .putString(INPUT_DATA_TYPE, dataType.toString())
            .putString(INPUT_ACCOUNT_NAME, account.name)
            .putString(INPUT_ACCOUNT_TYPE, account.type)
            .build()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (syncWifiOnly)
                    NetworkType.UNMETERED
                else
                    NetworkType.CONNECTED
            )
            // Kompakt: don't run periodic sync while the system reports critically low storage. WorkManager
            // parks the worker (no dispatch → no SQLITE_FULL retry loop) and auto-runs it once the system
            // reports STORAGE_OK again. Uses the same system threshold (min 500 MB / 10 %) as KompaktStorage.
            .setRequiresStorageNotLow(true)
            .build()
        return PeriodicWorkRequestBuilder<PeriodicSyncWorker>(interval, TimeUnit.SECONDS)
            .addTag(PeriodicSyncWorker.workerName(account, dataType))
            .addTag(commonTag(account, dataType))
            .setInputData(arguments)
            .setConstraints(constraints)
            .setInitialDelay(delayFirstRunBy, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Activate periodic synchronization of an account with a specific authority.
     *
     * @param account    account to sync
     * @param dataType   type of data to synchronize
     * @param interval   interval between recurring syncs in seconds
     * @return operation object to check when and whether activation was successful
     */
    fun enablePeriodic(
        account: Account,
        dataType: SyncDataType,
        interval: Long,
        syncWifiOnly: Boolean,
        rescheduleFromNow: Boolean = false
    ): Operation {
        logger.fine("Updating periodic worker for account=$account, dataType=$dataType, interval=$interval, syncWifiOnly=$syncWifiOnly, rescheduleFromNow=$rescheduleFromNow")
        val name = PeriodicSyncWorker.workerName(account, dataType)
        val workRequest = buildPeriodic(
            account, dataType, interval, syncWifiOnly,
            // WorkManager runs a freshly enqueued periodic worker straight away, so without this the
            // re-enqueue would sync again on top of the manual sync that asked for the reschedule.
            delayFirstRunBy = if (rescheduleFromNow) interval else 0
        )
        return WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            name,
            // UPDATE keeps the existing schedule (just updates interval/constraints for the next iteration);
            // CANCEL_AND_REENQUEUE drops it, so the delayed request above becomes the whole schedule and
            // every following run is counted from now (used after a successful manual sync).
            if (rescheduleFromNow)
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            else
                ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /**
     * Disables periodic synchronization of an account for a specific authority.
     *
     * @param account     account to sync
     * @param dataType    type of data to synchronize
     * @return operation object to check process state of work cancellation
     */
    fun disablePeriodic(account: Account, dataType: SyncDataType): Operation {
        logger.fine("Disabling periodic worker for account=$account, dataType=$dataType")
        return WorkManager.getInstance(context)
            .cancelUniqueWork(PeriodicSyncWorker.workerName(account, dataType))
    }


    // common / helpers

    /**
     * Stops running sync workers and removes pending sync workers from queue, for all authorities.
     */
    fun cancelAllWork(account: Account) {
        val workManager = WorkManager.getInstance(context)
        for (dataType in SyncDataType.entries) {
            workManager.cancelUniqueWork(OneTimeSyncWorker.workerName(account, dataType))
            workManager.cancelUniqueWork(PeriodicSyncWorker.workerName(account, dataType))
        }
    }

    /**
     * Observes whether >0 sync workers (both [PeriodicSyncWorker] and [OneTimeSyncWorker])
     * exist, belonging to given account and authorities, and which are/is in the given worker state.
     *
     * @param workStates   list of states of workers to match
     * @param account      the account which the workers belong to
     * @param dataTypes    data types of sync work
     * @param whichTag     function to generate tag that should be observed for given account and authority
     *
     * @return flow that emits `true` if at least one worker with matching query was found; `false` otherwise
     */
    fun hasAnyFlow(
        workStates: List<WorkInfo.State>,
        account: Account? = null,
        dataTypes: Iterable<SyncDataType>? = null,
        whichTag: (account: Account, dataType: SyncDataType) -> String = { account, dataType ->
            commonTag(account, dataType)
        }
    ): Flow<Boolean> {
        val workQuery = WorkQuery.Builder.fromStates(workStates)
        if (account != null && dataTypes != null)
            workQuery.addTags(
                dataTypes.map { dataType -> whichTag(account, dataType) }
            )
        return WorkManager.getInstance(context)
            .getWorkInfosFlow(workQuery.build())
            .map { workInfoList ->
                workInfoList.isNotEmpty()
            }
    }

}