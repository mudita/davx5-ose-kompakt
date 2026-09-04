/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_DATE_CHANGED
import android.content.Intent.ACTION_LOCALE_CHANGED
import android.content.Intent.ACTION_TIMEZONE_CHANGED
import android.content.Intent.ACTION_TIME_CHANGED
import android.content.IntentFilter
import android.text.format.DateFormat
import at.bitfire.davdroid.repository.KompaktTimeFormatRepository
import at.bitfire.davdroid.util.broadcastReceiverFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import com.mudita.frontitude.R as RFrontitude

internal fun interface KompaktLastSyncFormatter {
    fun format(lastSyncMillis: Long): String
}

/**
 * Emits a formatter for the last-synchronization label, replacing it whenever the device changes how
 * that label should read: its language, its 12/24-hour setting, its time zone, or the current date.
 */
class KompaktLastSyncFormatSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeFormat: KompaktTimeFormatRepository
) {

    internal val formatter: Flow<KompaktLastSyncFormatter> =
        combine(timeFormat.is24HourFormat, displayChanges()) { _, _ -> buildFormatter() }

    // ACTION_TIME_TICK is deliberately absent: it arrives every minute, and a label that repaints
    // on a timer is what ghosts this display. Nothing here is deduplicated either — a date
    // rollover announces no new value of its own, so comparing inputs would strand the label on a
    // day that has passed. The repaint is prevented downstream, where the rendered label is
    // compared.
    private fun displayChanges(): Flow<Intent> =
        broadcastReceiverFlow(context, IntentFilter().apply {
            addAction(ACTION_LOCALE_CHANGED)
            addAction(ACTION_DATE_CHANGED)
            addAction(ACTION_TIMEZONE_CHANGED)
            addAction(ACTION_TIME_CHANGED)
        }, immediate = true)

    // The value from is24HourFormat is ignored and read again here: with TIME_12_24 unset the
    // effective format comes from the locale, which a setting observer never sees.
    private fun buildFormatter() = lastSyncFormatter(
        zone = ZoneId.systemDefault(),
        is24Hour = DateFormat.is24HourFormat(context),
        words = readWords()
    ) { Instant.now() }

    private fun readWords() = KompaktLastSyncWords(
        today = context.getString(RFrontitude.string.common_label_today),
        yesterday = context.getString(RFrontitude.string.common_label_yesterday),
        weekdays = WEEKDAY_LABELS.mapValues { (_, label) -> context.getString(label) }
    )

}

// now is read per call rather than captured, so a formatter that outlives a day rollover still
// buckets against the current date.
internal fun lastSyncFormatter(
    zone: ZoneId,
    is24Hour: Boolean,
    words: KompaktLastSyncWords,
    now: () -> Instant
): KompaktLastSyncFormatter =
    KompaktLastSyncFormatter { lastSyncMillis ->
        formatLastSync(
            lastSync = Instant.ofEpochMilli(lastSyncMillis),
            now = now(),
            zone = zone,
            is24Hour = is24Hour,
            words = words
        )
    }

private val WEEKDAY_LABELS = mapOf(
    DayOfWeek.MONDAY to RFrontitude.string.common_label_mon,
    DayOfWeek.TUESDAY to RFrontitude.string.common_label_tue,
    DayOfWeek.WEDNESDAY to RFrontitude.string.common_label_wed,
    DayOfWeek.THURSDAY to RFrontitude.string.common_label_thu,
    DayOfWeek.FRIDAY to RFrontitude.string.common_label_fri,
    DayOfWeek.SATURDAY to RFrontitude.string.common_label_sat,
    DayOfWeek.SUNDAY to RFrontitude.string.common_label_sun
)
