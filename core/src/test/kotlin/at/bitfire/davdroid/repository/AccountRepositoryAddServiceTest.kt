/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.content.Context
import at.bitfire.davdroid.R
import at.bitfire.davdroid.resource.LocalAddressBookStore
import at.bitfire.davdroid.resource.LocalCalendarStore
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.AutomaticSyncManager
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.TasksAppManager
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

/**
 * [AccountRepository.addServiceBlocking] writes the row and nothing that reads the token: the account
 * may still hold the one the grant replaces.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class AccountRepositoryAddServiceTest {

    // core's unit tests don't merge Android resources, so R.string.account_type can't resolve for real.
    private val context = spyk(RuntimeEnvironment.getApplication() as Context) {
        every { getString(R.string.account_type) } returns "test.account.type"
    }

    private val serviceRepository = mockk<DavServiceRepository> {
        every { insertOrReplaceBlocking(any()) } returns SERVICE_ID
    }

    private val accountRepository = AccountRepository(
        accountSettingsFactory = mockk<AccountSettings.Factory>(),
        automaticSyncManager = Lazy { mockk<AutomaticSyncManager>() },
        context = context,
        collectionRepository = mockk<DavCollectionRepository>(),
        defaultDispatcher = UnconfinedTestDispatcher(),
        homeSetRepository = mockk<DavHomeSetRepository>(),
        localCalendarStore = Lazy { mockk<LocalCalendarStore>() },
        localAddressBookStore = Lazy { mockk<LocalAddressBookStore>() },
        logger = Logger.getGlobal(),
        serviceRepository = serviceRepository,
        syncWorkerManager = Lazy { mockk<SyncWorkerManager>() },
        tasksAppManager = Lazy { mockk<TasksAppManager>() }
    )

    @Before
    fun setUp() {
        mockkObject(RefreshCollectionsWorker.Companion)
        every { RefreshCollectionsWorker.enqueue(any(), any()) } returns ("" to mockk())
    }

    @After
    fun tearDown() {
        unmockkObject(RefreshCollectionsWorker.Companion)
    }

    @Test
    fun `adding a service fetches none of its collections`() {
        val id = accountRepository.addServiceBlocking(
            "user@example.com",
            KompaktSyncService.CALENDAR,
            DavResourceFinder.Configuration.ServiceInfo()
        )

        assertEquals(SERVICE_ID, id)
        verify(exactly = 0) { RefreshCollectionsWorker.enqueue(any(), any()) }
    }

    private companion object {
        const val SERVICE_ID = 7L
    }

}
