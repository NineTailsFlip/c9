package life.furrer.c9.calendar

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import life.furrer.nine.core.util.DateFmt

enum class RecurrenceFrequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

private val UNTIL_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
private val UNTIL_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE

/** Builds and describes simple RRULEs (frequency + interval only — no BYDAY/COUNT; [withUntil]
 *  is the one exception, added for "this and following" edits per §6.1.3). [describe] parses
 *  just enough of an RRULE to summarize its own frequency/interval for display (§9.3's "Every 6
 *  months") — this is not the "never parse an RRULE to display anything" the provider's
 *  recurrence *expansion* covers (§6.1): we still never compute instance dates ourselves. */
object Recurrence {

    fun buildRule(frequency: RecurrenceFrequency, interval: Int): String? {
        val freq = when (frequency) {
            RecurrenceFrequency.NONE -> return null
            RecurrenceFrequency.DAILY -> "DAILY"
            RecurrenceFrequency.WEEKLY -> "WEEKLY"
            RecurrenceFrequency.MONTHLY -> "MONTHLY"
            RecurrenceFrequency.YEARLY -> "YEARLY"
        }
        return if (interval <= 1) "FREQ=$freq" else "FREQ=$freq;INTERVAL=$interval"
    }

    fun describe(rrule: String): String {
        val fields = fieldsOf(rrule)
        val unit = unitOf(fields["FREQ"]) ?: return "Repeats"
        val interval = fields["INTERVAL"]?.toIntOrNull() ?: 1
        return if (interval <= 1) "Every $unit" else "Every $interval ${unit}s"
    }

    fun parseFrequency(rrule: String?): RecurrenceFrequency {
        if (rrule == null) return RecurrenceFrequency.NONE
        return when (fieldsOf(rrule)["FREQ"]) {
            "DAILY" -> RecurrenceFrequency.DAILY
            "WEEKLY" -> RecurrenceFrequency.WEEKLY
            "MONTHLY" -> RecurrenceFrequency.MONTHLY
            "YEARLY" -> RecurrenceFrequency.YEARLY
            else -> RecurrenceFrequency.NONE
        }
    }

    fun parseInterval(rrule: String?): Int {
        if (rrule == null) return 1
        return fieldsOf(rrule)["INTERVAL"]?.toIntOrNull() ?: 1
    }

    /** §6.1.3's "this and following": truncates [rrule] so it ends just before [untilMillis] —
     *  the split instance's own start — by setting UNTIL to one second earlier. COUNT is
     *  mutually exclusive with UNTIL per RFC 5545, so it's dropped if present (this app's own
     *  editor never writes COUNT in the first place, but a series synced from elsewhere might
     *  have one). UNTIL's format depends on whether the event is all-day: a bare date
     *  (`yyyyMMdd`) with no time component, or a UTC date-time (`yyyyMMddTHHmmssZ`) — the same
     *  UTC-anchored handling as any other all-day value (§6.1.1), via [DateFmt.allDayToLocalDate]. */
    fun withUntil(rrule: String, untilMillis: Long, allDay: Boolean): String {
        val fields = fieldsOf(rrule).toMutableMap()
        val freq = fields.remove("FREQ") ?: return rrule
        fields.remove("COUNT")
        val untilSourceMillis = untilMillis - 1000L
        fields["UNTIL"] = if (allDay) {
            DateFmt.allDayToLocalDate(untilSourceMillis).format(UNTIL_DATE_FORMAT)
        } else {
            Instant.ofEpochMilli(untilSourceMillis).atZone(ZoneOffset.UTC).format(UNTIL_DATETIME_FORMAT)
        }
        val rest = fields.entries.joinToString(";") { (k, v) -> "$k=$v" }
        return if (rest.isEmpty()) "FREQ=$freq" else "FREQ=$freq;$rest"
    }

    private fun unitOf(freq: String?): String? = when (freq) {
        "DAILY" -> "day"
        "WEEKLY" -> "week"
        "MONTHLY" -> "month"
        "YEARLY" -> "year"
        else -> null
    }

    private fun fieldsOf(rrule: String): Map<String, String> = rrule.split(";")
        .mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }
        .toMap()
}
