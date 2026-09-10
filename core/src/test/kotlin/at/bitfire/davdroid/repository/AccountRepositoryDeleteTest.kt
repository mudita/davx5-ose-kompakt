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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

private const val ACCOUNT_TYPE = "test.account.type"

// delete() removed the account but never stopped sync work, unlike rename() which always has: a sync
// in flight kept running, queued jobs stayed queued, and periodic workers kept firing against an
// account that no longer existed. These cover the fix for the main account's sync work.
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

    @Before
    fun setUp() {
        every { localAddressBookStore.deleteByAccount(account) } returns Unit
    }

    @Test
    fun `delete cancels all sync work for the account`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns null
        coEvery { serviceRepository.deleteByAccount(account.name) } returns Unit

        accountRepository.delete(account.name)

        verify { syncWorkerManager.cancelAllWork(account) }
    }

    @Test
    fun `delete cancels sync work before purging address books`() = runTest {
        val service = mockk<Service> { every { id } returns 42L }
        val collection = mockk<Collection> { every { id } returns 99L }
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns service
        coEvery { collectionRepository.getByService(42L) } returns listOf(collection)
        every { localAddressBookStore.deleteByCollectionId(99L) } returns Unit
        coEvery { serviceRepository.deleteByAccount(account.name) } returns Unit

        accountRepository.delete(account.name)

        verifyOrder {
            syncWorkerManager.cancelAllWork(account)
            localAddressBookStore.deleteByCollectionId(99L)
        }
    }

    // An address book whose collection row is stale, duplicated or gone is invisible to the
    // per-collection lookup, and used to survive the unlink with all of its contacts.
    @Test
    fun `delete purges address books by owner account, not only by collection row`() = runTest {
        val service = mockk<Service> { every { id } returns 42L }
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns service
        coEvery { collectionRepository.getByService(42L) } returns emptyList()
        coEvery { serviceRepository.deleteByAccount(account.name) } returns Unit

        accountRepository.delete(account.name)

        verify { localAddressBookStore.deleteByAccount(account) }
    }

    @Test
    fun `delete removes the account even if cancelling sync work fails`() = runTest {
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns null
        coEvery { serviceRepository.deleteByAccount(account.name) } returns Unit
        every { syncWorkerManager.cancelAllWork(account) } throws RuntimeException("boom")

        val result = accountRepository.delete(account.name)

        assertTrue(result)
    }

}
