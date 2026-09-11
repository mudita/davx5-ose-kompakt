/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.ui.account.KompaktAccountProgressUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.logging.Logger

class KompaktStartSyncUseCaseTest {

    // Mocked rather than real: the unit-test android.jar leaves Account.equals throwing, and the use
    // case only hands the Account on to its seams.
    private val account = mockk<Account>()

    private val calendarRun: UUID = UUID.randomUUID()
    private val contactsRun: UUID = UUID.randomUUID()

    // What ran, in the order it ran: configuring a service has to happen before the switch is read the
    // second time, because applying the defaults is what writes the interval that switch reflects.
    private val calls = mutableListOf<String>()

    private lateinit var initDefaults: KompaktInitDefaults
    private lateinit var eligibility: KompaktSyncEligibility
    private lateinit var provisioning: KompaktServiceProvisioning
    private lateinit var storage: KompaktStorageAvailability
    private lateinit var network: KompaktNetworkAvailability
    private lateinit var syncWork: KompaktSyncWork
    private lateinit var accountProgress: KompaktAccountProgressUseCase
    private lateinit var startSync: KompaktStartSyncUseCase

    @Before
    fun setUp() {
        initDefaults = mockk()
        every { initDefaults.isApplied(any(), any()) } returns true
        coEvery { initDefaults.ensureApplied(any(), any(), any()) } answers {
            calls += "defaults:${secondArg<KompaktSyncService>()}"
            KompaktInitDefaults.Outcome.APPLIED
        }

        eligibility = mockk()
        consent(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS)
        switchOn(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS)

        provisioning = mockk()
        coEvery { provisioning.ensureRow(any(), any()) } answers {
            calls += "row:${secondArg<KompaktSyncService>()}"
            true
        }

        storage = mockk()
        every { storage.isLow() } returns false

        network = mockk()
        every { network.isAvailable(account) } returns true

        syncWork = mockk()
        coEvery { syncWork.enqueue(account, KompaktSyncService.CALENDAR, any()) } answers {
            calls += "enqueue:CALENDAR"
            calendarRun
        }
        coEvery { syncWork.enqueue(account, KompaktSyncService.CONTACTS, any()) } answers {
            calls += "enqueue:CONTACTS"
            contactsRun
        }

        accountProgress = mockk()
        syncing()

        startSync = KompaktStartSyncUseCase(
            initDefaults, eligibility, provisioning, storage, network, syncWork, accountProgress,
            Logger.getAnonymousLogger()
        )
    }

    private fun consent(vararg services: KompaktSyncService) {
        every { eligibility.consented(account) } returns services.toSet()
    }

    /** Re-read after configuring, so a stub has to answer both times. */
    private fun switchOn(vararg services: KompaktSyncService) {
        every { eligibility.switchedOn(account) } answers {
            calls += "switch"
            services.toSet()
        }
    }

    /** Reports the named services as already syncing, and every other one as idle. */
    private fun syncing(vararg services: KompaktSyncService) {
        every { accountProgress(account, any()) } answers {
            val dataType = secondArg<SyncDataType>()
            flowOf(services.any { it.dataType == dataType })
        }
    }

    @Test
    fun startsEveryRequestedServiceThatMaySync() = runTest {
        val start = startSync(account)

        assertEquals(
            KompaktSyncStartResult.Started(
                mapOf(
                    KompaktSyncService.CALENDAR to calendarRun,
                    KompaktSyncService.CONTACTS to contactsRun
                )
            ),
            start
        )
    }

    @Test
    fun configuresEachServiceBeforeReadingItsSwitchAgain() = runTest {
        startSync(account, services = listOf(KompaktSyncService.CALENDAR))

        assertEquals(
            listOf("switch", "row:CALENDAR", "defaults:CALENDAR", "switch", "enqueue:CALENDAR"),
            calls
        )
    }

    @Test
    fun touchesOnlyTheServicesItWasAskedFor() = runTest {
        val start = startSync(account, services = listOf(KompaktSyncService.CALENDAR))

        assertEquals(KompaktSyncStartResult.Started(mapOf(KompaktSyncService.CALENDAR to calendarRun)), start)
        coVerify(exactly = 0) { provisioning.ensureRow(any(), KompaktSyncService.CONTACTS) }
        coVerify(exactly = 0) { syncWork.enqueue(any(), KompaktSyncService.CONTACTS, any()) }
    }

