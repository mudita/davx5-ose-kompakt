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
interface KompaktSyncOutcomeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(outcome: KompaktSyncOutcome)

    @Query("SELECT * FROM kompakt_sync_outcome WHERE serviceId = :serviceId AND dataType = :dataType")
    suspend fun get(serviceId: Long, dataType: String): KompaktSyncOutcome?

    @Query("SELECT * FROM kompakt_sync_outcome WHERE serviceId = :serviceId AND dataType = :dataType")
    fun observe(serviceId: Long, dataType: String): Flow<KompaktSyncOutcome?>

}
