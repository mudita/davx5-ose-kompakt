/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

class KompaktLastSyncFormatSourceTest {

    private val words = KompaktLastSyncWords(
        today = "Today",
        yesterday = "Yesterday",
        weekdays = DayOfWeek.entries.associateWith { it.name.take(3) }
    )

    private var clock: Instant = Instant.parse("2026-05-14T14:30:00Z")

    @Test
    fun readsTheWallClockWhenTheLabelIsBuiltRatherThanWhenItIsEmitted() {
        // Nothing rebuilds the formatter between the two calls, so one that captured the time when
        // it was built would still answer "Today" after the day rolled over.
        val formatter = lastSyncFormatter(
            zone = ZoneId.of("UTC"),
            is24Hour = true,
            words = words
        ) { clock }
        val syncedAt = Instant.parse("2026-05-14T11:30:00Z").toEpochMilli()

        assertEquals("Today 11:30", formatter.format(syncedAt))

        clock = Instant.parse("2026-05-15T00:10:00Z")

        assertEquals("Yesterday 11:30", formatter.format(syncedAt))
    }

}
