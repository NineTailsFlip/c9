package life.furrer.c9.calendar.reminders

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
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

/** An alert row already in the table, whatever its state — what a reminder is checked against. */
data class ExistingAlert(
    val eventId: Long,
    val begin: Long,
    val alarmTime: Long,
    val minutes: Int?,
    val id: Long = 0,
    val state: Int = CalendarAlerts.STATE_SCHEDULED,
    val allDay: Boolean = false,
) {
    val key: AlertKey get() = AlertKey(eventId, begin, alarmTime)
}

/** An all-day reminder's SCHEDULED row written at another UTC offset than today's, to be moved
 *  to [alarmTime] — or, when null, deleted, since a row for the same reminder already sits there. */
data class Realignment(val alertId: Long, val from: Long, val alarmTime: Long?)

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

    /** How far apart two zones' UTC offsets can be (UTC-12 to UTC+14) — and so how far an
     *  all-day reminder's alarm time can move when the offset changes. */
    const val MAX_OFFSET_SHIFT_MS = 26 * 60 * 60_000L

    /** Every UTC offset in use is a whole number of quarter hours... */
    private const val OFFSET_STEP_MS = 15 * 60_000L

    /** ...from UTC-12 to UTC+14. */
    private const val MIN_OFFSET_MS = -12 * 60 * 60_000L
    private const val MAX_OFFSET_MS = 14 * 60 * 60_000L

    /** Look again at least this often, whatever is or isn't coming up — a safety net for anything
     *  a missed change trigger left out. */
    const val RECHECK_MS = 24 * 60 * 60_000L

    /** An all-day instance's begin is UTC midnight of its date; its reminders count from local
     *  midnight. Done exactly as the provider does — shifted by the zone's offset *now* — so the
     *  alarm time C9 computes is the very one the provider would put in its row, and the two
     *  dedupe on [AlertKey]. (Across a DST switch or a timezone change the offset, and so the
     *  alarm time, moves — see [plan] for how a row from before still counts.) */
    fun alarmTime(instance: UpcomingInstance, minutes: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val start = if (instance.allDay) instance.begin - offsetMillis(now, zone) else instance.begin
        return start - minutes * 60_000L
    }

    private fun offsetMillis(at: Long, zone: ZoneId): Long =
        zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds * 1000L

    /** Where an all-day reminder's SCHEDULED row belongs now, if not where it is: written at
     *  another offset than the zone's *now* — before a timezone change or a DST switch — its alarm
     *  time is off by the difference (an hour early, crossing from Zurich to London). Null for a
     *  row that's right, a timed one (its alarm time doesn't depend on the offset), and a snooze
     *  (minutes 0 at no offset's alarm time — asked for at a moment, not relative to midnight). */
    fun realignedAlarmTime(allDay: Boolean, begin: Long, alarmTime: Long, minutes: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!allDay) return null
        val written = writtenOffset(begin, alarmTime, minutes) ?: return null
        val offset = offsetMillis(now, zone)
        return if (written == offset) null else begin - offset - minutes * 60_000L
    }

    /**
     * The all-day SCHEDULED rows in [existing] that [realignedAlarmTime] says belong elsewhere,
     * and where to: the alarm time C9 (and the provider) would compute today, so the reminder
     * rings at the same local time as if the row had been written now.
     *
     * Moved rather than dismissed and written anew: a moved row is exactly the row the provider's
     * scheduler would write now, so its own `alarmExists` check sees the reminder as taken care of
     * and writes no second one, its cleanup keeps it (that only looks at event, begin, end and
     * minutes), and it fires once, like any other row. A dismissed row would instead look to C9's
     * own scheduling — and to [ReminderPlanner]'s repeat check — like a reminder already done
     * with, a shifted sibling, and the reminder would never ring.
     *
     * Deleted instead when the target alarm time already has a row for the same reminder: the
     * provider got there first after the change (that row rings), or it's one that already rang.
     * Rows more than [ReminderPlanner.STALE_MS] overdue are left to the planner to retire.
     */
    fun realign(existing: List<ExistingAlert>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Realignment> {
        val taken = existing.groupBy({ it.key }, { it.minutes })
        val claimed = mutableSetOf<AlertKey>()
        return existing.mapNotNull { row ->
            if (row.state != CalendarAlerts.STATE_SCHEDULED || row.alarmTime < now - ReminderPlanner.STALE_MS) return@mapNotNull null
            val minutes = row.minutes ?: return@mapNotNull null
            val target = realignedAlarmTime(row.allDay, row.begin, row.alarmTime, minutes, now, zone) ?: return@mapNotNull null
            val key = AlertKey(row.eventId, row.begin, target)
            // A row of another reminder (other minutes) at the target: try anyway — the table's
            // UNIQUE index refuses the move, and the planner keeps the row from ringing early.
            if (minutes in taken[key].orEmpty() || !claimed.add(key)) Realignment(row.id, row.alarmTime, null)
            else Realignment(row.id, row.alarmTime, target)
        }
    }

    /** The UTC offset an all-day reminder row's alarm time was worked out with ([alarmTime]
     *  inverted), or null when no offset in use gives it — a snooze (minutes 0, at whatever
     *  millisecond it was asked for), which only lands on a quarter hour by coincidence. Rows the
     *  provider or C9 wrote for an all-day reminder always fit one. */
    fun writtenOffset(begin: Long, alarmTime: Long, minutes: Int): Long? {
        val offset = begin - minutes * 60_000L - alarmTime
        return offset.takeIf { it % OFFSET_STEP_MS == 0L && it in MIN_OFFSET_MS..MAX_OFFSET_MS }
    }

    /** Whether two alarm times of the same all-day reminder (event, begin, minutes) are the same
     *  reminder written at two different offsets: a whole number of quarter hours apart, within
     *  [MAX_OFFSET_SHIFT_MS]. */
    fun isOffsetShift(a: Long, b: Long): Boolean {
        val shift = a - b
        return shift != 0L && shift % OFFSET_STEP_MS == 0L && kotlin.math.abs(shift) <= MAX_OFFSET_SHIFT_MS
    }

    /** All-day ends at local midnight of its end date, as in [ReminderPlanner.endMillis]. */
    private fun endMillis(instance: UpcomingInstance, zone: ZoneId): Long =
        if (instance.allDay) DateFmt.allDayToLocalDate(instance.end).atStartOfDay(zone).toInstant().toEpochMilli() else instance.end

    /**
     * [instances] with [minutesByEvent] (each event's METHOD_ALERT reminders), against the alert
     * rows that already exist ([existing], any state — a dismissed reminder must not come back)
     * and the alarm times of SCHEDULED rows still in the future ([pending]: snoozes, rows the
     * provider wrote ahead of time).
     *
     * An all-day reminder also counts as having a row when one for the same instance and lead
     * time sits at a different offset from local midnight — written before a DST switch or a
     * timezone change moved the offset. Same event, begin and minutes, and the alarm time a whole
     * number of quarter hours away within the possible offsets: a snooze (minutes 0 too) lands
     * on the millisecond like that only by coincidence. Otherwise a reminder that already rang
     * would get a second row an hour off, and ring again.
     *
     * - Due: alarm time within the last [SLACK_MS], instance not over yet (the provider's
     *   "ignore events whose end time is already in the past"), no row yet.
     * - Next alarm: the earliest reminder or pending row after [now], at the latest [RECHECK_MS]
     *   from now — or just after the zone's next offset transition, if that comes first.
     *
     * Declined invitations are left out — the planner dismisses their rows anyway, so they'd only
     * wake the phone for nothing. Cancelled events (STATUS_CANCELED) are not: the provider rings
     * for them, and C9 keeps to what it does.
     */
    fun plan(
        instances: List<UpcomingInstance>,
        minutesByEvent: Map<Long, List<Int>>,
        existing: List<ExistingAlert>,
        pending: List<Long>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReminderTimes {
        val keys = existing.map { it.key }.toSet()
        val allDayRows = existing.groupBy({ Triple(it.eventId, it.begin, it.minutes) }, { it.alarmTime })
        fun handled(instance: UpcomingInstance, minutes: Int, at: Long): Boolean =
            AlertKey(instance.eventId, instance.begin, at) in keys ||
                instance.allDay && allDayRows[Triple(instance.eventId, instance.begin, minutes)].orEmpty().any { isOffsetShift(it, at) }
        val due = linkedMapOf<AlertKey, DueReminder>()
        var next = now + RECHECK_MS
        for (instance in instances) {
            if (instance.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED) continue
            // Every lead time literally, negative ones too, exactly as the provider's scheduler
            // takes them: a CalDAV trigger after the start ("9:00 on the day" of an all-day
            // event) arrives as negative minutes, and even -1 (Reminders.MINUTES_DEFAULT) gets a
            // row there, at begin + 1 minute. Anything C9 left out would be back on the provider's
            // unreliable path — and only identical alarm times dedupe against its rows.
            for (minutes in minutesByEvent[instance.eventId].orEmpty().distinct()) {
                val at = alarmTime(instance, minutes, now, zone)
                if (at > now) {
                    if (at < next) next = at
                    continue
                }
                if (at < now - SLACK_MS || endMillis(instance, zone) < now) continue
                val reminder = DueReminder(instance.eventId, instance.begin, instance.end, at, minutes)
                if (!handled(instance, minutes, at)) due.putIfAbsent(reminder.key, reminder)
            }
        }
        pending.filter { it > now }.minOrNull()?.let { if (it < next) next = it }
        // A DST switch moves every all-day alarm time computed before it — and unlike a timezone
        // change, it sends no broadcast. Look again right after it, so all-day rows get realigned
        // (and C9's own times recomputed) while the new times are still ahead: spring forward
        // makes them an hour earlier, which waiting for the old time would already have missed.
        zone.rules.nextTransition(Instant.ofEpochMilli(now))?.instant?.toEpochMilli()?.plus(1)?.let { if (it < next) next = it }
        return ReminderTimes(due.values.sortedBy { it.alarmTime }, next)
    }
}
