package life.furrer.c9.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import life.furrer.nine.core.util.DateFmt

private const val GAP_THRESHOLD_MILLIS = 90 * 60_000L

sealed interface CalendarRow {
    data class DayHeader(val date: LocalDate, val isToday: Boolean) : CalendarRow
    /** [fromEarlier]/[intoLater]: a timed event that began on a previous day / runs past this one's
     *  end (Fri-Sun trip on Saturday: both; 23:00-01:00 on the second day: [fromEarlier] only). */
    data class EventRow(
        val event: EventEntry,
        val isPast: Boolean,
        val displayIndex: Int? = null,
        val fromEarlier: Boolean = false,
        val intoLater: Boolean = false,
    ) : CalendarRow
    data class GapLabel(val label: String) : CalendarRow
    data object NowLine : CalendarRow
    data class EmptyDay(val date: LocalDate) : CalendarRow
}

/** Turns a day's events into the rendered row list — where the view logic in §7.5 actually
 *  lives (now-line placement, gap labels, past-dimming, the empty-day placeholder), kept as a
 *  pure function per §10 so it's testable without any Android/view dependency. */
object CalendarRows {

    /** Builds rows for one day: all-day events first, then timed events in order, with a
     *  [CalendarRow.GapLabel] inserted between consecutive timed events more than 90 minutes
     *  apart (§7.5.3), and a [CalendarRow.NowLine] inserted between the last past and next
     *  upcoming timed event **only if [nowMillis] actually falls within this [day]** — falling
     *  after the last event (or there being no timed events at all) puts it at the end instead.
     *  Past-dimming ([CalendarRow.EventRow.isPast]) compares directly against [nowMillis]
     *  regardless of which day this is, so a fully-past day (reached by paging backward) still
     *  dims all its events even though it gets no now-line. All-day events are never marked
     *  past — they read as a persistent banner, not something that "ends" during the day (matches
     *  §9.1's mockup, which never dims the all-day row).
     *
     *  [events] must already be sorted by BEGIN ascending (the query guarantees this) — this
     *  function does not sort. An empty day emits a single [CalendarRow.EmptyDay] placeholder
     *  instead of nothing (§7.5.4: Day/3-Day always show empty days, unlike Agenda). */
    fun buildDayRows(
        day: LocalDate,
        events: List<EventEntry>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CalendarRow> {
        if (events.isEmpty()) return listOf(CalendarRow.EmptyDay(day))

        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // A timed event covering this whole day (middle of a multi-day trip) is a banner like an
        // all-day one: first, never "past", and outside the gap/now-line sequence.
        val (banner, timed) = events.partition { it.allDay || (it.startMillis < dayStart && it.endMillis >= dayEnd) }
        val rows = mutableListOf<CalendarRow>()
        banner.forEach { rows.add(CalendarRow.EventRow(it, isPast = false, fromEarlier = !it.allDay, intoLater = !it.allDay)) }
        val nowInThisDay = nowMillis in dayStart until dayEnd
        var nowLineInserted = false

        timed.forEachIndexed { index, event ->
            if (nowInThisDay && !nowLineInserted && nowMillis <= event.startMillis) {
                rows.add(CalendarRow.NowLine)
                nowLineInserted = true
            }
            rows.add(
                CalendarRow.EventRow(
                    event,
                    isPast = event.endMillis < nowMillis,
                    fromEarlier = event.startMillis < dayStart,
                    intoLater = event.endMillis > dayEnd,
                ),
            )
            if (index < timed.size - 1) {
                val gap = timed[index + 1].startMillis - event.endMillis
                if (gap > GAP_THRESHOLD_MILLIS) {
                    rows.add(CalendarRow.GapLabel(DateFmt.formatGapLabel(gap)))
                }
            }
        }
        if (nowInThisDay && !nowLineInserted) {
            rows.add(CalendarRow.NowLine)
        }
        return rows
    }

    /** Every local date [event] appears on: those where begin < end-of-day and end > start-of-day
     *  (end exclusive, so ending exactly at midnight doesn't reach the next day), and always at
     *  least its start day. All-day events are UTC midnight-to-midnight and map to their UTC
     *  calendar dates via [DateFmt.allDayToLocalDate], never through [zone]. */
    fun datesOf(event: EventEntry, zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> {
        val first: LocalDate
        val last: LocalDate
        if (event.allDay) {
            first = DateFmt.allDayToLocalDate(event.startMillis)
            last = if (event.endMillis > event.startMillis) DateFmt.allDayToLocalDate(event.endMillis - 1) else first
        } else {
            first = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate()
            last = if (event.endMillis > event.startMillis) {
                Instant.ofEpochMilli(event.endMillis - 1).atZone(zone).toLocalDate()
            } else {
                first
            }
        }
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    }

    /** Groups [events] under every day they span, restricted to [window] (a span can be far
     *  longer than the loaded range, and Agenda renders every key it is given). Per-day order
     *  stays that of [events], i.e. by BEGIN. */
    fun groupByDay(
        events: List<EventEntry>,
        window: ClosedRange<LocalDate>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, List<EventEntry>> {
        val byDay = LinkedHashMap<LocalDate, MutableList<EventEntry>>()
        for (event in events) {
            for (date in datesOf(event, zone)) {
                if (date in window) byDay.getOrPut(date) { mutableListOf() }.add(event)
            }
        }
        return byDay
    }

    /** The date the row at [index] belongs to, for views (3-Day/Week/Agenda) that group rows
     *  under a [CalendarRow.DayHeader]/[CalendarRow.EmptyDay] per day — an [CalendarRow.EventRow]/
     *  [CalendarRow.GapLabel]/[CalendarRow.NowLine] never carries its own date, so this walks
     *  backward to the nearest row that does. Not meaningful for [DayView], which never inserts a
     *  header at all (it has exactly one day, known without consulting rows). */
    fun dateForRow(rows: List<CalendarRow>, index: Int): LocalDate? {
        for (i in index downTo 0) {
            when (val row = rows.getOrNull(i)) {
                is CalendarRow.DayHeader -> return row.date
                is CalendarRow.EmptyDay -> return row.date
                else -> {}
            }
        }
        return null
    }

    /** Numbers [CalendarRow.EventRow]s 1-9 continuously across [rows] — which may already be a
     *  concatenation of several days' [buildDayRows] output with [CalendarRow.DayHeader]s
     *  interspersed (§7.3: indices continue across day sections, they don't reset per day).
     *  Headers, gap labels, the now-line, and empty-day placeholders don't consume a number;
     *  rows past the 9th get `null`. */
    fun assignDisplayIndices(rows: List<CalendarRow>): List<CalendarRow> {
        var next = 1
        return rows.map { row ->
            if (row is CalendarRow.EventRow) {
                val displayIndex = if (next <= 9) next else null
                next++
                row.copy(displayIndex = displayIndex)
            } else {
                row
            }
        }
    }
}
