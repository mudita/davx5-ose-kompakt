/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import at.bitfire.davdroid.R
import at.bitfire.davdroid.TestUtils
import at.bitfire.davdroid.resource.LocalAddressBookStore
import at.bitfire.davdroid.resource.LocalCalendar
import at.bitfire.davdroid.resource.LocalCalendarStore
import at.bitfire.davdroid.resource.LocalDataStore
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.AutomaticSyncManager
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.TasksAppManager
import at.bitfire.davdroid.sync.account.AccountsCleanupWorker
import at.bitfire.davdroid.sync.account.TestAccount
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class AccountRepositoryTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val mockKRule = MockKRule(this)

    // System under test

    @Inject
    lateinit var accountRepository: AccountRepository

    // Real injections

    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var accountSettingsFactory: AccountSettings.Factory

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Dependency overrides

    @BindValue @MockK(relaxed = true)
    lateinit var automaticSyncManager: AutomaticSyncManager

    @BindValue @MockK(relaxed = true)
    lateinit var localAddressBookStore: LocalAddressBookStore

    @BindValue @MockK(relaxed = true)
    lateinit var localCalendarStore: LocalCalendarStore

    @BindValue @MockK(relaxed = true)
    lateinit var serviceRepository: DavServiceRepository

    @BindValue @MockK(relaxed = true)
    lateinit var syncWorkerManager: SyncWorkerManager

    @BindValue @MockK(relaxed = true)
    lateinit var tasksAppManager: TasksAppManager


    // Account setup
    private val newName = "Renamed Account"
    lateinit var am: AccountManager
    lateinit var accountType: String
    lateinit var account: Account

    @Before
    fun setUp() {
        hiltRule.inject()
        TestUtils.setUpWorkManager(context, workerFactory)

        // Account setup
        am = AccountManager.get(context)
        accountType = context.getString(R.string.account_type)
        account = TestAccount.create()

        // AccountsCleanupWorker static mocking
        mockkObject(AccountsCleanupWorker)
        every { AccountsCleanupWorker.lockAccountsCleanup() } returns Unit
    }

    @After
    fun tearDown() {
        am.getAccountsByType(accountType).forEach { account ->
            am.removeAccountExplicitly(account)
        }

        unmockkObject(AccountsCleanupWorker)
        clearAllMocks()
    }


    // testRename

    @Test(expected = IllegalArgumentException::class)
    fun testRename_checksForAlreadyExisting() = runTest {
        val existing = Account("Existing Account", accountType)
        am.addAccountExplicitly(existing, null, null)

        accountRepository.rename(account.name, existing.name)
    }

    @Test
    fun testRename_locksAccountsCleanup() = runTest {
        accountRepository.rename(account.name, newName)

        verify { AccountsCleanupWorker.lockAccountsCleanup() }
    }

    @Test
    fun testRename_renamesAccountInAndroid() = runTest {
        accountRepository.rename(account.name, newName)

        val accountsAfter = am.getAccountsByType(accountType)
        assertTrue(accountsAfter.any { it.name == newName })
    }

    @Test
    fun testRename_cancelsRunningSynchronizationOfOldAccount() = runTest {
        accountRepository.rename(account.name, newName)

        coVerify { syncWorkerManager.cancelAllWork(account) }
    }

    @Test
    fun testRename_disablesPeriodicSyncsForOldAccount() = runTest {
        accountRepository.rename(account.name, newName)

        for (dataType in SyncDataType.entries)
            coVerify(exactly = 1) {
                syncWorkerManager.disablePeriodic(account, dataType)
            }
    }

    @Test
    fun testRename_updatesAccountNameReferencesInDatabase() = runTest {
        accountRepository.rename(account.name, newName)

        coVerify { serviceRepository.renameAccount(account.name, newName) }
    }

    @Test
    fun testRename_updatesAddressBooks() = runTest {
        accountRepository.rename(account.name, newName)

        val newAccount = accountRepository.fromName(newName)
        coVerify { localAddressBookStore.updateAccount(account, newAccount, any()) }
    }

    @Test
    fun testRename_updatesCalendarEvents() = runTest {
        accountRepository.rename(account.name, newName)

        val newAccount = accountRepository.fromName(newName)
        coVerify { localCalendarStore.updateAccount(account, newAccount, any()) }
    }

    @Test
    fun testRename_updatesAccountNameOfLocalTasks() = runTest {
        val mockDataStore = mockk<LocalDataStore<*>>(relaxed = true)
        every { tasksAppManager.getDataStore() } returns mockDataStore
        accountRepository.rename(account.name, newName)

        val newAccount = accountRepository.fromName(newName)
        coVerify { mockDataStore.updateAccount(account, newAccount, any()) }
    }

    @Test
    fun testRename_updatesAutomaticSync() = runTest {
        accountRepository.rename(account.name, newName)

        val newAccount = accountRepository.fromName(newName)
        coVerify { automaticSyncManager.updateAutomaticSync(newAccount) }
    }

    @Test
    fun testRename_releasesAccountsCleanupWorkerMutex() = runTest {
        accountRepository.rename(account.name, newName)

        verify { AccountsCleanupWorker.lockAccountsCleanup() }
        coVerify { serviceRepository.renameAccount(account.name, newName) }
    }


    // testDelete

    @Test
    fun testDelete_purgesSyncedCalendarsThroughProvider() = runTest {
        val client = mockk<ContentProviderClient>(relaxed = true)
        val calendar1 = mockk<LocalCalendar>(relaxed = true)
        val calendar2 = mockk<LocalCalendar>(relaxed = true)
        every { localCalendarStore.acquireContentProvider(any()) } returns client
        every { localCalendarStore.getAll(account, client) } returns listOf(calendar1, calendar2)

        accountRepository.delete(account.name)

        verify { localCalendarStore.getAll(account, client) }
        verify(exactly = 1) { localCalendarStore.delete(calendar1) }
        verify(exactly = 1) { localCalendarStore.delete(calendar2) }
    }

    @Test
    fun testDelete_purgesCalendarsBeforeRemovingAccount() = runTest {
        val client = mockk<ContentProviderClient>(relaxed = true)
        var accountStillExistedDuringPurge = false
        every { localCalendarStore.acquireContentProvider(any()) } returns client
        every { localCalendarStore.getAll(account, client) } answers {
            accountStillExistedDuringPurge =
                am.getAccountsByType(accountType).any { it.name == account.name }
            emptyList()
        }

        accountRepository.delete(account.name)

        // purge ran while the account still existed => before removeAccountExplicitly
        assertTrue(accountStillExistedDuringPurge)
        // account was actually removed
        assertTrue(am.getAccountsByType(accountType).none { it.name == account.name })
    }

    @Test
    fun testDelete_stillRemovesAccountWhenCalendarProviderUnavailable() = runTest {
        // provider unavailable (e.g. missing permission) => acquireContentProvider returns null
        every { localCalendarStore.acquireContentProvider(any()) } returns null

        val result = accountRepository.delete(account.name)

        assertTrue(result)
        verify(exactly = 0) { localCalendarStore.delete(any()) }
        assertTrue(am.getAccountsByType(accountType).none { it.name == account.name })
    }

    @Test
    fun testDelete_stillRemovesAccountWhenCalendarPurgeThrows() = runTest {
        // a provider failure during the purge must not prevent account removal
        every { localCalendarStore.acquireContentProvider(any()) } throws RuntimeException("boom")

        val result = accountRepository.delete(account.name)

        assertTrue(result)
        assertTrue(am.getAccountsByType(accountType).none { it.name == account.name })
    }

}
