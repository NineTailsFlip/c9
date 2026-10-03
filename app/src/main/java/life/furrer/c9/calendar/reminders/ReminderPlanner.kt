package life.furrer.c9.calendar.reminders

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
import java.time.ZoneId
import life.furrer.nine.core.util.DateFmt

/** One `CalendarContract.CalendarAlerts` row: an alarm the calendar provider scheduled for one
 *  reminder of one event instance, joined (by the provider) with that event's own columns. */
data class AlertRow(
    val id: Long,
    val eventId: Long,
    val state: Int,
    val title: String?,
    val location: String?,
    val allDay: Boolean,
    val alarmTime: Long,
    val begin: Long,
    val end: Long,
    val selfAttendeeStatus: Int = Attendees.ATTENDEE_STATUS_NONE,
    val visible: Boolean = true,
    /** The reminder's lead time; 0 for a snooze (see [ReminderAlerts.insertSnooze]) — and for an
     *  "at time of event" reminder, which can't be told apart from one. Null when the row has none
     *  (not -1: that's a real lead time, Reminders.MINUTES_DEFAULT taken literally). */
    val minutes: Int? = null,
)

/** A notification is one per event *instance* — the provider writes one alert row per reminder,
 *  so an event with a 15-minute and a 5-minute reminder has two rows sharing this key, and the
 *  second simply updates the first one's notification. */
data class InstanceKey(val eventId: Long, val begin: Long)

data class ReminderNotice(
    val key: InstanceKey,
    val title: String?,
    val location: String?,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    /** Rings/vibrates. Only ever one notice per pass, and only one that just fired. */
    val loud: Boolean,
    /** The SCHEDULED alerts this pass means to fire for it (id -> alarm time) — whether it may
     *  ring depends on those actually firing, see [ReminderPlanner.settle]. */
    val fresh: Map<Long, Long> = emptyMap(),
)

/** What one pass over the due alerts decided: provider state to write, notifications to post,
 *  and notifications to take down. */
data class ReminderPlan(
    val stateChanges: Map<Long, Int>,
    val notices: List<ReminderNotice>,
    val cancels: Set<InstanceKey>,
)

/** The decision half of reminder handling, kept free of any Android service so every rule here
 *  is a plain unit test. Follows AOSP Calendar's/Etar's AlertService closely:
 *
 *  - SCHEDULED and due -> FIRED, and notified with sound. The state flip is the dedupe: the
 *    provider may deliver the same alarm time twice (it reschedules missed alarms at boot, and
 *    C9's own alarm usually coincides with one of its own), but a row only ever fires once.
 *  - FIRED -> notified again, silently. Notifications don't survive a reboot, the provider's
 *    rows do; re-posting is what brings an unanswered reminder back, and is a no-op for one
 *    that's still showing.
 *  - Declined invitations, and calendars the provider marks invisible, are DISMISSED (AOSP does
 *    the same with declines; the provider itself stops scheduling invisible calendars).
 *  - Nothing old is dragged up: a reminder that never got to fire for an instance that has
 *    already ended (phone off through the meeting), or one more than a day late, is DISMISSED
 *    silently, and a fired one is retired a day after its instance ended. A snooze is exempt
 *    from the "already ended" part: it was asked for knowing when the event ends. */
object ReminderPlanner {

    /** A reminder (other than a snooze) for something that ended this recently still shows — a zero-length event's
     *  "at time of event" alarm lands exactly on its end, and a short meeting may end before a
     *  late alarm is delivered. */
    const val ENDED_GRACE_MS = 15 * 60_000L

    /** The provider itself only reschedules missed alarms from the last day
     *  (`CalendarAlerts.rescheduleMissedAlarms`); anything older isn't a reminder any more. */
    const val STALE_MS = 24 * 60 * 60_000L

    /** How far back [ReminderAlerts.queryDue] reads DISMISSED rows: far enough for any due
     *  SCHEDULED row that isn't [STALE_MS] old to find a sibling written at another offset. */
    const val SIBLING_LOOKBACK_MS = STALE_MS + ReminderSchedule.MAX_OFFSET_SHIFT_MS

