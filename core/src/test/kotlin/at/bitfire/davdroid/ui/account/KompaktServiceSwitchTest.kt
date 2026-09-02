/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.KompaktServiceToggle
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.KompaktSyncWork
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktServiceSwitchTest {

    private val account = Account("user@example.com", "bitfire.at.davdroid.mudita")

    private val accountSettings = mockk<KompaktAccountSettings>()
    private val toggle = mockk<KompaktServiceToggle>(relaxed = true)
    private val syncWork = mockk<KompaktSyncWork>(relaxed = true)
    private val initDefaults = mockk<KompaktInitDefaults>(relaxed = true)

    private val switches = KompaktServiceSwitch(accountSettings, toggle, syncWork, initDefaults)

    private fun consentTo(vararg services: KompaktSyncService) {
        every { accountSettings.getAuthState(any()) } returns mockk<AuthState> {
            every { scopeSet } returns services.map { it.scope }.toSet()
        }
    }

    @Test
    fun consentMissingOutranksTheStoredInterval() {
        // A scope granted during a re-auth brings no service, no discovery and no interval with it, so
        // the interval decides between On and Off and consent only vetoes.
        assertEquals(KompaktSyncSwitch.ConsentMissing, kompaktSyncSwitch(consented = false, on = true))
        assertEquals(KompaktSyncSwitch.ConsentMissing, kompaktSyncSwitch(consented = false, on = false))
    }

    @Test
    fun theIntervalDecidesOnceConsentIsGranted() {
        assertEquals(KompaktSyncSwitch.On, kompaktSyncSwitch(consented = true, on = true))
        assertEquals(KompaktSyncSwitch.Off, kompaktSyncSwitch(consented = true, on = false))
    }

    @Test
    fun readsEachServicesPositionIndependently() {
        consentTo(KompaktSyncService.CALENDAR)
        every { toggle.isOn(any(), any()) } returns true

        assertEquals(KompaktSyncSwitch.On, switches.read(account, KompaktSyncService.CALENDAR))
        assertEquals(KompaktSyncSwitch.ConsentMissing, switches.read(account, KompaktSyncService.CONTACTS))
    }

    @Test
    fun switchingOnPersistsAndMarksDefaultsApplied() = runTest {
        switches.setEnabled(account, KompaktSyncService.CALENDAR, on = true)

        coVerifyOrder {
            toggle.set(account, KompaktSyncService.CALENDAR, true)
            initDefaults.markApplied(account, KompaktSyncService.CALENDAR)
        }
        coVerify(exactly = 0) { syncWork.cancel(any(), any()) }
    }

    @Test
    fun switchingOffPersistsBeforeItCancels() = runTest {
        // Persisting first disables the periodic worker and the content trigger; cancelling first would
        // leave a window in which the scheduler restarts what was just cancelled.
        switches.setEnabled(account, KompaktSyncService.CONTACTS, on = false)

        coVerifyOrder {
            toggle.set(account, KompaktSyncService.CONTACTS, false)
            syncWork.cancel(account, KompaktSyncService.CONTACTS)
        }
    }

    @Test
    fun switchingOffOneServiceDoesNotTouchTheOther() = runTest {
        switches.setEnabled(account, KompaktSyncService.CONTACTS, on = false)

        coVerify(exactly = 0) { syncWork.cancel(account, KompaktSyncService.CALENDAR) }
        coVerify(exactly = 0) { toggle.set(account, KompaktSyncService.CALENDAR, any()) }
    }

    @Test
    fun markingOneServicesDefaultsDoesNotMarkTheOther() = runTest {
        switches.setEnabled(account, KompaktSyncService.CALENDAR, on = true)

        coVerify(exactly = 0) { initDefaults.markApplied(account, KompaktSyncService.CONTACTS) }
    }

}
