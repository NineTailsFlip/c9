package life.furrer.c9.core

import android.content.Context
import life.furrer.nine.core.Nine
import life.furrer.nine.core.prefs.NinePrefs

object Prefs {
    private const val KEY_DEFAULT_CALENDAR_ID = "default_calendar_id"
    private const val KEY_DEFAULT_EVENT_DURATION_MINUTES = "default_event_duration_minutes"
    private const val KEY_DEFAULT_ACCOUNT_NAME = "default_account_name"
    private const val KEY_DEFAULT_ACCOUNT_TYPE = "default_account_type"
    private const val KEY_REMINDER_NOTIFICATIONS = "reminder_notifications"
    private const val KEY_REMINDER_SNOOZE_MINUTES = "reminder_snooze_minutes"
    private const val KEY_ASKED_NOTIFICATION_PERMISSION = "asked_notification_permission"
    private const val KEY_REMINDER_ALARM_AT = "reminder_alarm_at"

    /** New events preselect this calendar when set (§9.13-adjacent Settings addition) — falls
     *  back to the first available calendar, same as before this setting existed, when null or
     *  when the id no longer matches any calendar the account still offers. */
    fun defaultCalendarId(context: Context): Long? {
        val value = prefs(context).getLong(KEY_DEFAULT_CALENDAR_ID, -1L)
        return if (value == -1L) null else value
    }

    fun setDefaultCalendarId(context: Context, value: Long?) {
        prefs(context).edit().apply {
            if (value == null) remove(KEY_DEFAULT_CALENDAR_ID) else putLong(KEY_DEFAULT_CALENDAR_ID, value)
        }.apply()
    }

    /** New events get this long a duration by default (§7's EventEditActivity previously
     *  hardcoded 1h here). */
    fun defaultEventDurationMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_EVENT_DURATION_MINUTES, 60)

    fun setDefaultEventDurationMinutes(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_EVENT_DURATION_MINUTES, value).apply()
    }

    /** New contacts are written to this account without asking when set, and match the account
     *  by name+type (an account's own identity in the provider) rather than by list position,
     *  since the writable-accounts list order isn't guaranteed stable. Null means "ask each
     *  time," the pre-existing behavior. */
    fun defaultAccountName(context: Context): String? = prefs(context).getString(KEY_DEFAULT_ACCOUNT_NAME, null)

    fun defaultAccountType(context: Context): String? = prefs(context).getString(KEY_DEFAULT_ACCOUNT_TYPE, null)

    fun setDefaultAccount(context: Context, name: String?, type: String?) {
        prefs(context).edit().apply {
            if (name == null || type == null) {
                remove(KEY_DEFAULT_ACCOUNT_NAME)
                remove(KEY_DEFAULT_ACCOUNT_TYPE)
            } else {
                putString(KEY_DEFAULT_ACCOUNT_NAME, name)
                putString(KEY_DEFAULT_ACCOUNT_TYPE, type)
            }
        }.apply()
    }

    /** Whether C9 posts a notification when an event reminder is due (see
     *  `calendar/reminders/`). On by default — on the target phone C9 is the only calendar app,
     *  so with it off nothing reminds anyone. Off is for a second calendar app that already
     *  notifies, which would otherwise make every reminder ring twice. */
    fun reminderNotifications(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMINDER_NOTIFICATIONS, true)

    fun setReminderNotifications(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMINDER_NOTIFICATIONS, value).apply()
    }

    /** How far a reminder notification's Snooze pushes it back. */
    fun reminderSnoozeMinutes(context: Context): Int =
        prefs(context).getInt(KEY_REMINDER_SNOOZE_MINUTES, 5)

    fun setReminderSnoozeMinutes(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_REMINDER_SNOOZE_MINUTES, value).apply()
    }

    /** The notification permission is asked for once, unprompted, from the calendar — after a
     *  refusal it's the system's App info screen, not a dialog on every launch. */
    fun askedNotificationPermission(context: Context): Boolean =
        deviceState(context).getBoolean(KEY_ASKED_NOTIFICATION_PERMISSION, false)

    fun setAskedNotificationPermission(context: Context) {
        deviceState(context).edit().putBoolean(KEY_ASKED_NOTIFICATION_PERMISSION, true).apply()
    }

    /** When C9's reminder alarm is set for (see ReminderScheduler.arm), or null when it isn't —
     *  AlarmManager can't be asked. Device state: an alarm doesn't travel to another phone. */
    fun reminderAlarmAt(context: Context): Long? =
        deviceState(context).getLong(KEY_REMINDER_ALARM_AT, -1L).takeIf { it >= 0 }

    fun setReminderAlarmAt(context: Context, at: Long?) {
        deviceState(context).edit().apply { if (at == null) remove(KEY_REMINDER_ALARM_AT) else putLong(KEY_REMINDER_ALARM_AT, at) }.apply()
    }

    private fun prefs(context: Context) = NinePrefs.of(context)

    /** Facts about this install rather than settings — kept out of [NinePrefs.of], which core's
     *  Backup exports and imports whole, and out of Android's own backup too (see
     *  res/xml/data_extraction_rules.xml): restored onto a new phone, "already asked" would mean
     *  the permission there is never asked for at all. Same reasoning as FavoritesSync's own file. */
    private fun deviceState(context: Context) =
        context.getSharedPreferences("${Nine.app.id}_device_state", Context.MODE_PRIVATE)
}
