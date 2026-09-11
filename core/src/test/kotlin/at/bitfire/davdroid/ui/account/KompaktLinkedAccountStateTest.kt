/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.sync.KompaktSyncFailure
import at.bitfire.davdroid.sync.KompaktSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktLinkedAccountStateTest {

    private val settled = KompaktServiceSyncState(KompaktSyncSwitch.On, KompaktSyncStatus.NeverSynced)
    private val resolvingSwitch =
        KompaktServiceSyncState(KompaktSyncSwitch.Resolving, KompaktSyncStatus.NeverSynced)
    private val resolvingStatus =
        KompaktServiceSyncState(KompaktSyncSwitch.On, KompaktSyncStatus.Resolving)


    // newContactsConsentVisible

    @Test
    fun `an account that predates Contacts sync is offered it`() {
        assertTrue(
            newContactsConsentVisible(
                contacts = KompaktSyncSwitch.ConsentMissing,
                alreadyShown = false
            )
        )
    }

    @Test
    fun `an account linked through the combined consent screen never sees it`() {
        // Linking marks it shown up front, so declining Contacts there isn't second-guessed later
        assertFalse(
            newContactsConsentVisible(
                contacts = KompaktSyncSwitch.ConsentMissing,
                alreadyShown = true
            )
        )
    }

    @Test
    fun `nothing to offer once Contacts consent exists`() {
        assertFalse(
            newContactsConsentVisible(
                contacts = KompaktSyncSwitch.Off,
                alreadyShown = false
            )
        )
    }


    private val failedCalendar = setOf(KompaktSyncService.CALENDAR)

    // linkedAccountDialog

    @Test
    fun anExpiredAuthorizationOutranksEveryOtherDialog() {
        // Nothing else can be acted on until the account is re-authorized, so it is the one worth
        // showing when several are pending at once.
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(authError = true, outOfStorage = true, noInternet = true, syncFailed = failedCalendar, confirmDisable = null)
        )
    }

    @Test
    fun aFullDiskOutranksTheNetworkDialogs() {
        assertEquals(
            KompaktLinkedAccountDialog.OutOfStorage,
            linkedAccountDialog(authError = false, outOfStorage = true, noInternet = true, syncFailed = failedCalendar, confirmDisable = null)
        )
    }

    @Test
    fun aMissingConnectionOutranksAFailedRun() {
        // The failure is the symptom of being offline; naming the cause is the more useful of the two.
        assertEquals(
            KompaktLinkedAccountDialog.NoInternet,
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = true, syncFailed = failedCalendar, confirmDisable = null)
        )
    }

    @Test
    fun aFailedRunIsReportedWhenNothingElseExplainsIt() {
        assertEquals(
            KompaktLinkedAccountDialog.SyncFailed(failedCalendar),
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = false, syncFailed = failedCalendar, confirmDisable = null)
        )
    }

    @Test
    fun theFailureDialogCarriesOnlyWhatFailed() {
        // Try again re-syncs this set, so a service that succeeded is not re-run.
        assertEquals(
            KompaktLinkedAccountDialog.SyncFailed(setOf(KompaktSyncService.CONTACTS)),
            linkedAccountDialog(
                authError = false,
                outOfStorage = false,
                noInternet = false,
                syncFailed = setOf(KompaktSyncService.CONTACTS)
            )
        )
    }

    @Test
    fun theAggregatedFailureOutranksASingleServicesCause() {
        // Both can be pending: a run finishes and raises the modal while a cause sheet is open. The
        // modal speaks for the whole attempt, so it wins rather than stacking two bottom sheets.
        val cause = KompaktLinkedAccountDialog.ExplainSyncFailure(
            KompaktSyncService.CONTACTS,
            KompaktSyncFailure.ServerProblem
        )

        assertEquals(
            KompaktLinkedAccountDialog.SyncFailed(failedCalendar),
            linkedAccountDialog(
                authError = false,
                outOfStorage = false,
                noInternet = false,
                syncFailed = failedCalendar,
                explainSyncFailure = cause
            )
        )
        assertEquals(
            cause,
            linkedAccountDialog(
                authError = false,
                outOfStorage = false,
                noInternet = false,
                syncFailed = null,
                explainSyncFailure = cause
            )
        )
    }

    @Test
    fun noDialogWhenNothingIsWrong() {
        assertNull(
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = false, syncFailed = null, confirmDisable = null)
        )
    }

    @Test
    fun theDisableConfirmationCarriesTheServiceItAsksAbout() {
        // The sheet names the service from this, rather than the screen remembering which row was tapped.
        assertEquals(
            KompaktLinkedAccountDialog.ConfirmDisable(KompaktSyncService.CONTACTS),
            linkedAccountDialog(
                authError = false,
                outOfStorage = false,
                noInternet = false,
                syncFailed = null,
                confirmDisable = KompaktSyncService.CONTACTS
            )
        )
    }

    @Test
    fun aPendingProblemOutranksTheDisableConfirmation() {
        // The confirmation is an intent, not a condition: showing it over the auth error would put a
        // dismissible sheet above one whose dismiss paths are locked on purpose.
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(
                authError = true,
                outOfStorage = false,
                noInternet = false,
                syncFailed = null,
                confirmDisable = KompaktSyncService.CALENDAR
            )
        )
    }

    @Test
    fun `the new-contacts offer shows when it is the only thing set`() {
        assertEquals(
            KompaktLinkedAccountDialog.NewContactsConsent,
            linkedAccountDialog(false, false, false, null, null, true, null)
        )
    }

    @Test
    fun `an auth error locks out the new-contacts offer instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(authError = true, outOfStorage = false, noInternet = false, syncFailed = null, newContactsConsent = true, requestConsent = null)
        )
    }

    @Test
    fun `out-of-storage locks out the new-contacts offer instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.OutOfStorage,
            linkedAccountDialog(authError = false, outOfStorage = true, noInternet = false, syncFailed = null, newContactsConsent = true, requestConsent = null)
        )
    }

    @Test
    fun `the four error tiers still pick the right dialog on their own`() {
        assertEquals(KompaktLinkedAccountDialog.NoInternet, linkedAccountDialog(authError = false, outOfStorage = false, noInternet = true, syncFailed = null, newContactsConsent = false, requestConsent = null))
        assertEquals(KompaktLinkedAccountDialog.SyncFailed(failedCalendar), linkedAccountDialog(authError = false, outOfStorage = false, noInternet = false, syncFailed = failedCalendar, newContactsConsent = false, requestConsent = null))
    }

    @Test
    fun `a requested consent shows when it is the only thing set`() {
        assertEquals(
            KompaktLinkedAccountDialog.RequestConsent(KompaktSyncService.CONTACTS),
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = false, syncFailed = null, newContactsConsent = false, requestConsent = KompaktSyncService.CONTACTS)
        )
    }

    @Test
    fun `a requested consent outranks the new-contacts offer`() {
        assertEquals(
            KompaktLinkedAccountDialog.RequestConsent(KompaktSyncService.CALENDAR),
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = false, syncFailed = null, newContactsConsent = true, requestConsent = KompaktSyncService.CALENDAR)
        )
    }

    @Test
    fun `an auth error locks out a requested consent instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(authError = true, outOfStorage = false, noInternet = false, syncFailed = null, newContactsConsent = false, requestConsent = KompaktSyncService.CONTACTS)
        )
    }


    // isLoading

    @Test
    fun theScreenLoadsWhileEitherServiceIsStillResolving() {
        // Both rows are withheld until both resolve: showing one settled row next to a placeholder is
        // a second repaint on a screen that ghosts.
        assertTrue(state(calendar = resolvingSwitch, contacts = settled).isLoading)
        assertTrue(state(calendar = settled, contacts = resolvingStatus).isLoading)
    }

    @Test
    fun theScreenIsReadyOnceBothServicesHaveResolved() {
        assertFalse(state(calendar = settled, contacts = settled).isLoading)
    }

    private fun state(calendar: KompaktServiceSyncState, contacts: KompaktServiceSyncState) =
        KompaktLinkedAccountState(email = "user@example.com", calendar = calendar, contacts = contacts)

}
