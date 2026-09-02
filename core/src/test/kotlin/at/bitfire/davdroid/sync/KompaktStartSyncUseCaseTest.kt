/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktStartSyncUseCaseTest {

    private val account = Account("user@example.com", "bitfire.at.davdroid.mudita")

    private val initDefaults = mockk<KompaktInitDefaults>(relaxed = true)
    private val eligibility = mockk<KompaktSyncEligibility>()
    private val syncWork = mockk<KompaktSyncWork>(relaxed = true)

    private val startSync = KompaktStartSyncUseCase(initDefaults, eligibility, syncWork)

    @Before
    fun bothServicesEligible() {
        coEvery { eligibility.enabledServices(account) } returns KompaktSyncService.entries
        coEvery { syncWork.enqueue(account, any(), any()) } returns UUID.randomUUID()
    }

    @Test
    fun appliesDefaultsBeforeReadingEligibility() = runTest {
        // On a fresh account ensureApplied writes the interval the toggle read then reports, so reading
        // eligibility first makes the first request after linking enqueue nothing.
        startSync(account)

        coVerifyOrder {
            initDefaults.ensureApplied(account, KompaktSyncService.CALENDAR, any())
            eligibility.enabledServices(account)
        }
    }

    @Test
    fun enqueuesEveryEligibleServiceAndReturnsTheirRunIds() = runTest {
        val result = startSync(account)

        assertEquals(KompaktSyncService.entries.toSet(), result.keys)
        assertTrue(result.values.all { it != null })
        coVerify { syncWork.enqueue(account, KompaktSyncService.CALENDAR, true) }
        coVerify { syncWork.enqueue(account, KompaktSyncService.CONTACTS, true) }
    }

    @Test
    fun enqueuesNothingWhenNoServiceIsEligible() = runTest {
        coEvery { eligibility.enabledServices(account) } returns emptyList()

        assertTrue(startSync(account).isEmpty())
        coVerify(exactly = 0) { syncWork.enqueue(any(), any(), any()) }
    }

    @Test
    fun intersectsTheRequestWithTheEligibleSet() = runTest {
        coEvery { eligibility.enabledServices(account) } returns listOf(KompaktSyncService.CALENDAR)

        val result = startSync(account, services = listOf(KompaktSyncService.CONTACTS))

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { syncWork.enqueue(any(), any(), any()) }
    }

    @Test
    fun requestingOneServiceDoesNotEnqueueTheOther() = runTest {
        val result = startSync(account, services = listOf(KompaktSyncService.CALENDAR))

        assertEquals(setOf(KompaktSyncService.CALENDAR), result.keys)
        coVerify(exactly = 0) { syncWork.enqueue(account, KompaktSyncService.CONTACTS, any()) }
    }

    @Test
    fun passesAwaitDiscoveryThroughForCallersThatCannotBlock() = runTest {
        startSync(account, awaitDiscovery = false)

        coVerify { initDefaults.ensureApplied(account, KompaktSyncService.CALENDAR, false) }
        coVerify { initDefaults.ensureApplied(account, KompaktSyncService.CONTACTS, false) }
    }

}
