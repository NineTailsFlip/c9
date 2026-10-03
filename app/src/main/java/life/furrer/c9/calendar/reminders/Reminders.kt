package life.furrer.c9.calendar.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarAlerts
import life.furrer.c9.core.Prefs

/** Ties the pieces together: [refresh] is the one pass every trigger runs (the provider's
 *  `EVENT_REMINDER`, a snooze coming due, boot, an app update), and [dismiss]/[snooze] are the
 *  notification's actions. Everything here blocks on the provider — call it off the main thread.
 *
 *  All three are @Synchronized: each trigger runs on its own thread (a receiver's, the open
 *  screen's), and overlapping passes — boot plus the provider's boot-time EVENT_REMINDER, a snooze
 *  alarm landing on a provider alarm — or a pass overlapping a Dismiss would otherwise each read
 *  the same rows, both ring, or re-post what was just taken down. */
object Reminders {

    @Synchronized
    fun refresh(context: Context, now: Long = System.currentTimeMillis()) {
        // Off means hands off entirely: CalendarAlerts' contract asks an app that isn't notifying
        // not to touch the table, so a second calendar app that does notify still sees its rows.
        // Same with notifications refused (POST_NOTIFICATIONS denied, the app's notifications or
        // just the reminder channel blocked in system settings): marking alerts FIRED that
        // nobody can see would only lose them.
        if (!Prefs.reminderNotifications(context)) return
        if (!ReminderNotifications.allowed(context)) return
        if (!ReminderAlerts.hasPermission(context)) return
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

    /** Dismisses what's showing and schedules a fresh alert [Prefs.reminderSnoozeMinutes] from
     *  [now] — a new SCHEDULED row (so the usual pass fires it, dedupe and all) plus an alarm of
     *  C9's own to run that pass, since only the provider can arm the alarms it sends. A snooze
     *  doesn't outlive a reboot: the provider clears every still-SCHEDULED row when it starts up,
     *  the same as with AOSP Calendar's snooze. */
    @Synchronized
    fun snooze(context: Context, key: InstanceKey, end: Long, now: Long = System.currentTimeMillis()) {
        dismiss(context, key)
        val alarmTime = now + Prefs.reminderSnoozeMinutes(context) * 60_000L
        if (ReminderAlerts.insertSnooze(context, key, end, alarmTime, now)) scheduleRefresh(context, alarmTime)
    }

    /** Exact when allowed — C9 declares USE_EXACT_ALARM, which a calendar app gets without
     *  asking — and the closest inexact alarm otherwise, so a snooze is late rather than lost.
     *  Lint only knows SCHEDULE_EXACT_ALARM; canScheduleExactAlarms() is the actual check. */
    @SuppressLint("MissingPermission")
    fun scheduleRefresh(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_SNOOZE_DUE)
                // One alarm per time, like the provider's own EVENT_REMINDER data, so two snoozes
                // a minute apart don't replace each other.
                .setData(Uri.withAppendedPath(CalendarContract.CONTENT_URI, at.toString())),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = alarms.canScheduleExactAlarms() &&
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }.isSuccess
        if (!exact) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
}
