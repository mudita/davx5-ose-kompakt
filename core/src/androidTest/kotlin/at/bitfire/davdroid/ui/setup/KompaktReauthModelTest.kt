/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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

    @MockK
    lateinit var resourceFinderFactory: DavResourceFinder.Factory

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
            resourceFinderFactory = resourceFinderFactory,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            logger = logger
        )
        block(model)
    }

    private fun loginInfo(email: String?, withAuthState: Boolean = true, baseUri: URI? = URI("https://apidata.googleusercontent.com/caldav/v2/")) =
        LoginInfo(
            baseUri = baseUri,
            credentials = if (withAuthState) Credentials(authState = authState) else null,
            suggestedAccountName = email
        )

    private fun configWith(calDavEmail: String?) =
        DavResourceFinder.Configuration(
            cardDAV = null,
            calDAV = calDavEmail?.let {
                DavResourceFinder.Configuration.ServiceInfo(emails = mutableListOf(it))
            },
            encountered401 = false,
            logs = ""
        )

    @Test
    fun differentAccount_switchesToNewAccount_usingDetectedEmail() = runModel { model ->
        val finder = mockk<DavResourceFinder>()
        every { resourceFinderFactory.create(any(), any()) } returns finder
        every { finder.findInitialConfiguration() } returns configWith("detected@gmail.com")
        coEvery { accountRepository.delete("a@gmail.com") } returns true
        every { accountRepository.createBlocking(any(), any(), any(), any(), any()) } returns Account("detected@gmail.com", accountType)

        model.apply(accountA, loginInfo(email = "b@gmail.com"))
        advanceUntilIdle()

        coVerifyOrder {
            resourceFinderFactory.create(any(), any())
            accountRepository.delete("a@gmail.com")
            accountRepository.createBlocking("detected@gmail.com", any(), any(), any(), null)
        }
        verify(exactly = 0) { accountSettingsFactory.create(any()) }
        assertTrue(model.done.value)
    }

    @Test
    fun differentAccount_noCalDavDetected_keepsOldAccount() = runModel { model ->
        val finder = mockk<DavResourceFinder>()
        every { resourceFinderFactory.create(any(), any()) } returns finder
        every { finder.findInitialConfiguration() } returns configWith(null)

        model.apply(accountA, loginInfo(email = "b@gmail.com"))
        advanceUntilIdle()

        coVerify(exactly = 0) { accountRepository.delete(any()) }
        verify(exactly = 0) { accountRepository.createBlocking(any(), any(), any(), any(), any()) }
        assertTrue(model.done.value)
    }

    @Test
    fun differentAccount_createFails_deletesOldAndDoesNotCrash() = runModel { model ->
        val finder = mockk<DavResourceFinder>()
        every { resourceFinderFactory.create(any(), any()) } returns finder
        every { finder.findInitialConfiguration() } returns configWith("b@gmail.com")
        coEvery { accountRepository.delete("a@gmail.com") } returns true
        every { accountRepository.createBlocking(any(), any(), any(), any(), any()) } returns null

        model.apply(accountA, loginInfo(email = "b@gmail.com"))
        advanceUntilIdle()

        coVerify { accountRepository.delete("a@gmail.com") }
        verify { accountRepository.createBlocking("b@gmail.com", any(), any(), any(), null) }
        assertTrue(model.done.value)
    }

    @Test
    fun sameAccount_refreshesInPlace_doesNotSwitch() = runModel { model ->
        mockkStatic(AccountManager::class)
        try {
            every { AccountManager.get(any()) } returns mockk(relaxed = true)
            val settings = mockk<AccountSettings>()
            every { accountSettingsFactory.create(accountA) } returns settings
            every { settings.updateAuthState(authState) } just runs

            model.apply(accountA, loginInfo(email = "a@gmail.com"))
            advanceUntilIdle()

            verify { settings.updateAuthState(authState) }
            verify { syncWorkerManager.enqueueOneTimeAllAuthorities(accountA, manual = true) }
            coVerify(exactly = 0) { accountRepository.delete(any()) }
            verify(exactly = 0) { accountRepository.createBlocking(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { resourceFinderFactory.create(any(), any()) }
            assertTrue(model.done.value)
        } finally {
            unmockkStatic(AccountManager::class)
        }
    }

    @Test
    fun noAuthState_doesNothing() = runModel { model ->
        model.apply(accountA, loginInfo(email = "b@gmail.com", withAuthState = false))
        advanceUntilIdle()

        coVerify(exactly = 0) { accountRepository.delete(any()) }
        verify(exactly = 0) { accountRepository.createBlocking(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { resourceFinderFactory.create(any(), any()) }
        verify(exactly = 0) { accountSettingsFactory.create(any()) }
        assertTrue(model.done.value)
    }
}
