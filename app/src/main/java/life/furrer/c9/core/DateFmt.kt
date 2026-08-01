package life.furrer.c9.core

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

object DateFmt {
    private const val MILLIS_PER_MINUTE = 60_000L

    /** DTSTART/Instances.BEGIN for an all-day event is midnight UTC with EVENT_TIMEZONE="UTC" —
     *  the single most common calendar-client bug (§6.1.1). Converting through the system
     *  default zone can land on the wrong calendar date — especially near a DST transition,
     *  where the local zone's UTC offset for "midnight UTC" can fall on the previous local day —
     *  so this always reads the date components directly off the UTC-anchored instant instead.
     *  Never run an all-day value through [formatTime] or any other local-zone-aware path. */
    fun allDayToLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()

    fun formatTime(epochMillis: Long, is24Hour: Boolean): String {
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
    }

    /** e.g. "45 min", "3h", "3h 30m" — an event's duration in its selected-row metadata line. */
    fun formatDuration(durationMillis: Long): String {
        val totalMinutes = (durationMillis / MILLIS_PER_MINUTE).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$minutes min"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    /** e.g. "— 2h 15m —" — the separator shown between events more than 90 minutes apart
     *  (§7.5.3), so free time is legible without drawing an hour grid. */
    fun formatGapLabel(durationMillis: Long): String = "— ${formatDuration(durationMillis)} —"
}
