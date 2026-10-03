package life.furrer.c9.calendar.reminders

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/** Runs [work] on a thread of its own while keeping the broadcast alive — every reminder job
 *  queries and writes the calendar provider, which must stay off the main thread. */
private fun BroadcastReceiver.runAsync(work: () -> Unit) {
    val pending = goAsync()
    reminderThread(work, after = { pending?.finish() })
}

/** Everything from outside that calls for a reminder pass:
 *
 *  - the calendar provider's `EVENT_REMINDER` — sent as an implicit broadcast with
 *    FLAG_RECEIVER_INCLUDE_BACKGROUND, which is what lets a manifest receiver get it;
 *  - boot, after which notifications that were showing are gone (the still-FIRED alerts are
 *    posted again) and so are C9's alarm and content-trigger job;
 *  - app updates (notifications survive those; the pass re-arms and catches up);
 *  - the clock set or the timezone changed — both still delivered to manifest receivers — which
 *    moves all-day reminders (they count from local midnight) and makes the armed alarm stale;
 *  - C9's notifications or its reminder channel unblocked (or blocked) in system settings, sent
 *    to just this app: reminders start (or stop) right away instead of at the next trigger.
 *
 *  Exported because most of those come from other processes; whatever sends one, all it can do
 *  is make C9 look at the provider's own tables, so a stray one is harmless. */
class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CalendarContract.ACTION_EVENT_REMINDER,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
            NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
            -> runAsync { Reminders.refresh(context.applicationContext) }
        }
    }
}

/** The notification's own actions and C9's reminder alarm — only ever explicit intents from C9's
 *  own PendingIntents, so not exported. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            // SNOOZE_DUE: the per-snooze alarm of earlier versions, possibly still pending.
            ACTION_REMINDER_DUE, ACTION_SNOOZE_DUE -> runAsync { Reminders.refresh(app) }
            ACTION_DISMISS -> instanceOf(intent)?.let { key -> runAsync { Reminders.dismiss(app, key) } }
            ACTION_SNOOZE -> instanceOf(intent)?.let { key ->
                val end = intent.getLongExtra(ReminderNotifications.EXTRA_END, key.begin)
                val minutes = intent.getIntExtra(ReminderNotifications.EXTRA_SNOOZE_MINUTES, 0)
                runAsync {
                    if (minutes > 0) Reminders.snooze(app, key, end, minutes = minutes) else Reminders.snooze(app, key, end)
                }
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "life.furrer.c9.reminders.DISMISS"
        const val ACTION_SNOOZE = "life.furrer.c9.reminders.SNOOZE"
        const val ACTION_SNOOZE_DUE = "life.furrer.c9.reminders.SNOOZE_DUE"
        const val ACTION_REMINDER_DUE = "life.furrer.c9.reminders.REMINDER_DUE"

        internal fun instanceOf(intent: Intent): InstanceKey? {
            val eventId = intent.getLongExtra(ReminderNotifications.EXTRA_EVENT_ID, -1L)
            if (eventId == -1L || !intent.hasExtra(ReminderNotifications.EXTRA_BEGIN)) return null
            return InstanceKey(eventId, intent.getLongExtra(ReminderNotifications.EXTRA_BEGIN, 0L))
        }
    }
}
