/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.mockAuthState
import at.bitfire.davdroid.mockAuthStateWithoutScopes
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktSyncServiceTest {

    @Test
    fun mapsEachServiceToItsDataTypeAndServiceType() {
        assertEquals(SyncDataType.EVENTS, KompaktSyncService.CALENDAR.dataType)
        assertEquals(Service.TYPE_CALDAV, KompaktSyncService.CALENDAR.serviceType)
        assertEquals(SyncDataType.CONTACTS, KompaktSyncService.CONTACTS.dataType)
        assertEquals(Service.TYPE_CARDDAV, KompaktSyncService.CONTACTS.serviceType)
    }

    @Test
    fun tasksIsNotAToggleableService() {
        assertEquals(2, KompaktSyncService.entries.size)
        assertTrue(KompaktSyncService.entries.none { it.dataType == SyncDataType.TASKS })
    }

    @Test
    fun consentedWhenTheScopeIsGranted() {
        val authState = mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR)
        assertTrue(KompaktSyncService.CALENDAR.isConsented(authState))
        assertFalse(KompaktSyncService.CONTACTS.isConsented(authState))
    }

    @Test
    fun consentedPerServiceWhenBothScopesAreGranted() {
        val authState =
            mockAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR, KompaktOAuthGoogle.SCOPE_CONTACTS)
        assertTrue(KompaktSyncService.CALENDAR.isConsented(authState))
        assertTrue(KompaktSyncService.CONTACTS.isConsented(authState))
    }

    @Test
    fun notConsentedWithoutAnAuthState() {
        assertFalse(KompaktSyncService.CALENDAR.isConsented(null))
        assertFalse(KompaktSyncService.CONTACTS.isConsented(null))
    }

    @Test
    fun notConsentedWhenTheResponseEchoesNoScope() {
        assertFalse(KompaktSyncService.CALENDAR.isConsented(mockAuthStateWithoutScopes()))
    }

}
