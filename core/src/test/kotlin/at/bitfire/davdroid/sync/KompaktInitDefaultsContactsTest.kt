/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.content.Context
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.logging.Logger

class KompaktInitDefaultsContactsTest {

    private val account = mockk<Account>(relaxed = true)
    private val accountSettings = mockk<KompaktAccountSettings>(relaxed = true)
    private val collectionRepository = mockk<DavCollectionRepository>(relaxed = true)

    private val initDefaults = KompaktInitDefaults(
        context = mockk<Context>(relaxed = true),
        kompaktAccountSettings = accountSettings,
        collectionRepository = collectionRepository,
        serviceRepository = mockk<DavServiceRepository>(relaxed = true),
        logger = Logger.getLogger(javaClass.name)
    )

    init {
        every { accountSettings.getDefaultsAppliedVersion(any(), any()) } returns null
        every { accountSettings.getLegacyDefaultsAppliedVersion(any()) } returns null
        every { accountSettings.getSyncInterval(any(), any()) } returns null
    }


    @Test
    fun notReadyWhileNoAddressBookIsDiscovered() = runBlocking {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns emptyList()

        assertEquals(
            KompaktInitDefaults.Outcome.NOT_READY,
            initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)
        )

        // Returning before the interval write is what lets the caller retry after discovery. Writing
        // either one here would arm the periodic worker for a selection that never happened.
        coVerify(exactly = 0) { accountSettings.setSyncInterval(any(), any(), any()) }
        coVerify(exactly = 0) { accountSettings.setDefaultsApplied(any(), any(), any()) }
    }

    @Test
    fun ignoresCollectionsThatAreNotAddressBooks() = runBlocking {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns listOf(calendar(id = 9))

        assertEquals(
            KompaktInitDefaults.Outcome.NOT_READY,
            initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)
        )
        coVerify(exactly = 0) { collectionRepository.setSync(any(), any()) }
    }

    @Test
    fun selectsEveryDiscoveredAddressBook() = runBlocking {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns
            listOf(addressBook(id = 1), addressBook(id = 2))

        assertEquals(
            KompaktInitDefaults.Outcome.APPLIED,
            initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)
        )

        coVerify { collectionRepository.setSync(1, true) }
        coVerify { collectionRepository.setSync(2, true) }
        coVerify {
            accountSettings.setSyncInterval(
                account,
                SyncDataType.CONTACTS,
                KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS
            )
        }
        coVerify {
            accountSettings.setDefaultsApplied(
                account,
                KompaktSyncService.CONTACTS,
                KompaktInitDefaults.DEFAULTS_VERSION
            )
        }
    }

    @Test
    fun leavesAnAlreadySelectedAddressBookAlone() = runBlocking {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns
            listOf(addressBook(id = 1, sync = true))

        assertEquals(
            KompaktInitDefaults.Outcome.APPLIED,
            initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)
        )
        coVerify(exactly = 0) { collectionRepository.setSync(any(), any()) }
    }

    @Test
    fun keepsAStoredIntervalSoASwitchedOffServiceStaysOff() = runBlocking {
        // The manual sentinel is what a user who switched Contacts off before discovery finished has
        // stored. Selection still runs; overwriting the interval would turn their choice back on.
        every { accountSettings.getSyncInterval(any(), SyncDataType.CONTACTS) } returns
            AccountSettings.SYNC_INTERVAL_MANUALLY
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns listOf(addressBook(id = 1))

        assertEquals(
            KompaktInitDefaults.Outcome.APPLIED,
            initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)
        )

        coVerify { collectionRepository.setSync(1, true) }
        coVerify(exactly = 0) { accountSettings.setSyncInterval(any(), any(), any()) }
    }


    private fun addressBook(id: Long, sync: Boolean = false) = Collection(
        id = id,
        serviceId = SERVICE_ID,
        type = Collection.TYPE_ADDRESSBOOK,
        url = "https://apidata.googleusercontent.com/carddav/v1/principals/user@example.com/lists/default/".toHttpUrl(),
        sync = sync
    )

    private fun calendar(id: Long) = Collection(
        id = id,
        serviceId = SERVICE_ID,
        type = Collection.TYPE_CALENDAR,
        url = "https://apidata.googleusercontent.com/caldav/v2/user%40example.com/events/".toHttpUrl()
    )

    private companion object {
        const val SERVICE_ID = 7L
    }

}
