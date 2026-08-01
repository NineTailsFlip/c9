package life.furrer.c9.calendar

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure java.time — no Android dependency, plain JUnit. */
class CalendarRowsTest {

    private val day = LocalDate.of(2026, 7, 30)
    private val zone = ZoneId.systemDefault()

    private fun millisAt(hour: Int, minute: Int = 0): Long =
        day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun timedEvent(id: Long, startHour: Int, endHour: Int, startMinute: Int = 0, endMinute: Int = 0): EventEntry =
        EventEntry(
            eventId = id,
            title = "Event $id",
            startMillis = millisAt(startHour, startMinute),
            endMillis = millisAt(endHour, endMinute),
            allDay = false,
            location = null,
            calendarId = 1,
            displayColor = 0xFFFF0000.toInt(),
            rrule = null,
        )

    private fun allDayEvent(id: Long): EventEntry = EventEntry(
        eventId = id,
        title = "All-day $id",
        startMillis = 0,
        endMillis = 0,
        allDay = true,
        location = null,
        calendarId = 1,
        displayColor = 0xFFFF0000.toInt(),
        rrule = null,
    )

    /** Far outside [day] — no now-line should ever appear. */
    private val notToday = day.minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `an empty day yields a single placeholder row`() {
        val rows = CalendarRows.buildDayRows(day, emptyList(), notToday)
        assertEquals(listOf(CalendarRow.EmptyDay(day)), rows)
    }

    @Test
    fun `a gap of exactly 90 minutes does not get a gap label`() {
        val events = listOf(timedEvent(1, 9, 10), timedEvent(2, 11, 12, startMinute = 30))
        // 10:00 -> 11:30 is exactly 90 minutes.
        val rows = CalendarRows.buildDayRows(day, events, notToday)
        assertTrue(rows.none { it is CalendarRow.GapLabel })
    }

    @Test
    fun `a gap of more than 90 minutes gets a gap label`() {
        val events = listOf(timedEvent(1, 9, 10), timedEvent(2, 11, 12, startMinute = 31))
        // 10:00 -> 11:31 is 91 minutes.
        val rows = CalendarRows.buildDayRows(day, events, notToday)
        val gap = rows.filterIsInstance<CalendarRow.GapLabel>().single()
        assertEquals("— 1h 31m —", gap.label)
    }

    @Test
    fun `the now-line sits between the last past and next upcoming event`() {
        // Less than 90 minutes apart, so no gap label lands here too.
        val events = listOf(timedEvent(1, 8, 9), timedEvent(2, 9, 10, startMinute = 30))
        val now = millisAt(9, 15) // after event 1 ends, before event 2 starts
        val rows = CalendarRows.buildDayRows(day, events, now)

        val nowLineIndex = rows.indexOf(CalendarRow.NowLine)
        assertTrue(nowLineIndex > 0)
        assertTrue((rows[nowLineIndex - 1] as CalendarRow.EventRow).event.eventId == 1L)
        assertTrue((rows[nowLineIndex + 1] as CalendarRow.EventRow).event.eventId == 2L)
    }

    @Test
    fun `the now-line appears before the first event when now precedes everything`() {
        val events = listOf(timedEvent(1, 14, 15))
        val now = millisAt(8)
        val rows = CalendarRows.buildDayRows(day, events, now)
        assertEquals(CalendarRow.NowLine, rows.first())
    }

    @Test
    fun `the now-line appears after the last event when now is later than everything`() {
        val events = listOf(timedEvent(1, 8, 9))
        val now = millisAt(20)
        val rows = CalendarRows.buildDayRows(day, events, now)
        assertEquals(CalendarRow.NowLine, rows.last())
    }

    @Test
    fun `there is no now-line on a day that is not today`() {
        val events = listOf(timedEvent(1, 8, 9), timedEvent(2, 14, 15))
        val rows = CalendarRows.buildDayRows(day, events, notToday)
        assertTrue(rows.none { it is CalendarRow.NowLine })
    }

