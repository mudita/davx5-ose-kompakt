/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.sync.KompaktSyncFailure
import at.bitfire.davdroid.sync.KompaktSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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


    // rank

    /** Every member, so the assertions below cannot quietly stop covering one. */
    private val everyDialog = listOf(
        KompaktLinkedAccountDialog.AuthError,
        KompaktLinkedAccountDialog.SyncOff,
        KompaktLinkedAccountDialog.OutOfStorage,
        KompaktLinkedAccountDialog.OfflinePlus,
        KompaktLinkedAccountDialog.NoInternet,
        KompaktLinkedAccountDialog.SyncFailed(failedCalendar),
        KompaktLinkedAccountDialog.ExplainSyncFailure(
            KompaktSyncService.CALENDAR,
            KompaktSyncFailure.ServerProblem
        ),
        KompaktLinkedAccountDialog.ImportServiceNow(KompaktSyncService.CALENDAR),
        KompaktLinkedAccountDialog.RequestConsent(KompaktSyncService.CONTACTS),
        KompaktLinkedAccountDialog.NewContactsConsent,
        KompaktLinkedAccountDialog.ConfirmDisable(KompaktSyncService.CONTACTS),
        KompaktLinkedAccountDialog.ConfirmUnlink
    )

    private fun outranks(higher: KompaktLinkedAccountDialog, lower: KompaktLinkedAccountDialog) =
        rank(higher) < rank(lower)

    @Test
    fun `every dialog has its own place in the order`() {
        // rank's when is exhaustive, so a new member compiles only once ranked -- this is what then
        // forces it into everyDialog, and so into the auth-error table below.
        assertEquals(everyDialog.indices.toList(), everyDialog.map(::rank).sorted())
    }

    @Test
    fun `the auth error outranks every other dialog`() {
        // Its sheet has every dismiss path locked, and docs/app-integration.md promises the calendar
        // app it stays up, so nothing may be shown above it.
        (everyDialog - KompaktLinkedAccountDialog.AuthError).forEach { other ->
            assertTrue("$other", outranks(KompaktLinkedAccountDialog.AuthError, other))
        }
    }

    // dismissible defaults to true, so this is what catches a new member made non-dismissible without
    // the clearer that obliges -- rank's exhaustive when is what forces it into everyDialog first.
    @Test
    fun `only the auth error refuses to be dismissed`() {
        everyDialog.forEach { dialog ->
            assertEquals("$dialog", dialog == KompaktLinkedAccountDialog.AuthError, !dialog.dismissible)
        }
    }

    @Test
    fun `nothing switched on outranks the environment dialogs`() {
        // The use case answers eligibility before it consults storage or the network, so a stale
        // environment flag must not turn "your sync is off" into "check your connection".
        assertTrue(outranks(KompaktLinkedAccountDialog.SyncOff, KompaktLinkedAccountDialog.OutOfStorage))
        assertTrue(outranks(KompaktLinkedAccountDialog.SyncOff, KompaktLinkedAccountDialog.OfflinePlus))
        assertTrue(outranks(KompaktLinkedAccountDialog.SyncOff, KompaktLinkedAccountDialog.NoInternet))
    }

    @Test
    fun `the cause of the missing connection outranks the symptom`() {
        // The use case answers Offline+ before it consults the network, so the two are never raised
        // together -- but ranked the other way, a switch the user moved would read as a flaky connection.
        assertTrue(outranks(KompaktLinkedAccountDialog.OfflinePlus, KompaktLinkedAccountDialog.NoInternet))
    }

    @Test
    fun `a pending problem outranks a confirmation`() {
        // A confirmation is an intent, not a condition: it is the cheapest thing to ask again.
        listOf(
            KompaktLinkedAccountDialog.ConfirmDisable(KompaktSyncService.CALENDAR),
            KompaktLinkedAccountDialog.ConfirmUnlink
        ).forEach { confirmation ->
            assertTrue(outranks(KompaktLinkedAccountDialog.OutOfStorage, confirmation))
            assertTrue(outranks(KompaktLinkedAccountDialog.SyncFailed(failedCalendar), confirmation))
        }
    }

    @Test
    fun `a consent the user asked for outranks the unprompted offer`() {
        assertTrue(
            outranks(
                KompaktLinkedAccountDialog.RequestConsent(KompaktSyncService.CALENDAR),
                KompaktLinkedAccountDialog.NewContactsConsent
            )
        )
    }

    @Test
    fun `the import-service-now prompt outranks a requested consent`() {
        assertTrue(
            outranks(
                KompaktLinkedAccountDialog.ImportServiceNow(KompaktSyncService.CALENDAR),
                KompaktLinkedAccountDialog.RequestConsent(KompaktSyncService.CALENDAR)
            )
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
