package life.furrer.c9.calendar.reminders

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import life.furrer.c9.calendar.EventDetailActivity

/** Selecting a reminder notification: dismisses the reminder and opens that instance's event.
 *  An invisible trampoline Activity rather than a BroadcastReceiver, which Android 12+ no longer
 *  lets start an Activity from a notification tap, and rather than a hook in
 *  [EventDetailActivity], which shouldn't need to know a notification brought it up. */
class ReminderOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = ReminderReceiver.instanceOf(intent)
        if (key != null) {
            val app = applicationContext
            ReminderNotifications.cancel(app, key)
            Thread { Reminders.dismiss(app, key) }.start()
            startActivity(
                Intent(this, EventDetailActivity::class.java).apply {
                    putExtra(EventDetailActivity.EXTRA_EVENT_ID, key.eventId)
                    putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, key.begin)
                },
            )
        }
        finish()
    }
}
