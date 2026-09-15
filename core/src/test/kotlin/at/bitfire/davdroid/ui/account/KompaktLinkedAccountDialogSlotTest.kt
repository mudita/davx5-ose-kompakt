/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.ui.account.KompaktLinkedAccountDialog.AuthError
import at.bitfire.davdroid.ui.account.KompaktLinkedAccountDialog.NewContactsConsent
import at.bitfire.davdroid.ui.account.KompaktLinkedAccountDialog.NoInternet
import at.bitfire.davdroid.ui.account.KompaktLinkedAccountDialog.SyncFailed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KompaktLinkedAccountDialogSlotTest {

    private suspend fun KompaktLinkedAccountDialogSlot.showing() = dialog.first()

    @Test
    fun `a raise becomes the dialog on show`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()

        slot.raise(NoInternet)

        assertEquals(NoInternet, slot.showing())
    }

    @Test
    fun `a lower-priority raise is dropped while a higher one is held`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()

        slot.condition(AuthError, active = true)
        slot.raise(NoInternet)

        assertEquals(AuthError, slot.showing())
    }

    // Try again reads its services off the dialog, so a stale payload retries the wrong ones.
    @Test
    fun `a raise of equal rank replaces, so the newest payload wins`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()

        slot.raise(SyncFailed(setOf(KompaktSyncService.CALENDAR)))
        slot.raise(SyncFailed(setOf(KompaktSyncService.CONTACTS)))

        assertEquals(SyncFailed(setOf(KompaktSyncService.CONTACTS)), slot.showing())
    }


    // dismissal

    @Test
    fun `a dismissal empties the slot`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()
        slot.raise(NoInternet)

        slot.dismiss()

        assertNull(slot.showing())
    }

    // docs/app-integration.md promises the calendar app this sheet stays up while needs_reauth is set.
    @Test
    fun `a dismissal leaves the auth error in place`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()
        slot.condition(AuthError, active = true)

        slot.dismiss()

        assertEquals(AuthError, slot.showing())
    }


    // conditions

    @Test
    fun `an active condition shows`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()

        slot.condition(NewContactsConsent, active = true)

        assertEquals(NewContactsConsent, slot.showing())
    }

    // Entering with both: auth wins, and the consent offer must survive to be shown afterwards. Its
    // own condition never changes across this, so an edge-triggered slot loses it for good.
    @Test
    fun `a condition outranked on arrival still shows once the higher one clears`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()
        slot.condition(NewContactsConsent, active = true)
        slot.condition(AuthError, active = true)
        assertEquals(AuthError, slot.showing())

        slot.condition(AuthError, active = false)

        assertEquals(NewContactsConsent, slot.showing())
    }

    // Tap the logout icon, a background sync writes needs_reauth, the auth sheet takes over, re-auth
    // succeeds -- the destructive confirmation must not return unasked.
    @Test
    fun `an event outranked by an arriving condition is gone when that condition clears`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()
        slot.raise(KompaktLinkedAccountDialog.ConfirmUnlink)

        slot.condition(AuthError, active = true)
        assertEquals(AuthError, slot.showing())
        slot.condition(AuthError, active = false)

        assertNull(slot.showing())
    }

    @Test
    fun `an event the arriving condition does not outrank survives it`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()
        slot.raise(NoInternet)

        slot.condition(NewContactsConsent, active = true)
        slot.condition(NewContactsConsent, active = false)

        assertEquals(NoInternet, slot.showing())
    }

    @Test
    fun `a condition that goes false stops showing`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()
        slot.condition(NewContactsConsent, active = true)

        slot.condition(NewContactsConsent, active = false)

        assertNull(slot.showing())
    }

    // Available for a condition whose source cannot answer on the tap; the Contacts offer deliberately
    // does not use it, because there the flag is the honest answer.
    @Test
    fun `a dismissal can retire a condition without waiting for its source`() = runTest {
        val slot = KompaktLinkedAccountDialogSlot()
        slot.condition(NewContactsConsent, active = true)

        slot.dismiss()

        assertNull(slot.showing())
    }

}
