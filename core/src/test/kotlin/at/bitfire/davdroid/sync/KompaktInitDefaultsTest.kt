/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.Context
import at.bitfire.davdroid.TEST_ACCOUNT_NAME
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.logging.Logger

class KompaktInitDefaultsTest {

    companion object {
        private const val EMAIL = TEST_ACCOUNT_NAME
        private const val SERVICE_ID = 7L
    }

    private val account = mockAccount()

    private lateinit var accountSettings: KompaktAccountSettings
    private lateinit var collectionRepository: DavCollectionRepository
    private lateinit var initDefaults: KompaktInitDefaults

    @Before
    fun setUp() {
        accountSettings = mockk(relaxed = true)
        // Explicit rather than relaxed: a nullable Long or Int is what "nothing stored" is expressed
        // with, and a relaxed mock answers those with a zero.
        every { accountSettings.getDefaultsAppliedVersion(any(), any()) } returns null
        every { accountSettings.getLegacyDefaultsAppliedVersion(any()) } returns null
        every { accountSettings.getSyncInterval(any(), any()) } returns null
        coEvery { accountSettings.setSyncInterval(any(), any(), any()) } returns Unit

        collectionRepository = mockk(relaxed = true)
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns emptyList()

        initDefaults = KompaktInitDefaults(
            context = mockk<Context>(relaxed = true),
            kompaktAccountSettings = accountSettings,
            collectionRepository = collectionRepository,
            serviceRepository = mockk<DavServiceRepository>(relaxed = true),
            logger = mockk<Logger>(relaxed = true)
        )
    }


    // appliedVersionOf

    @Test
    fun prefersThePerServiceMarker() {
        assertEquals(2, appliedVersionOf(perService = 2, legacy = 0, service = KompaktSyncService.CALENDAR))
        assertEquals(2, appliedVersionOf(perService = 2, legacy = 0, service = KompaktSyncService.CONTACTS))
    }

    @Test
    fun theLegacyMarkerCountsForCalendarOnly() {
        // The single pre-existing key meant "calendar defaults done"; reading it as a Contacts value
        // would skip Contacts setup on every account that already exists.
        assertEquals(2, appliedVersionOf(perService = null, legacy = 2, service = KompaktSyncService.CALENDAR))
        assertEquals(0, appliedVersionOf(perService = null, legacy = 2, service = KompaktSyncService.CONTACTS))
    }

    @Test
    fun freshAccountIsVersionZeroForBothServices() {
        assertEquals(0, appliedVersionOf(perService = null, legacy = null, service = KompaktSyncService.CALENDAR))
        assertEquals(0, appliedVersionOf(perService = null, legacy = null, service = KompaktSyncService.CONTACTS))
    }


    // maybeApply: the marker

