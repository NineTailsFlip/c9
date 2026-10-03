package life.furrer.c9.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which days an event shows on (Instances semantics: begin < end-of-day, end > start-of-day)
 *  and how a continuation row reads. Zones are passed explicitly so the result never depends on
 *  the machine running the tests. */
class MultiDayEventsTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val la = ZoneId.of("America/Los_Angeles")
    private val zones = listOf(zurich, la)

    private fun millis(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long = millis(ZoneId.of("UTC"), y, m, d)

    private fun event(start: Long, end: Long, allDay: Boolean = false, rrule: String? = null, id: Long = 1) =
        EventEntry(id, "E$id", start, end, allDay, null, 1, 0xFFFF0000.toInt(), rrule)

    private fun d(day: Int) = LocalDate.of(2026, 10, day) // Fri 2 Oct 2026

    @Test
    fun fridayToSundayTripShowsOnFriSatSun() {
        for (zone in zones) {
            val trip = event(millis(zone, 2026, 10, 2, 18), millis(zone, 2026, 10, 4, 15))
            assertEquals(listOf(d(2), d(3), d(4)), CalendarRows.datesOf(trip, zone))
            val byDay = CalendarRows.groupByDay(listOf(trip), d(1)..d(7), zone)
            assertEquals(setOf(d(2), d(3), d(4)), byDay.keys)
        }
    }

    @Test
    fun overnightEventShowsOnBothDays() {
        for (zone in zones) {
            val e = event(millis(zone, 2026, 10, 2, 23), millis(zone, 2026, 10, 3, 1))
            assertEquals(listOf(d(2), d(3)), CalendarRows.datesOf(e, zone))
        }
    }

    @Test
    fun endingExactlyAtMidnightDoesNotReachNextDay() {
        for (zone in zones) {
            val e = event(millis(zone, 2026, 10, 2, 22), millis(zone, 2026, 10, 3, 0))
            assertEquals(listOf(d(2)), CalendarRows.datesOf(e, zone))
        }
    }

    @Test
    fun zeroLengthEventStaysOnItsStartDay() {
        val t = millis(zurich, 2026, 10, 2, 0)
        assertEquals(listOf(d(2)), CalendarRows.datesOf(event(t, t), zurich))
    }

    @Test
    fun multiDayAllDayEventMapsToUtcDatesNotLocalOnes() {
        // Fri-Sun inclusive: UTC Fri 00:00 to Mon 00:00 (exclusive end).
        val trip = event(utcMidnight(2026, 10, 2), utcMidnight(2026, 10, 5), allDay = true)
        for (zone in zones) {
            assertEquals(listOf(d(2), d(3), d(4)), CalendarRows.datesOf(trip, zone))
        }
        val single = event(utcMidnight(2026, 10, 3), utcMidnight(2026, 10, 4), allDay = true)
        for (zone in zones) assertEquals(listOf(d(3)), CalendarRows.datesOf(single, zone))
    }

    @Test
    fun recurringMultiDayEventShowsEveryDayOfEveryOccurrence() {
        // Weekly Fri 18:00 - Sun 15:00, two expanded instances, as Instances reports them.
        for (zone in zones) {
            val week1 = event(millis(zone, 2026, 10, 2, 18), millis(zone, 2026, 10, 4, 15), rrule = "FREQ=WEEKLY")
            val week2 = event(millis(zone, 2026, 10, 9, 18), millis(zone, 2026, 10, 11, 15), rrule = "FREQ=WEEKLY")
            val byDay = CalendarRows.groupByDay(listOf(week1, week2), d(1)..d(14), zone)
            assertEquals(setOf(d(2), d(3), d(4), d(9), d(10), d(11)), byDay.keys)
            assertEquals(listOf(week2), byDay[d(10)])
        }
    }

    @Test
    fun groupByDayClipsToTheLoadedWindow() {
        val zone = zurich
        val long = event(millis(zone, 2026, 9, 1), millis(zone, 2026, 12, 1))
        val byDay = CalendarRows.groupByDay(listOf(long), d(5)..d(6), zone)
        assertEquals(setOf(d(5), d(6)), byDay.keys)
    }

    @Test
    fun continuationRowsAreFlagged() {
        for (zone in zones) {
            val trip = event(millis(zone, 2026, 10, 2, 18), millis(zone, 2026, 10, 4, 15))
            val now = millis(zone, 2026, 10, 1, 12)
            fun row(day: Int) = CalendarRows.buildDayRows(d(day), listOf(trip), now, zone)
                .filterIsInstance<CalendarRow.EventRow>().single()

            assertEquals(false, row(2).fromEarlier); assertEquals(true, row(2).intoLater)
            assertEquals(true, row(3).fromEarlier); assertEquals(true, row(3).intoLater)
            assertEquals(true, row(4).fromEarlier); assertEquals(false, row(4).intoLater)
        }
    }

    @Test
    fun fullyCoveredMiddleDayIsABannerAboveTimedEvents() {
        val zone = zurich
        val trip = event(millis(zone, 2026, 10, 2, 18), millis(zone, 2026, 10, 4, 15))
        val lunch = event(millis(zone, 2026, 10, 3, 12), millis(zone, 2026, 10, 3, 13), id = 2)
        val now = millis(zone, 2026, 10, 3, 14)
        val rows = CalendarRows.buildDayRows(d(3), listOf(trip, lunch), now, zone)
        val first = rows.first() as CalendarRow.EventRow
        assertEquals(trip, first.event)
        assertEquals(false, first.isPast)
        // Banner sits outside the timed sequence: no gap label, and the now-line follows lunch.
        assertTrue(rows.none { it is CalendarRow.GapLabel })
        assertEquals(listOf("EventRow", "EventRow", "NowLine"), rows.map { it::class.simpleName })
    }

    @Test
    fun overnightContinuationIsPastAfterItsEndAndKeepsNowLineOnTheRightDay() {
        val zone = la
        val e = event(millis(zone, 2026, 10, 2, 23), millis(zone, 2026, 10, 3, 1))
        val now = millis(zone, 2026, 10, 3, 9)
        val rows = CalendarRows.buildDayRows(d(3), listOf(e), now, zone)
        assertEquals(CalendarRow.EventRow(e, isPast = true, fromEarlier = true, intoLater = false), rows[0])
        assertEquals(CalendarRow.NowLine, rows[1])
        // On the first day the event isn't past yet-or-not by "now" on another day, and has no now-line.
        val firstDay = CalendarRows.buildDayRows(d(2), listOf(e), now, zone)
        assertTrue(firstDay.none { it is CalendarRow.NowLine })
        assertEquals(true, (firstDay[0] as CalendarRow.EventRow).intoLater)
    }
}
