/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.sync.KompaktSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KompaktLinkedAccountDialogTest {

    @Test
    fun nothingToShow() {
        assertNull(dialog())
    }

    @Test
    fun theDisableConfirmationCarriesItsService() {
        assertEquals(
            KompaktLinkedAccountDialog.ConfirmDisable(KompaktSyncService.CONTACTS),
            dialog(confirmDisable = KompaktSyncService.CONTACTS)
        )
    }

    @Test
    fun aPersistentProblemOutranksTheDisableConfirmation() {
        // The confirmation is an intent, not a condition. Showing it over an auth error would put a
        // dismissible sheet above one whose dismiss paths are deliberately locked.
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            dialog(authError = true, confirmDisable = KompaktSyncService.CALENDAR)
        )
        assertEquals(
            KompaktLinkedAccountDialog.OutOfStorage,
            dialog(outOfStorage = true, confirmDisable = KompaktSyncService.CALENDAR)
        )
    }

    @Test
    fun authErrorOutranksEverythingElse() {
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            dialog(authError = true, outOfStorage = true, noInternet = true, syncFailed = true)
        )
    }

    @Test
    fun storageOutranksConnectivityWhichOutranksAFailedSync() {
        assertEquals(
            KompaktLinkedAccountDialog.OutOfStorage,
            dialog(outOfStorage = true, noInternet = true, syncFailed = true)
        )
        assertEquals(
            KompaktLinkedAccountDialog.NoInternet,
            dialog(noInternet = true, syncFailed = true)
        )
        assertEquals(KompaktLinkedAccountDialog.SyncFailed, dialog(syncFailed = true))
    }


    private fun dialog(
        authError: Boolean = false,
        outOfStorage: Boolean = false,
        noInternet: Boolean = false,
        syncFailed: Boolean = false,
        confirmDisable: KompaktSyncService? = null
    ) = linkedAccountDialog(authError, outOfStorage, noInternet, syncFailed, confirmDisable)

}
