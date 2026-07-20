/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the behaviour gate that decides whether an HTTP 401 is treated as device-clock skew
 * (→ "Account sync failed" dialog) or a genuine auth failure (→ re-authorization), by comparing the device
 * clock with the server clock read from the response `Date` header.
 */
class KompaktClockSkewExceptionTest {

    private val threshold = KompaktClockSkewException.CLOCK_SKEW_THRESHOLD_MS

    @Test
    fun `identical clocks are not skewed`() {
        assertFalse(KompaktClockSkewException.isClockSkewed(1_700_000_000_000L, 1_700_000_000_000L))
    }

    @Test
    fun `one minute difference is within tolerance`() {
        assertFalse(KompaktClockSkewException.isClockSkewed(0L, 60_000L))
    }

    @Test
    fun `difference exactly at threshold is not skewed`() {
        assertFalse(KompaktClockSkewException.isClockSkewed(0L, threshold))
    }

    @Test
    fun `difference just over threshold is skewed`() {
        assertTrue(KompaktClockSkewException.isClockSkewed(0L, threshold + 1))
    }

    @Test
    fun `device an hour ahead of server is skewed`() {
        assertTrue(KompaktClockSkewException.isClockSkewed(3_600_000L, 0L))
    }

    @Test
    fun `device an hour behind server is skewed`() {
        assertTrue(KompaktClockSkewException.isClockSkewed(0L, 3_600_000L))
    }
}
