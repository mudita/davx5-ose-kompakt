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

    // Callers always pass id = 0: Room binds NULL for a zero autoGenerate primary key, so the conflict
    // lands on the unique index and REPLACE keeps one row per service and data type. Round-tripping a
    // row that was read back into this would replace by primary key instead.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(outcome: KompaktSyncOutcome)

    @Query("SELECT * FROM kompakt_sync_outcome WHERE serviceId = :serviceId AND dataType = :dataType")
    suspend fun get(serviceId: Long, dataType: String): KompaktSyncOutcome?

    @Query("SELECT * FROM kompakt_sync_outcome WHERE serviceId = :serviceId AND dataType = :dataType")
    fun observe(serviceId: Long, dataType: String): Flow<KompaktSyncOutcome?>

}
