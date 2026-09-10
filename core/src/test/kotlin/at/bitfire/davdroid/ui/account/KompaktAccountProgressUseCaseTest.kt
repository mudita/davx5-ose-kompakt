/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.worker.BaseSyncWorker.Companion.commonTag
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.PeriodicSyncWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class KompaktAccountProgressUseCaseTest {

    private val account = mockAccount()
    private val dataType = SyncDataType.EVENTS

    private val commonWorkTag = commonTag(account, dataType)
    private val oneTimeTag = OneTimeSyncWorker.workerName(account, dataType)
    private val periodicTag = PeriodicSyncWorker.workerName(account, dataType)

    // Replayed rather than a StateFlow: a StateFlow conflates equal values on its own, which would
    // pass the distinctUntilChanged tests below even if the operator were dropped.
    private val work = MutableSharedFlow<List<WorkInfo>>(replay = 1)
    private val query = slot<WorkQuery>()

    private val workManager = mockk<WorkManager>().also {
        every { it.getWorkInfosFlow(capture(query)) } returns work
    }

    private val useCase = KompaktAccountProgressUseCase(workManager)

    private val testDispatcher = StandardTestDispatcher()

    // Unconfined so each emission reaches the collector inline; backgroundScope cancels it at the end.
    private fun TestScope.syncing(): List<Boolean> =
        mutableListOf<Boolean>().also { seen ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                useCase(account, dataType).toList(seen)
            }
        }

    private fun workInfo(
        state: WorkInfo.State,
        vararg tags: String,
        stopReason: Int = WorkInfo.STOP_REASON_NOT_STOPPED
    ): WorkInfo =
        mockk<WorkInfo>().also {
            every { it.state } returns state
            every { it.tags } returns tags.toSet()
            every { it.stopReason } returns stopReason
        }

    private fun oneTime(state: WorkInfo.State, stopReason: Int = WorkInfo.STOP_REASON_NOT_STOPPED) =
        workInfo(state, commonWorkTag, oneTimeTag, stopReason = stopReason)

    private fun periodic(state: WorkInfo.State) = workInfo(state, commonWorkTag, periodicTag)

    @Test
    fun noWork_isNotSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(emptyList())

        assertEquals(listOf(false), seen)
    }

    // The reason the periodic worker cannot be counted while it waits: there is always one enqueued.
    @Test
    fun enqueuedPeriodic_isNotSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(periodic(WorkInfo.State.ENQUEUED)))

        assertEquals(listOf(false), seen)
    }

    @Test
    fun runningPeriodic_isSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(periodic(WorkInfo.State.RUNNING)))

        assertEquals(listOf(true), seen)
    }

    @Test
    fun enqueuedOneTime_isSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(oneTime(WorkInfo.State.ENQUEUED)))

        assertEquals(listOf(true), seen)
    }

    // A run appended behind an unfinished one waits blocked, and it is still a sync that was asked for.
    @Test
    fun blockedOneTime_isSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(oneTime(WorkInfo.State.BLOCKED)))

        assertEquals(listOf(true), seen)
    }

    // Losing the network mid-sync returns the run to ENQUEUED with its tags intact. Reproduced on a
    // Kompakt: stop_reason 7, and the row claimed "Synchronizing..." for four minutes with nothing
    // running, until connectivity came back.
    @Test
    fun oneTimeStoppedByAConstraint_isNotSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(oneTime(WorkInfo.State.ENQUEUED, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)))

        assertEquals(listOf(false), seen)
    }

    // The reason resets when the run finally executes, so the row starts claiming a sync again on the
    // same event that makes the claim true.
    @Test
    fun aStoppedOneTimeResumingOnceTheConstraintIsMet_isSyncingAgain() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(oneTime(WorkInfo.State.ENQUEUED, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)))
        work.emit(listOf(oneTime(WorkInfo.State.RUNNING)))

        assertEquals(listOf(false, true), seen)
    }

    // A stopped one-time run must not hide a periodic one that really is running.
    @Test
    fun aStoppedOneTimeAlongsideARunningPeriodic_isSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(
            listOf(
                oneTime(WorkInfo.State.ENQUEUED, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY),
                periodic(WorkInfo.State.RUNNING)
            )
        )

        assertEquals(listOf(true), seen)
    }

    // The regression this class exists for: the queued-to-running handoff must not report idle in
    // between, which is what two separate queries did.
    @Test
    fun oneTimeGoingFromEnqueuedToRunning_neverReportsIdle() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(oneTime(WorkInfo.State.ENQUEUED)))
        work.emit(listOf(oneTime(WorkInfo.State.RUNNING)))

        assertEquals(listOf(true), seen)
    }

    // What is left once the manual run finishes: the periodic worker waiting for its next interval.
    @Test
    fun oneTimeFinishing_leavesTheWaitingPeriodicAsIdle() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(oneTime(WorkInfo.State.RUNNING), periodic(WorkInfo.State.ENQUEUED)))
        work.emit(listOf(periodic(WorkInfo.State.ENQUEUED)))

        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun anAutomaticSyncStartingWhileOneIsQueued_staysSyncing() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(periodic(WorkInfo.State.ENQUEUED), oneTime(WorkInfo.State.ENQUEUED)))

        assertEquals(listOf(true), seen)
    }

    @Test
    fun unchangedState_doesNotReportAgain() = runTest(testDispatcher) {
        val seen = syncing()

        work.emit(listOf(oneTime(WorkInfo.State.ENQUEUED)))
        work.emit(listOf(oneTime(WorkInfo.State.ENQUEUED)))

        assertEquals(listOf(true), seen)
    }

    // Both halves matter: the states decide what the query returns at all, so dropping BLOCKED here
    // would hide a queued run however the predicate reads it.
    @Test
    fun query_asksForUnfinishedWorkOfThisAccountAndDataType() = runTest(testDispatcher) {
        syncing()

        assertEquals(
            listOf(WorkInfo.State.BLOCKED, WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING),
            query.captured.states
        )
        assertEquals(listOf(commonWorkTag), query.captured.tags)
    }

}
