/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.sync.KompaktSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktLinkedAccountStateTest {

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

    @Test
    fun `nothing set is no dialog`() {
        assertNull(linkedAccountDialog(false, false, false, false, false, null))
    }

    @Test
    fun `the new-contacts offer shows when it is the only thing set`() {
        assertEquals(
            KompaktLinkedAccountDialog.NewContactsConsent,
            linkedAccountDialog(false, false, false, false, true, null)
        )
    }

    @Test
    fun `an auth error locks out the new-contacts offer instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(true, false, false, false, true, null)
        )
    }

    @Test
    fun `out-of-storage locks out the new-contacts offer instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.OutOfStorage,
            linkedAccountDialog(false, true, false, false, true, null)
        )
    }

    @Test
    fun `the four error tiers still pick the right dialog on their own`() {
        assertEquals(KompaktLinkedAccountDialog.NoInternet, linkedAccountDialog(false, false, true, false, false, null))
        assertEquals(KompaktLinkedAccountDialog.SyncFailed, linkedAccountDialog(false, false, false, true, false, null))
    }

    @Test
    fun `a requested consent shows when it is the only thing set`() {
        assertEquals(
            KompaktLinkedAccountDialog.RequestConsent(KompaktSyncService.CONTACTS),
            linkedAccountDialog(false, false, false, false, false, KompaktSyncService.CONTACTS)
        )
    }

    @Test
    fun `a requested consent outranks the new-contacts offer`() {
        assertEquals(
            KompaktLinkedAccountDialog.RequestConsent(KompaktSyncService.CALENDAR),
            linkedAccountDialog(false, false, false, false, true, KompaktSyncService.CALENDAR)
        )
    }

    @Test
    fun `an auth error locks out a requested consent instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(true, false, false, false, false, KompaktSyncService.CONTACTS)
        )
    }

}
