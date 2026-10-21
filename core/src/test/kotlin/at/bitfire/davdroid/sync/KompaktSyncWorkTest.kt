/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.Context
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import at.bitfire.davdroid.TEST_ACCOUNT_NAME
import at.bitfire.davdroid.TEST_ACCOUNT_TYPE
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class KompaktSyncWorkTest {

    private val account = mockAccount()

    private val runId = UUID.randomUUID()

    private val context = mockk<Context>()

    private lateinit var syncWorkerManager: SyncWorkerManager
    private lateinit var workManager: WorkManager
    private lateinit var syncWork: KompaktSyncWorkImpl

    @Before
    fun setUp() {
        syncWorkerManager = mockk()
        every { syncWorkerManager.enqueueOneTimeReturningId(any(), any(), any(), any(), any(), any()) } returns runId

        workManager = mockk(relaxed = true)

        syncWork = KompaktSyncWorkImpl(context, syncWorkerManager, workManager)

        mockkObject(RefreshCollectionsWorker.Companion)
        every { RefreshCollectionsWorker.enqueue(context, SERVICE_ID) } returns (REFRESH_NAME to landedOperation())
    }

    @After
    fun tearDown() {
        unmockkObject(RefreshCollectionsWorker.Companion)
    }

    private fun landedOperation(): Operation {
        val result = mockk<ListenableFuture<Operation.State.SUCCESS>> {
            every { isDone } returns true
            every { get() } returns mockk()
        }
        return mockk { every { this@mockk.result } returns result }
    }

    private fun workInfo(state: WorkInfo.State) = mockk<WorkInfo> { every { this@mockk.state } returns state }

    private fun refreshRuns(vararg states: WorkInfo.State) =
        every { workManager.getWorkInfosForUniqueWorkFlow(REFRESH_NAME) } returns
            flowOf(*states.map { state -> listOf(workInfo(state)) }.toTypedArray())

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

    // refreshing collections

    @Test
    fun refreshesTheCollectionsOfTheServiceItWasGiven() {
        syncWork.enqueueRefreshCollections(SERVICE_ID)

        verify { RefreshCollectionsWorker.enqueue(context, SERVICE_ID) }
    }

    @Test
    fun waitsForTheRefreshToFinishBeforeAnswering() = runTest {
        refreshRuns(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED)

        assertTrue(syncWork.refreshCollectionsAndWait(SERVICE_ID))
    }

    // Until the enqueue lands the unique name still lists the previous, finished run: answering from it is
    // how a run left over from a recycled service id passed for this one's.
    @Test
    fun answersFromItsOwnRunNotTheOneTheEnqueueReplaces() = runTest {
        var landed = false
        val listener = slot<Runnable>()
        val result = mockk<ListenableFuture<Operation.State.SUCCESS>>(relaxed = true) {
            every { isDone } answers { landed }
            every { addListener(capture(listener), any()) } just runs
            every { get() } returns mockk()
        }
        every { RefreshCollectionsWorker.enqueue(context, SERVICE_ID) } returns
            (REFRESH_NAME to mockk { every { this@mockk.result } returns result })
        val runs = MutableStateFlow(listOf(workInfo(WorkInfo.State.SUCCEEDED)))
        every { workManager.getWorkInfosForUniqueWorkFlow(REFRESH_NAME) } returns runs

        val answer = async(UnconfinedTestDispatcher(testScheduler)) { syncWork.refreshCollectionsAndWait(SERVICE_ID) }
        assertFalse(answer.isCompleted)

        runs.value = listOf(workInfo(WorkInfo.State.ENQUEUED))
        landed = true
        listener.captured.run()
        runs.value = listOf(workInfo(WorkInfo.State.FAILED))

        assertFalse(answer.await())
    }

    @Test
    fun aRefreshThatFailedAnswersFalse() = runTest {
        refreshRuns(WorkInfo.State.RUNNING, WorkInfo.State.FAILED)

        assertFalse(syncWork.refreshCollectionsAndWait(SERVICE_ID))
    }

    private companion object {
        const val SERVICE_ID = 7L
        const val REFRESH_NAME = "refreshCollectionsWorker-7"
    }

}
