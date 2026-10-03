package life.furrer.c9.calendar.reminders

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import life.furrer.c9.calendar.CalendarActivity
import life.furrer.c9.calendar.EventDetailActivity

/** Selecting a reminder notification: dismisses the reminder and opens that instance's event.
 *  An invisible trampoline Activity rather than a BroadcastReceiver, which Android 12+ no longer
 *  lets start an Activity from a notification tap, and rather than a hook in
 *  [EventDetailActivity], which shouldn't need to know a notification brought it up.
 *
 *  The event opens in the calendar's own task, so it's in Recents and Back lands on the
 *  calendar: on top of whatever that task already shows when it's running, or as a fresh calendar
 *  with the event on top when it isn't. Not CLEAR_TOP on the calendar — that would throw away an
 *  event being edited, and the editor only saves on Back. */
class ReminderOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = ReminderReceiver.instanceOf(intent)
        if (key != null && firstOpen(key, SystemClock.elapsedRealtime())) {
            val app = applicationContext
            ReminderNotifications.cancel(app, key)
            Thread { Reminders.dismiss(app, key) }.start()
            val detail = Intent(this, EventDetailActivity::class.java).apply {
                putExtra(EventDetailActivity.EXTRA_EVENT_ID, key.eventId)
                putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, key.begin)
            }
            val calendarTask = getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull { task ->
                runCatching { task.taskInfo.baseActivity?.className in CALENDAR_ROOTS }.getOrDefault(false)
            }
            if (calendarTask != null) {
                calendarTask.moveToFront()
                calendarTask.startActivity(this, detail, null)
            } else {
                startActivities(
                    arrayOf(Intent(this, CalendarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), detail),
                )
            }
        }
        finish()
    }

    internal companion object {
        /** The calendar task's root: the launcher alias, or the Activity itself when another
         *  route (e.g. a VIEW with NEW_TASK) started it. */
        private val CALENDAR_ROOTS = setOf(CalendarActivity::class.java.name, "life.furrer.c9.CalendarLauncher")

        /** A second launch for the same instance this soon is one selection arriving twice, not
         *  a second request. */
        internal const val REPEAT_WINDOW_MS = 3_000L

        @Volatile
        internal var lastOpened: Pair<InstanceKey, Long>? = null

        /** Whether this is the first launch for [key] within [REPEAT_WINDOW_MS]. A repeat would
         *  put the same event on top of itself — the calendar task exists by then — so the first
         *  Back would seem to do nothing. */
        internal fun firstOpen(key: InstanceKey, at: Long): Boolean {
            val last = lastOpened
            if (last != null && last.first == key && at - last.second in 0 until REPEAT_WINDOW_MS) return false
            lastOpened = key to at
            return true
        }
    }
}
