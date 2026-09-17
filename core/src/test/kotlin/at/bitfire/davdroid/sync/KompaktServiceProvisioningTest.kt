/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.settings.KompaktAccountSettings
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.logging.Logger

/**
 * [KompaktServiceProvisioning.clearProvisioning] — the order it undoes things in is the contract, because
 * each step would be re-armed or made unfindable by the next if they ran the other way round.
 */
class KompaktServiceProvisioningTest {

    private val account = mockAccount()

    private val accountSettings = mockk<KompaktAccountSettings>(relaxed = true)
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val syncWork = mockk<KompaktSyncWork>(relaxed = true)
    private val automaticSyncManager = mockk<AutomaticSyncManager>(relaxed = true)

    private val provisioning = KompaktServiceProvisioning(
        accountSettings = accountSettings,
        accountRepository = accountRepository,
        serviceRepository = mockk<DavServiceRepository>(relaxed = true),
        resourceFinderFactory = mockk<DavResourceFinder.Factory>(),
        oAuthGoogle = mockk<KompaktOAuthGoogle>(),
        syncWork = syncWork,
        automaticSyncManager = Lazy { automaticSyncManager },
        logger = mockk<Logger>(relaxed = true)
    )

    @Before
    fun setUp() {
        coEvery { accountRepository.removeService(any(), any()) } returns true
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

}
