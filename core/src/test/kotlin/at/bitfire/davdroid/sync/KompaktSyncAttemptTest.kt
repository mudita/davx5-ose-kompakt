/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import androidx.work.WorkInfo
import androidx.work.WorkManager
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class KompaktSyncAttemptTest {

    private val account = mockAccount()
    private val calendarId: UUID = UUID.randomUUID()
    private val contactsId: UUID = UUID.randomUUID()

    // Replay rather than a StateFlow: the attempt no longer dedupes, so a fake that did would hide
    // whether a repeated cause is acted on twice.
    private val offlineCause = MutableSharedFlow<KompaktOfflineCause?>(replay = 1)
    private val calendarWork = MutableSharedFlow<WorkInfo?>(replay = 1)
    private val contactsWork = MutableSharedFlow<WorkInfo?>(replay = 1)

    private lateinit var startSync: KompaktStartSyncUseCase
    private lateinit var outcomes: KompaktServiceSyncOutcome
    private lateinit var accountSettings: KompaktAccountSettings
    private lateinit var connectivity: KompaktConnectivity
    private lateinit var syncWork: KompaktSyncWork
    private lateinit var workManager: WorkManager
    private lateinit var attempt: KompaktSyncAttempt

    @Before
    fun setUp() {
        startSync = mockk()
        outcomes = mockk(relaxed = true)
        coEvery { outcomes.get(any(), any()) } returns null

        accountSettings = mockk(relaxed = true)
        every { accountSettings.getReauthNeeded(account) } returns false

        connectivity = mockk()
        every { connectivity.observe() } returns offlineCause
        offlineCause.tryEmit(null)

        syncWork = mockk(relaxed = true)

        workManager = mockk()
        every { workManager.getWorkInfoByIdFlow(calendarId) } returns calendarWork
        every { workManager.getWorkInfoByIdFlow(contactsId) } returns contactsWork

        attempt = KompaktSyncAttempt(
            startSync, outcomes, accountSettings, connectivity, syncWork, workManager
        )
    }

    @Test
    fun `low storage is reported, and nothing is awaited`() = runTest {
        coEvery { startSync(account, any(), any()) } returns KompaktSyncStartResult.NoStorage

        assertEquals(KompaktAttemptResult.BlockedNoStorage, attempt.run(account, KompaktSyncService.entries))
    }

    @Test
    fun `no internet is reported, and nothing is awaited`() = runTest {
        coEvery { startSync(account, any(), any()) } returns KompaktSyncStartResult.NoNetwork

        assertEquals(KompaktAttemptResult.BlockedNoNetwork, attempt.run(account, KompaktSyncService.entries))
    }

    @Test
    fun `an empty eligible set is NoneEligible, which SHP-1157 turns into a dialog`() = runTest {
        coEvery { startSync(account, any(), any()) } returns KompaktSyncStartResult.NoneEligible

        assertEquals(KompaktAttemptResult.NoneEligible, attempt.run(account, KompaktSyncService.entries))
    }

    @Test
    fun `every service already syncing is AlreadySyncing, and silent`() = runTest {
        coEvery { startSync(account, any(), any()) } returns KompaktSyncStartResult.AlreadySyncing

        assertEquals(KompaktAttemptResult.AlreadySyncing, attempt.run(account, KompaktSyncService.entries))
    }

    @Test
    fun `a clean run succeeds`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns outcomeRow(succeeded = true)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.SUCCEEDED))

        assertEquals(KompaktAttemptResult.Succeeded, attempt.run(account, listOf(KompaktSyncService.CALENDAR)))
    }

    @Test
    fun `a failed run reports the service so Try again can retry only that one`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.ServerProblem)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.FAILED))

        assertEquals(
            KompaktAttemptResult.Failed(setOf(KompaktSyncService.CALENDAR)),
            attempt.run(account, listOf(KompaktSyncService.CALENDAR))
        )
    }

    @Test
    fun `an auth failure defers to the re-auth dialog rather than raising its own`() = runTest(UnconfinedTestDispatcher()) {
        every { accountSettings.getReauthNeeded(account) } returns true
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.AuthExpired)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.FAILED))

        assertEquals(KompaktAttemptResult.AuthFailed, attempt.run(account, listOf(KompaktSyncService.CALENDAR)))
    }

    // A stale id cannot wedge the drain: WorkManager prunes finished work after a day, and
    // APPEND_OR_REPLACE can replace a chain, either of which makes getWorkInfoByIdFlow emit null.
    @Test
    fun `a null WorkInfo counts as finished`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns outcomeRow(succeeded = true)
        calendarWork.emit(null)

        assertEquals(KompaktAttemptResult.Succeeded, attempt.run(account, listOf(KompaktSyncService.CALENDAR)))
    }

    // Switching a service off cancels its run, and the worker's isStopped guard skips the write — so the
    // row still sitting there is an earlier attempt's, and reporting it would raise a failure modal for
    // a service the user just switched off.
    @Test
    fun `a cancelled run reports no failure, even over a stale one in the store`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.ServerProblem)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.CANCELLED))

        assertEquals(KompaktAttemptResult.Succeeded, attempt.run(account, listOf(KompaktSyncService.CALENDAR)))
    }

    @Test
    fun `a cancelled run does not mask a sibling that really failed`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns
            started(KompaktSyncService.CALENDAR to calendarId, KompaktSyncService.CONTACTS to contactsId)
        coEvery { outcomes.get(account, any()) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.ServerProblem)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.CANCELLED))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.FAILED))

        assertEquals(
            KompaktAttemptResult.Failed(setOf(KompaktSyncService.CONTACTS)),
            attempt.run(account, KompaktSyncService.entries)
        )
    }

    @Test
    fun `both services failing are reported together, so one modal covers the attempt`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns
            started(KompaktSyncService.CALENDAR to calendarId, KompaktSyncService.CONTACTS to contactsId)
        coEvery { outcomes.get(account, any()) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.ServerProblem)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.FAILED))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.FAILED))

        assertEquals(
            KompaktAttemptResult.Failed(setOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS)),
            attempt.run(account, KompaktSyncService.entries)
        )
    }

    // Losing the network parks a manual run on NetworkType.CONNECTED indefinitely; the watch is what
    // stops the row spinning, and it must cancel every service the attempt started, not just calendar.
    @Test
    fun `losing the network cancels what is in flight and raises no failure of its own`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns
            started(KompaktSyncService.CALENDAR to calendarId, KompaktSyncService.CONTACTS to contactsId)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.RUNNING))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.RUNNING))

        val result = async { attempt.run(account, KompaktSyncService.entries) }

        offlineCause.tryEmit(KompaktOfflineCause.NoNetwork)
        advanceTimeBy(KompaktSyncAttempt.OFFLINE_GRACE_MS + 1)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.CANCELLED))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.CANCELLED))

        assertEquals(KompaktAttemptResult.Interrupted(KompaktOfflineCause.NoNetwork), result.await())
        coVerify { syncWork.cancel(account, KompaktSyncService.CALENDAR) }
        coVerify { syncWork.cancel(account, KompaktSyncService.CONTACTS) }
    }

    // No grace for Offline+: the user just moved the switch, so there is no blip to wait out. Asserted
    // with the clock never advanced -- under the network's grace this would still be running.
    @Test
    fun `Offline plus interrupts at once rather than waiting out the grace period`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.RUNNING))

        val result = async { attempt.run(account, listOf(KompaktSyncService.CALENDAR)) }

        offlineCause.tryEmit(KompaktOfflineCause.OfflinePlus)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.CANCELLED))

        assertEquals(KompaktAttemptResult.Interrupted(KompaktOfflineCause.OfflinePlus), result.await())
        coVerify { syncWork.cancel(account, KompaktSyncService.CALENDAR) }
    }

    // The radios go a moment after Offline+ turns on, so the cause arrives first and the symptom
    // follows. Reporting the symptom would tell the user to check a connection they turned off.
    @Test
    fun `the network dropping after Offline plus does not rename the interruption`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.RUNNING))

        val result = async { attempt.run(account, listOf(KompaktSyncService.CALENDAR)) }

        offlineCause.tryEmit(KompaktOfflineCause.OfflinePlus)
        offlineCause.tryEmit(KompaktOfflineCause.NoNetwork)
        advanceTimeBy(KompaktSyncAttempt.OFFLINE_GRACE_MS + 1)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.CANCELLED))

        assertEquals(KompaktAttemptResult.Interrupted(KompaktOfflineCause.OfflinePlus), result.await())
    }

    // The other order, which is the one that can actually mislead: the radios are seen going first, the
    // grace elapses on that, and only then does the switch broadcast arrive. Naming the connection would
    // tell the user to check something they had just turned off themselves.
    @Test
    fun `Offline plus arriving after the grace has elapsed renames the interruption`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.RUNNING))

        val result = async { attempt.run(account, listOf(KompaktSyncService.CALENDAR)) }

        offlineCause.tryEmit(KompaktOfflineCause.NoNetwork)
        advanceTimeBy(KompaktSyncAttempt.OFFLINE_GRACE_MS + 1)
        offlineCause.tryEmit(KompaktOfflineCause.OfflinePlus)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.CANCELLED))

        assertEquals(KompaktAttemptResult.Interrupted(KompaktOfflineCause.OfflinePlus), result.await())
    }

    @Test
    fun `Offline plus is reported before anything is started`() = runTest {
        coEvery { startSync(account, any(), any()) } returns KompaktSyncStartResult.OfflinePlus

        assertEquals(KompaktAttemptResult.BlockedOfflinePlus, attempt.run(account, KompaktSyncService.entries))
    }

    // Leaving the screen cancels the drain mid-flight. What it took has to go back, because the verdict
    // reads the store for every service it drains: a leaked id would report a failure for a service the
    // next attempt never asked about, and Try again for that service is a no-op.
    @Test
    fun `a cancelled attempt leaves nothing behind for the next one`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, KompaktSyncService.entries, any()) } returns
            started(KompaktSyncService.CALENDAR to calendarId, KompaktSyncService.CONTACTS to contactsId)
        coEvery { startSync(account, listOf(KompaktSyncService.CONTACTS), any()) } returns
            started(KompaktSyncService.CONTACTS to contactsId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.ServerProblem)
        coEvery { outcomes.get(account, KompaktSyncService.CONTACTS) } returns outcomeRow(succeeded = true)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.FAILED))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.RUNNING))

        val abandoned = async { attempt.run(account, KompaktSyncService.entries) }
        abandoned.cancelAndJoin()

        contactsWork.emit(workInfo(contactsId, WorkInfo.State.SUCCEEDED))

        assertEquals(
            KompaktAttemptResult.Succeeded,
            attempt.run(account, listOf(KompaktSyncService.CONTACTS))
        )
    }

    // The grace period exists so a blip does not cost the user a sync.
    @Test
    fun `a blip that recovers inside the grace period neither cancels nor interrupts`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns outcomeRow(succeeded = true)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.RUNNING))

        val result = async { attempt.run(account, listOf(KompaktSyncService.CALENDAR)) }

        offlineCause.tryEmit(KompaktOfflineCause.NoNetwork)
        advanceTimeBy(KompaktSyncAttempt.OFFLINE_GRACE_MS / 2)
        offlineCause.tryEmit(null)
        advanceTimeBy(KompaktSyncAttempt.OFFLINE_GRACE_MS)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.SUCCEEDED))

        assertEquals(KompaktAttemptResult.Succeeded, result.await())
        coVerify(exactly = 0) { syncWork.cancel(any(), any()) }
    }

    // Both callers await the same drain and get the same verdict, so the screen raises one modal.
    @Test
    fun `a second call joins the drain and both callers get the same verdict`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, listOf(KompaktSyncService.CALENDAR), any()) } returns started(KompaktSyncService.CALENDAR to calendarId)
        coEvery { startSync(account, listOf(KompaktSyncService.CONTACTS), any()) } returns started(KompaktSyncService.CONTACTS to contactsId)
        coEvery { outcomes.get(account, any()) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.ServerProblem)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.RUNNING))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.RUNNING))

        val first = async { attempt.run(account, listOf(KompaktSyncService.CALENDAR)) }
        val second = async { attempt.run(account, listOf(KompaktSyncService.CONTACTS)) }

        calendarWork.emit(workInfo(calendarId, WorkInfo.State.FAILED))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.FAILED))

        val both = KompaktAttemptResult.Failed(setOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS))
        assertEquals(both, second.await())
        assertEquals(both, first.await())
    }

    // An interruption outranks a sibling's real failure: with the network gone that failure is almost
    // certainly the same event, and the offline sheet is already up.
    @Test
    fun `an interruption suppresses a failure recorded before the drop`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { startSync(account, any(), any()) } returns
            started(KompaktSyncService.CALENDAR to calendarId, KompaktSyncService.CONTACTS to contactsId)
        coEvery { outcomes.get(account, KompaktSyncService.CALENDAR) } returns
            outcomeRow(succeeded = false, cause = KompaktSyncFailure.NetworkProblem)
        calendarWork.emit(workInfo(calendarId, WorkInfo.State.FAILED))
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.RUNNING))

        val result = async { attempt.run(account, KompaktSyncService.entries) }

        offlineCause.tryEmit(KompaktOfflineCause.NoNetwork)
        advanceTimeBy(KompaktSyncAttempt.OFFLINE_GRACE_MS + 1)
        contactsWork.emit(workInfo(contactsId, WorkInfo.State.CANCELLED))

        assertEquals(KompaktAttemptResult.Interrupted(KompaktOfflineCause.NoNetwork), result.await())
    }

    private fun started(vararg runs: Pair<KompaktSyncService, UUID>) =
        KompaktSyncStartResult.Started(runs.toMap())

    private fun workInfo(id: UUID, state: WorkInfo.State) = WorkInfo(id, state, emptySet())

    private fun outcomeRow(succeeded: Boolean, cause: KompaktSyncFailure? = null) =
        KompaktSyncOutcome(
            serviceId = 7,
            dataType = KompaktSyncService.CALENDAR.dataType.name,
            at = 1_000L,
            succeeded = succeeded,
            cause = cause?.name,
            trigger = KompaktServiceSyncOutcome.TRIGGER_MANUAL,
            detail = null
        )
}
