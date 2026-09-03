/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import at.bitfire.davdroid.mockAuthState
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktServiceToggle
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.KompaktSyncWork
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KompaktServiceSwitchTest {

    // Mocked rather than real: the unit-test android.jar leaves Account.equals throwing, and the
    // switch only hands the Account on to its seams.
    private val account = mockk<Account>()

    private val authStates = MutableSharedFlow<AuthState?>(extraBufferCapacity = 8)
    private val toggles = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)

    private lateinit var accountSettings: KompaktAccountSettings
    private lateinit var toggle: KompaktServiceToggle
    private lateinit var syncWork: KompaktSyncWork
    private lateinit var switch: KompaktServiceSwitch

    @Before
    fun setUp() {
        accountSettings = mockk(relaxed = true)
        every { accountSettings.observeAuthState(account, true) } returns authStates

        toggle = mockk(relaxed = true)
        every { toggle.observe(account, any()) } returns toggles

        syncWork = mockk(relaxed = true)

        switch = KompaktServiceSwitch(accountSettings, toggle, syncWork)
    }


    // read

    @Test
    fun readsConsentMissingWhileTheScopeIsUngranted() {
        every { accountSettings.getAuthState(account) } returns
            mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR)
        every { toggle.isOn(account, KompaktSyncService.CONTACTS) } returns true

        assertEquals(
            KompaktSyncSwitch.ConsentMissing,
            switch.read(account, KompaktSyncService.CONTACTS)
        )
    }

    @Test
    fun readsTheStoredToggleOnceTheScopeIsGranted() {
        every { accountSettings.getAuthState(account) } returns
            mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR, KompaktOAuthGoogle.SCOPE_CONTACTS)
        every { toggle.isOn(account, KompaktSyncService.CALENDAR) } returns true
        every { toggle.isOn(account, KompaktSyncService.CONTACTS) } returns false

        assertEquals(KompaktSyncSwitch.On, switch.read(account, KompaktSyncService.CALENDAR))
        assertEquals(KompaktSyncSwitch.Off, switch.read(account, KompaktSyncService.CONTACTS))
    }


    // observe

    @Test
    fun observesNothingUntilBothSourcesHaveReported() = runTest {
        val seen = observe(KompaktSyncService.CALENDAR)

        authStates.emit(mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR))

        assertEquals(emptyList<KompaktSyncSwitch>(), seen)
    }

    @Test
    fun reactsToAScopeGrantedByAReauthorization() = runTest {
        val seen = observe(KompaktSyncService.CONTACTS)

        authStates.emit(mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR))
        toggles.emit(true)
        authStates.emit(
            mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR, KompaktOAuthGoogle.SCOPE_CONTACTS)
        )

        assertEquals(listOf(KompaktSyncSwitch.ConsentMissing, KompaktSyncSwitch.On), seen)
    }

    @Test
    fun reactsToTheToggleBeingSwitched() = runTest {
        val seen = observe(KompaktSyncService.CALENDAR)

        authStates.emit(mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR))
        toggles.emit(true)
        toggles.emit(false)

        assertEquals(listOf(KompaktSyncSwitch.On, KompaktSyncSwitch.Off), seen)
    }

    @Test
    fun doesNotRepaintForAChangeTheSwitchCannotShow() = runTest {
        // A refreshed access token re-emits the whole auth state; the position it derives is the same
        // one, and repeating it repaints a screen that ghosts.
        val seen = observe(KompaktSyncService.CALENDAR)

        authStates.emit(mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR))
        toggles.emit(true)
        authStates.emit(mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR))
        toggles.emit(true)

        assertEquals(listOf(KompaktSyncSwitch.On), seen)
    }


    // setEnabled

    @Test
    fun persistsBeforeCancellingTheRunningWork() = runTest {
        // The persisted write is what disables the periodic worker and the content trigger, so
        // cancelling first leaves a window for the scheduler to restart the run.
        switch.setEnabled(account, KompaktSyncService.CALENDAR, on = false)

        coVerifyOrder {
            toggle.set(account, KompaktSyncService.CALENDAR, false)
            syncWork.cancel(account, KompaktSyncService.CALENDAR)
        }
    }

    @Test
    fun switchingOnCancelsNothing() = runTest {
        switch.setEnabled(account, KompaktSyncService.CONTACTS, on = true)

        coVerify { toggle.set(account, KompaktSyncService.CONTACTS, true) }
        coVerify(exactly = 0) { syncWork.cancel(any(), any()) }
    }

    @Test
    fun cancelsOnlyTheServiceThatWasSwitchedOff() = runTest {
        switch.setEnabled(account, KompaktSyncService.CONTACTS, on = false)

        coVerify(exactly = 0) { syncWork.cancel(account, KompaktSyncService.CALENDAR) }
    }

    // Unconfined so the collector subscribes before the first emission and receives each one inline;
    // backgroundScope cancels it when the test ends.
    private fun TestScope.observe(service: KompaktSyncService): List<KompaktSyncSwitch> {
        val seen = mutableListOf<KompaktSyncSwitch>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            switch.observe(account, service).toList(seen)
        }
        return seen
    }

}
