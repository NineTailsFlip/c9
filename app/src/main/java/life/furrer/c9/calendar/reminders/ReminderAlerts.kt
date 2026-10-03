package life.furrer.c9.calendar.reminders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts

/** The `CalendarContract.CalendarAlerts` seam. The calendar provider owns this table — it
 *  inserts a SCHEDULED row for each reminder coming due and sends `EVENT_REMINDER` when its
 *  alarm time arrives — and the app that notifies moves rows on to FIRED (notification showing)
 *  and DISMISSED (done with), exactly as `CalendarAlerts`' own documentation asks. Since the
 *  provider doesn't reliably get round to every reminder, C9 also inserts the rows it missed,
 *  just as the provider would (see [ReminderScheduler.insert]). */
object ReminderAlerts {

    private val PROJECTION = arrayOf(
        CalendarAlerts._ID,
        CalendarAlerts.EVENT_ID,
        CalendarAlerts.STATE,
        CalendarAlerts.TITLE,
        CalendarAlerts.EVENT_LOCATION,
        CalendarAlerts.ALL_DAY,
        CalendarAlerts.ALARM_TIME,
        CalendarAlerts.BEGIN,
        CalendarAlerts.END,
        CalendarAlerts.SELF_ATTENDEE_STATUS,
        CalendarAlerts.MINUTES,
        // The provider's alerts query joins its Events view, which carries the calendar's
        // VISIBLE flag (verified in CalendarProvider2's sCalendarAlertsProjectionMap).
        CalendarAlerts.VISIBLE,
    )

    /** Due SCHEDULED and FIRED rows, and DISMISSED ones back to a lookback bound. */
    private const val DUE_SELECTION =
        "(${CalendarAlerts.STATE}=? OR ${CalendarAlerts.STATE}=? OR " +
            "(${CalendarAlerts.STATE}=? AND ${CalendarAlerts.ALARM_TIME}>=?)) AND ${CalendarAlerts.ALARM_TIME}<=?"

    /** The provider's update for this table runs straight against CalendarAlerts itself, so the
     *  selection may only name that table's own columns. */
    private const val FIRED_FOR_INSTANCE_SELECTION =
        "${CalendarAlerts.EVENT_ID}=? AND ${CalendarAlerts.BEGIN}=? AND ${CalendarAlerts.STATE}=?"

