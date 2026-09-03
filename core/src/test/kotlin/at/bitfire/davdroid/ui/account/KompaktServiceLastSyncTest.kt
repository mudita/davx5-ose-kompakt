/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.TEST_ACCOUNT_NAME
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.SyncDataType
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
import org.junit.Before
import org.junit.Test

class KompaktServiceLastSyncTest {

    companion object {
        private const val EMAIL = TEST_ACCOUNT_NAME
        private const val CALDAV_SERVICE_ID = 7L
        private const val CARDDAV_SERVICE_ID = 8L
    }

    private val account = mockAccount()

    private val caldavService = Service(id = CALDAV_SERVICE_ID, accountName = EMAIL, type = Service.TYPE_CALDAV)

    // SharedFlows, not StateFlows: both sources are Room queries that re-emit on any write to the
    // table, equal values included, which is what the distinctUntilChanged under test is for. A
    // StateFlow would conflate those repeats itself and hide it.
    private val serviceRows = MutableSharedFlow<Service?>(extraBufferCapacity = 8)
    private val lastSyncTimes = MutableSharedFlow<Long?>(extraBufferCapacity = 8)

    private lateinit var serviceRepository: DavServiceRepository
    private lateinit var syncStatsRepository: DavSyncStatsRepository
    private lateinit var lastSync: KompaktServiceLastSync

    @Before
    fun setUp() {
        serviceRepository = mockk()
        every { serviceRepository.getServiceFlow(EMAIL, any()) } returns serviceRows

        syncStatsRepository = mockk()
        every { syncStatsRepository.lastSyncFlow(any(), any()) } returns lastSyncTimes

        lastSync = KompaktServiceLastSync(serviceRepository, syncStatsRepository)
    }

    @Test
    fun reportsPendingBeforeAnythingHasBeenRead() = runTest {
        val seen = observe()

        assertEquals(listOf(Reported.Pending), seen)
    }

    @Test
    fun reportsNoTimeWhenTheServiceIsNotConfigured() = runTest {
        // A settled null, not a pending one: an unconfigured service has no time to wait for, and the
        // screen would otherwise stay loading forever.
        val seen = observe()

        serviceRows.emit(null)

        assertEquals(listOf(Reported.Pending, Reported.Value(null)), seen)
    }

    @Test
    fun reportsTheStoredTimeOnceTheServiceExists() = runTest {
        val seen = observe()

        serviceRows.emit(caldavService)
        lastSyncTimes.emit(1_700_000_000_000)

        assertEquals(listOf(Reported.Pending, Reported.Value(1_700_000_000_000)), seen)
    }

    @Test
    fun followsTheServiceRowWhenDiscoveryCreatesItLater() = runTest {
        val seen = observe()

        serviceRows.emit(null)
        serviceRows.emit(caldavService)
        lastSyncTimes.emit(1_700_000_000_000)

        assertEquals(
            listOf(Reported.Pending, Reported.Value(null), Reported.Value(1_700_000_000_000)),
            seen
        )
    }

    @Test
    fun reportsANeverSyncedServiceAsNoTime() = runTest {
        val seen = observe()

        serviceRows.emit(caldavService)
        lastSyncTimes.emit(null)

        assertEquals(listOf(Reported.Pending, Reported.Value(null)), seen)
    }

    @Test
    fun doesNotRepaintForAWriteThatChangesNoTime() = runTest {
        val seen = observe()

        serviceRows.emit(caldavService)
        lastSyncTimes.emit(1_700_000_000_000)
        lastSyncTimes.emit(1_700_000_000_000)

        assertEquals(listOf(Reported.Pending, Reported.Value(1_700_000_000_000)), seen)
    }

    @Test
    fun asksForTheServiceAndDataTypeOfTheServiceItWasGiven() = runTest {
        val carddavService =
            Service(id = CARDDAV_SERVICE_ID, accountName = EMAIL, type = Service.TYPE_CARDDAV)
        observe(KompaktSyncService.CONTACTS)

        serviceRows.emit(carddavService)

        verify { serviceRepository.getServiceFlow(EMAIL, Service.TYPE_CARDDAV) }
        verify { syncStatsRepository.lastSyncFlow(CARDDAV_SERVICE_ID, SyncDataType.CONTACTS) }
    }

    // Unconfined so the collector subscribes before the first emission and receives each one inline;
    // backgroundScope cancels it when the test ends.
    private fun TestScope.observe(
        service: KompaktSyncService = KompaktSyncService.CALENDAR
    ): List<Reported<Long?>> {
        val seen = mutableListOf<Reported<Long?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            lastSync.observe(account, service).toList(seen)
        }
        return seen
    }

}
