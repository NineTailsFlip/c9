package life.furrer.c9.calendar

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import org.json.JSONArray

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    /** `Calendars.MAX_REMINDERS`: how many reminders one event may have (0: none), null if unset. */
    val maxReminders: Int? = null,
)

object CalendarStore {
    private const val PREFS_NAME = "c9"
    private const val KEY_CALENDAR_IDS = "calendar_ids"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun queryCalendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.MAX_REMINDERS,
        )
        val result = mutableListOf<CalendarInfo>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountNameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val accountTypeIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
                val colorIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
                val maxRemindersIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.MAX_REMINDERS)
                while (cursor.moveToNext()) {
                    result.add(
                        CalendarInfo(
                            id = cursor.getLong(idIndex),
                            displayName = cursor.getString(nameIndex) ?: "Calendar",
                            accountName = cursor.getString(accountNameIndex) ?: "",
                            accountType = cursor.getString(accountTypeIndex) ?: "",
                            color = cursor.getInt(colorIndex),
                            maxReminders = cursor.getInt(maxRemindersIndex).takeIf { !cursor.isNull(maxRemindersIndex) },
                        )
                    )
                }
            }
        return result.sortedWith(compareBy({ it.accountName.lowercase() }, { it.displayName.lowercase() }))
    }

    /** Groups calendars by account for §9.7's section headers, preserving [queryCalendars]'s
     *  account-then-name ordering. Grouped straight off the provider's own ACCOUNT_NAME column —
     *  never a hardcoded account type, per §1's "don't hardcode DAVx⁵ identifiers". */
    fun groupByAccount(calendars: List<CalendarInfo>): List<Pair<String, List<CalendarInfo>>> =
        calendars.groupBy { it.accountName }.toList()

    /** Null means the user has never customized this — callers should default to "all calendars". */
    fun getSelectedCalendarIds(context: Context): Set<Long>? {
        val json = prefs(context).getString(KEY_CALENDAR_IDS, null) ?: return null
        val array = JSONArray(json)
        val ids = mutableSetOf<Long>()
        for (i in 0 until array.length()) ids.add(array.getLong(i))
        return ids
    }

    fun setSelectedCalendarIds(context: Context, ids: Set<Long>) {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_CALENDAR_IDS, array.toString()).apply()
    }

    /** Requests an immediate sync for every distinct account behind the visible calendars.
     *  Never touches AccountManager — account identity comes straight from the provider's own
     *  ACCOUNT_NAME/ACCOUNT_TYPE columns (§5.3), which is also why WRITE_SYNC_SETTINGS (a normal
     *  install-time permission) is enough and GET_ACCOUNTS was never needed. */
    fun requestSync(context: Context) {
        val selectedIds = getSelectedCalendarIds(context)
        val visible = queryCalendars(context).filter { selectedIds == null || it.id in selectedIds }
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        visible.map { it.accountName to it.accountType }.distinct().forEach { (name, type) ->
            ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