    /** Guarded on the row still being SCHEDULED, so firing is a compare-and-set: of two passes
     *  (or apps) racing for one alert, exactly one sees the update land. */
    private const val SCHEDULED_ID_SELECTION = "${CalendarAlerts._ID}=? AND ${CalendarAlerts.STATE}=?"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Every SCHEDULED or FIRED alert whose alarm time has come — and the DISMISSED ones of the
     *  last [ReminderPlanner.SIBLING_LOOKBACK_MS], which the planner checks a SCHEDULED all-day
     *  row against (a dismissed reminder mustn't ring again from a row at another offset). A row
     *  only ever goes FIRED or DISMISSED once due, so nothing past [now] is needed for that. */
    fun queryDue(context: Context, now: Long): List<AlertRow> {
        if (!hasPermission(context)) return emptyList()
        val cursor = context.contentResolver.query(
            CalendarAlerts.CONTENT_URI,
            PROJECTION,
            DUE_SELECTION,
            arrayOf(
                CalendarAlerts.STATE_SCHEDULED.toString(),
                CalendarAlerts.STATE_FIRED.toString(),
                CalendarAlerts.STATE_DISMISSED.toString(),
                (now - ReminderPlanner.SIBLING_LOOKBACK_MS).toString(),
                now.toString(),
            ),
            "${CalendarAlerts.BEGIN} ASC",
        ) ?: return emptyList()
        return cursor.use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    private fun read(c: Cursor): AlertRow = AlertRow(
        id = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts._ID)),
        eventId = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.EVENT_ID)),
        state = c.getInt(c.getColumnIndexOrThrow(CalendarAlerts.STATE)),
        title = c.getString(c.getColumnIndexOrThrow(CalendarAlerts.TITLE)),
        location = c.getString(c.getColumnIndexOrThrow(CalendarAlerts.EVENT_LOCATION)),
        allDay = c.getInt(c.getColumnIndexOrThrow(CalendarAlerts.ALL_DAY)) != 0,
        alarmTime = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.ALARM_TIME)),
        begin = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.BEGIN)),
        end = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.END)),
        selfAttendeeStatus = c.intOr(CalendarAlerts.SELF_ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_NONE),
        visible = c.intOr(CalendarAlerts.VISIBLE, 1) != 0,
        minutes = c.getColumnIndex(CalendarAlerts.MINUTES).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getInt(it) },
    )

    private fun Cursor.intOr(column: String, fallback: Int): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getInt(index)
    }

    /** Moves one alert to [state] (in practice DISMISSED), whatever it was. */
    fun setState(context: Context, alertId: Long, state: Int) {
        val values = ContentValues().apply { put(CalendarAlerts.STATE, state) }
        runCatching {
            context.contentResolver.update(ContentUris.withAppendedId(CalendarAlerts.CONTENT_URI, alertId), values, null, null)
        }
    }

    /** SCHEDULED -> FIRED, only if the row is still SCHEDULED — returns whether it was, i.e.
     *  whether this caller is the one that fired it. Through the table Uri with a selection:
     *  the provider's single-row Uri ignores any selection. Also stamps the received/notify
     *  times, as AOSP does — they exist to debug late or missed alarms. */
    fun markFired(context: Context, alertId: Long, now: Long): Boolean {
        val values = ContentValues().apply {
            put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED)
            put(CalendarAlerts.RECEIVED_TIME, now)
            put(CalendarAlerts.NOTIFY_TIME, now)
        }
        return runCatching {
            context.contentResolver.update(
                CalendarAlerts.CONTENT_URI,
                values,
                SCHEDULED_ID_SELECTION,
                arrayOf(alertId.toString(), CalendarAlerts.STATE_SCHEDULED.toString()),
            )
        }.getOrDefault(0) == 1
    }

    /** Dismisses every fired alert of one instance — what a notification stands for. Only FIRED
     *  ones: a later reminder of the same instance that hasn't come due yet still should. */
    fun dismissInstance(context: Context, key: InstanceKey) {
        if (!hasPermission(context)) return
        val values = ContentValues().apply { put(CalendarAlerts.STATE, CalendarAlerts.STATE_DISMISSED) }
        runCatching {
            context.contentResolver.update(
                CalendarAlerts.CONTENT_URI,
                values,
                FIRED_FOR_INSTANCE_SELECTION,
                arrayOf(key.eventId.toString(), key.begin.toString(), CalendarAlerts.STATE_FIRED.toString()),
            )
        }
    }

    /** A new SCHEDULED alert for [key] at [alarmTime] — AOSP Calendar's snooze, row for row.
     *  MINUTES stays 0: the provider's cleanup deletes any alert whose minutes match none of the
     *  event's reminders, *except* a 0 one, which is how a snooze survives it. [end] has to be
     *  the instance's own: an alert whose begin/end match no instance is deleted too. */
    fun insertSnooze(context: Context, key: InstanceKey, end: Long, alarmTime: Long, now: Long): Boolean {
        if (!hasPermission(context)) return false
        val values = ContentValues().apply {
            put(CalendarAlerts.EVENT_ID, key.eventId)
            put(CalendarAlerts.BEGIN, key.begin)
            put(CalendarAlerts.END, end)
            put(CalendarAlerts.ALARM_TIME, alarmTime)
            put(CalendarAlerts.CREATION_TIME, now)
            put(CalendarAlerts.RECEIVED_TIME, 0L)
            put(CalendarAlerts.NOTIFY_TIME, 0L)
            put(CalendarAlerts.STATE, CalendarAlerts.STATE_SCHEDULED)
            put(CalendarAlerts.MINUTES, 0)
        }
        return runCatching { context.contentResolver.insert(CalendarAlerts.CONTENT_URI, values) }.getOrNull() != null
    }
}
