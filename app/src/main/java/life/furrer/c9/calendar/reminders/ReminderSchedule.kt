package life.furrer.c9.calendar.reminders

import android.provider.CalendarContract.Attendees
import java.time.Instant
import java.time.ZoneId
import life.furrer.nine.core.util.DateFmt

/** One `Instances` row of an event in a visible calendar — what a reminder is relative to. */
data class UpcomingInstance(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val selfAttendeeStatus: Int = Attendees.ATTENDEE_STATUS_NONE,
)

/** The identity of a CalendarAlerts row as far as the provider is concerned: its table is UNIQUE
 *  on these three, and its own scheduler skips a reminder that already has a row with them. */
data class AlertKey(val eventId: Long, val begin: Long, val alarmTime: Long)

/** A reminder that has come due without an alert row behind it — to be inserted as SCHEDULED,
 *  the row the provider itself would have written (`CalendarAlerts.insert`). */
data class DueReminder(val eventId: Long, val begin: Long, val end: Long, val alarmTime: Long, val minutes: Int) {
    val key: AlertKey get() = AlertKey(eventId, begin, alarmTime)
}

/** What one look at the upcoming reminders decided: rows to insert now, and when to look again. */
data class ReminderTimes(val due: List<DueReminder>, val nextAlarm: Long)

/** The timing half of C9's own reminder scheduling — pure, so every rule is a plain unit test.
 *
 *  The calendar provider only ever arms its *next* alarm, and re-runs its scheduling when its own
 *  receiver gets the EVENT_REMINDER it sent; on a device where that broadcast reaches only C9 the
 *  provider never schedules the following reminder until some unrelated change. So C9 works out
 *  reminder times itself, the way AOSP Calendar's/Etar's `AlarmScheduler` does (instances in a
 *  window joined with their METHOD_ALERT reminders), and fills in the alert rows itself, the way
 *  the provider's `CalendarAlarmManager.scheduleNextAlarmLocked` would have. */
object ReminderSchedule {

    /** How far back a reminder that never got a row still fires — the provider's own
     *  `SCHEDULE_ALARM_SLACK`: covers a reminder whose time had already passed when the event was
     *  created or synced, an alarm the device slept through, a timezone just crossed. */
    const val SLACK_MS = 2 * 60 * 60_000L

    /** Instances starting this far ahead are looked at for every reminder (Etar's lookahead).
     *  Reminders set further ahead of their event than this are rare enough to get a query of
     *  their own over a window as long as they need (see [ReminderScheduler]). */
    const val LOOKAHEAD_MS = 7 * 24 * 60 * 60_000L

    /** Look again at least this often, whatever is or isn't coming up — a safety net for anything
     *  a missed change trigger left out. */
    const val RECHECK_MS = 24 * 60 * 60_000L

    /** An all-day instance's begin is UTC midnight of its date; its reminders count from local
     *  midnight. Done exactly as the provider does — shifted by the zone's offset *now* — so the
     *  alarm time C9 computes is the very one the provider would put in its row, and the two
     *  dedupe on [AlertKey]. */
    fun alarmTime(instance: UpcomingInstance, minutes: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val start = if (instance.allDay) instance.begin - offsetMillis(now, zone) else instance.begin
        return start - minutes * 60_000L
    }

    private fun offsetMillis(at: Long, zone: ZoneId): Long =
        zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds * 1000L

    /** All-day ends at local midnight of its end date, as in [ReminderPlanner.endMillis]. */
    private fun endMillis(instance: UpcomingInstance, zone: ZoneId): Long =
        if (instance.allDay) DateFmt.allDayToLocalDate(instance.end).atStartOfDay(zone).toInstant().toEpochMilli() else instance.end

    /**
     * [instances] with [minutesByEvent] (each event's METHOD_ALERT reminders), against the alert
     * rows that already exist ([existing], any state — a dismissed reminder must not come back)
     * and the alarm times of SCHEDULED rows still in the future ([pending]: snoozes, rows the
     * provider wrote ahead of time).
     *
     * - Due: alarm time within the last [SLACK_MS], instance not over yet (the provider's
     *   "ignore events whose end time is already in the past"), no row yet.
     * - Next alarm: the earliest reminder or pending row after [now], at the latest [RECHECK_MS]
     *   from now.
     *
     * Declined invitations are left out — the planner dismisses their rows anyway, so they'd only
     * wake the phone for nothing.
     */
    fun plan(
        instances: List<UpcomingInstance>,
        minutesByEvent: Map<Long, List<Int>>,
        existing: Set<AlertKey>,
        pending: List<Long>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReminderTimes {
        val due = linkedMapOf<AlertKey, DueReminder>()
        var next = now + RECHECK_MS
        for (instance in instances) {
            if (instance.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED) continue
            // -1 is Reminders.MINUTES_DEFAULT, "the calendar's default" — nothing to compute from.
            for (minutes in minutesByEvent[instance.eventId].orEmpty().filter { it >= 0 }.distinct()) {
                val at = alarmTime(instance, minutes, now, zone)
                if (at > now) {
                    if (at < next) next = at
                    continue
                }
                if (at < now - SLACK_MS || endMillis(instance, zone) < now) continue
                val reminder = DueReminder(instance.eventId, instance.begin, instance.end, at, minutes)
                if (reminder.key !in existing) due.putIfAbsent(reminder.key, reminder)
            }
        }
        pending.filter { it > now }.minOrNull()?.let { if (it < next) next = it }
        return ReminderTimes(due.values.sortedBy { it.alarmTime }, next)
    }
}
