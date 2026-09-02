/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class KompaktInitDefaultsVersionTest {

    @Test
    fun prefersThePerServiceMarker() {
        assertEquals(2, appliedVersionOf(perService = 2, legacy = 0, service = KompaktSyncService.CALENDAR))
        assertEquals(2, appliedVersionOf(perService = 2, legacy = 0, service = KompaktSyncService.CONTACTS))
    }

    @Test
    fun theLegacyMarkerCountsForCalendarOnly() {
        // The single pre-existing key meant "calendar defaults done"; reading it as a Contacts value
        // would skip Contacts setup on every account that already exists.
        assertEquals(2, appliedVersionOf(perService = null, legacy = 2, service = KompaktSyncService.CALENDAR))
        assertEquals(0, appliedVersionOf(perService = null, legacy = 2, service = KompaktSyncService.CONTACTS))
    }

    @Test
    fun freshAccountIsVersionZeroForBothServices() {
        assertEquals(0, appliedVersionOf(perService = null, legacy = null, service = KompaktSyncService.CALENDAR))
        assertEquals(0, appliedVersionOf(perService = null, legacy = null, service = KompaktSyncService.CONTACTS))
    }

}