    @Test
    fun `events that ended before now are marked past regardless of which day this is`() {
        val events = listOf(timedEvent(1, 8, 9))
        // A day entirely in the past, reached by paging backward — no now-line, but still past.
        val farFuture = day.plusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = CalendarRows.buildDayRows(day, events, farFuture)
        val eventRow = rows.filterIsInstance<CalendarRow.EventRow>().single()
        assertTrue(eventRow.isPast)
    }

    @Test
    fun `all-day events are never marked past`() {
        val events = listOf(allDayEvent(1))
        val farFuture = day.plusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = CalendarRows.buildDayRows(day, events, farFuture)
        val eventRow = rows.filterIsInstance<CalendarRow.EventRow>().single()
        assertTrue(!eventRow.isPast)
    }

    @Test
    fun `assignDisplayIndices numbers only event rows and skips everything else`() {
        val rows = listOf(
            CalendarRow.DayHeader(day, isToday = true),
            CalendarRow.EventRow(timedEvent(1, 8, 9), isPast = true),
            CalendarRow.NowLine,
            CalendarRow.GapLabel("— 2h —"),
            CalendarRow.EventRow(timedEvent(2, 12, 13), isPast = false),
        )
        val indexed = CalendarRows.assignDisplayIndices(rows)

        assertTrue(indexed[0] is CalendarRow.DayHeader) // unchanged — headers carry no index
        assertEquals(1, (indexed[1] as CalendarRow.EventRow).displayIndex)
        assertEquals(2, (indexed[4] as CalendarRow.EventRow).displayIndex)
    }

    @Test
    fun `display indices are capped at 9 and continue across a multi-day concatenation`() {
        val firstDayRows = (1..6).map { CalendarRow.EventRow(timedEvent(it.toLong(), it, it + 1), isPast = false) }
        val secondDayRows = (7..11).map { CalendarRow.EventRow(timedEvent(it.toLong() + 100, 1, 2), isPast = false) }
        val concatenated = listOf(CalendarRow.DayHeader(day, isToday = true)) + firstDayRows +
            listOf(CalendarRow.DayHeader(day.plusDays(1), isToday = false)) + secondDayRows

        val indexed = CalendarRows.assignDisplayIndices(concatenated)
        val eventRows = indexed.filterIsInstance<CalendarRow.EventRow>()

        assertEquals((1..9).toList(), eventRows.take(9).map { it.displayIndex })
        assertEquals(listOf(null, null), eventRows.takeLast(2).map { it.displayIndex })
    }

    @Test
    fun `dateForRow resolves an event row to its section's day header`() {
        val secondDay = day.plusDays(1)
        val rows = listOf(
            CalendarRow.DayHeader(day, isToday = false),
            CalendarRow.EventRow(timedEvent(1, 8, 9), isPast = false),
            CalendarRow.DayHeader(secondDay, isToday = false),
            CalendarRow.EventRow(timedEvent(2, 8, 9), isPast = false),
        )
        assertEquals(day, CalendarRows.dateForRow(rows, 1))
        assertEquals(secondDay, CalendarRows.dateForRow(rows, 3))
    }

    @Test
    fun `dateForRow resolves a gap label or now-line to the same section as surrounding events`() {
        val rows = listOf(
            CalendarRow.DayHeader(day, isToday = false),
            CalendarRow.EventRow(timedEvent(1, 8, 9), isPast = false),
            CalendarRow.GapLabel("— 2h —"),
            CalendarRow.NowLine,
            CalendarRow.EventRow(timedEvent(2, 12, 13), isPast = false),
        )
        assertEquals(day, CalendarRows.dateForRow(rows, 2))
        assertEquals(day, CalendarRows.dateForRow(rows, 3))
    }

    @Test
    fun `dateForRow resolves a header or empty-day row to its own date directly`() {
        assertEquals(day, CalendarRows.dateForRow(listOf(CalendarRow.DayHeader(day, isToday = false)), 0))
        assertEquals(day, CalendarRows.dateForRow(listOf(CalendarRow.EmptyDay(day)), 0))
    }

    @Test
    fun `dateForRow returns null when nothing precedes the index`() {
        val rows = listOf(CalendarRow.EventRow(timedEvent(1, 8, 9), isPast = false))
        assertEquals(null, CalendarRows.dateForRow(rows, 0))
    }
}
