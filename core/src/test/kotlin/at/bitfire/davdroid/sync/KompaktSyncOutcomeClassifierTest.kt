/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KompaktSyncOutcomeClassifierTest {

    @Test
    fun `a clean result is not a failure`() {
        assertNull(KompaktSyncOutcomeClassifier.classify(SyncResult()))
    }

    @Test
    fun `each field maps to its cause`() {
        assertEquals(
            KompaktSyncFailure.AuthExpired,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numAuthExceptions = 1))
        )
        assertEquals(
            KompaktSyncFailure.ClockSkew,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numClockSkewErrors = 1))
        )
        assertEquals(
            KompaktSyncFailure.DeviceError,
            KompaktSyncOutcomeClassifier.classify(SyncResult(contentProviderError = true))
        )
        assertEquals(
            KompaktSyncFailure.DeviceError,
            KompaktSyncOutcomeClassifier.classify(SyncResult(localStorageError = true))
        )
        assertEquals(
            KompaktSyncFailure.DeviceError,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numDeadObjectExceptions = 1))
        )
        assertEquals(
            KompaktSyncFailure.ServerProblem,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numHttpExceptions = 1))
        )
        assertEquals(
            KompaktSyncFailure.ServerProblem,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numServiceUnavailableExceptions = 1))
        )
        assertEquals(
            KompaktSyncFailure.NetworkProblem,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numIoExceptions = 1))
        )
        assertEquals(
            KompaktSyncFailure.Unknown,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numUnclassifiedErrors = 1))
        )
    }

    // Ordered by what the user can act on, so the most actionable cause survives a mixed run.
    @Test
    fun `auth outranks every other cause`() {
        val everything = SyncResult(
            contentProviderError = true,
            localStorageError = true,
            numAuthExceptions = 1,
            numClockSkewErrors = 1,
            numHttpExceptions = 1,
            numUnclassifiedErrors = 1,
            numDeadObjectExceptions = 1,
            numIoExceptions = 1,
            numServiceUnavailableExceptions = 1
        )
        assertEquals(KompaktSyncFailure.AuthExpired, KompaktSyncOutcomeClassifier.classify(everything))
    }

    @Test
    fun `precedence runs auth, skew, device, server, network, unknown`() {
        assertEquals(
            KompaktSyncFailure.ClockSkew,
            KompaktSyncOutcomeClassifier.classify(
                SyncResult(numClockSkewErrors = 1, contentProviderError = true, numHttpExceptions = 1)
            )
        )
        assertEquals(
            KompaktSyncFailure.DeviceError,
            KompaktSyncOutcomeClassifier.classify(
                SyncResult(contentProviderError = true, numHttpExceptions = 1, numIoExceptions = 1)
            )
        )
        assertEquals(
            KompaktSyncFailure.ServerProblem,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numHttpExceptions = 1, numIoExceptions = 1))
        )
        assertEquals(
            KompaktSyncFailure.NetworkProblem,
            KompaktSyncOutcomeClassifier.classify(SyncResult(numIoExceptions = 1, numUnclassifiedErrors = 1))
        )
    }
}
