/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit4.MockKRule
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.util.logging.Logger

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
class KompaktReauthModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val mockkRule = MockKRule(this)

    @RelaxedMockK
    lateinit var context: Context

    @MockK
    lateinit var accountSettingsFactory: AccountSettings.Factory

    @RelaxedMockK
    lateinit var syncWorkerManager: SyncWorkerManager

    @MockK
    lateinit var accountRepository: AccountRepository

    @RelaxedMockK
    lateinit var logger: Logger

    private val accountType = "at.bitfire.davdroid.mudita"
    private val accountA = Account("a@gmail.com", accountType)
    private val authState = mockk<AuthState>(relaxed = true)

    private fun runModel(block: suspend TestScope.(KompaktReauthModel) -> Unit) = runTest {
        val model = KompaktReauthModel(
            context = context,
            accountSettingsFactory = accountSettingsFactory,
            syncWorkerManager = syncWorkerManager,
            accountRepository = accountRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            logger = logger
        )
        block(model)
    }

    private fun loginInfo(email: String?, withAuthState: Boolean = true) =
        LoginInfo(
            baseUri = URI("https://apidata.googleusercontent.com/caldav/v2/"),
            credentials = if (withAuthState) Credentials(authState = authState) else null,
            suggestedAccountName = email
        )

    @Test
    fun sameAccount_refreshesInPlace_andEmitsRefreshed_withoutUnlinking() = runModel { model ->
        mockkStatic(AccountManager::class)
        try {
            val am = mockk<AccountManager>(relaxed = true)
            every { AccountManager.get(any()) } returns am
            val settings = mockk<AccountSettings>()
            every { accountSettingsFactory.create(accountA) } returns settings
            every { settings.updateAuthState(authState) } just runs

            model.apply(accountA, loginInfo(email = "a@gmail.com"))
            advanceUntilIdle()

            verify { settings.updateAuthState(authState) }
            verify { am.setUserData(accountA, AccountSettings.KEY_NEEDS_REAUTH, null) }
            verify { syncWorkerManager.enqueueOneTimeAllAuthorities(accountA, manual = true) }
            coVerify(exactly = 0) { accountRepository.delete(any()) }   // A never unlinked
            assertEquals(KompaktReauthModel.ReauthState.Refreshed, model.state.value)
        } finally {
            unmockkStatic(AccountManager::class)
        }
    }

    @Test
    fun differentAccount_movesToSwitching_withoutTouchingAnyAccount() = runModel { model ->
        model.apply(accountA, loginInfo(email = "b@gmail.com"))
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state is KompaktReauthModel.ReauthState.SwitchingToNewAccount)
        assertEquals("b@gmail.com", (state as KompaktReauthModel.ReauthState.SwitchingToNewAccount).loginInfo.suggestedAccountName)
        coVerify(exactly = 0) { accountRepository.delete(any()) }        // A not unlinked yet (screen unlinks after linking B)
        verify(exactly = 0) { accountSettingsFactory.create(any()) }     // no in-place refresh
    }

    @Test
    fun noAuthState_emitsRefreshed_withoutTouchingAnyAccount() = runModel { model ->
        model.apply(accountA, loginInfo(email = "b@gmail.com", withAuthState = false))
        advanceUntilIdle()

        assertEquals(KompaktReauthModel.ReauthState.Refreshed, model.state.value)
        coVerify(exactly = 0) { accountRepository.delete(any()) }
        verify(exactly = 0) { accountSettingsFactory.create(any()) }
    }

    @Test
    fun completeSwitch_unlinksOldAccount_andReachesDone() = runModel { model ->
        coEvery { accountRepository.delete("a@gmail.com") } returns true

        model.completeSwitch(accountA)
        advanceUntilIdle()

        coVerify { accountRepository.delete("a@gmail.com") }
        assertEquals(KompaktReauthModel.ReauthState.Done, model.state.value)
    }
}
