/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import at.bitfire.davdroid.TEST_ACCOUNT_NAME
import at.bitfire.davdroid.db.AppDatabase
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.db.KompaktSyncOutcomeDao
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.sync.KompaktSyncFailure
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.ui.account.Reported
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KompaktSyncOutcomeRepositoryTest {

    private companion object {
        const val CALDAV_SERVICE_ID = 7L
    }

    private val account = mockAccount()
    private val caldavService =
        Service(id = CALDAV_SERVICE_ID, accountName = TEST_ACCOUNT_NAME, type = Service.TYPE_CALDAV)

    private val rows = MutableSharedFlow<KompaktSyncOutcome?>(extraBufferCapacity = 8)
    private val serviceRows = MutableSharedFlow<Service?>(extraBufferCapacity = 8)

    private lateinit var dao: KompaktSyncOutcomeDao
    private lateinit var serviceRepository: DavServiceRepository
    private lateinit var repository: KompaktSyncOutcomeRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        every { dao.observe(any(), any()) } returns rows

        serviceRepository = mockk()
        every { serviceRepository.getServiceFlow(TEST_ACCOUNT_NAME, any()) } returns serviceRows

        val db = mockk<AppDatabase>()
        every { db.kompaktSyncOutcomeDao() } returns dao

        repository = KompaktSyncOutcomeRepository(db, serviceRepository)
    }

    @Test
    fun `record writes a failure row with id zero, so REPLACE resolves on the unique index`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CALDAV) } returns caldavService

        repository.record(account, KompaktSyncService.CALENDAR, KompaktSyncFailure.ServerProblem, manual = true)

        val written = slot<KompaktSyncOutcome>()
        coVerify { dao.insertOrReplace(capture(written)) }
        assertEquals(0L, written.captured.id)
        assertEquals(CALDAV_SERVICE_ID, written.captured.serviceId)
        assertEquals(KompaktSyncService.CALENDAR.dataType.name, written.captured.dataType)
        assertEquals(false, written.captured.succeeded)
        assertEquals(KompaktSyncFailure.ServerProblem.name, written.captured.cause)
        assertEquals(KompaktSyncOutcomeRepository.TRIGGER_MANUAL, written.captured.trigger)
    }

    @Test
    fun `record writes a success row with no cause`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CALDAV) } returns caldavService

        repository.record(account, KompaktSyncService.CALENDAR, cause = null, manual = false)

        val written = slot<KompaktSyncOutcome>()
        coVerify { dao.insertOrReplace(capture(written)) }
        assertTrue(written.captured.succeeded)
        assertEquals(null, written.captured.cause)
        assertEquals(KompaktSyncOutcomeRepository.TRIGGER_AUTOMATIC, written.captured.trigger)
    }

    // An account unlinked mid-run cascades the service row away; writing anyway would throw
    // SQLiteConstraintException out of the worker.
    @Test
    fun `record is a no-op when the service row is gone`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CALDAV) } returns null

        repository.record(account, KompaktSyncService.CALENDAR, KompaktSyncFailure.Unknown, manual = true)

        coVerify(exactly = 0) { dao.insertOrReplace(any()) }
    }

    @Test
    fun `get returns null when the service row is gone`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(TEST_ACCOUNT_NAME, Service.TYPE_CARDDAV) } returns null

        assertEquals(null, repository.get(account, KompaktSyncService.CONTACTS))
        coVerify(exactly = 0) { dao.get(any(), any()) }
    }

    @Test
    fun `observe reports Pending first, then the row`() = runTest {
        val seen = mutableListOf<Reported<KompaktSyncOutcome?>>()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val job = scope.launch {
            repository.observe(account, KompaktSyncService.CALENDAR).toList(seen)
        }

        serviceRows.emit(caldavService)
        rows.emit(null)

        assertEquals(Reported.Pending, seen.first())
        assertEquals(Reported.Value(null), seen.last())
        job.cancel()
    }

    @Test
    fun `observe reports a null value when the service row is absent`() = runTest {
        val seen = mutableListOf<Reported<KompaktSyncOutcome?>>()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val job = scope.launch {
            repository.observe(account, KompaktSyncService.CONTACTS).toList(seen)
        }

        serviceRows.emit(null)

        assertEquals(Reported.Value(null), seen.last())
        job.cancel()
    }
}
