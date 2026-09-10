/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

object KompaktSyncOutcomeClassifier {

    // Ordered by what the user can act on, so a run carrying several causes reports the one worth
    // showing rather than whichever field happens to be checked first.
    fun classify(result: SyncResult): KompaktSyncFailure? = when {
        !result.hasError() -> null
        result.numAuthExceptions > 0 -> KompaktSyncFailure.AuthExpired
        result.numClockSkewErrors > 0 -> KompaktSyncFailure.ClockSkew
        result.contentProviderError
            || result.localStorageError
            || result.numDeadObjectExceptions > 0 -> KompaktSyncFailure.DeviceError
        result.numHttpExceptions > 0
            || result.numServiceUnavailableExceptions > 0 -> KompaktSyncFailure.ServerProblem
        result.numIoExceptions > 0 -> KompaktSyncFailure.NetworkProblem
        else -> KompaktSyncFailure.Unknown
    }

}
