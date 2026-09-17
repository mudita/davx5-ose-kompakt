/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.content.ContentProviderClient
import android.content.Context
import at.bitfire.davdroid.R
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.resource.LocalAddressBookStore
import at.bitfire.davdroid.resource.LocalCalendar
import at.bitfire.davdroid.resource.LocalCalendarStore
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.AutomaticSyncManager
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.TasksAppManager
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

private const val ACCOUNT_TYPE = "test.account.type"
private const val ACCOUNT_NAME = "user@example.com"

/**
 * [AccountRepository.removeService] — the counterpart to `addServiceBlocking`. The synced copies have
 * to go before the row, because deleting it cascades the collections they are found by.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class AccountRepositoryRemoveServiceTest {

    // core's unit tests don't merge Android resources, so R.string.account_type can't resolve for real.
    private val context = spyk(RuntimeEnvironment.getApplication() as Context) {
        every { getString(R.string.account_type) } returns ACCOUNT_TYPE
    }

    private val collectionRepository = mockk<DavCollectionRepository>(relaxed = true)
    private val localAddressBookStore = mockk<LocalAddressBookStore>(relaxed = true)
    private val localCalendarStore = mockk<LocalCalendarStore>(relaxed = true)
    private val serviceRepository = mockk<DavServiceRepository>(relaxed = true)

    private val accountRepository = AccountRepository(
        accountSettingsFactory = mockk<AccountSettings.Factory>(),
        automaticSyncManager = Lazy { mockk<AutomaticSyncManager>() },
        context = context,
        collectionRepository = collectionRepository,
        defaultDispatcher = UnconfinedTestDispatcher(),
        homeSetRepository = mockk<DavHomeSetRepository>(),
        localCalendarStore = Lazy { localCalendarStore },
        localAddressBookStore = Lazy { localAddressBookStore },
        logger = Logger.getGlobal(),
        serviceRepository = serviceRepository,
        syncWorkerManager = Lazy { mockk<SyncWorkerManager>() },
        tasksAppManager = Lazy { mockk<TasksAppManager>() }
    )

    private val serviceId = 7L
    private val collectionId = 11L

    private fun serviceRow(type: String) {
        coEvery { serviceRepository.getByAccountAndType(ACCOUNT_NAME, type) } returns
            mockk<Service> { every { id } returns serviceId }
        coEvery { collectionRepository.getByService(serviceId) } returns
            listOf(mockk<Collection> { every { id } returns collectionId })
    }

    @Test
    fun `contacts - address books go by both lookups, then the row`() = runTest {
        serviceRow(Service.TYPE_CARDDAV)

        accountRepository.removeService(ACCOUNT_NAME, KompaktSyncService.CONTACTS)

        coVerifyOrder {
            localAddressBookStore.deleteByAccount(accountRepository.fromName(ACCOUNT_NAME))
            localAddressBookStore.deleteByCollectionId(collectionId)
            serviceRepository.deleteById(serviceId)
        }
    }

    @Test
    fun `calendar - the local calendar goes, then the row`() = runTest {
        serviceRow(Service.TYPE_CALDAV)
        val localCalendar = mockk<LocalCalendar>()
        every { localCalendarStore.acquireContentProvider(throwOnMissingPermissions = false) } returns
            mockk<ContentProviderClient>(relaxed = true)
        every { localCalendarStore.getByDbCollectionId(any(), any(), collectionId) } returns localCalendar

        accountRepository.removeService(ACCOUNT_NAME, KompaktSyncService.CALENDAR)

        coVerifyOrder {
            localCalendarStore.delete(localCalendar)
            serviceRepository.deleteById(serviceId)
        }
    }

    // Without a provider the calendars can't be reached; the row must survive so a retry can still find
    // them, rather than being orphaned on the device with no collection rows left to match.
    @Test
    fun `calendar - no provider leaves both the calendars and the row alone`() = runTest {
        serviceRow(Service.TYPE_CALDAV)
        every { localCalendarStore.acquireContentProvider(throwOnMissingPermissions = false) } returns null

        accountRepository.removeService(ACCOUNT_NAME, KompaktSyncService.CALENDAR)

        coVerify(exactly = 0) { localCalendarStore.delete(any()) }
        coVerify(exactly = 0) { serviceRepository.deleteById(any()) }
    }

    @Test
    fun `a service with no row is a no-op`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(ACCOUNT_NAME, Service.TYPE_CARDDAV) } returns null

        accountRepository.removeService(ACCOUNT_NAME, KompaktSyncService.CONTACTS)

        coVerify(exactly = 0) { localAddressBookStore.deleteByAccount(any()) }
        coVerify(exactly = 0) { serviceRepository.deleteById(any()) }
    }

}
