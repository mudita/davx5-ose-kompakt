/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import at.bitfire.davdroid.db.AppDatabase
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.sync.SyncDataType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * How the last sync attempt for one service and data type ended. Keyed by service row id, like
 * [DavSyncStatsRepository]: resolving an account to that row is the caller's job.
 */
class KompaktSyncOutcomeRepository @Inject constructor(db: AppDatabase) {

    private val dao = db.kompaktSyncOutcomeDao()

    /**
     * Always inserts with `id = 0`, so Room binds NULL for the autoGenerate primary key and the
     * conflict lands on the unique service-and-data-type index instead of replacing by primary key.
     */
    suspend fun record(
        serviceId: Long,
        dataType: SyncDataType,
        succeeded: Boolean,
        cause: String?,
        trigger: String,
        detail: String?
    ) {
        dao.insertOrReplace(
            KompaktSyncOutcome(
                id = 0,
                serviceId = serviceId,
                dataType = dataType.name,
                at = System.currentTimeMillis(),
                succeeded = succeeded,
                cause = cause,
                trigger = trigger,
                detail = detail
            )
        )
    }

    suspend fun get(serviceId: Long, dataType: SyncDataType): KompaktSyncOutcome? =
        dao.get(serviceId, dataType.name)

    fun observe(serviceId: Long, dataType: SyncDataType): Flow<KompaktSyncOutcome?> =
        dao.observe(serviceId, dataType.name)

}
