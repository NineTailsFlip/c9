package life.furrer.c9.core

import android.content.Context

object Prefs {
    private const val PREFS_NAME = "c9"
    private const val KEY_USE_MONOSPACE_FONT = "use_monospace_font"
    private const val KEY_IS_24_HOUR = "is_24_hour"
    private const val KEY_SHOW_HINT_BAR = "show_hint_bar"
    private const val KEY_DEFAULT_CALENDAR_ID = "default_calendar_id"
    private const val KEY_DEFAULT_EVENT_DURATION_MINUTES = "default_event_duration_minutes"
    private const val KEY_DEFAULT_ACCOUNT_NAME = "default_account_name"
    private const val KEY_DEFAULT_ACCOUNT_TYPE = "default_account_type"

    fun useMonospaceFont(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_MONOSPACE_FONT, true)

    fun setUseMonospaceFont(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_MONOSPACE_FONT, value).apply()
    }

    fun is24Hour(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_24_HOUR, true)

    fun setIs24Hour(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_24_HOUR, value).apply()
    }

    /** §7.2: the hint bar "costs one row, and it can be turned off in Settings." */
    fun showHintBar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_HINT_BAR, true)

    fun setShowHintBar(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_HINT_BAR, value).apply()
    }

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
