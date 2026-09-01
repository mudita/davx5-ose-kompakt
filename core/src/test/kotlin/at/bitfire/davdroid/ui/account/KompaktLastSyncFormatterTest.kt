/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class KompaktLastSyncFormatterTest {

    private val zone = ZoneId.of("UTC")
    private val afternoon = Instant.parse("2026-05-14T14:30:00Z")
    private val morning = Instant.parse("2026-05-14T09:05:00Z")

    private lateinit var previousLocale: Locale

    @Before
    fun pinLocale() {
        // AM/PM text is locale-dependent; pin to US so assertions are deterministic on any CI locale.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun format_24Hour_afternoon() {
        assertEquals(
            "14.05.2026 · 14:30",
            lastSyncFormatter(is24Hour = true, zone = zone).format(afternoon)
        )
    }

    @Test
    fun format_12Hour_afternoon() {
        assertEquals(
            "14.05.2026 · 02:30 PM",
            lastSyncFormatter(is24Hour = false, zone = zone).format(afternoon)
        )
    }

    @Test
    fun format_24Hour_morning() {
        assertEquals(
            "14.05.2026 · 09:05",
            lastSyncFormatter(is24Hour = true, zone = zone).format(morning)
        )
    }

    @Test
    fun format_12Hour_morning() {
        assertEquals(
            "14.05.2026 · 09:05 AM",
            lastSyncFormatter(is24Hour = false, zone = zone).format(morning)
        )
    }
}