    @Test
    fun aServiceAlreadyAtThisVersionIsLeftAlone() = runTest {
        every {
            accountSettings.getDefaultsAppliedVersion(account, KompaktSyncService.CALENDAR)
        } returns KompaktInitDefaults.DEFAULTS_VERSION

        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.ALREADY_APPLIED, outcome)
        coVerify(exactly = 0) { collectionRepository.getByService(any()) }
        coVerify(exactly = 0) { accountSettings.setSyncInterval(any(), any(), any()) }
    }

    @Test
    fun theMarkerIsWrittenForTheServiceItRanFor() = runTest {
        initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)

        coVerify {
            accountSettings.setDefaultsApplied(
                account,
                KompaktSyncService.CONTACTS,
                KompaktInitDefaults.DEFAULTS_VERSION
            )
        }
        coVerify(exactly = 0) {
            accountSettings.setDefaultsApplied(any(), KompaktSyncService.CALENDAR, any())
        }
    }


    // maybeApply: collection selection

    @Test
    fun selectsThePrimaryCalendarAndNothingElse() = runTest {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns listOf(
            calendar(id = 1, url = "https://apidata.googleusercontent.com/caldav/v2/user%40example.com/events/"),
            calendar(id = 2, url = "https://apidata.googleusercontent.com/caldav/v2/holidays/events/", sync = true)
        )

        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.APPLIED, outcome)
        coVerify { collectionRepository.setSync(1, true) }
        coVerify { collectionRepository.setSync(2, false) }
    }

    @Test
    fun leavesACollectionWhoseSelectionIsAlreadyRight() = runTest {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns listOf(
            calendar(id = 1, url = "https://caldav.example/user%40example.com/events/", sync = true),
            calendar(id = 2, url = "https://caldav.example/holidays/events/")
        )

        initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, SERVICE_ID)

        coVerify(exactly = 0) { collectionRepository.setSync(any(), any()) }
    }

    @Test
    fun ignoresCollectionsThatAreNotCalendars() = runTest {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns listOf(
            Collection(
                id = 9,
                serviceId = SERVICE_ID,
                type = Collection.TYPE_ADDRESSBOOK,
                url = "https://carddav.example/user%40example.com/contacts/".toHttpUrl()
            )
        )

        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.NOT_READY, outcome)
        coVerify(exactly = 0) { collectionRepository.setSync(any(), any()) }
    }

    @Test
    fun notReadyBeforeDiscoveryHasFoundAnyCalendar() = runTest {
        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.NOT_READY, outcome)
        coVerify(exactly = 0) { accountSettings.setDefaultsApplied(any(), any(), any()) }
        coVerify(exactly = 0) { accountSettings.setSyncInterval(any(), any(), any()) }
    }

    @Test
    fun notReadyRatherThanLockingInASecondaryCalendar() = runTest {
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns listOf(
            calendar(id = 1, url = "https://caldav.example/holidays/events/", displayName = "Holidays")
        )

        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.NOT_READY, outcome)
        coVerify(exactly = 0) { accountSettings.setDefaultsApplied(any(), any(), any()) }
    }

    @Test
    fun contactsSelectsNoCollectionYet() = runTest {
        // Selection is what creates the local address book, and the syncer deletes local collections
        // on a run that finds none in the database — which a 403 from a narrowed scope produces.
        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.APPLIED, outcome)
        coVerify(exactly = 0) { collectionRepository.getByService(any()) }
        coVerify(exactly = 0) { collectionRepository.setSync(any(), any()) }
    }


    // maybeApply: the interval

    @Test
    fun writesTheKompaktIntervalForTheServiceWhenNothingIsStored() = runTest {
        initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)

        coVerify {
            accountSettings.setSyncInterval(
                account,
                SyncDataType.CONTACTS,
                KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS
            )
        }
    }

    @Test
    fun keepsTheChoiceOfAUserWhoSwitchedTheServiceOffBeforeDiscoveryFinished() = runTest {
        // The stored manual sentinel is a choice, not an absent key: overwriting it would turn the
        // service back on and re-arm the periodic worker.
        every {
            accountSettings.getSyncInterval(account, SyncDataType.CONTACTS)
        } returns AccountSettings.SYNC_INTERVAL_MANUALLY

        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.APPLIED, outcome)
        coVerify(exactly = 0) { accountSettings.setSyncInterval(any(), any(), any()) }
        coVerify { accountSettings.setDefaultsApplied(account, KompaktSyncService.CONTACTS, any()) }
    }

    @Test
    fun aFailedIntervalWriteStillMarksTheServiceApplied() = runTest {
        // The account can disappear mid-setup; retrying selection forever because of it is worse than
        // leaving the interval unwritten.
        coEvery {
            accountSettings.setSyncInterval(any(), any(), any())
        } throws IllegalStateException("account gone")

        val outcome = initDefaults.maybeApply(account, KompaktSyncService.CONTACTS, SERVICE_ID)

        assertEquals(KompaktInitDefaults.Outcome.APPLIED, outcome)
        coVerify { accountSettings.setDefaultsApplied(account, KompaktSyncService.CONTACTS, any()) }
    }

    private fun calendar(
        id: Long,
        url: String,
        displayName: String? = null,
        sync: Boolean = false
    ) = Collection(
        id = id,
        serviceId = SERVICE_ID,
        type = Collection.TYPE_CALENDAR,
        url = url.toHttpUrl(),
        displayName = displayName,
        sync = sync
    )

}
