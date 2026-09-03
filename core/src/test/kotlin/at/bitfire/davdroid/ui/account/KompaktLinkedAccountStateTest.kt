/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

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


    // linkedAccountDialog

    @Test
    fun anExpiredAuthorizationOutranksEveryOtherDialog() {
        // Nothing else can be acted on until the account is re-authorized, so it is the one worth
        // showing when several are pending at once.
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(authError = true, outOfStorage = true, noInternet = true, syncFailed = true)
        )
    }

    @Test
    fun aFullDiskOutranksTheNetworkDialogs() {
        assertEquals(
            KompaktLinkedAccountDialog.OutOfStorage,
            linkedAccountDialog(authError = false, outOfStorage = true, noInternet = true, syncFailed = true)
        )
    }

    @Test
    fun aMissingConnectionOutranksAFailedRun() {
        // The failure is the symptom of being offline; naming the cause is the more useful of the two.
        assertEquals(
            KompaktLinkedAccountDialog.NoInternet,
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = true, syncFailed = true)
        )
    }

    @Test
    fun aFailedRunIsReportedWhenNothingElseExplainsIt() {
        assertEquals(
            KompaktLinkedAccountDialog.SyncFailed,
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = false, syncFailed = true)
        )
    }

    @Test
    fun noDialogWhenNothingIsWrong() {
        assertNull(
            linkedAccountDialog(authError = false, outOfStorage = false, noInternet = false, syncFailed = false)
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