    /** [alerts]: every SCHEDULED or FIRED row that's due, plus the DISMISSED rows of the last
     *  [SIBLING_LOOKBACK_MS] — those only count as siblings, see [isShiftedRepeat]. */
    fun plan(alerts: List<AlertRow>, now: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderPlan {
        val changes = linkedMapOf<Long, Int>()
        val dropped = mutableSetOf<InstanceKey>()
        // Per instance: a row to show, and the SCHEDULED rows (id -> alarm time) firing for it.
        val shown = linkedMapOf<InstanceKey, Pair<AlertRow, MutableMap<Long, Long>>>()
        val handled = alerts
            .filter { it.allDay && (it.state == CalendarAlerts.STATE_FIRED || it.state == CalendarAlerts.STATE_DISMISSED) }
            .groupBy({ Triple(it.eventId, it.begin, it.minutes) }, { it.alarmTime })

        for (alert in alerts) {
            if (alert.state != CalendarAlerts.STATE_SCHEDULED && alert.state != CalendarAlerts.STATE_FIRED) continue
            if (alert.alarmTime > now) continue
            if (isShiftedRepeat(alert, handled)) {
                // Rang (or was dismissed) already: put away without a sound or a notification.
                changes[alert.id] = CalendarAlerts.STATE_DISMISSED
                continue
            }
            val key = InstanceKey(alert.eventId, alert.begin)
            val endsAt = endMillis(alert, zone)
            val declined = alert.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED
            val retire = when (alert.state) {
                // A snooze is a reminder asked for after the fact — "tell me again in 30 minutes"
                // about a meeting that ends in 10 still means telling — so only the staleness
                // rule applies to one.
                CalendarAlerts.STATE_SCHEDULED ->
                    (alert.minutes != 0 && endsAt < now - ENDED_GRACE_MS) || alert.alarmTime < now - STALE_MS
                else -> endsAt < now - STALE_MS
            }
            if (declined || !alert.visible || retire) {
                changes[alert.id] = CalendarAlerts.STATE_DISMISSED
                dropped += key
                continue
            }
            if (isEarly(alert, now, zone)) continue
            val entry = shown.getOrPut(key) { alert to mutableMapOf() }
            if (alert.state == CalendarAlerts.STATE_SCHEDULED) {
                changes[alert.id] = CalendarAlerts.STATE_FIRED
                entry.second[alert.id] = alert.alarmTime
            }
        }

        val notices = shown.map { (key, value) ->
            val row = value.first
            ReminderNotice(key, row.title, row.location, row.begin, row.end, row.allDay, loud = false, fresh = value.second)
        }.sortedBy { it.begin }
        val firing = notices.flatMap { it.fresh.keys }.toSet()
        return ReminderPlan(changes, settle(notices, firing), dropped - shown.keys)
    }

    /** An all-day reminder's SCHEDULED row that's a repeat of one already FIRED or DISMISSED:
     *  same event, begin and minutes, at an alarm time a whole offset shift away. The provider
     *  writes one like that after a timezone change or DST switch — its scheduler only skips a
     *  reminder with a row at the *exact* alarm time, and the offset (so the alarm time) moved —
     *  and, still within its two-hour slack, it'd ring a second time. A snooze (minutes 0 too)
     *  fits no offset ([ReminderSchedule.writtenOffset]), so it's never taken for one; nor is a
     *  row without minutes, which can't be matched to a reminder at all. */
    private fun isShiftedRepeat(alert: AlertRow, handled: Map<Triple<Long, Long, Int?>, List<Long>>): Boolean {
        val minutes = alert.minutes ?: return false
        return alert.state == CalendarAlerts.STATE_SCHEDULED && alert.allDay &&
            ReminderSchedule.writtenOffset(alert.begin, alert.alarmTime, minutes) != null &&
            handled[Triple(alert.eventId, alert.begin, minutes)].orEmpty().any { ReminderSchedule.isOffsetShift(it, alert.alarmTime) }
    }

    /** An all-day reminder's SCHEDULED row written at another offset whose time *today* hasn't
     *  come yet ([ReminderSchedule.realignedAlarmTime]) — normally moved there before this pass
     *  ([ReminderScheduler.look]); should the move not have happened, the row still mustn't
     *  ring early. It rings once that time comes (C9's alarm is set for it). */
    private fun isEarly(alert: AlertRow, now: Long, zone: ZoneId): Boolean {
        if (alert.state != CalendarAlerts.STATE_SCHEDULED) return false
        val minutes = alert.minutes ?: return false
        val realigned = ReminderSchedule.realignedAlarmTime(alert.allDay, alert.begin, alert.alarmTime, minutes, now, zone)
        return realigned != null && realigned > now
    }

    /** Decides which notice rings, given the alerts that really did go SCHEDULED -> FIRED
     *  ([fired]) — a row another pass, another app or the provider changed or deleted between
     *  this pass reading it and writing it isn't this pass's to ring for, so its notice (if it
     *  still has one) is posted quietly. One sound per pass however many fired at once (a reboot
     *  can deliver a backlog): the most recently due reminder gets it, the rest arrive quietly
     *  beside it. */
    fun settle(notices: List<ReminderNotice>, fired: Set<Long>): List<ReminderNotice> {
        val loudKey = notices
            .mapNotNull { notice -> notice.fresh.filterKeys { it in fired }.values.maxOrNull()?.let { notice.key to it } }
            .maxByOrNull { it.second }
            ?.first
        return notices.map { it.copy(loud = it.key == loudKey) }
    }

    /** All-day instances carry UTC-midnight bounds (see [DateFmt.allDayToLocalDate]); their real
     *  end is the local midnight of the end date. */
    fun endMillis(alert: AlertRow, zone: ZoneId = ZoneId.systemDefault()): Long =
        if (alert.allDay) {
            DateFmt.allDayToLocalDate(alert.end).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            alert.end
        }
}