    @Test
    fun startsNothingForAServiceWhoseConsentIsGone() = runTest {
        consent(KompaktSyncService.CALENDAR)

        val start = startSync(account)

        assertEquals(KompaktSyncStartResult.Started(mapOf(KompaktSyncService.CALENDAR to calendarRun)), start)
        coVerify(exactly = 0) { syncWork.enqueue(any(), KompaktSyncService.CONTACTS, any()) }
    }

    // The complaint this ordering fixes: nothing can sync, so the environment is beside the point and
    // saying "no internet" would send the user to check a connection that changes nothing.
    @Test
    fun aSwitchedOffAccountIsRuledOutBeforeTheGuardsAreConsulted() = runTest {
        switchOn()
        every { storage.isLow() } returns true
        every { network.isAvailable(account) } returns false

        assertEquals(KompaktSyncStartResult.NoneEligible, startSync(account))
        coVerify(exactly = 0) { provisioning.ensureRow(any(), any()) }
        assertTrue(calls.none { it.startsWith("enqueue") })
    }

    @Test
    fun revokedConsentIsRuledOutBeforeTheGuardsAreConsulted() = runTest {
        consent()
        every { network.isAvailable(account) } returns false

        assertEquals(KompaktSyncStartResult.NoneEligible, startSync(account))
    }

    // A service that has never had a default written reads "off" without the user having chosen it, so
    // it must survive the cheap check and be configured before the switch means anything.
    @Test
    fun anUnconfiguredServiceIsNotMistakenForASwitchedOffOne() = runTest {
        every { initDefaults.isApplied(any(), any()) } returns false
        every { eligibility.switchedOn(account) } returnsMany listOf(
            emptySet(),
            setOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS)
        )

        val start = startSync(account)

        assertEquals(
            KompaktSyncStartResult.Started(
                mapOf(
                    KompaktSyncService.CALENDAR to calendarRun,
                    KompaktSyncService.CONTACTS to contactsRun
                )
            ),
            start
        )
    }

    @Test
    fun lowStorageStartsNothing() = runTest {
        every { storage.isLow() } returns true

        assertEquals(KompaktSyncStartResult.NoStorage, startSync(account))
        coVerify(exactly = 0) { syncWork.enqueue(any(), any(), any()) }
    }

    // Nothing may be enqueued that cannot run: a request parked on an unmet network constraint reads as
    // a sync in progress for as long as it waits, and nothing cancels it.
    @Test
    fun noNetworkStartsNothing() = runTest {
        every { network.isAvailable(account) } returns false

        assertEquals(KompaktSyncStartResult.NoNetwork, startSync(account))
        coVerify(exactly = 0) { syncWork.enqueue(any(), any(), any()) }
    }

    @Test
    fun aServiceWithNoRowAndNoneDiscoverableIsLeftAlone() = runTest {
        coEvery { provisioning.ensureRow(account, KompaktSyncService.CONTACTS) } returns false

        val start = startSync(account)

        assertEquals(KompaktSyncStartResult.Started(mapOf(KompaktSyncService.CALENDAR to calendarRun)), start)
        coVerify(exactly = 0) { initDefaults.ensureApplied(any(), KompaktSyncService.CONTACTS, any()) }
        coVerify(exactly = 0) { syncWork.enqueue(any(), KompaktSyncService.CONTACTS, any()) }
    }

    @Test
    fun aServiceThatCannotBeConfiguredAtAllIsLeftAlone() = runTest {
        coEvery { provisioning.ensureRow(account, KompaktSyncService.CONTACTS) } throws RuntimeException("no")

        assertEquals(
            KompaktSyncStartResult.Started(mapOf(KompaktSyncService.CALENDAR to calendarRun)),
            startSync(account)
        )
    }

    @Test
    fun startsNoSecondRunForAServiceThatIsAlreadySyncing() = runTest {
        syncing(KompaktSyncService.CALENDAR)

        val start = startSync(account)

        assertEquals(KompaktSyncStartResult.Started(mapOf(KompaktSyncService.CONTACTS to contactsRun)), start)
        coVerify(exactly = 0) { syncWork.enqueue(any(), KompaktSyncService.CALENDAR, any()) }
    }

    // Distinct from NoneEligible: this one has to stay silent, because the row already shows a spinner.
    @Test
    fun everyEligibleServiceAlreadySyncingIsAlreadySyncing() = runTest {
        syncing(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS)

        assertEquals(KompaktSyncStartResult.AlreadySyncing, startSync(account))
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
    fun passesTheDiscoveryWaitOnToTheDefaults() = runTest {
        startSync(account, services = listOf(KompaktSyncService.CALENDAR), awaitDiscovery = false)

        coVerify { initDefaults.ensureApplied(account, KompaktSyncService.CALENDAR, false) }
    }

}
