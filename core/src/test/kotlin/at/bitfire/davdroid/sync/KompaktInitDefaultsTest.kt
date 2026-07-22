/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.AccountSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

/**
 * Guards the Kompakt sync bootstrap that fixes SHP-1070: after linking, only the user's **primary**
 * Google calendar is selected for synchronization, the auto-sync default is enabled **only on the very
 * first setup**, and the whole thing runs at most once per [KompaktInitDefaults.DEFAULTS_VERSION]. If any
 * of these break, an auto-sync could sync the wrong calendar, nothing at all, or clobber the user's
 * auto-sync choice.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktInitDefaultsTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val account = Account("user@gmail.com", "bitfire.at.davdroid")
    private val serviceId = 42L

    // simulated AccountManager userData for [account], driving appliedVersion()/markApplied()
    private val userData = mutableMapOf<String, String?>()
    private val accountManager = mockk<AccountManager>()

    private val collectionRepository = mockk<DavCollectionRepository>(relaxed = true)
    private val serviceRepository = mockk<DavServiceRepository>(relaxed = true)
    private val accountSettings = mockk<AccountSettings>(relaxed = true)
    private val accountSettingsFactory = mockk<AccountSettings.Factory> {
        every { create(any<Account>(), any()) } returns accountSettings
        every { create(any<Account>()) } returns accountSettings
    }

    private val initDefaults = KompaktInitDefaults(
        context = context,
        accountSettingsFactory = accountSettingsFactory,
        collectionRepository = collectionRepository,
        serviceRepository = serviceRepository,
        logger = Logger.getGlobal()
    )

    @Before
    fun setUp() {
        mockkStatic(AccountManager::class)
        every { AccountManager.get(any()) } returns accountManager
        every { accountManager.getUserData(account, any()) } answers { userData[secondArg()] }
        every { accountManager.setUserData(account, any(), any()) } answers {
            userData[secondArg()] = thirdArg()
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun calendar(id: Long, url: String, displayName: String? = null, sync: Boolean = false) =
        Collection(
            id = id,
            serviceId = serviceId,
            type = Collection.TYPE_CALENDAR,
            url = url.toHttpUrl(),
            displayName = displayName,
            sync = sync
        )


    // findPrimaryCalendarId

    @Test
    fun `findPrimaryCalendarId picks the calendar whose URL contains the account email`() {
        val calendars = listOf(
            calendar(1, "https://apidata.googleusercontent.com/caldav/v2/other.calendar/events/"),
            calendar(2, "https://apidata.googleusercontent.com/caldav/v2/user%40gmail.com/events/"),
            calendar(3, "https://apidata.googleusercontent.com/caldav/v2/holidays/events/")
        )
        assertEquals(2L, initDefaults.findPrimaryCalendarId(account, calendars))
    }

    @Test
    fun `findPrimaryCalendarId falls back to the calendar whose display name equals the email`() {
        val calendars = listOf(
            calendar(1, "https://example.com/cal/a/", displayName = "Birthdays"),
            calendar(2, "https://example.com/cal/b/", displayName = "user@gmail.com")
        )
        assertEquals(2L, initDefaults.findPrimaryCalendarId(account, calendars))
    }

    @Test
    fun `findPrimaryCalendarId returns null when no calendar identifies the primary`() {
        val calendars = listOf(
            calendar(1, "https://example.com/cal/a/", displayName = "Birthdays"),
            calendar(2, "https://example.com/cal/b/", displayName = "Work")
        )
        assertNull(initDefaults.findPrimaryCalendarId(account, calendars))
    }


    // maybeApply

    @Test
    fun `maybeApply on first setup selects only the primary calendar and enables auto-sync`() = runBlocking {
        val primary = calendar(1, "https://gcal/user%40gmail.com/events/", sync = false)
        val secondary = calendar(2, "https://gcal/holidays/events/", sync = true)
        coEvery { collectionRepository.getByService(serviceId) } returns listOf(primary, secondary)

        val outcome = initDefaults.maybeApply(account, serviceId)

        assertEquals(KompaktInitDefaults.Outcome.APPLIED, outcome)
        coVerify(exactly = 1) { collectionRepository.setSync(1L, true) }   // select the primary
        coVerify(exactly = 1) { collectionRepository.setSync(2L, false) }  // deselect the secondary
        // first setup (version 0) → enable automatic sync by default
        verify(exactly = 1) {
            accountSettings.setSyncInterval(SyncDataType.EVENTS, KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS)
        }
        assertEquals(KompaktInitDefaults.DEFAULTS_VERSION.toString(), userData[KompaktInitDefaults.KEY_DEFAULTS_APPLIED])
    }

    @Test
    fun `maybeApply does nothing once defaults are already applied for the current version`() = runBlocking {
        userData[KompaktInitDefaults.KEY_DEFAULTS_APPLIED] = KompaktInitDefaults.DEFAULTS_VERSION.toString()

        val outcome = initDefaults.maybeApply(account, serviceId)

        assertEquals(KompaktInitDefaults.Outcome.ALREADY_APPLIED, outcome)
        coVerify(exactly = 0) { collectionRepository.setSync(any(), any()) }
        verify(exactly = 0) { accountSettings.setSyncInterval(any(), any()) }
    }

    @Test
    fun `maybeApply returns NOT_READY and changes nothing when the primary can't be identified yet`() = runBlocking {
        coEvery { collectionRepository.getByService(serviceId) } returns listOf(
            calendar(1, "https://gcal/holidays/events/", displayName = "Holidays")
        )

        val outcome = initDefaults.maybeApply(account, serviceId)

        assertEquals(KompaktInitDefaults.Outcome.NOT_READY, outcome)
        coVerify(exactly = 0) { collectionRepository.setSync(any(), any()) }
        // must NOT mark applied — the caller should retry once discovery completes
        assertNull(userData[KompaktInitDefaults.KEY_DEFAULTS_APPLIED])
    }

    @Test
    fun `maybeApply returns NOT_READY when no calendars are discovered yet`() = runBlocking {
        coEvery { collectionRepository.getByService(serviceId) } returns emptyList()

        val outcome = initDefaults.maybeApply(account, serviceId)

        assertEquals(KompaktInitDefaults.Outcome.NOT_READY, outcome)
        assertNull(userData[KompaktInitDefaults.KEY_DEFAULTS_APPLIED])
    }

    @Test
    fun `maybeApply on a defaults-version bump re-selects the primary but does not touch auto-sync`() = runBlocking {
        // an existing account that already ran an older defaults version (1 < DEFAULTS_VERSION)
        userData[KompaktInitDefaults.KEY_DEFAULTS_APPLIED] = "1"
        val primary = calendar(1, "https://gcal/user%40gmail.com/events/", sync = false)
        coEvery { collectionRepository.getByService(serviceId) } returns listOf(primary)

        val outcome = initDefaults.maybeApply(account, serviceId)

        assertEquals(KompaktInitDefaults.Outcome.APPLIED, outcome)
        coVerify(exactly = 1) { collectionRepository.setSync(1L, true) }
        // version bump (not first setup) must respect the user's auto-sync choice → don't set the interval
        verify(exactly = 0) { accountSettings.setSyncInterval(any(), any()) }
        assertEquals(KompaktInitDefaults.DEFAULTS_VERSION.toString(), userData[KompaktInitDefaults.KEY_DEFAULTS_APPLIED])
    }

}
