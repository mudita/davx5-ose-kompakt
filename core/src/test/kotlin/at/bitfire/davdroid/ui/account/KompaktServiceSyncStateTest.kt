/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.Assert.assertEquals
import org.junit.Test

class KompaktServiceSyncStateTest {

    @Test
    fun `missing consent outranks the sync interval`() {
        // A stale interval must never make an unconsented service look enabled — consent is checked first
        assertEquals(
            KompaktSyncSwitch.ConsentMissing,
            syncSwitch(consentGranted = false, autoSyncEnabled = true)
        )
    }

    @Test
    fun `granted and on the Kompakt interval reads On`() {
        assertEquals(
            KompaktSyncSwitch.On,
            syncSwitch(consentGranted = true, autoSyncEnabled = true)
        )
    }

    @Test
    fun `granted without the Kompakt interval reads Off`() {
        // The interval, not the scope, decides On vs Off: a scope granted during re-auth brings no
        // service, no discovery and no interval with it
        assertEquals(
            KompaktSyncSwitch.Off,
            syncSwitch(consentGranted = true, autoSyncEnabled = false)
        )
    }

}
