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
        assertNull(linkedAccountDialog(false, false, false, false, false))
    }

    @Test
    fun `the new-contacts offer shows when it is the only thing set`() {
        assertEquals(
            KompaktLinkedAccountDialog.NewContactsConsent,
            linkedAccountDialog(false, false, false, false, true)
        )
    }

    @Test
    fun `an auth error locks out the new-contacts offer instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.AuthError,
            linkedAccountDialog(true, false, false, false, true)
        )
    }

    @Test
    fun `out-of-storage locks out the new-contacts offer instead of stacking with it`() {
        assertEquals(
            KompaktLinkedAccountDialog.OutOfStorage,
            linkedAccountDialog(false, true, false, false, true)
        )
    }

    @Test
    fun `the four error tiers still pick the right dialog on their own`() {
        assertEquals(KompaktLinkedAccountDialog.NoInternet, linkedAccountDialog(false, false, true, false, false))
        assertEquals(KompaktLinkedAccountDialog.SyncFailed, linkedAccountDialog(false, false, false, true, false))
    }

}
