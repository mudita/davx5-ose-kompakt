/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.ui.setup.KompaktConsentState.ABSENT
import at.bitfire.davdroid.ui.setup.KompaktConsentState.GRANTED
import at.bitfire.davdroid.ui.setup.KompaktConsentState.KEPT
import at.bitfire.davdroid.ui.setup.KompaktConsentState.REVOKED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KompaktConsentChangeTest {

    private val calendar = setOf(Service.TYPE_CALDAV)
    private val contacts = setOf(Service.TYPE_CARDDAV)
    private val both = setOf(Service.TYPE_CALDAV, Service.TYPE_CARDDAV)
    private val none = emptySet<String>()

    private fun diff(before: Set<String>, after: Set<String>) = consentDiff(before, after)

    private fun states(calendar: KompaktConsentState, contacts: KompaktConsentState) = mapOf(
        KompaktSyncService.CALENDAR to calendar,
        KompaktSyncService.CONTACTS to contacts
    )


    // consentDiff — every service gets a state, so a caller can read gains as readily as losses

    @Test
    fun `nothing changed`() {
        assertEquals(states(KEPT, KEPT), diff(both, both))
    }

    @Test
    fun `a service the account never had stays absent`() {
        assertEquals(states(KEPT, ABSENT), diff(calendar, calendar))
    }

    @Test
    fun `one service dropped while the other is kept`() {
        assertEquals(states(KEPT, REVOKED), diff(both, calendar))
        assertEquals(states(REVOKED, KEPT), diff(both, contacts))
    }

    @Test
    fun `one service swapped for the other`() {
        assertEquals(states(REVOKED, GRANTED), diff(calendar, contacts))
        assertEquals(states(GRANTED, REVOKED), diff(contacts, calendar))
    }

    @Test
    fun `a service added alongside the one already granted`() {
        assertEquals(states(KEPT, GRANTED), diff(calendar, both))
    }

    @Test
    fun `everything dropped`() {
        assertEquals(states(REVOKED, REVOKED), diff(both, none))
        assertEquals(states(REVOKED, ABSENT), diff(calendar, none))
    }


    // withdrawnService — which service the message names

    @Test
    fun `the service that was revoked is the one named`() {
        assertEquals(KompaktSyncService.CONTACTS, withdrawnService(diff(both, calendar)))
        assertEquals(KompaktSyncService.CALENDAR, withdrawnService(diff(both, contacts)))
    }

    @Test
    fun `a swap names the service that went, not the one that arrived`() {
        assertEquals(KompaktSyncService.CALENDAR, withdrawnService(diff(calendar, contacts)))
    }

    @Test
    fun `nothing lost is nothing to say`() {
        assertNull(withdrawnService(diff(both, both)))
        assertNull(withdrawnService(diff(calendar, calendar)))
    }

    @Test
    fun `a gain alone is not a loss`() {
        assertNull(withdrawnService(diff(calendar, both)))
    }

}
