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
import io.mockk.slot
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

/**
 * The refresh-in-place branch: the change this model reads out of an authorization, that it hands the
 * whole change to provisioning before storing anything, and that a change which cannot be applied
 * leaves the account untouched. Which service the change means what for is
 * [at.bitfire.davdroid.sync.KompaktServiceProvisioningTest]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
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

    @Before
    fun setUp() {
        coEvery { provisioning.applyConsentChange(any(), any(), any()) } returns true
    }

    /** What the account held before the re-authorization. */
    private fun held(vararg scopes: String) {
        every { kompaktAccountSettings.getAuthState(account) } returns mockAuthState(*scopes)
    }

    /** A re-authorization of the same account granting exactly [scopes]. */
    private fun reauthGranting(vararg scopes: String) = LoginInfo(
        credentials = Credentials(authState = mockAuthState(*scopes)),
        suggestedAccountName = account.name
    )

    // the change the authorization made is what provisioning is handed

    @Test
    fun `the change read out of the authorization is the one applied`() = runTest {
        held(calendarScope)
        val consent = slot<Map<KompaktSyncService, KompaktConsentState>>()
        coEvery { provisioning.applyConsentChange(account, capture(consent), any()) } returns true

        model().apply(account, reauthGranting(contactsScope))

        assertEquals(KompaktConsentState.GRANTED, consent.captured[KompaktSyncService.CONTACTS])
        assertEquals(KompaktConsentState.REVOKED, consent.captured[KompaktSyncService.CALENDAR])
    }

    @Test
    fun `a re-authorization that changed nothing still applies the change it read`() = runTest {
        held(calendarScope, contactsScope)
        val consent = slot<Map<KompaktSyncService, KompaktConsentState>>()
        coEvery { provisioning.applyConsentChange(account, capture(consent), any()) } returns true

        model().apply(account, reauthGranting(calendarScope, contactsScope))

        assertEquals(KompaktConsentState.KEPT, consent.captured[KompaktSyncService.CALENDAR])
        assertEquals(KompaktConsentState.KEPT, consent.captured[KompaktSyncService.CONTACTS])
    }

    // The account does not hold the granting token yet, so provisioning has to be handed it rather than
    // read it back from the account.
    @Test
    fun `the change is applied before the token is stored`() = runTest {
        held(calendarScope)
        model().apply(account, reauthGranting(calendarScope, contactsScope))

        coVerifyOrder {
            provisioning.applyConsentChange(account, any(), any())
            kompaktAccountSettings.updateAuthState(account, any())
        }
    }

    // The sync decides what to enqueue from the rows that exist, so a row written after it is a row the
    // enqueue could not see.
    @Test
    fun `the change is applied before the sync is enqueued`() = runTest {
        held(calendarScope)
        model().apply(account, reauthGranting(calendarScope, contactsScope))

        coVerifyOrder {
            provisioning.applyConsentChange(account, any(), any())
            startSyncUseCase(any(), any(), any())
        }
    }


    // a change that cannot be applied applies nothing at all, so the whole authorization is retryable

    @Test
    fun `a change that cannot be applied fails the re-authorization`() = runTest {
        held(calendarScope)
        coEvery { provisioning.applyConsentChange(any(), any(), any()) } returns false

        val model = model()
        model.apply(account, reauthGranting(calendarScope, contactsScope))

        assertEquals(KompaktReauthModel.ReauthState.Failed, model.state.value)
    }

    @Test
    fun `a change that throws fails the re-authorization`() = runTest {
        held(calendarScope)
        coEvery { provisioning.applyConsentChange(any(), any(), any()) } throws IllegalStateException("no")

        val model = model()
        model.apply(account, reauthGranting(calendarScope, contactsScope))

        assertEquals(KompaktReauthModel.ReauthState.Failed, model.state.value)
    }

    @Test
    fun `a change that cannot be applied leaves the token unstored`() = runTest {
        held(calendarScope)
        coEvery { provisioning.applyConsentChange(any(), any(), any()) } returns false

        model().apply(account, reauthGranting(calendarScope, contactsScope))

        coVerify(exactly = 0) { kompaktAccountSettings.updateAuthState(any(), any()) }
    }

    @Test
    fun `a change that cannot be applied enqueues no sync`() = runTest {
        held(calendarScope)
        coEvery { provisioning.applyConsentChange(any(), any(), any()) } returns false

        model().apply(account, reauthGranting(calendarScope, contactsScope))

        coVerify(exactly = 0) { startSyncUseCase(any(), any(), any()) }
    }


    @Test
    fun `a token that cannot be stored fails the re-authorization`() = runTest {
        held(calendarScope)
        coEvery { kompaktAccountSettings.updateAuthState(any(), any()) } throws IllegalStateException("no")

        val model = model()
        model.apply(account, reauthGranting(calendarScope, contactsScope))

        assertEquals(KompaktReauthModel.ReauthState.Failed, model.state.value)
    }

    // The change is already applied by then, so the retry has to be able to walk over it. Both halves
    // of it answer the second call without doing anything, which is what makes that safe.
    @Test
    fun `a re-authorization retried after a failed write goes through`() = runTest {
        held(calendarScope)
        coEvery { kompaktAccountSettings.updateAuthState(any(), any()) } throws IllegalStateException("no")

        val model = model()
        model.apply(account, reauthGranting(calendarScope, contactsScope))
        coEvery { kompaktAccountSettings.updateAuthState(any(), any()) } returns Unit
        model.apply(account, reauthGranting(calendarScope, contactsScope))

        assertEquals(
            KompaktConsentState.GRANTED,
            (model.state.value as KompaktReauthModel.ReauthState.Refreshed)
                .consent?.get(KompaktSyncService.CONTACTS)
        )
    }

    // a later step failing may not cost the user the explanation of what changed

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
