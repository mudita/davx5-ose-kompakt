/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

class KompaktStartSyncUseCaseTest {

    // Mocked rather than real: the unit-test android.jar leaves Account.equals throwing, and the use
    // case only hands the Account on to its seams.
    private val account = mockk<Account>()

    private val calendarRun = UUID.randomUUID()
    private val contactsRun = UUID.randomUUID()

    // What ran, in the order it ran: the defaults have to be applied before eligibility is read,
    // because applying them is what writes the interval eligibility then looks for.
    private val calls = mutableListOf<String>()

    private lateinit var initDefaults: KompaktInitDefaults
    private lateinit var eligibility: KompaktSyncEligibility
    private lateinit var syncWork: KompaktSyncWork
    private lateinit var startSync: KompaktStartSyncUseCase

    @Before
    fun setUp() {
        initDefaults = mockk()
        coEvery { initDefaults.ensureApplied(any(), any(), any()) } answers {
            calls += "defaults:${secondArg<KompaktSyncService>()}"
            KompaktInitDefaults.Outcome.APPLIED
        }

        eligibility = mockk()
        enable(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS)

        syncWork = mockk()
        coEvery { syncWork.enqueue(account, KompaktSyncService.CALENDAR, any()) } answers {
            calls += "enqueue:CALENDAR"
            calendarRun
        }
        coEvery { syncWork.enqueue(account, KompaktSyncService.CONTACTS, any()) } answers {
            calls += "enqueue:CONTACTS"
            contactsRun
        }

        startSync = KompaktStartSyncUseCase(initDefaults, eligibility, syncWork)
    }

    private fun enable(vararg services: KompaktSyncService) {
        coEvery { eligibility.enabledServices(account) } answers {
            calls += "eligibility"
            services.toList()
        }
    }

    @Test
    fun appliesTheDefaultsOfEveryServiceBeforeReadingEligibility() = runTest {
        // On a fresh account the order decides between syncing and silently doing nothing.
        startSync(account)

        assertEquals(
            listOf("defaults:CALENDAR", "defaults:CONTACTS", "eligibility"),
            calls.take(3)
        )
    }

    @Test
    fun returnsTheRunItStartedForEachEnabledService() = runTest {
        val runs = startSync(account)

        assertEquals(
            mapOf(
                KompaktSyncService.CALENDAR to calendarRun,
                KompaktSyncService.CONTACTS to contactsRun
            ),
            runs
        )
    }

    @Test
    fun startsNothingForAServiceThatIsNotEligible() = runTest {
        enable(KompaktSyncService.CALENDAR)

        val runs = startSync(account)

        assertEquals(mapOf(KompaktSyncService.CALENDAR to calendarRun), runs)
        coVerify(exactly = 0) { syncWork.enqueue(any(), KompaktSyncService.CONTACTS, any()) }
    }

    @Test
    fun touchesOnlyTheServicesItWasAskedFor() = runTest {
        val runs = startSync(account, services = listOf(KompaktSyncService.CALENDAR))

        assertEquals(mapOf(KompaktSyncService.CALENDAR to calendarRun), runs)
        coVerify(exactly = 0) { initDefaults.ensureApplied(any(), KompaktSyncService.CONTACTS, any()) }
        coVerify(exactly = 0) { syncWork.enqueue(any(), KompaktSyncService.CONTACTS, any()) }
    }

    @Test
    fun startsNothingWhenNoServiceIsEligible() = runTest {
        enable()

        assertEquals(emptyMap<KompaktSyncService, UUID?>(), startSync(account))
        coVerify(exactly = 0) { syncWork.enqueue(any(), any(), any()) }
    }

    @Test
    fun theRunItStartsIsAManualOne() = runTest {
        // A manual run ignores the network and battery constraints a periodic one carries: the user
        // asked for it now.
        startSync(account, services = listOf(KompaktSyncService.CALENDAR))

        coVerify { syncWork.enqueue(account, KompaktSyncService.CALENDAR, true) }
    }

    @Test
    fun passesOnWhetherTheCallerCanWaitForDiscovery() = runTest {
        // A BroadcastReceiver has no lifecycle to block on, so it attempts once without waiting.
        startSync(account, awaitDiscovery = false)

        coVerify { initDefaults.ensureApplied(account, KompaktSyncService.CALENDAR, false) }
        coVerify { initDefaults.ensureApplied(account, KompaktSyncService.CONTACTS, false) }
    }

    @Test
    fun waitsForDiscoveryUnlessToldOtherwise() = runTest {
        startSync(account)

        coVerify { initDefaults.ensureApplied(account, KompaktSyncService.CALENDAR, true) }
    }

}
