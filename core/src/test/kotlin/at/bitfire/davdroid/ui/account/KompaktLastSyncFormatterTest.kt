/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class KompaktLastSyncFormatterTest {

    private val utc = ZoneId.of("UTC")

    /** Thursday, 14 May 2026, 14:30 UTC. */
    private val now = Instant.parse("2026-05-14T14:30:00Z")

    private val words = KompaktLastSyncWords(
        today = "Today",
        yesterday = "Yesterday",
        weekdays = mapOf(
            DayOfWeek.MONDAY to "Mon",
            DayOfWeek.TUESDAY to "Tue",
            DayOfWeek.WEDNESDAY to "Wed",
            DayOfWeek.THURSDAY to "Thu",
            DayOfWeek.FRIDAY to "Fri",
            DayOfWeek.SATURDAY to "Sat",
            DayOfWeek.SUNDAY to "Sun"
        )
    )

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

    private fun format(
        lastSync: String,
        now: Instant = this.now,
        zone: ZoneId = utc,
        is24Hour: Boolean = true
    ) = formatLastSync(Instant.parse(lastSync), now, zone, is24Hour, words)


    // day buckets

    @Test
    fun theSameDayIsNamedRatherThanDated() {
        assertEquals("Today 11:30", format("2026-05-14T11:30:00Z"))
    }

    @Test
    fun theDayBeforeIsNamedRatherThanDated() {
        assertEquals("Yesterday 11:30", format("2026-05-13T11:30:00Z"))
    }

    @Test
    fun twoDaysBackFallsToTheWeekday() {
        assertEquals("Tue 11:00", format("2026-05-12T11:00:00Z"))
    }

    @Test
    fun sixDaysBackIsTheLastWeekday() {
        assertEquals("Fri 11:00", format("2026-05-08T11:00:00Z"))
    }

    @Test
    fun theSeventhDayBackIsDatedRatherThanRepeatingTodaysWeekday() {
        // Seven days back is a Thursday, and so is the reference day — the window stops at six so a
        // weekday label can never name the day the reader is standing in.
        assertEquals("07.05.2026 11:00", format("2026-05-07T11:00:00Z"))
    }

    @Test
    fun theEighthDayBackIsDated() {
        assertEquals("06.05.2026 11:00", format("2026-05-06T11:00:00Z"))
    }


    // clock format

    @Test
    fun twelveHourFormatCarriesTheMeridiem() {
        assertEquals("Today 02:30 PM", format("2026-05-14T14:30:00Z", is24Hour = false))
    }

    @Test
    fun aDatedLabelFollowsTheClockFormatToo() {
        assertEquals("06.05.2026 11:00 AM", format("2026-05-06T11:00:00Z", is24Hour = false))
    }


    // zone

    @Test
    fun theDayBucketIsDecidedInTheDeviceZone() {
        // 13 May in UTC, but already 14 May in Tokyo, so the device zone makes this the current day.
        assertEquals("Today 05:00", format("2026-05-13T20:00:00Z", zone = ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun aMinuteBeforeMidnightIsTheDayBeforeAMinuteAfterIsTheSameDay() {
        val justAfterMidnight = Instant.parse("2026-05-14T00:10:00Z")
        assertEquals("Yesterday 23:59", format("2026-05-13T23:59:00Z", now = justAfterMidnight))
        assertEquals("Today 00:01", format("2026-05-14T00:01:00Z", now = justAfterMidnight))
    }

    @Test
    fun theBucketCountsCalendarDaysAcrossADaylightSavingChange() {
        // Warsaw moves to summer time on 29 March 2026: the sync is at +01:00 and "now" at +02:00,
        // three calendar days apart.
        assertEquals(
            "Sat 10:00",
            format(
                "2026-03-28T09:00:00Z",
                now = Instant.parse("2026-03-31T10:00:00Z"),
                zone = ZoneId.of("Europe/Warsaw")
            )
        )
    }


    // clock skew

    @Test
    fun aTimestampFromTomorrowReadsAsTheSameDay() {
        // The device clock can lag the server's, so the difference can be negative. Naming the
        // current day is wrong by less than printing a date in the future.
        assertEquals("Today 09:00", format("2026-05-15T09:00:00Z"))
    }
}
