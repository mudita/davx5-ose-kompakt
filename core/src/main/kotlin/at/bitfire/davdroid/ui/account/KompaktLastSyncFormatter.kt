/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val TIME_PATTERN_24H = "HH:mm"
private const val TIME_PATTERN_12H = "hh:mm a"
private const val DATE_PATTERN = "dd.MM.yyyy"

internal data class KompaktLastSyncWords(
    val today: String,
    val yesterday: String,
    val weekdays: Map<DayOfWeek, String>
)

internal fun formatLastSync(
    lastSync: Instant,
    now: Instant,
    zone: ZoneId,
    is24Hour: Boolean,
    words: KompaktLastSyncWords
): String {
    val syncedAt = lastSync.atZone(zone)
    val daysBack = ChronoUnit.DAYS.between(syncedAt.toLocalDate(), now.atZone(zone).toLocalDate())
    val day = when {
        // A device clock behind the server's yields a negative difference. Naming the current day is
        // wrong by less than printing a date in the future.
        daysBack <= 0L -> words.today
        daysBack == 1L -> words.yesterday
        daysBack <= 7L -> words.weekdays.getValue(syncedAt.dayOfWeek)
        else -> syncedAt.format(DateTimeFormatter.ofPattern(DATE_PATTERN))
    }
    val time = syncedAt.format(
        DateTimeFormatter.ofPattern(if (is24Hour) TIME_PATTERN_24H else TIME_PATTERN_12H)
    )
    return "$day $time"
}
