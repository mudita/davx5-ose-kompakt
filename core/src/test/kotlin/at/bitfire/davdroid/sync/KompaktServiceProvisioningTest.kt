/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.mockAuthState
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.ui.setup.KompaktConsentState
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.logging.Logger

/**
 * [KompaktServiceProvisioning.clearProvisioning] — the order it undoes things in is the contract, because
 * each step would be re-armed or made unfindable by the next if they ran the other way round — and
 * [KompaktServiceProvisioning.applyConsentChange], which decides per service what an authorization owes
 * each of them and refuses the whole change rather than apply half of it.
 */
class KompaktServiceProvisioningTest {

    private val account = mockAccount()

    private val authState = mockAuthState()

    private val accountSettings = mockk<KompaktAccountSettings>(relaxed = true)
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val syncWork = mockk<KompaktSyncWork>(relaxed = true)
    private val automaticSyncManager = mockk<AutomaticSyncManager>(relaxed = true)
    private val serviceRepository = mockk<DavServiceRepository>(relaxed = true)
    private val resourceFinderFactory = mockk<DavResourceFinder.Factory>()
    private val oAuthGoogle = mockk<KompaktOAuthGoogle>(relaxed = true)

    private val provisioning = KompaktServiceProvisioning(
        accountSettings = accountSettings,
        accountRepository = accountRepository,
        serviceRepository = serviceRepository,
        resourceFinderFactory = resourceFinderFactory,
        oAuthGoogle = oAuthGoogle,
        syncWork = syncWork,
        automaticSyncManager = Lazy { automaticSyncManager },
        logger = mockk<Logger>(relaxed = true)
    )

    @Before
    fun setUp() {
        coEvery { accountRepository.removeService(any(), any()) } returns true
    }

    /** A service that already has its row, so provisioning it is a no-op rather than a discovery. */
    private fun alreadyProvisioned(service: KompaktSyncService) {
        coEvery { serviceRepository.getByAccountAndType(account.name, service.serviceType) } returns mockk()
    }

    /** A service with no row, whose discovery comes back with nothing to write one from. */
    private fun undiscoverable(service: KompaktSyncService) {
        coEvery { serviceRepository.getByAccountAndType(account.name, service.serviceType) } returns null
        every { resourceFinderFactory.create(any(), any()) } returns mockk {
            every { findInitialConfiguration() } returns DavResourceFinder.Configuration(
                cardDAV = null,
                calDAV = null,
                encountered401 = false,
                logs = ""
            )
        }
    }

    // Otherwise a run already in flight recreates what the removal just took.
    @Test
    fun `an in-flight run is cancelled before the service is removed`() = runTest {
        provisioning.clearProvisioning(account, KompaktSyncService.CONTACTS)

        coVerifyOrder {
            syncWork.cancel(account, KompaktSyncService.CONTACTS)
            accountRepository.removeService(account.name, KompaktSyncService.CONTACTS)
        }
    }

    @Test
    fun `the stored interval and the applied-defaults marker are both forgotten`() = runTest {
        provisioning.clearProvisioning(account, KompaktSyncService.CONTACTS)

        coVerify {
            accountSettings.clearSyncInterval(account, SyncDataType.CONTACTS)
            accountSettings.clearDefaultsApplied(account, KompaktSyncService.CONTACTS)
        }
    }

    // It disables the periodic worker only because no row is left to schedule for, so it has to run last.
    @Test
    fun `automatic sync is updated after the service is gone`() = runTest {
        provisioning.clearProvisioning(account, KompaktSyncService.CALENDAR)

        coVerifyOrder {
            accountRepository.removeService(account.name, KompaktSyncService.CALENDAR)
            automaticSyncManager.updateAutomaticSync(account, SyncDataType.EVENTS, any())
        }
    }

    // A half-cleared service is worse than an untouched one: with the row still there, a forgotten
    // interval makes updateAutomaticSync fall back to the default and arm the periodic worker.
    @Test
    fun `a removal that gives up clears nothing else`() = runTest {
        coEvery { accountRepository.removeService(account.name, KompaktSyncService.CALENDAR) } returns false

        provisioning.clearProvisioning(account, KompaktSyncService.CALENDAR)

        coVerify(exactly = 0) {
            accountSettings.clearSyncInterval(account, SyncDataType.EVENTS)
            accountSettings.clearDefaultsApplied(account, KompaktSyncService.CALENDAR)
            automaticSyncManager.updateAutomaticSync(account, SyncDataType.EVENTS, any())
        }
    }

    // applyConsentChange

    @Test
    fun `a granted service gets its row and a revoked one loses everything`() = runTest {
        alreadyProvisioned(KompaktSyncService.CALENDAR)

        val applied = provisioning.applyConsentChange(
            account,
            mapOf(
                KompaktSyncService.CALENDAR to KompaktConsentState.GRANTED,
                KompaktSyncService.CONTACTS to KompaktConsentState.REVOKED
            ),
            authState
        )

        assertTrue(applied)
        coVerify { accountRepository.removeService(account.name, KompaktSyncService.CONTACTS) }
    }

    @Test
    fun `a service the authorization left alone is neither provisioned nor cleared`() = runTest {
        val applied = provisioning.applyConsentChange(
            account,
            mapOf(
                KompaktSyncService.CALENDAR to KompaktConsentState.KEPT,
                KompaktSyncService.CONTACTS to KompaktConsentState.ABSENT
            ),
            authState
        )

        assertTrue(applied)
        coVerify(exactly = 0) { accountRepository.removeService(any(), any()) }
        coVerify(exactly = 0) { accountRepository.addServiceBlocking(any(), any(), any()) }
    }

    // Granting runs first for exactly this reason: the step that takes a service away must not have run
    // when the change is refused.
    @Test
    fun `a grant that cannot be provisioned refuses the change before anything is removed`() = runTest {
        undiscoverable(KompaktSyncService.CALENDAR)

        val applied = provisioning.applyConsentChange(
            account,
            mapOf(
                KompaktSyncService.CALENDAR to KompaktConsentState.GRANTED,
                KompaktSyncService.CONTACTS to KompaktConsentState.REVOKED
            ),
            authState
        )

        assertFalse(applied)
        coVerify(exactly = 0) { accountRepository.removeService(any(), any()) }
    }

    @Test
    fun `a removal that gives up refuses the change`() = runTest {
        coEvery { accountRepository.removeService(any(), any()) } returns false

        val applied = provisioning.applyConsentChange(
            account,
            mapOf(KompaktSyncService.CONTACTS to KompaktConsentState.REVOKED),
            authState
        )

        assertFalse(applied)
    }

}
