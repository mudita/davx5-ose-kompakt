/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.content.Context
import android.os.Looper
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.KompaktServiceProvisioning
import at.bitfire.davdroid.sync.KompaktServiceSyncOutcome
import at.bitfire.davdroid.sync.KompaktSyncAttempt
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.ui.setup.KompaktConsentState
import at.bitfire.davdroid.util.dateformat.KompaktLastSyncFormatSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

/**
 * A withdrawn Contacts consent satisfies the unprompted "you can also sync Contacts" offer as well as
 * the message about the withdrawal, so answering the message must not hand the slot to the offer —
 * that would present the permission the user just declined as a feature they have not tried yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktLinkedAccountModelTest {

    private val account = mockAccount()

    private val kompaktAccountSettings = mockk<KompaktAccountSettings>(relaxed = true)
    private val switches = mockk<KompaktServiceSwitch>(relaxed = true)
    private val serviceRepository = mockk<DavServiceRepository>(relaxed = true)
    private val lastSyncSource = mockk<KompaktServiceLastSync>(relaxed = true)
    private val outcomeSource = mockk<KompaktServiceSyncOutcome>(relaxed = true)
    private val accountProgress = mockk<KompaktAccountProgressUseCase>(relaxed = true)
    private val lastSyncFormat = mockk<KompaktLastSyncFormatSource>(relaxed = true)

    // Real, not mocked: the ranking between the two dialogs is exactly what is under test.
    private val dialogSlot = KompaktLinkedAccountDialogSlot()

    private fun model(): KompaktLinkedAccountModel {
        // Contacts reads ConsentMissing and the offer has never been shown, so it is armed — the state a
        // withdrawal leaves behind, and the only one in which both dialogs are true at once.
        every { switches.observe(account, KompaktSyncService.CONTACTS) } returns
            flowOf(KompaktSyncSwitch.ConsentMissing)
        every { switches.observe(account, KompaktSyncService.CALENDAR) } returns
            flowOf(KompaktSyncSwitch.On)
        every { kompaktAccountSettings.observeNewContactsConsentShown(account) } returns flowOf(false)
        every { kompaktAccountSettings.observeReauthNeeded(account) } returns flowOf(false)
        every { kompaktAccountSettings.getReauthNeeded(account) } returns false
        every { serviceRepository.getServiceFlow(any(), any()) } returns flowOf(null)
        every { accountProgress(any(), any()) } returns flowOf(false)
        every { lastSyncSource.observe(any(), any()) } returns flowOf(Reported.Value(null))
        every { outcomeSource.observe(any(), any()) } returns flowOf(Reported.Value(null))
        every { lastSyncFormat.formatter } returns flowOf(mockk(relaxed = true))

        return KompaktLinkedAccountModel(
            account = account,
            initialReauth = false,
            context = RuntimeEnvironment.getApplication() as Context,
            kompaktAccountSettings = kompaktAccountSettings,
            accountRepository = mockk<AccountRepository>(relaxed = true),
            initDefaults = mockk<KompaktInitDefaults>(relaxed = true),
            serviceRepository = serviceRepository,
            provisioning = mockk<KompaktServiceProvisioning>(relaxed = true),
            lastSyncFormat = lastSyncFormat,
            switches = switches,
            lastSyncSource = lastSyncSource,
            outcomeSource = outcomeSource,
            syncAttempt = mockk<KompaktSyncAttempt>(relaxed = true),
            accountProgress = accountProgress,
            dialogSlot = dialogSlot,
            ioDispatcher = UnconfinedTestDispatcher(),
            logger = Logger.getGlobal()
        )
    }

    @Test
    fun `answering the withdrawal message does not surface the contacts offer`() = runTest {
        val shown = mutableListOf<KompaktLinkedAccountDialog?>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            dialogSlot.dialog.collect { shown += it }
        }

        val model = model()
        // The init collectors run on the main looper; without this the offer is never armed and the
        // scenario under test does not happen at all.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(KompaktLinkedAccountDialog.NewContactsConsent, shown.last())

        model.consentChanged(mapOf(KompaktSyncService.CONTACTS to KompaktConsentState.REVOKED))
        assertEquals(
            KompaktLinkedAccountDialog.PermissionChanged(KompaktSyncService.CONTACTS),
            shown.last()
        )

        val beforeAnswer = shown.size
        model.acknowledgePermissionChange(KompaktSyncService.CONTACTS)

        // Nothing but the empty slot: the offer must never become the showing dialog on the way out.
        assertEquals(listOf<KompaktLinkedAccountDialog?>(null), shown.drop(beforeAnswer))

        collector.cancel()
    }

}
