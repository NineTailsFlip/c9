package life.furrer.c9.calendar.reminders

import android.content.Context
import android.provider.CalendarContract.CalendarAlerts
import life.furrer.c9.core.Prefs

/** Ties the pieces together: [refresh] is the one pass every trigger runs, and [dismiss]/[snooze]
 *  are the notification's actions. Everything here blocks on the provider — call it off the main
 *  thread.
 *
 *  All three are @Synchronized: each trigger runs on its own thread (a receiver's, the open
 *  screen's), and overlapping passes — boot plus the provider's boot-time EVENT_REMINDER,
 *  C9's alarm landing on one of the provider's — or a pass overlapping a Dismiss would otherwise
 *  each read the same rows, both ring, both insert, or re-post what was just taken down. */
object Reminders {

    /** Whether C9 is the one notifying: reminders on, notifications able to show, calendar
     *  access. Anything else means hands off entirely: CalendarAlerts' contract asks an app that
     *  isn't notifying not to touch the table, so a second calendar app that does notify still
     *  sees its rows — and marking alerts FIRED that nobody can see (POST_NOTIFICATIONS denied,
     *  the app's notifications or just the reminder channel blocked) would only lose them. */
    fun active(context: Context): Boolean =
        Prefs.reminderNotifications(context) && ReminderNotifications.allowed(context) && ReminderAlerts.hasPermission(context)

    /** The one pass every trigger runs: C9's own alarm, the provider's `EVENT_REMINDER`, boot,
     *  an app update, a clock or timezone change.
     *
     *  1. Insert the alert rows of reminders that came due and have none — the provider only
     *     writes the row for its next alarm, and on some devices never gets to the one after.
     *  2. Fire and notify what's due from the alert rows, whoever wrote them.
     *  3. Arm C9's alarm for the next reminder. */
    @Synchronized
    fun refresh(context: Context, now: Long = System.currentTimeMillis()) {
        if (!active(context)) {
            ReminderScheduler.disarm(context)
            return
        }
        val times = runCatching { ReminderScheduler.look(context, now) }.getOrNull()
        times?.let { ReminderScheduler.insert(context, it.due, now) }
        val plan = ReminderPlanner.plan(ReminderAlerts.queryDue(context, now), now)
        val fired = mutableSetOf<Long>()
        plan.stateChanges.forEach { (alertId, state) ->
            if (state == CalendarAlerts.STATE_FIRED) {
                if (ReminderAlerts.markFired(context, alertId, now)) fired += alertId
            } else {
                ReminderAlerts.setState(context, alertId, state)
            }
        }
        val snoozeMinutes = Prefs.reminderSnoozeMinutes(context)
        ReminderPlanner.settle(plan.notices, fired).forEach { ReminderNotifications.post(context, it, snoozeMinutes) }
        // Whatever's showing that this pass didn't post has no live alert behind it any more —
        // not just the ones the plan retired: the provider deletes alert rows outright (FIRED
        // ones too) when an event is deleted or moved, a reminder removed or an account
        // dropped, then sends EVENT_REMINDER for "now" precisely so the notifier catches up.
        ReminderNotifications.cancelAllExcept(context, plan.notices.map { it.key }.toSet())
        ReminderScheduler.arm(context, times?.nextAlarm ?: (now + ReminderScheduler.RETRY_MS))
    }

    /** [refresh] on a thread of its own — for the UI, e.g. reminders just switched back on, so
     *  what's due shows now rather than at the next unrelated EVENT_REMINDER. */
    fun refreshInBackground(context: Context) {
        val app = context.applicationContext
        Thread { refresh(app) }.start()
    }

    @Synchronized
    fun dismiss(context: Context, key: InstanceKey) {
        ReminderNotifications.cancel(context, key)
        ReminderAlerts.dismissInstance(context, key)
    }

    /** Dismisses what's showing and schedules a fresh alert [minutes] from [now] — a new
     *  SCHEDULED row (so the usual pass fires it, dedupe and all), which C9's own alarm then
     *  counts among the times to wake up for. A snooze doesn't outlive a reboot: the provider
     *  clears every still-SCHEDULED row when it starts up, the same as with AOSP Calendar's. */
    @Synchronized
    fun snooze(
        context: Context,
        key: InstanceKey,
        end: Long,
        now: Long = System.currentTimeMillis(),
        minutes: Int = Prefs.reminderSnoozeMinutes(context),
    ) {
        dismiss(context, key)
        val alarmTime = now + minutes * 60_000L
        if (ReminderAlerts.insertSnooze(context, key, end, alarmTime, now)) ReminderScheduler.armNext(context, now)
    }
}
