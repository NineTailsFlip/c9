package life.furrer.c9.calendar.reminders

import android.content.Context
import android.util.Log
import android.provider.CalendarContract.CalendarAlerts
import life.furrer.c9.core.Prefs

/** Ties the pieces together: [refresh] is the one pass every trigger runs, and [dismiss]/[snooze]
 *  are the notification's actions. Everything here blocks on the provider — call it off the main
 *  thread.
 *
 *  All three are @Synchronized: each trigger runs on its own thread (a receiver's, the job's, the
 *  open screen's), and overlapping passes — boot plus the provider's boot-time EVENT_REMINDER,
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

    /** The one pass every trigger runs: C9's own alarm, the provider's `EVENT_REMINDER`, a
     *  calendar change (the content-trigger job), boot, an app update, a clock or timezone change.
     *
     *  1. Insert the alert rows of reminders that came due and have none — the provider only
     *     writes the row for its next alarm, and on some devices never gets to the one after.
     *  2. Fire and notify what's due from the alert rows, whoever wrote them.
     *  3. Arm C9's alarm for the next reminder, and the content-trigger job ([armJob] false only
     *     for the job itself, which re-arms once it's done).
     *
     *  The alarm is armed whatever happens in between — the planned time, or a retry when the
     *  provider couldn't be read or a step threw (which still propagates, for the caller to log):
     *  a pass must never end with reminders on and nothing set to wake C9 up again. */
    @Synchronized
    fun refresh(context: Context, now: Long = System.currentTimeMillis(), armJob: Boolean = true) {
        if (!active(context)) {
            ReminderScheduler.disarm(context)
            Log.i(ReminderScheduler.TAG, "pass: hands off, disarmed")
            return
        }
        var times: ReminderTimes? = null
        var inserted = 0
        var fired = 0
        var passed = false
        try {
            times = ReminderScheduler.lookOrNull(context, now)
            times?.let { inserted = ReminderScheduler.insert(context, it.due, now) }
            fired = pass(context, now)
            passed = true
        } finally {
            val planned = times?.nextAlarm
            // Anything short of a clean pass retries — though never later than a reminder the
            // look did find.
            val at = if (passed && planned != null) planned else minOf(planned ?: Long.MAX_VALUE, ReminderScheduler.retryAt(context, now))
            ReminderScheduler.arm(context, at)
            if (armJob) ReminderScheduler.ensureJob(context)
            // One line per pass, to count them on a device: `adb logcat -s C9Reminders`.
            Log.i(
                ReminderScheduler.TAG,
                "pass${if (passed) "" else " (failed)"}${if (armJob) "" else " from job"}: " +
                    "${times?.due?.size ?: "?"} due, $inserted inserted, $fired fired, next alarm in ${(at - now) / 1000}s",
            )
        }
    }

    /** Fires and notifies what's due from the alert rows, whoever wrote them; returns how many
     *  rows this pass fired. */
    private fun pass(context: Context, now: Long): Int {
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
        // dropped — and when a sync adapter deletes an event it doesn't even send EVENT_REMINDER,
        // which is why the content-trigger job runs this pass too.
        ReminderNotifications.cancelAllExcept(context, plan.notices.map { it.key }.toSet())
        return fired.size
    }

    /** [refresh] in the background unless everything is plainly armed already — for screens'
     *  onResume, the cheap catch-up after calendar or notification permission was just granted. */
    fun ensureRunning(context: Context) {
        if (!ReminderScheduler.isJobArmed(context) || !ReminderScheduler.isAlarmArmed(context)) refreshInBackground(context)
    }

    /** [refresh] on a thread of its own — for the UI, e.g. reminders just switched back on, so
     *  what's due shows now rather than at the next unrelated EVENT_REMINDER. */
    fun refreshInBackground(context: Context) {
        val app = context.applicationContext
        synchronized(background) {
            background.removeAll { !it.isAlive }
            background += reminderThread({ refresh(app) })
        }
    }

    private val background: MutableSet<Thread> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Waits for every [refreshInBackground] pass still running — for tests, where the one
     *  C9Application starts would otherwise land at some random point in the test. */
    internal fun awaitBackground() {
        synchronized(background) { background.toList() }.forEach { it.join(5_000) }
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
