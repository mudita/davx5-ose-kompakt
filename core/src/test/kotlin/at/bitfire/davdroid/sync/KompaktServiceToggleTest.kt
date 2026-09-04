/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KompaktServiceToggleTest {

    // Mocked rather than real: the unit-test android.jar leaves Account.equals throwing, and the
    // toggle only ever hands the Account on to the settings seam.
    private val account = mockk<Account>()

    private lateinit var accountSettings: KompaktAccountSettings
    private lateinit var toggle: KompaktServiceToggleImpl

    @Before
    fun setUp() {
        accountSettings = mockk(relaxed = true)
        toggle = KompaktServiceToggleImpl(accountSettings)
    }


    // toggleOn

    @Test
    fun onWhenTheStoredIntervalIsTheKompaktInterval() {
        assertTrue(toggleOn(KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS))
    }

    @Test
    fun offWhenTheUserSwitchedItOff() {
        // setSyncInterval(null) stores a sentinel rather than clearing the key.
        assertFalse(toggleOn(AccountSettings.SYNC_INTERVAL_MANUALLY))
    }

    @Test
    fun offWhenNothingIsStored() {
        // An absent key is not "on": upstream substitutes a four-hour default for it, so reading it as
        // on would report a toggle the user never set.
        assertFalse(toggleOn(null))
    }

    @Test
    fun offForAnyOtherStoredInterval() {
        assertFalse(toggleOn(4 * 60 * 60L))
        assertFalse(toggleOn(0L))
    }


    // isOn

    @Test
    fun readsTheIntervalOfTheServiceItWasAskedAbout() {
        every { accountSettings.getSyncInterval(account, SyncDataType.EVENTS) } returns
            KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS
        every { accountSettings.getSyncInterval(account, SyncDataType.CONTACTS) } returns null

        assertTrue(toggle.isOn(account, KompaktSyncService.CALENDAR))
        assertFalse(toggle.isOn(account, KompaktSyncService.CONTACTS))
    }


    // observe

    @Test
    fun reportsEveryStoredIntervalAsAPosition() = runTest {
        val intervals = MutableSharedFlow<Long?>(extraBufferCapacity = 8)
        every { accountSettings.observeSyncInterval(account, SyncDataType.EVENTS, true) } returns intervals

        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            toggle.observe(account, KompaktSyncService.CALENDAR).toList(seen)
        }

        intervals.emit(null)
        intervals.emit(KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS)
        intervals.emit(AccountSettings.SYNC_INTERVAL_MANUALLY)

        assertEquals(listOf(false, true, false), seen)
    }


    // set

    @Test
    fun switchingOnStoresTheKompaktInterval() = runTest {
        coEvery { accountSettings.setSyncInterval(any(), any(), any()) } returns Unit

        toggle.set(account, KompaktSyncService.CALENDAR, on = true)

        coVerify {
            accountSettings.setSyncInterval(
                account,
                SyncDataType.EVENTS,
                KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS
            )
        }
    }

    @Test
    fun switchingOffStoresNoInterval() = runTest {
        // null, not zero and not the sentinel: turning that into the stored manual value is the
        // settings seam's job, and it reschedules the periodic worker while doing so.
        coEvery { accountSettings.setSyncInterval(any(), any(), any()) } returns Unit

        toggle.set(account, KompaktSyncService.CONTACTS, on = false)

        coVerify { accountSettings.setSyncInterval(account, SyncDataType.CONTACTS, null) }
    }

}
