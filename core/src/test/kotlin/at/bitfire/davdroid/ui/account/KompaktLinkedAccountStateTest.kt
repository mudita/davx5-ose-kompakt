/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktLinkedAccountStateTest {

    @Test
    fun `an account that predates Contacts sync sees the nudge`() {
        assertTrue(
            contactsDiscoveryNudgeVisible(
                calendar = KompaktSyncSwitch.On,
                contacts = KompaktSyncSwitch.ConsentMissing,
                nudgeSettled = false
            )
        )
    }

    @Test
    fun `an account linked through the combined consent screen never sees it`() {
        // Linking settles the nudge up front, so declining Contacts there isn't second-guessed later
        assertFalse(
            contactsDiscoveryNudgeVisible(
                calendar = KompaktSyncSwitch.On,
                contacts = KompaktSyncSwitch.ConsentMissing,
                nudgeSettled = true
            )
        )
    }

    @Test
    fun `Calendar switched off still counts as granted`() {
        // "not ConsentMissing", not "On" — turning Calendar sync off doesn't revoke its consent, so
        // such an account is still a candidate for discovering Contacts sync
        assertTrue(
            contactsDiscoveryNudgeVisible(
                calendar = KompaktSyncSwitch.Off,
                contacts = KompaktSyncSwitch.ConsentMissing,
                nudgeSettled = false
            )
        )
    }

    @Test
    fun `nothing to discover once Contacts consent exists`() {
        assertFalse(
            contactsDiscoveryNudgeVisible(
                calendar = KompaktSyncSwitch.On,
                contacts = KompaktSyncSwitch.Off,
                nudgeSettled = false
            )
        )
    }

    @Test
    fun `a Calendar-missing account never sees it`() {
        // Deliberately one-directional: a missing Calendar consent can only come from a partial grant
        // at link time, where the user already saw the choice
        assertFalse(
            contactsDiscoveryNudgeVisible(
                calendar = KompaktSyncSwitch.ConsentMissing,
                contacts = KompaktSyncSwitch.ConsentMissing,
                nudgeSettled = false
            )
        )
    }

}
