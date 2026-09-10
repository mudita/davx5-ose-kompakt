/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How the last sync attempt for one service and data type ended. [SyncStats] answers when a sync last
 * *succeeded*; this answers how the last attempt *finished*, which is the question a failure needs and
 * the one a periodic run cannot answer through WorkManager.
 */
@Entity(tableName = "kompakt_sync_outcome",
    foreignKeys = [
        ForeignKey(childColumns = arrayOf("serviceId"), entity = Service::class, parentColumns = arrayOf("id"), onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["serviceId", "dataType"], unique = true)
    ]
)
data class KompaktSyncOutcome(
    @PrimaryKey(autoGenerate = true)
    val id: Long,

    val serviceId: Long,
    val dataType: String,

    val at: Long,
    val succeeded: Boolean,
    val cause: String?,
    val trigger: String,
    val detail: String?
)
