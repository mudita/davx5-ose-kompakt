/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.dav4jvm.okhttp.exception.HttpException
import at.bitfire.davdroid.resource.LocalResource
import java.util.logging.Level
import java.util.logging.Logger

object KompaktUploadFailure {

    // A rejected resource keeps its dirty flag and is retried on every sync, so one the server never
    // accepts must not hold back the other uploads and the download of remote changes. Only answers
    // that are about the body of this one resource qualify; anything that can mean the whole
    // collection, the account or the connection (403, 405, 415, 426, …) still ends the sync, or a
    // sync where every upload fails would be reported as a success.
    private val RESOURCE_LOCAL_STATUSES = setOf(400, 413, 422, 423)

    suspend fun runConfined(local: LocalResource, syncResult: SyncResult, logger: Logger, upload: suspend () -> Unit) {
        try {
            upload()
        } catch (e: SyncException) {
            if (!isConfinedToResource(e))
                throw e
            synchronized(syncResult) {
                syncResult.numRejectedUploads++
            }
            logger.log(Level.WARNING, "Server rejected ${local.fileName ?: "#${local.id}"}, keeping it dirty and continuing", e)
        }
    }

    fun isConfinedToResource(e: Throwable): Boolean {
        val http = (if (e is SyncException) e.cause else e) as? HttpException ?: return false
        return http.statusCode in RESOURCE_LOCAL_STATUSES
    }

}
