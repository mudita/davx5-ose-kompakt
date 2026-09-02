/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.SyncDataType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktServiceLastSyncTest {

    private val account = Account("user@example.com", "bitfire.at.davdroid.mudita")

    private val serviceRepository = mockk<DavServiceRepository>()
    private val syncStatsRepository = mockk<DavSyncStatsRepository>()

    private val lastSync = KompaktServiceLastSync(serviceRepository, syncStatsRepository)

    private fun serviceRow(id: Long) = mockk<Service>().also { every { it.id } returns id }

    @Test
    fun reportsPendingBeforeTheFirstValue() = runTest {
        every { serviceRepository.getServiceFlow(account.name, KompaktSyncService.CALENDAR.serviceType) } returns flowOf(serviceRow(7L))
        every { syncStatsRepository.lastSyncFlow(7L, SyncDataType.EVENTS) } returns flowOf(500L)

        assertEquals(
            listOf(Reported.Pending, Reported.Value(500L)),
            lastSync.observe(account, KompaktSyncService.CALENDAR).toList()
        )
    }

    @Test
    fun readsTheServicesOwnRowAndDataType() = runTest {
        every { serviceRepository.getServiceFlow(account.name, KompaktSyncService.CONTACTS.serviceType) } returns flowOf(serviceRow(9L))
        every { syncStatsRepository.lastSyncFlow(9L, SyncDataType.CONTACTS) } returns flowOf(1_200L)

        assertEquals(
            listOf(Reported.Pending, Reported.Value(1_200L)),
            lastSync.observe(account, KompaktSyncService.CONTACTS).toList()
        )
        verify { syncStatsRepository.lastSyncFlow(9L, SyncDataType.CONTACTS) }
    }

    @Test
    fun neverSyncedWhenNoCollectionIsSelected() = runTest {
        // MAX over zero rows is NULL, so the shipping Contacts configuration needs no special case.
        every { serviceRepository.getServiceFlow(account.name, KompaktSyncService.CONTACTS.serviceType) } returns flowOf(serviceRow(9L))
        every { syncStatsRepository.lastSyncFlow(9L, SyncDataType.CONTACTS) } returns flowOf(null)

        assertEquals(
            listOf(Reported.Pending, Reported.Value<Long?>(null)),
            lastSync.observe(account, KompaktSyncService.CONTACTS).toList()
        )
    }

    @Test
    fun neverSyncedWhenTheAccountHasNoServiceRow() = runTest {
        // The one case the query cannot answer, because there is no service id to ask about.
        every { serviceRepository.getServiceFlow(account.name, KompaktSyncService.CONTACTS.serviceType) } returns flowOf(null)

        assertEquals(
            listOf(Reported.Pending, Reported.Value<Long?>(null)),
            lastSync.observe(account, KompaktSyncService.CONTACTS).toList()
        )
        verify(exactly = 0) { syncStatsRepository.lastSyncFlow(any(), any()) }
    }

}
