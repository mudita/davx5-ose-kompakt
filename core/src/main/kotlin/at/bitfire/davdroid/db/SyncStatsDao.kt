/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(syncStats: SyncStats)

    @Query("SELECT * FROM syncstats WHERE collectionId=:id")
    fun getByCollectionIdFlow(id: Long): Flow<List<SyncStats>>

    /** Most recent successful sync time (across all collections and data types), or null if there was none yet. */
    @Query("SELECT MAX(lastSync) FROM syncstats")
    suspend fun getLastSyncTime(): Long?

    /** Most recent successful sync time across the collections selected for a service. */
    @Query("SELECT MAX(syncstats.lastSync) FROM syncstats " +
        "INNER JOIN collection ON collection.id = syncstats.collectionId " +
        "WHERE collection.serviceId = :serviceId AND collection.sync " +
        "AND syncstats.dataType = :dataType")
    fun lastSyncFlow(serviceId: Long, dataType: String): Flow<Long?>

}
