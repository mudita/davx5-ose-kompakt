/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.startup

import android.accounts.Account
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.ui.KompaktAuthStatePublisher
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

class KompaktAuthStateReplicatorTest {

    // Mocked Accounts because the unit-test android.jar leaves Account.equals throwing; MockK's
    // reference equality is what the accounts Set and distinctUntilChanged need here.
    private val accountA = mockk<Account>()
    private val accountB = mockk<Account>()

    // A SharedFlow, not a StateFlow: AccountRepository.getAllFlow is a callbackFlow that re-emits
    // whatever OnAccountsUpdateListener reports, equal sets included, which is the whole reason the
    // replicator applies distinctUntilChanged. A StateFlow would conflate those repeats itself and
    // hide it.
    private val accounts = MutableSharedFlow<Set<Account>>(replay = 1, extraBufferCapacity = 8)
    private val reauthFlows = mutableMapOf<Account, MutableSharedFlow<Boolean>>()
    private val published = mutableListOf<Pair<Account, Boolean>>()

    private lateinit var accountRepository: AccountRepository
    private lateinit var accountSettings: KompaktAccountSettings

    @Before
    fun setUp() {
        accountRepository = mockk()
        every { accountRepository.getAllFlow() } returns accounts

        accountSettings = mockk()
        // Stubbed only for emitInitial = false: the replicator must never subscribe with an initial
        // emission, or it would announce a change on every app start. A true would find no answer.
        every { accountSettings.observeReauthNeeded(any(), false) } answers {
            reauthFlow(firstArg())
        }
    }

    private fun reauthFlow(account: Account) =
        reauthFlows.getOrPut(account) { MutableSharedFlow(extraBufferCapacity = 8) }

    private val publisher = object : KompaktAuthStatePublisher {
        override fun publish(account: Account, needsReauth: Boolean) {
            published += account to needsReauth
        }
    }

    // backgroundScope's Job so the collector is cancelled at test end, but an unconfined dispatcher
    // over it: advanceUntilIdle does not start background work on a StandardTestDispatcher, whereas
    // unconfined subscribes eagerly at launch and then delivers each emission inline.
    private fun TestScope.startReplicator() {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        KompaktAuthStateReplicator(
            accountRepository = accountRepository,
            kompaktAccountSettings = accountSettings,
            publisher = publisher,
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            defaultDispatcher = dispatcher
        ).onAppCreate()
        advanceUntilIdle()
    }

    private fun TestScope.setAccounts(vararg accounts: Account) {
        this@KompaktAuthStateReplicatorTest.accounts.tryEmit(accounts.toSet())
        advanceUntilIdle()
    }

    private fun TestScope.emitReauth(account: Account, needsReauth: Boolean) {
        reauthFlow(account).tryEmit(needsReauth)
        advanceUntilIdle()
    }

    @Test
    fun publishesAChangeForItsOwnAccount() = runTest {
        setAccounts(accountA)
        startReplicator()

        emitReauth(accountA, true)

        assertEquals(listOf(accountA to true), published)
    }

    @Test
    fun publishesNothingUntilSomethingChanges() = runTest {
        setAccounts(accountA, accountB)

        startReplicator()

        assertEquals(emptyList<Pair<Account, Boolean>>(), published)
    }

    @Test
    fun publishesEachAccountSeparately() = runTest {
        setAccounts(accountA, accountB)
        startReplicator()

        emitReauth(accountB, true)
        emitReauth(accountA, false)

        assertEquals(listOf(accountB to true, accountA to false), published)
    }

    @Test
    fun subscribesOncePerAccountWhenTheAccountsSetRepeats() = runTest {
        setAccounts(accountA)
        startReplicator()

        setAccounts(accountA)

        verify(exactly = 1) { accountSettings.observeReauthNeeded(accountA, false) }
    }

    @Test
    fun picksUpAnAccountAddedAfterStart() = runTest {
        setAccounts(accountA)
        startReplicator()

        setAccounts(accountA, accountB)
        emitReauth(accountB, true)

        assertEquals(listOf(accountB to true), published)
    }

    @Test
    fun stopsPublishingForAnAccountThatIsGone() = runTest {
        setAccounts(accountA, accountB)
        startReplicator()

        setAccounts(accountB)
        emitReauth(accountA, true)

        assertEquals(emptyList<Pair<Account, Boolean>>(), published)
    }
}
