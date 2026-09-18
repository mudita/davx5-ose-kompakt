/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.mockAuthState
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktStartSyncUseCase
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.KompaktServiceProvisioning
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.logging.Logger

/**
 * The refresh-in-place branch: what a re-authorization does to the services it no longer covers, and
 * what must survive a step of it failing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KompaktReauthModelTest {

    private val account = mockAccount()

    private val calendarScope = KompaktSyncService.CALENDAR.scope
    private val contactsScope = KompaktSyncService.CONTACTS.scope
    private val logger: Logger = Logger.getGlobal()

    private val kompaktAccountSettings = mockk<KompaktAccountSettings>(relaxed = true)
    private val startSyncUseCase = mockk<KompaktStartSyncUseCase>(relaxed = true)
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val provisioning = mockk<KompaktServiceProvisioning>(relaxed = true)

    private fun model() = KompaktReauthModel(
        kompaktAccountSettings = kompaktAccountSettings,
        startSyncUseCase = startSyncUseCase,
        accountRepository = accountRepository,
        provisioning = provisioning,
        ioDispatcher = UnconfinedTestDispatcher(),
        logger = logger
    )

    /** What the account held before the re-authorization. */
    private fun held(vararg scopes: String) {
        every { kompaktAccountSettings.getAuthState(account) } returns mockAuthState(*scopes)
    }

    /** A re-authorization of the same account granting exactly [scopes]. */
    private fun reauthGranting(vararg scopes: String) = LoginInfo(
        credentials = Credentials(authState = mockAuthState(*scopes)),
        suggestedAccountName = account.name
    )

    // a withdrawn consent is taken back to never-consented

    @Test
    fun `the service that lost its scope is removed`() = runTest {
        held(calendarScope, contactsScope)
        model().apply(account, reauthGranting(calendarScope))

        coVerify { provisioning.clearProvisioning(account, KompaktSyncService.CONTACTS) }
    }

    @Test
    fun `the service that kept its scope is left alone`() = runTest {
        held(calendarScope, contactsScope)
        model().apply(account, reauthGranting(calendarScope))

        coVerify(exactly = 0) { provisioning.clearProvisioning(account, KompaktSyncService.CALENDAR) }
    }

    @Test
    fun `a re-authorization that changed nothing removes nothing`() = runTest {
        held(calendarScope, contactsScope)
        model().apply(account, reauthGranting(calendarScope, contactsScope))

        coVerify(exactly = 0) { provisioning.clearProvisioning(any(), any()) }
    }

    @Test
    fun `a scope gained is not a scope lost`() = runTest {
        held(calendarScope)
        model().apply(account, reauthGranting(calendarScope, contactsScope))

        coVerify(exactly = 0) { provisioning.clearProvisioning(any(), any()) }
    }

    // The sync would otherwise be enqueued while the withdrawn service still had its row and interval.
    @Test
    fun `the service is removed before the sync is enqueued`() = runTest {
        held(calendarScope, contactsScope)
        model().apply(account, reauthGranting(calendarScope))

        coVerifyOrder {
            provisioning.clearProvisioning(account, KompaktSyncService.CONTACTS)
            startSyncUseCase(any(), any(), any())
        }
    }


    // neither later step may cost the user the explanation of what changed

    @Test
    fun `a removal that fails still reports the change`() = runTest {
        held(calendarScope, contactsScope)
        coEvery { provisioning.clearProvisioning(any(), any()) } throws IllegalStateException("no")

        val model = model()
        model.apply(account, reauthGranting(calendarScope))

        val state = model.state.value as KompaktReauthModel.ReauthState.Refreshed
        assertEquals(
            KompaktConsentState.REVOKED,
            state.consent?.get(KompaktSyncService.CONTACTS)
        )
    }

    @Test
    fun `a sync that cannot be enqueued still reports the change`() = runTest {
        held(calendarScope, contactsScope)
        coEvery { startSyncUseCase(any(), any(), any()) } throws IllegalStateException("no")

        val model = model()
        model.apply(account, reauthGranting(calendarScope))

        val state = model.state.value as KompaktReauthModel.ReauthState.Refreshed
        assertEquals(
            KompaktConsentState.REVOKED,
            state.consent?.get(KompaktSyncService.CONTACTS)
        )
    }

    // Contacts never granted, and refused again on the combined screen: no consent changed, so nothing
    // else here reacts — yet the offer to grant it would otherwise follow the refusal straight away.
    @Test
    fun `refusing Contacts again retires the offer to grant it`() = runTest {
        held(calendarScope)
        model().apply(account, reauthGranting(calendarScope))

        coVerify { kompaktAccountSettings.setNewContactsConsentShown(account) }
    }

}
