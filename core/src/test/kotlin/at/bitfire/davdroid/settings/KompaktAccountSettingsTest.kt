/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.davdroid.sync.SyncDataType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

        // Unconfined so a write reaches its collector inline, without kotlinx-coroutines-test.
        settings = KompaktAccountSettingsImpl(
            context = mockk<Context>(relaxed = true),
            accountSettingsFactory = factory,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(AccountManager::class)
    }

    private fun <T> CoroutineScope.collect(flow: Flow<T>): List<T> =
        mutableListOf<T>().also { seen ->
            launch(Dispatchers.Unconfined) { flow.toList(seen) }
        }

    @Test
    fun reauthNeeded_roundTrips() = runBlocking {
        assertFalse(settings.getReauthNeeded(account))

        settings.setReauthNeeded(account, true)
        assertTrue(settings.getReauthNeeded(account))

        settings.setReauthNeeded(account, false)
        assertFalse(settings.getReauthNeeded(account))
    }

    // KompaktAuthStateProvider publishes this key by testing it against "1", and the calendar app
    // reads that, so the stored representation is part of the cross-app contract.
    @Test
    fun reauthNeeded_storesFlagAndClearsToNull() = runBlocking {
        settings.setReauthNeeded(account, true)
        assertEquals("1", userData[account to AccountSettings.KEY_NEEDS_REAUTH])

        settings.setReauthNeeded(account, false)
        assertNull(userData[account to AccountSettings.KEY_NEEDS_REAUTH])
    }

    @Test
    fun observeReauthNeeded_emitsInitialThenChanges() = runBlocking {
        val seen = collect(settings.observeReauthNeeded(account))

        settings.setReauthNeeded(account, true)
        settings.setReauthNeeded(account, false)

        assertEquals(listOf(false, true, false), seen)
        coroutineContext.cancelChildren()
    }

    @Test
    fun observeReauthNeeded_withoutInitial_emitsOnlyChanges() = runBlocking {
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setReauthNeeded(account, true)

        assertEquals(listOf(true), seen)
        coroutineContext.cancelChildren()
    }

    @Test
    fun observe_ignoresOtherAccounts() = runBlocking {
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setReauthNeeded(otherAccount, true)

        assertEquals(emptyList<Boolean>(), seen)
        coroutineContext.cancelChildren()
    }

    @Test
    fun observe_ignoresOtherKeys() = runBlocking {
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setDefaultsApplied(account, 3)

        assertEquals(emptyList<Boolean>(), seen)
        coroutineContext.cancelChildren()
    }

    @Test
    fun observe_doesNotAnnounceAWriteThatChangesNothing() = runBlocking {
        settings.setReauthNeeded(account, true)
        val seen = collect(settings.observeReauthNeeded(account, emitInitial = false))

        settings.setReauthNeeded(account, true)

        assertEquals(emptyList<Boolean>(), seen)
        coroutineContext.cancelChildren()
    }

    @Test
    fun defaultsAppliedVersion_roundTripsAndRejectsGarbage() = runBlocking {
        assertNull(settings.getDefaultsAppliedVersion(account))

        settings.setDefaultsApplied(account, 7)
        assertEquals(7, settings.getDefaultsAppliedVersion(account))

        userData[account to KompaktAccountSettingsImpl.KEY_DEFAULTS_APPLIED] = "not-a-number"
        assertNull(settings.getDefaultsAppliedVersion(account))
    }

    // Unlike AccountSettings.getSyncInterval, which substitutes a four-hour default and so cannot
    // report "never configured".
    @Test
    fun getSyncInterval_isNullWhenNothingStored() {
        assertNull(settings.getSyncInterval(account, SyncDataType.EVENTS))
    }

    @Test
    fun setSyncInterval_delegatesAndAnnouncesTheStoredValue() = runBlocking {
        every { accountSettings.setSyncInterval(SyncDataType.EVENTS, 900) } answers {
            userData[account to AccountSettings.KEY_SYNC_INTERVAL_CALENDARS] = "900"
            true
        }
        val seen = collect(settings.observeSyncInterval(account, SyncDataType.EVENTS))

        settings.setSyncInterval(account, SyncDataType.EVENTS, 900)

        verify { accountSettings.setSyncInterval(SyncDataType.EVENTS, 900) }
        assertEquals(listOf(null, 900L), seen)
        coroutineContext.cancelChildren()
    }

    @Test
    fun updateAuthState_delegatesToAccountSettings() = runBlocking {
        val authState = mockk<AuthState>(relaxed = true)

        settings.updateAuthState(account, authState)

        verify { accountSettings.updateAuthState(authState) }
    }
}
