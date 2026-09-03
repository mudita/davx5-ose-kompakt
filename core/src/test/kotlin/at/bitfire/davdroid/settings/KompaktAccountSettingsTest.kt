/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.SyncDataType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KompaktAccountSettingsTest {

    // Mocked rather than real Accounts: the unit-test android.jar leaves Account.equals throwing and
    // its name/type fields null, and the change channel keys on Account. MockK gives a mock
    // reference-based equals and hashCode, which is the identity this class actually relies on — it
    // never reads either field, it only hands the Account back to AccountManager.
    private val account = mockk<Account>()
    private val otherAccount = mockk<Account>()

    private val userData = mutableMapOf<Pair<Account, String>, String?>()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var accountSettings: AccountSettings
    private lateinit var settings: KompaktAccountSettingsImpl

    @Before
    fun setUp() {
        val accountManager = mockk<AccountManager>(relaxed = true)
        every { accountManager.getUserData(any(), any()) } answers {
            userData[firstArg<Account>() to secondArg()]
        }
        every { accountManager.setUserData(any(), any(), any()) } answers {
            userData[firstArg<Account>() to secondArg()] = thirdArg()
            Unit
        }

        mockkStatic(AccountManager::class)
        every { AccountManager.get(any()) } returns accountManager

        accountSettings = mockk(relaxed = true)
        val factory = mockk<AccountSettings.Factory>()
        every { factory.create(any(), any()) } returns accountSettings

        settings = KompaktAccountSettingsImpl(
            context = mockk<Context>(relaxed = true),
            accountSettingsFactory = factory,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(AccountManager::class)
    }

    // Unconfined so the collector subscribes before the first write and receives each announcement
    // inline; backgroundScope cancels it when the test ends.
    private fun <T> TestScope.collect(flow: Flow<T>): List<T> =
        mutableListOf<T>().also { seen ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(seen) }
        }

    @Test
    fun reauthNeeded_roundTrips() = runTest(testDispatcher) {
        assertFalse(settings.getReauthNeeded(account))

        settings.setReauthNeeded(account, true)
        assertTrue(settings.getReauthNeeded(account))

        settings.setReauthNeeded(account, false)
        assertFalse(settings.getReauthNeeded(account))
    }

    // KompaktAuthStateProvider publishes this key by testing it against "1", and the calendar app
    // reads that, so the stored representation is part of the cross-app contract.
    @Test
    fun reauthNeeded_storesFlagAndClearsToNull() = runTest(testDispatcher) {
        settings.setReauthNeeded(account, true)
        assertEquals("1", userData[account to AccountSettings.KEY_NEEDS_REAUTH])

        settings.setReauthNeeded(account, false)
        assertNull(userData[account to AccountSettings.KEY_NEEDS_REAUTH])
    }

    @Test
    fun observeReauthNeeded_emitsInitialThenChanges() = runTest(testDispatcher) {
        val seen = collect(settings.observeReauthNeeded(account))

        settings.setReauthNeeded(account, true)
        settings.setReauthNeeded(account, false)

        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun observeReauthNeeded_withoutInitial_emitsOnlyChanges() = runTest(testDispatcher) {
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setReauthNeeded(account, true)

        assertEquals(listOf(true), seen)
    }

    @Test
    fun observe_ignoresOtherAccounts() = runTest(testDispatcher) {
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setReauthNeeded(otherAccount, true)

        assertEquals(emptyList<Boolean>(), seen)
    }

    @Test
    fun observe_ignoresOtherKeys() = runTest(testDispatcher) {
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setDefaultsApplied(account, KompaktSyncService.CALENDAR, 3)

        assertEquals(emptyList<Boolean>(), seen)
    }

    @Test
    fun observe_doesNotAnnounceAWriteThatChangesNothing() = runTest(testDispatcher) {
        settings.setReauthNeeded(account, true)
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setReauthNeeded(account, true)

        assertEquals(emptyList<Boolean>(), seen)
    }

    @Test
    fun defaultsAppliedVersion_roundTripsAndRejectsGarbage() = runTest(testDispatcher) {
        assertNull(settings.getDefaultsAppliedVersion(account, KompaktSyncService.CALENDAR))

        settings.setDefaultsApplied(account, KompaktSyncService.CALENDAR, 7)
        assertEquals(7, settings.getDefaultsAppliedVersion(account, KompaktSyncService.CALENDAR))

        userData[account to "${KompaktAccountSettingsImpl.KEY_DEFAULTS_APPLIED}_calendar"] = "not-a-number"
        assertNull(settings.getDefaultsAppliedVersion(account, KompaktSyncService.CALENDAR))
    }

    // Unlike AccountSettings.getSyncInterval, which substitutes a four-hour default and so cannot
    // report "never configured".
    @Test
    fun getSyncInterval_isNullWhenNothingStored() {
        assertNull(settings.getSyncInterval(account, SyncDataType.EVENTS))
    }

    @Test
    fun setSyncInterval_delegatesAndAnnouncesTheStoredValue() = runTest(testDispatcher) {
        every { accountSettings.setSyncInterval(SyncDataType.EVENTS, 900) } answers {
            userData[account to AccountSettings.KEY_SYNC_INTERVAL_CALENDARS] = "900"
            true
        }
        val seen = collect(settings.observeSyncInterval(account, SyncDataType.EVENTS))

        settings.setSyncInterval(account, SyncDataType.EVENTS, 900)

        verify { accountSettings.setSyncInterval(SyncDataType.EVENTS, 900) }
        assertEquals(listOf(null, 900L), seen)
    }

    @Test
    fun updateAuthState_delegatesToAccountSettings() = runTest(testDispatcher) {
        val authState = mockk<AuthState>(relaxed = true)

        settings.updateAuthState(account, authState)

        verify { accountSettings.updateAuthState(authState) }
    }

    @Test
    fun authStateOf_readsNothingStoredAsNoAuthorization() {
        assertNull(authStateOf(null))
    }

    @Test
    fun authStateOf_readsStoredNonsenseAsNoAuthorization() {
        // Null rather than a throw: the linked-account screen resolves this while composing, and an
        // account whose stored authorization cannot be parsed is one that needs re-authorizing, not
        // one that crashes the screen.
        assertNull(authStateOf("not json"))
    }
}
