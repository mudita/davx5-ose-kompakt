/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.startup

import android.accounts.Account
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.ui.KompaktAccountStatePublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KompaktAccountStateReplicatorTest {

    private val accountA = mockk<Account>()
    private val accountB = mockk<Account>()

    // Replaying SharedFlows rather than StateFlows, so repeats reach the replicator as they would in production.
    private val accounts = MutableSharedFlow<Set<Account>>(replay = 1, extraBufferCapacity = 8)
    private val reauthFlows = mutableMapOf<Account, MutableSharedFlow<Boolean>>()
    private val shownFlows = mutableMapOf<Account, MutableSharedFlow<Boolean>>()
    private var published = 0

    private lateinit var accountRepository: AccountRepository
    private lateinit var accountSettings: KompaktAccountSettings

    @Before
    fun setUp() {
        accountRepository = mockk()
        every { accountRepository.getAllFlow() } returns accounts

        // Stubbed only for emitInitial = false, so a subscription that would announce on every app start finds no answer.
        accountSettings = mockk()
        every { accountSettings.observeReauthNeeded(any(), false) } answers { flowOf(reauthFlows, firstArg()) }
        every { accountSettings.observeNewContactsConsentShown(any(), false) } answers { flowOf(shownFlows, firstArg()) }
    }

    private fun flowOf(flows: MutableMap<Account, MutableSharedFlow<Boolean>>, account: Account) =
        flows.getOrPut(account) { MutableSharedFlow(extraBufferCapacity = 8) }

    private val publisher = object : KompaktAccountStatePublisher {
        override fun publish() {
            published++
        }
    }

    // Unconfined over backgroundScope: advanceUntilIdle never starts background work on a standard dispatcher.
    private fun TestScope.startReplicator() {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        KompaktAccountStateReplicator(
            accountRepository = accountRepository,
            kompaktAccountSettings = accountSettings,
            publisher = publisher,
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            defaultDispatcher = dispatcher
        ).onAppCreate()
        advanceUntilIdle()
    }

    private fun TestScope.setAccounts(vararg accounts: Account) {
        this@KompaktAccountStateReplicatorTest.accounts.tryEmit(accounts.toSet())
        advanceUntilIdle()
    }

    private fun TestScope.emit(flows: MutableMap<Account, MutableSharedFlow<Boolean>>, account: Account, value: Boolean) {
        flowOf(flows, account).tryEmit(value)
        advanceUntilIdle()
    }

    @Test
    fun publishesNothingUntilSomethingChanges() = runTest {
        setAccounts(accountA, accountB)
        startReplicator()

        assertEquals(0, published)
    }

    @Test
    fun publishesAReauthChange() = runTest {
        setAccounts(accountA)
        startReplicator()

        emit(reauthFlows, accountA, true)

        assertEquals(1, published)
    }

    @Test
    fun publishesAConsentShownChange() = runTest {
        setAccounts(accountA)
        startReplicator()

        emit(shownFlows, accountA, true)

        assertEquals(1, published)
    }

    @Test
    fun subscribesOncePerAccountWhenTheAccountsSetRepeats() = runTest {
        setAccounts(accountA)
        startReplicator()

        setAccounts(accountA)

        verify(exactly = 1) { accountSettings.observeNewContactsConsentShown(accountA, false) }
    }

    @Test
    fun stopsPublishingForAnAccountThatIsGone() = runTest {
        setAccounts(accountA, accountB)
        startReplicator()

        setAccounts(accountB)
        emit(shownFlows, accountA, true)

        assertEquals(0, published)
    }

}
