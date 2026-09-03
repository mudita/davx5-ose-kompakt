/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import androidx.work.WorkManager
import at.bitfire.davdroid.TEST_ACCOUNT_NAME
import at.bitfire.davdroid.TEST_ACCOUNT_TYPE
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class KompaktSyncWorkTest {

    private val account = mockAccount()

    private val runId = UUID.randomUUID()

    private lateinit var syncWorkerManager: SyncWorkerManager
    private lateinit var workManager: WorkManager
    private lateinit var syncWork: KompaktSyncWorkImpl

    @Before
    fun setUp() {
        syncWorkerManager = mockk()
        every { syncWorkerManager.enqueueOneTimeReturningId(any(), any(), any(), any(), any(), any()) } returns runId

        workManager = mockk(relaxed = true)

        syncWork = KompaktSyncWorkImpl(syncWorkerManager, workManager)
    }

    @Test
    fun enqueuesTheDataTypeOfTheServiceItWasGiven() = runTest {
        syncWork.enqueue(account, KompaktSyncService.CONTACTS)

        verify {
            syncWorkerManager.enqueueOneTimeReturningId(
                account, SyncDataType.CONTACTS, true, null, false, false
            )
        }
    }

    @Test
    fun enqueuesAPeriodicRunAsNonManual() = runTest {
        syncWork.enqueue(account, KompaktSyncService.CALENDAR, manual = false)

        verify {
            syncWorkerManager.enqueueOneTimeReturningId(
                account, SyncDataType.EVENTS, false, null, false, false
            )
        }
    }

    @Test
    fun returnsTheIdOfTheRunThatWillExecute() = runTest {
        // The caller observes that specific run, so the id of an already-pending one matters as much
        // as the id of a freshly enqueued one.
        assertEquals(runId, syncWork.enqueue(account, KompaktSyncService.CALENDAR))
    }

    @Test
    fun returnsNoIdWhenNothingWasEnqueued() = runTest {
        every { syncWorkerManager.enqueueOneTimeReturningId(any(), any(), any(), any(), any(), any()) } returns null

        assertNull(syncWork.enqueue(account, KompaktSyncService.CALENDAR))
    }

    @Test
    fun cancelsTheUniqueWorkOfThatServiceAlone() = runTest {
        syncWork.cancel(account, KompaktSyncService.CALENDAR)

        verify {
            workManager.cancelUniqueWork(OneTimeSyncWorker.workerName(account, SyncDataType.EVENTS))
        }
        verify(exactly = 0) {
            workManager.cancelUniqueWork(OneTimeSyncWorker.workerName(account, SyncDataType.CONTACTS))
        }
    }

    @Test
    fun theCancelledNameIsTheOneTheWorkerWasEnqueuedUnder() = runTest {
        syncWork.cancel(account, KompaktSyncService.CONTACTS)

        verify {
            workManager.cancelUniqueWork(
                "onetime-sync CONTACTS $TEST_ACCOUNT_TYPE/$TEST_ACCOUNT_NAME"
            )
        }
    }

}
