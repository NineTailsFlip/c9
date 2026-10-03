package life.furrer.c9.calendar.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.CalendarContract.Events
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.c9.R
import life.furrer.nine.core.util.DateFmt

private const val CHANNEL_REMINDERS = "event_reminders"

/** Every reminder notification shares this id and is told apart by its tag (the instance), so
 *  two instances can never collide the way a hashed id could. */
private const val NOTIFICATION_ID = 1
private const val TAG_PREFIX = "reminder:"

/** Event reminder notifications, in the house style of M9's `MessageNotifications` and P9's
 *  `CallNotifications`: one channel, the launcher's monochrome silhouette as the small icon, and
 *  only the actions a keypad user actually wants from the shade — Snooze and Dismiss. Selecting
 *  the notification itself opens the event (and dismisses the reminder, like AOSP Calendar). */
object ReminderNotifications {

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // High: a reminder that arrives silently in the shade is a reminder missed. Sound and
        // vibration are the channel defaults, so they're the user's to change in system settings.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Event reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
    }

    fun tag(key: InstanceKey): String = "$TAG_PREFIX${key.eventId}:${key.begin}"

    fun post(context: Context, notice: ReminderNotice, snoozeMinutes: Int) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(tag(notice.key), NOTIFICATION_ID, build(context, notice, snoozeMinutes))
    }

    fun cancel(context: Context, key: InstanceKey) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag(key), NOTIFICATION_ID)
    }

    /** Takes down every reminder notification — the setting was switched off. */
    fun cancelAll(context: Context) = cancelAllExcept(context, emptySet())

    /** Takes down every reminder notification except [keep]'s. */
    fun cancelAllExcept(context: Context, keep: Set<InstanceKey>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val kept = keep.map(::tag).toSet()
        manager.activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.tag?.startsWith(TAG_PREFIX) == true && it.tag !in kept }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun build(context: Context, notice: ReminderNotice, snoozeMinutes: Int): Notification {
        val text = describe(notice)
        val builder = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_calendar_monochrome)
            .setContentTitle(notice.title?.takeIf { it.isNotBlank() } ?: "(No title)")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_EVENT)
            .setShowWhen(false)
            .setOnlyAlertOnce(!notice.loud)
            .setContentIntent(openPending(context, notice))
            // Clearing it from the shade counts as dismissing it, or it would come back (silently)
            // on the next pass over the provider's still-FIRED rows.
            .setDeleteIntent(actionPending(context, ReminderReceiver.ACTION_DISMISS, notice))
            .addAction(
                Notification.Action.Builder(
                    null as Icon?,
                    "Snooze $snoozeMinutes min",
                    actionPending(context, ReminderReceiver.ACTION_SNOOZE, notice),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null as Icon?,
                    "Dismiss",
                    actionPending(context, ReminderReceiver.ACTION_DISMISS, notice),
                ).build(),
            )
        if (!notice.loud) {
            // The platform's spelling of NotificationCompat.setSilent: a child of a group that
            // only lets its (never posted) summary alert makes no sound — needed because
            // setOnlyAlertOnce alone only quiets an *update*, and a re-post after a reboot is new.
            builder.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }
        return builder.build()
    }

    private const val SILENT_GROUP = "silent"

    /** "10:00 – 11:00 · Room 4", with the day in front when it isn't today ("Tue 4 Oct, ..."). */
    fun describe(notice: ReminderNotice, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = if (notice.allDay) {
            val day = DateFmt.allDayToLocalDate(notice.begin)
            if (day == today) "All day" else "${dayText(day)}, all day"
        } else {
            val day = Instant.ofEpochMilli(notice.begin).atZone(zone).toLocalDate()
            val range = "${DateFmt.formatTime(notice.begin, zone)} – ${DateFmt.formatTime(notice.end, zone)}"
            if (day == today) range else "${dayText(day)}, $range"
        }
        val location = notice.location?.trim()?.takeIf { it.isNotEmpty() }
        return if (location == null) time else "$time · $location"
    }

    private fun dayText(day: LocalDate): String {
        val dow = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return "$dow ${day.dayOfMonth} $month"
    }

    /** Identifies the instance in every PendingIntent's data, so each notification's intents
     *  are distinct to PendingIntent matching without juggling request codes. */
    internal fun instanceUri(key: InstanceKey): Uri =
        ContentUris.withAppendedId(Events.CONTENT_URI, key.eventId).buildUpon()
            .appendQueryParameter("begin", key.begin.toString())
            .build()

    private fun fill(intent: Intent, notice: ReminderNotice): Intent = intent.apply {
        data = instanceUri(notice.key)
        putExtra(EXTRA_EVENT_ID, notice.key.eventId)
        putExtra(EXTRA_BEGIN, notice.key.begin)
        putExtra(EXTRA_END, notice.end)
    }

    private fun actionPending(context: Context, action: String, notice: ReminderNotice): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            fill(Intent(context, ReminderReceiver::class.java).setAction(action), notice),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openPending(context: Context, notice: ReminderNotice): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            fill(Intent(context, ReminderOpenActivity::class.java), notice),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_BEGIN = "begin"
    const val EXTRA_END = "end"
}
