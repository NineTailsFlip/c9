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
 *    our own snooze alarm can coincide with one of its own), but a row only ever fires once.
 *  - FIRED -> notified again, silently. Notifications don't survive a reboot or an app update,
 *    the provider's rows do; re-posting is what brings an unanswered reminder back, and is a
 *    no-op for one that's still showing.
 *  - Declined invitations, and calendars the provider marks invisible, are DISMISSED (AOSP does
 *    the same with declines; the provider itself stops scheduling invisible calendars).
 *  - Nothing old is dragged up: a reminder that never got to fire for an instance that has
 *    already ended (phone off through the meeting), or one more than a day late, is DISMISSED
 *    silently, and a fired one is retired a day after its instance ended. */
object ReminderPlanner {

    /** A reminder for something that ended this recently still shows — a zero-length event's
     *  "at time of event" alarm lands exactly on its end, and a short meeting may end before a
     *  late alarm is delivered. */
    const val ENDED_GRACE_MS = 15 * 60_000L

    /** The provider itself only reschedules missed alarms from the last day
     *  (`CalendarAlerts.rescheduleMissedAlarms`); anything older isn't a reminder any more. */
    const val STALE_MS = 24 * 60 * 60_000L

    fun plan(alerts: List<AlertRow>, now: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderPlan {
        val changes = linkedMapOf<Long, Int>()
        val dropped = mutableSetOf<InstanceKey>()
        // Per instance: (newest alarm among its rows, whether any of them just fired, a row).
        val shown = linkedMapOf<InstanceKey, Triple<Long, Boolean, AlertRow>>()

        for (alert in alerts) {
            if (alert.state != CalendarAlerts.STATE_SCHEDULED && alert.state != CalendarAlerts.STATE_FIRED) continue
            if (alert.alarmTime > now) continue
            val key = InstanceKey(alert.eventId, alert.begin)
            val endsAt = endMillis(alert, zone)
            val declined = alert.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED
            val retire = when (alert.state) {
                CalendarAlerts.STATE_SCHEDULED ->
                    endsAt < now - ENDED_GRACE_MS || alert.alarmTime < now - STALE_MS
                else -> endsAt < now - STALE_MS
            }
            if (declined || !alert.visible || retire) {
                changes[alert.id] = CalendarAlerts.STATE_DISMISSED
                dropped += key
                continue
            }
            val fresh = alert.state == CalendarAlerts.STATE_SCHEDULED
            if (fresh) changes[alert.id] = CalendarAlerts.STATE_FIRED
            val previous = shown[key]
            shown[key] = Triple(
                maxOf(previous?.first ?: Long.MIN_VALUE, alert.alarmTime),
                fresh || previous?.second == true,
                previous?.third ?: alert,
            )
        }

        // One sound per pass however many fired at once (a reboot can deliver a backlog): the
        // most recently due reminder gets it, the rest arrive quietly beside it.
        val loudKey = shown.entries.filter { it.value.second }.maxByOrNull { it.value.first }?.key
        val notices = shown.map { (key, value) ->
            val row = value.third
            ReminderNotice(key, row.title, row.location, row.begin, row.end, row.allDay, loud = key == loudKey)
        }.sortedBy { it.begin }
        return ReminderPlan(changes, notices, dropped - shown.keys)
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
