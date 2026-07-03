/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val LAST_SYNC_PATTERN_24H = "dd.MM.yyyy · HH:mm"
private const val LAST_SYNC_PATTERN_12H = "dd.MM.yyyy · hh:mm a"

internal fun lastSyncFormatter(is24Hour: Boolean, zone: ZoneId): DateTimeFormatter =
    DateTimeFormatter
        .ofPattern(if (is24Hour) LAST_SYNC_PATTERN_24H else LAST_SYNC_PATTERN_12H)
        .withZone(zone)
