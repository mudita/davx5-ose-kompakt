/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktSyncSwitchTest {

    @Test
    fun consentMissingOutranksTheStoredInterval() {
        // A scope granted during a re-auth brings no service, no discovery and no interval with it, so
        // the interval decides between On and Off and consent only vetoes.
        assertEquals(KompaktSyncSwitch.ConsentMissing, kompaktSyncSwitch(consented = false, on = true))
        assertEquals(KompaktSyncSwitch.ConsentMissing, kompaktSyncSwitch(consented = false, on = false))
    }

    @Test
    fun theDerivationNeverProducesResolving() {
        // Resolving is a seed for the flow, not an outcome of the rule: every derived position is a
        // real one, so a settled switch can never fall back to "not read yet".
        val derived = listOf(true, false).flatMap { consented ->
            listOf(true, false).map { on -> kompaktSyncSwitch(consented, on) }
        }
        assertTrue(derived.none { it == KompaktSyncSwitch.Resolving })
    }

    @Test
    fun theIntervalDecidesOnceConsentIsGranted() {
        assertEquals(KompaktSyncSwitch.On, kompaktSyncSwitch(consented = true, on = true))
        assertEquals(KompaktSyncSwitch.Off, kompaktSyncSwitch(consented = true, on = false))
    }

}
