/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class KompaktSyncRequestUseCaseTest {

    private val account = mockAccount()

    private val started = mutableListOf<Pair<Account, List<KompaktSyncService>>>()

    private lateinit var accountRepository: AccountRepository
    private lateinit var syncStatsRepository: DavSyncStatsRepository
    private lateinit var startSync: KompaktStartSyncUseCase
    private lateinit var requestSync: KompaktSyncRequestUseCase

    @Before
    fun setUp() {
        accountRepository = mockk()
        every { accountRepository.getAll() } returns arrayOf(account)

        syncStatsRepository = mockk()
        for (service in KompaktSyncService.entries)
            neverSynced(service)

        startSync = mockk()
        coEvery { startSync(any(), any(), any()) } answers {
            started += firstArg<Account>() to secondArg<Collection<KompaktSyncService>>().toList()
            KompaktSyncStart.NoneEligible
        }

        requestSync = KompaktSyncRequestUseCase(accountRepository, syncStatsRepository, startSync)
    }

    private fun neverSynced(service: KompaktSyncService) {
        coEvery { syncStatsRepository.getLastSyncTime(service.dataType) } returns null
    }

    private fun lastSynced(service: KompaktSyncService, ago: Duration) {
        coEvery { syncStatsRepository.getLastSyncTime(service.dataType) } returns
            System.currentTimeMillis() - ago.inWholeMilliseconds
    }

    @Test
    fun startsEveryRequestedServiceThatHasNeverSynced() = runTest {
        requestSync(KompaktSyncService.entries)

        assertEquals(
            listOf(account to listOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS)),
            started
        )
    }

    @Test
    fun startsOnlyTheServicesItWasAskedFor() = runTest {
        requestSync(listOf(KompaktSyncService.CONTACTS))

        assertEquals(listOf(account to listOf(KompaktSyncService.CONTACTS)), started)
    }

    @Test
    fun throttlesEachServiceAgainstItsOwnLastSync() = runTest {
        // The point of requesting per service: a calendar sync a minute ago must not swallow a
        // contacts request that is well past its own window.
        lastSynced(KompaktSyncService.CALENDAR, 1.minutes)
        lastSynced(KompaktSyncService.CONTACTS, 2.hours)

        requestSync(KompaktSyncService.entries)

        assertEquals(listOf(account to listOf(KompaktSyncService.CONTACTS)), started)
    }

    @Test
    fun startsNothingWhenEveryRequestedServiceIsThrottled() = runTest {
        lastSynced(KompaktSyncService.CALENDAR, 1.minutes)
        lastSynced(KompaktSyncService.CONTACTS, 1.minutes)

        requestSync(KompaktSyncService.entries)

        assertTrue(started.isEmpty())
    }

    @Test
    fun startsNothingWhenNoServiceWasRequested() = runTest {
        requestSync(emptyList())

        assertTrue(started.isEmpty())
    }

    @Test
    fun startsAServiceOnceItsThrottleWindowHasElapsed() = runTest {
        lastSynced(KompaktSyncService.CALENDAR, 15.minutes)

        requestSync(listOf(KompaktSyncService.CALENDAR))

        assertEquals(listOf(account to listOf(KompaktSyncService.CALENDAR)), started)
    }

    @Test
    fun requestsASyncForEveryLinkedAccount() = runTest {
        val other = mockAccount(name = "other@example.com")
        every { accountRepository.getAll() } returns arrayOf(account, other)

        requestSync(listOf(KompaktSyncService.CALENDAR))

        assertEquals(
            listOf(
                account to listOf(KompaktSyncService.CALENDAR),
                other to listOf(KompaktSyncService.CALENDAR)
            ),
            started
        )
    }

    @Test
    fun doesNotWaitForDiscovery() = runTest {
        // A BroadcastReceiver has no lifecycle to block on.
        requestSync(listOf(KompaktSyncService.CALENDAR))

        coVerify { startSync(account, any(), false) }
    }

}
