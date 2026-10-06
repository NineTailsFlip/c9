package life.furrer.c9.calendar

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import life.furrer.nine.core.util.DateFmt

/** A reminder's lead time - `Reminders.MINUTES`, the only thing the provider stores - turned into
 *  words, and to and from the moment it rings for an event starting at a given time. */
object ReminderLead {

    /** What the editor's Reminder menu offers. 4 weeks, not a month: a lead time is a number of
     *  minutes, and 40320 is the most Google Calendar accepts, so it syncs on every account. */
    val PRESETS: List<Int> = listOf(0, 5, 10, 15, 30, 60, 1440, 10_080, 40_320)

    /** The furthest ahead a custom reminder may be: Google's limit again, and past C9's own
     *  scheduler's lookahead ([life.furrer.c9.calendar.reminders.ReminderScheduler]) a reminder
     *  would ring only if the provider's own alarms do. Synced reminders beyond it are kept as is. */
    const val MAX_MINUTES = 40_320

    private const val WEEK = 10_080
    private const val DAY = 1440
    private const val HOUR = 60

    /** "at time of event", "default" for the calendar's default (-1), or every non-zero part of
     *  the lead time, largest first: "1 day 2h 30 min" (short) / "1 day 2 hours 30 minutes"
     *  ([long], the detail screen's wording). Without "before", which callers add once. */
    fun label(minutes: Int, long: Boolean = false): String {
        if (minutes < 0) return "default"
        if (minutes == 0) return "at time of event"
        val parts = mutableListOf<String>()
        var rest = minutes
        rest.div(WEEK).takeIf { it > 0 }?.let { parts += plural(it, "week"); rest %= WEEK }
        rest.div(DAY).takeIf { it > 0 }?.let { parts += plural(it, "day"); rest %= DAY }
        rest.div(HOUR).takeIf { it > 0 }?.let { parts += if (long) plural(it, "hour") else "${it}h"; rest %= HOUR }
        if (rest > 0) parts += if (long) plural(rest, "minute") else "$rest min"
        return parts.joinToString(" ")
    }

    private fun plural(count: Int, unit: String) = "$count $unit${if (count > 1) "s" else ""}"

    /** The lead time of a reminder ringing at [ringsAt] for an event starting at [startMillis], or
     *  null when that's after the start (the provider has no "after" reminders: a negative value
     *  means the calendar's default). An all-day event starts at local midnight of its day - the
     *  provider and [life.furrer.c9.calendar.reminders.ReminderSchedule] time its reminders from
     *  there, not from the stored UTC midnight. */
    fun leadMinutes(startMillis: Long, allDay: Boolean, ringsAt: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val lead = (eventStart(startMillis, allDay, zone) - ringsAt.atZone(zone).toInstant().toEpochMilli()) / 60_000L
        return if (lead < 0 || lead > Int.MAX_VALUE) null else lead.toInt()
    }

    /** When a reminder [minutes] ahead of an event starting at [startMillis] rings, the inverse of
     *  [leadMinutes]: what a custom reminder's date and time entry start from. */
    fun ringsAt(startMillis: Long, allDay: Boolean, minutes: Int, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        Instant.ofEpochMilli(eventStart(startMillis, allDay, zone) - minutes.coerceAtLeast(0) * 60_000L).atZone(zone).toLocalDateTime()

    private fun eventStart(startMillis: Long, allDay: Boolean, zone: ZoneId): Long =
        if (allDay) DateFmt.allDayToLocalDate(startMillis).atStartOfDay(zone).toInstant().toEpochMilli() else startMillis
}
