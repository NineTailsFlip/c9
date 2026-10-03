package life.furrer.c9.calendar.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/** Runs [work] on a thread of its own while keeping the broadcast alive — every reminder job
 *  queries and writes the calendar provider, which must stay off the main thread. */
private fun BroadcastReceiver.runAsync(work: () -> Unit) {
    val pending = goAsync()
    Thread {
        try {
            work()
        } finally {
            pending?.finish()
        }
    }.start()
}

/** The calendar provider's `EVENT_REMINDER` — sent as an implicit broadcast with
 *  FLAG_RECEIVER_INCLUDE_BACKGROUND, which is what lets a manifest receiver get it on modern
 *  Android — plus boot and app updates, after which notifications that were showing are gone and
 *  the still-FIRED alerts are posted again. Exported because the first of those comes from the
 *  provider's process; whatever sends it, all it can do is make C9 look at the provider's own
 *  table, so a stray one is harmless. */
class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CalendarContract.ACTION_EVENT_REMINDER,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> runAsync { Reminders.refresh(context.applicationContext) }
        }
    }
}

/** The notification's own actions and C9's snooze alarm — only ever explicit intents from C9's
 *  own PendingIntents, so not exported. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_SNOOZE_DUE -> runAsync { Reminders.refresh(app) }
            ACTION_DISMISS -> instanceOf(intent)?.let { key -> runAsync { Reminders.dismiss(app, key) } }
            ACTION_SNOOZE -> instanceOf(intent)?.let { key ->
                val end = intent.getLongExtra(ReminderNotifications.EXTRA_END, key.begin)
                runAsync { Reminders.snooze(app, key, end) }
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "life.furrer.c9.reminders.DISMISS"
        const val ACTION_SNOOZE = "life.furrer.c9.reminders.SNOOZE"
        const val ACTION_SNOOZE_DUE = "life.furrer.c9.reminders.SNOOZE_DUE"

        internal fun instanceOf(intent: Intent): InstanceKey? {
            val eventId = intent.getLongExtra(ReminderNotifications.EXTRA_EVENT_ID, -1L)
            if (eventId == -1L || !intent.hasExtra(ReminderNotifications.EXTRA_BEGIN)) return null
            return InstanceKey(eventId, intent.getLongExtra(ReminderNotifications.EXTRA_BEGIN, 0L))
        }
    }
}
