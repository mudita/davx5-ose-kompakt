/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.mockAuthState
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import org.junit.Before
import org.junit.Test
import java.util.logging.Logger

class KompaktServiceSetupTest {

    private val account = mockAccount()
    private val service = KompaktSyncService.CONTACTS

    // Replaying rather than StateFlow, which would conflate the repeats distinctUntilChanged is there for.
    private val rows = MutableSharedFlow<Service?>(replay = 1)
    private val authStates = MutableSharedFlow<AuthState?>(replay = 1)

    private val serviceRepository = mockk<DavServiceRepository>()
    private val accountSettings = mockk<KompaktAccountSettings>()
    private val collectionDiscovery = mockk<KompaktCollectionDiscovery>(relaxed = true)
    private val initDefaults = mockk<KompaktInitDefaults>(relaxed = true)

    private val setup = KompaktServiceSetup(
        serviceRepository = serviceRepository,
        accountSettings = accountSettings,
        collectionDiscovery = collectionDiscovery,
        initDefaults = initDefaults,
        logger = mockk<Logger>(relaxed = true)
    )

    @Before
    fun setUp() {
        every { serviceRepository.getServiceFlow(account.name, service.serviceType) } returns rows
        every { accountSettings.observeAuthState(account, any()) } returns authStates
    }

    // Unconfined over backgroundScope: advanceUntilIdle never starts background work on a standard dispatcher.
    private fun TestScope.keepSetUp() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { setup.keepSetUp(account, service) }
    }

    private fun row(id: Long) = Service(id, account.name, service.serviceType)

    private fun granting() = mockAuthState(KompaktSyncService.CALENDAR.scope, KompaktSyncService.CONTACTS.scope)

    private fun notGranting() = mockAuthState(KompaktSyncService.CALENDAR.scope)

    @Test
    fun `a granted row has its collections fetched, then its defaults applied`() = runTest {
        keepSetUp()
        rows.emit(row(7))
        authStates.emit(granting())

        coVerifyOrder {
            collectionDiscovery.ensure(account, service, true)
            initDefaults.ensureApplied(account, service)
        }
    }

    // Re-authorization and added consent write the row first; a fetch then would run on the old token.
    @Test
    fun `a row is not fetched before the stored token grants it`() = runTest {
        keepSetUp()
        authStates.emit(notGranting())
        rows.emit(row(7))

        coVerify(exactly = 0) { collectionDiscovery.ensure(any(), any(), any()) }
    }

    @Test
    fun `storing the granting token is what starts the setup`() = runTest {
        keepSetUp()
        authStates.emit(notGranting())
        rows.emit(row(7))
        authStates.emit(granting())

        coVerify(exactly = 1) { collectionDiscovery.ensure(account, service, true) }
    }

    @Test
    fun `a grant without a row has nothing to set up`() = runTest {
        keepSetUp()
        rows.emit(null)
        authStates.emit(granting())

        coVerify(exactly = 0) { collectionDiscovery.ensure(any(), any(), any()) }
    }

    @Test
    fun `a new row is set up again`() = runTest {
        keepSetUp()
        authStates.emit(granting())
        rows.emit(row(7))
        rows.emit(row(8))

        coVerify(exactly = 2) { collectionDiscovery.ensure(account, service, true) }
    }

    // A token refresh rewrites the stored token without changing what it grants.
    @Test
    fun `a token rewritten with the same grant does not set the row up again`() = runTest {
        keepSetUp()
        rows.emit(row(7))
        authStates.emit(granting())
        authStates.emit(granting())

        coVerify(exactly = 1) { collectionDiscovery.ensure(account, service, true) }
    }

    // Revoking and granting again writes a fresh row before the new token, just as the first grant does.
    @Test
    fun `a row written after the grant was withdrawn waits for the grant again`() = runTest {
        keepSetUp()
        authStates.emit(granting())
        rows.emit(row(7))
        authStates.emit(notGranting())
        rows.emit(row(8))

        coVerify(exactly = 1) { collectionDiscovery.ensure(account, service, true) }
        authStates.emit(granting())

        coVerify(exactly = 2) { collectionDiscovery.ensure(account, service, true) }
    }

    @Test
    fun `withdrawing the grant cancels a setup still running`() = runTest {
        val fetched = CompletableDeferred<Boolean>()
        coEvery { collectionDiscovery.ensure(account, service, true) } coAnswers { fetched.await() }
        keepSetUp()
        authStates.emit(granting())
        rows.emit(row(7))

        authStates.emit(notGranting())
        fetched.complete(true)

        coVerify(exactly = 0) { initDefaults.ensureApplied(any(), any()) }
    }

    @Test
    fun `a setup that throws does not stop the next row from being set up`() = runTest {
        coEvery { collectionDiscovery.ensure(account, service, true) } throws IllegalStateException() andThen true
        keepSetUp()
        authStates.emit(granting())
        rows.emit(row(7))
        rows.emit(row(8))

        coVerify(exactly = 1) { initDefaults.ensureApplied(account, service) }
    }

}
