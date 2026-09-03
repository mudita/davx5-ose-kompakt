/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.settings.AccountSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktServiceToggleTest {

    @Test
    fun onWhenTheStoredIntervalIsTheKompaktInterval() {
        assertTrue(toggleOn(KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS))
    }

    @Test
    fun offWhenTheUserSwitchedItOff() {
        // setSyncInterval(null) stores a sentinel rather than clearing the key.
        assertFalse(toggleOn(AccountSettings.SYNC_INTERVAL_MANUALLY))
    }

    @Test
    fun offWhenNothingIsStored() {
        // An absent key is not "on": upstream substitutes a four-hour default for it, so reading it as
        // on would report a toggle the user never set.
        assertFalse(toggleOn(null))
    }

    @Test
    fun offForAnyOtherStoredInterval() {
        assertFalse(toggleOn(4 * 60 * 60L))
        assertFalse(toggleOn(0L))
    }

}
