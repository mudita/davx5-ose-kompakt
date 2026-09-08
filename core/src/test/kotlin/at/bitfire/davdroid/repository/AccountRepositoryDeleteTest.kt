/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.accounts.Account
import android.content.Context
import at.bitfire.davdroid.R
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.resource.LocalAddressBookStore
import at.bitfire.davdroid.resource.LocalCalendarStore
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.AutomaticSyncManager
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.TasksAppManager
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

private const val ACCOUNT_TYPE = "test.account.type"

// delete() removed the account but never stopped sync work, unlike rename() which always has: a sync
// in flight kept running, queued jobs stayed queued, and periodic workers kept firing against an
// account that no longer existed. These cover the fix, for both the main account and its address books.
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class AccountRepositoryDeleteTest {

    // core's unit tests don't merge Android resources (it would break Robolectric's targetSdk ceiling
    // elsewhere), so R.string.account_type can't resolve for real; spy just that one lookup instead.
    private val context = spyk(RuntimeEnvironment.getApplication() as Context) {
        every { getString(R.string.account_type) } returns ACCOUNT_TYPE
    }
    private val logger = Logger.getGlobal()

    private val collectionRepository = mockk<DavCollectionRepository>()
    private val localAddressBookStore = mockk<LocalAddressBookStore>()
    private val serviceRepository = mockk<DavServiceRepository>()
    private val syncWorkerManager = mockk<SyncWorkerManager>(relaxed = true)

    private val accountRepository = AccountRepository(
        accountSettingsFactory = mockk<AccountSettings.Factory>(),
        automaticSyncManager = Lazy { mockk<AutomaticSyncManager>() },
        context = context,
        collectionRepository = collectionRepository,
        defaultDispatcher = UnconfinedTestDispatcher(),
        homeSetRepository = mockk<DavHomeSetRepository>(),
        localCalendarStore = Lazy { mockk<LocalCalendarStore>() },
        localAddressBookStore = Lazy { localAddressBookStore },
        logger = logger,
        serviceRepository = serviceRepository,
        syncWorkerManager = Lazy { syncWorkerManager },
        tasksAppManager = Lazy { mockk<TasksAppManager>() }
    )

    private val account = Account("user@example.com", ACCOUNT_TYPE)

    @Test
    fun `delete cancels all sync work for the account`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns null
        coEvery { serviceRepository.deleteByAccount(account.name) } returns Unit
        every { localAddressBookStore.getAddressBookAccounts(account) } returns emptyList()

        accountRepository.delete(account.name)

        verify { syncWorkerManager.cancelAllWork(account) }
        for (dataType in SyncDataType.entries)
            verify(exactly = 1) { syncWorkerManager.disablePeriodic(account, dataType) }
    }

    @Test
    fun `delete cancels sync work before purging address books`() = runTest {
        val service = mockk<Service> { every { id } returns 42L }
        val collection = mockk<Collection> { every { id } returns 99L }
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns service
        coEvery { collectionRepository.getByService(42L) } returns listOf(collection)
        every { localAddressBookStore.deleteByCollectionId(99L) } returns Unit
        coEvery { serviceRepository.deleteByAccount(account.name) } returns Unit
        every { localAddressBookStore.getAddressBookAccounts(account) } returns emptyList()

        accountRepository.delete(account.name)

        verifyOrder {
            syncWorkerManager.cancelAllWork(account)
            localAddressBookStore.deleteByCollectionId(99L)
        }
    }

    @Test
    fun `delete cancels sync work for every address book account too`() = runTest {
        val addressBook1 = Account("Address book 1", "address_book_type")
        val addressBook2 = Account("Address book 2", "address_book_type")
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns null
        coEvery { serviceRepository.deleteByAccount(account.name) } returns Unit
        every { localAddressBookStore.getAddressBookAccounts(account) } returns listOf(addressBook1, addressBook2)

        accountRepository.delete(account.name)

        for (addressBookAccount in listOf(addressBook1, addressBook2)) {
            verify { syncWorkerManager.cancelAllWork(addressBookAccount) }
            for (dataType in SyncDataType.entries)
                verify(exactly = 1) { syncWorkerManager.disablePeriodic(addressBookAccount, dataType) }
        }
    }

}
