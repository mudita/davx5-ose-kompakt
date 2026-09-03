/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktLinkedAccountStateTest {

    @Test
    fun `an account that predates Contacts sync is offered it`() {
        assertTrue(
            newContactsConsentVisible(
                calendar = KompaktSyncSwitch.On,
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
                calendar = KompaktSyncSwitch.On,
                contacts = KompaktSyncSwitch.ConsentMissing,
                alreadyShown = true
            )
        )
    }

    @Test
    fun `Calendar switched off still counts as granted`() {
        // "not ConsentMissing", not "On" — turning Calendar sync off doesn't revoke its consent, so
        // such an account is still a candidate for being offered Contacts sync
        assertTrue(
            newContactsConsentVisible(
                calendar = KompaktSyncSwitch.Off,
                contacts = KompaktSyncSwitch.ConsentMissing,
                alreadyShown = false
            )
        )
    }

    @Test
    fun `nothing to offer once Contacts consent exists`() {
        assertFalse(
            newContactsConsentVisible(
                calendar = KompaktSyncSwitch.On,
                contacts = KompaktSyncSwitch.Off,
                alreadyShown = false
            )
        )
    }

    @Test
    fun `a Calendar-missing account never sees it`() {
        // Deliberately one-directional: a missing Calendar consent can only come from a partial grant
        // at link time, where the user already saw the choice
        assertFalse(
            newContactsConsentVisible(
                calendar = KompaktSyncSwitch.ConsentMissing,
                contacts = KompaktSyncSwitch.ConsentMissing,
                alreadyShown = false
            )
        )
    }

}
