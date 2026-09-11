/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.TEST_ACCOUNT_NAME
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.KompaktSyncOutcomeRepository
import at.bitfire.davdroid.ui.account.Reported
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class KompaktServiceSyncOutcomeTest {

    private companion object {
        const val CALDAV_SERVICE_ID = 7L
        const val CARDDAV_SERVICE_ID = 8L
    }

    private val account = mockAccount()
    private val caldavService =
        Service(id = CALDAV_SERVICE_ID, accountName = TEST_ACCOUNT_NAME, type = Service.TYPE_CALDAV)

    // Replayed rather than a StateFlow: a StateFlow conflates equal values on its own, which would
    // pass the distinctUntilChanged expectation below even if the operator were dropped.
    private val rows = MutableSharedFlow<KompaktSyncOutcome?>(extraBufferCapacity = 8)
    private val serviceRows = MutableSharedFlow<Service?>(extraBufferCapacity = 8)

    private lateinit var serviceRepository: DavServiceRepository
    private lateinit var outcomes: KompaktSyncOutcomeRepository
    private lateinit var source: KompaktServiceSyncOutcome

    @Before
    fun setUp() {
        serviceRepository = mockk()
        every { serviceRepository.getServiceFlow(TEST_ACCOUNT_NAME, any()) } returns serviceRows
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CALDAV) } returns caldavService
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CARDDAV) } returns
            Service(id = CARDDAV_SERVICE_ID, accountName = TEST_ACCOUNT_NAME, type = Service.TYPE_CARDDAV)

        outcomes = mockk(relaxed = true)
        every { outcomes.observe(any(), any()) } returns rows

        source = KompaktServiceSyncOutcome(serviceRepository, outcomes)
    }

    private fun TestScope.observing(service: KompaktSyncService): List<Reported<KompaktSyncOutcome?>> =
        mutableListOf<Reported<KompaktSyncOutcome?>>().also { seen ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                source.observe(account, service).toList(seen)
            }
        }

    @Test
    fun `record resolves the account to its service row`() = runTest {
        source.record(account, KompaktSyncService.CONTACTS, KompaktSyncFailure.ServerProblem, manual = true)

        coVerify {
            outcomes.record(
                serviceId = CARDDAV_SERVICE_ID,
                dataType = SyncDataType.CONTACTS,
                succeeded = false,
                cause = KompaktSyncFailure.ServerProblem.name,
                trigger = KompaktServiceSyncOutcome.TRIGGER_MANUAL,
                detail = null
            )
        }
    }

    @Test
    fun `a null cause records a success, and an automatic run its trigger`() = runTest {
        source.record(account, KompaktSyncService.CALENDAR, cause = null, manual = false, detail = "d")

        coVerify {
            outcomes.record(
                serviceId = CALDAV_SERVICE_ID,
                dataType = SyncDataType.EVENTS,
                succeeded = true,
                cause = null,
                trigger = KompaktServiceSyncOutcome.TRIGGER_AUTOMATIC,
                detail = "d"
            )
        }
    }

    // An account unlinked mid-sync has already cascaded the row away, and an unguarded insert would
    // fail the foreign key inside the worker.
    @Test
    fun `record is a no-op when the service row is gone`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CARDDAV) } returns null

        source.record(account, KompaktSyncService.CONTACTS, cause = null, manual = false)

        coVerify(exactly = 0) { outcomes.record(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `get resolves the account and asks for that service`() = runTest {
        val row = mockk<KompaktSyncOutcome>()
        coEvery { outcomes.get(CALDAV_SERVICE_ID, SyncDataType.EVENTS) } returns row

        assertEquals(row, source.get(account, KompaktSyncService.CALENDAR))
    }

    @Test
    fun `get returns null when the service row is gone`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CALDAV) } returns null

        assertNull(source.get(account, KompaktSyncService.CALENDAR))
        coVerify(exactly = 0) { outcomes.get(any(), any()) }
    }

    // Without the pending value the first frame paints a tick from syncstats and then repaints to the
    // alert icon, which is the repaint the e-ink screen must not do.
    @Test
    fun `observe reports Pending before anything has been read`() = runTest {
        val seen = observing(KompaktSyncService.CALENDAR)

        assertEquals(listOf(Reported.Pending), seen)
    }

    @Test
    fun `observe reports the stored row against the resolved service`() = runTest {
        val seen = observing(KompaktSyncService.CALENDAR)
        val row = mockk<KompaktSyncOutcome>()

        serviceRows.emit(caldavService)
        rows.emit(row)

        assertEquals(listOf(Reported.Pending, Reported.Value(row)), seen)
        verify { outcomes.observe(CALDAV_SERVICE_ID, SyncDataType.EVENTS) }
    }

    // No row is "not failed", which the cell renders as the last-sync time rather than an alert.
    @Test
    fun `observe reports a null value when the service row is absent`() = runTest {
        val seen = observing(KompaktSyncService.CONTACTS)

        serviceRows.emit(null)

        assertEquals(listOf(Reported.Pending, Reported.Value(null)), seen)
    }

    @Test
    fun `observe does not report the same value twice`() = runTest {
        val seen = observing(KompaktSyncService.CALENDAR)

        serviceRows.emit(caldavService)
        rows.emit(null)
        rows.emit(null)

        assertEquals(listOf(Reported.Pending, Reported.Value(null)), seen)
    }

}
