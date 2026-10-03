package life.furrer.c9.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.nine.core.util.DateFmt

enum class RecurrenceFrequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

private val UNTIL_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
private val UNTIL_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
private val UNTIL_FLOATING_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/** RRULE parts [Recurrence.describe] puts into words (WKST changes nothing a reader would notice
 *  in the rules it describes) — any other part makes it append "(custom)" rather than read as a
 *  plainer rule than the one actually stored. */
private val DESCRIBED_PARTS = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "COUNT", "UNTIL", "WKST")
private val BYDAY_PATTERN = Regex("""([+-]?\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)""")
private val WEEKDAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/** Builds and describes simple RRULEs. [buildRule] only ever writes frequency + interval — the
 *  editor has no BYDAY/COUNT controls, so it keeps an existing event's rule verbatim rather than
 *  rebuilding it. [withUntil]/[withCount] adjust an existing rule for "this and following" edits
 *  per §6.1.3. [describe] parses just enough of an RRULE to summarize it for display (§9.3's
 *  "Every 6 months", plus weekdays/month days/COUNT/UNTIL) — this is not the "never parse an
 *  RRULE to display anything" the provider's recurrence *expansion* covers (§6.1): we still never
 *  compute instance dates ourselves. */
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

    /** e.g. "Every 2 weeks on Mon, Wed until 1 Dec 2026", "Every month on the last Fri, 10
     *  times". A part it can't put into words appends "(custom)", so the summary never claims a
     *  simpler rule than the one actually stored. [zone] reads a UTC UNTIL as a local date. */
    fun describe(rrule: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val fields = fieldsOf(rrule)
        val unit = unitOf(fields["FREQ"]) ?: return "Repeats"
        val interval = fields["INTERVAL"]?.toIntOrNull() ?: 1
        var custom = fields.keys.any { it !in DESCRIBED_PARTS }
        return buildString {
            append(if (interval <= 1) "Every $unit" else "Every $interval ${unit}s")
            fields["BYDAY"]?.let { value ->
                describeByDay(value, locale)?.let { append(" on $it") } ?: run { custom = true }
            }
            fields["BYMONTHDAY"]?.let { value ->
                describeByMonthDay(value)?.let { append(" on $it") } ?: run { custom = true }
            }
            fields["COUNT"]?.let { value ->
                value.toIntOrNull()?.let { append(if (it == 1) ", once" else ", $it times") } ?: run { custom = true }
            }
            fields["UNTIL"]?.let { value ->
                untilDate(value, zone)?.let { append(" until ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))}") }
                    ?: run { custom = true }
            }
            if (custom) append(" (custom)")
        }
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

    /** COUNT, or null for a rule that doesn't limit its occurrences by count. */
    fun countOf(rrule: String): Int? = fieldsOf(rrule)["COUNT"]?.toIntOrNull()

    /** [rrule] with its COUNT replaced by [count], every other part left exactly as it was — for
     *  §6.1.3's "this and following" continuation, which must only produce the occurrences the
     *  original series had left, not a fresh full COUNT of its own. */
    fun withCount(rrule: String, count: Int): String = rrule.split(";")
        .joinToString(";") { part -> if (part.substringBefore('=') == "COUNT") "COUNT=$count" else part }

    /** "Mon, Wed", "weekdays", "the 1st Mon", "the last Fri" — or null for a form it doesn't
     *  spell out (e.g. "-2MO"). */
    private fun describeByDay(value: String, locale: Locale): String? {
        val entries = value.split(",").map { BYDAY_PATTERN.matchEntire(it.trim()) ?: return null }
        val plain = entries.all { it.groupValues[1].isEmpty() }
        if (plain && entries.map { it.groupValues[2] }.toSet() == WEEKDAY_CODES.take(5).toSet()) return "weekdays"
        val words = entries.map { match ->
            val day = DayOfWeek.of(WEEKDAY_CODES.indexOf(match.groupValues[2]) + 1).getDisplayName(TextStyle.SHORT, locale)
            val ordinal = match.groupValues[1].takeIf { it.isNotEmpty() }?.let { ordinalOf(it.toInt()) ?: return null }
            if (ordinal == null) day else "$ordinal $day"
        }
        return (if (plain) "" else "the ") + words.joinToString(", ")
    }

    /** "day 15", "days 1, 15", "the last day" — or null for any other negative day. */
    private fun describeByMonthDay(value: String): String? {
        val days = value.split(",").map { it.trim().toIntOrNull() ?: return null }
        if (days == listOf(-1)) return "the last day"
        if (days.any { it !in 1..31 }) return null
        return if (days.size == 1) "day ${days.single()}" else "days ${days.joinToString(", ")}"
    }

    private fun ordinalOf(n: Int): String? = when (n) {
        -1 -> "last"
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        in 4..5 -> "${n}th"
        else -> null
    }

    /** UNTIL's three RFC 5545 forms: a bare date, a UTC date-time (read in [zone]), or a floating
     *  local date-time. */
    private fun untilDate(value: String, zone: ZoneId): LocalDate? = runCatching {
        when {
            value.length == 8 -> LocalDate.parse(value, UNTIL_DATE_FORMAT)
            value.endsWith("Z") -> LocalDateTime.parse(value.dropLast(1), UNTIL_FLOATING_FORMAT)
                .atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate()
            else -> LocalDateTime.parse(value, UNTIL_FLOATING_FORMAT).toLocalDate()
        }
    }.getOrNull()

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
