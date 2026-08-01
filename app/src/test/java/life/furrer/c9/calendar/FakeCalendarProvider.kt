package life.furrer.c9.calendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders

private const val AUTHORITY = "com.android.calendar"
private const val EVENTS = 1
private const val EVENT_ID = 2
private const val EVENT_EXCEPTION_ID = 3
private const val REMINDERS = 4
private const val CALENDARS = 5
private const val CALENDAR_ID = 6
private const val COLORS = 7

/** A minimal in-memory calendar provider for tests — the real CalendarProvider2 isn't available
 *  under Robolectric (confirmed: nothing is registered for content://com.android.calendar in the
 *  test environment, which is exactly why M1/M2's own provider tests only exercise the
 *  permission-gated/empty-result paths). This simulates just enough of the real provider's write
 *  behavior for EventStore's write path to be meaningfully tested against: DIRTY defaults to 1 on
 *  every insert/update/delete unless the caller appended CALLER_IS_SYNCADAPTER=true — the one
 *  thing EventStore must never do (§6.1.2) — and an exception insert links via ORIGINAL_ID the
 *  same way the real provider does. DIRTY/DELETED/ORIGINAL_ID all live as ordinary columns in the
 *  same ContentValues blob as everything else, so they round-trip through query() like any other
 *  field instead of needing special-cased plumbing. */
class FakeCalendarProvider : ContentProvider() {

    companion object {
        val events = mutableMapOf<Long, ContentValues>()
        val reminders = mutableMapOf<Long, MutableList<ContentValues>>()
        val calendars = mutableMapOf<Long, ContentValues>()
        val colors = mutableListOf<ContentValues>()
        private var nextEventId = 1L

        fun reset() {
            events.clear()
            reminders.clear()
            calendars.clear()
            colors.clear()
            nextEventId = 1L
        }

        fun putCalendar(
            id: Long,
            accountName: String,
            accountType: String,
            displayName: String = "Calendar",
            color: Int = 0xFF0000FF.toInt(),
        ) {
            calendars[id] = ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, accountName)
                put(Calendars.ACCOUNT_TYPE, accountType)
                put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(Calendars.CALENDAR_COLOR, color)
            }
        }

        fun putColor(accountName: String, accountType: String, key: String, argb: Int) {
            colors.add(
                ContentValues().apply {
                    put(Colors.ACCOUNT_NAME, accountName)
                    put(Colors.ACCOUNT_TYPE, accountType)
                    put(Colors.COLOR_KEY, key)
                    put(Colors.COLOR, argb)
                    put(Colors.COLOR_TYPE, Colors.TYPE_EVENT)
                },
            )
        }
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "events", EVENTS)
        addURI(AUTHORITY, "events/#", EVENT_ID)
        // Events.CONTENT_EXCEPTION_URI is "content://com.android.calendar/exception" — a single
        // top-level segment, not "events/exception" (confirmed empirically: Robolectric bundles
        // real framework code, so printing the constant at test time gave the actual value).
        addURI(AUTHORITY, "exception/#", EVENT_EXCEPTION_ID)
        addURI(AUTHORITY, "reminders", REMINDERS)
        addURI(AUTHORITY, "calendars", CALENDARS)
        addURI(AUTHORITY, "calendars/#", CALENDAR_ID)
        addURI(AUTHORITY, "colors", COLORS)
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? = when (matcher.match(uri)) {
        EVENTS -> {
            val id = nextEventId++
            val row = ContentValues(values)
            row.put(Events.DIRTY, if (isSyncAdapter(uri)) 0 else 1)
            events[id] = row
            ContentUris.withAppendedId(Events.CONTENT_URI, id)
        }
        EVENT_EXCEPTION_ID -> {
            // Confirmed on-device against the real CalendarProvider2 (a bug this fake's earlier,
            // laxer version let straight through to production): it throws
            // IllegalArgumentException("Exceptions can't overwrite dtend") if DTEND is present.
            require(values?.containsKey(Events.DTEND) != true) { "Exceptions can't overwrite dtend" }
            val originalId = ContentUris.parseId(uri)
            val id = nextEventId++
            val row = ContentValues(values)
            row.put(Events.ORIGINAL_ID, originalId)
            row.put(Events.DIRTY, if (isSyncAdapter(uri)) 0 else 1)
            events[id] = row
            ContentUris.withAppendedId(Events.CONTENT_URI, id)
        }
        REMINDERS -> {
            val eventId = values?.getAsLong(Reminders.EVENT_ID)
            if (eventId != null) reminders.getOrPut(eventId) { mutableListOf() }.add(ContentValues(values))
            Reminders.CONTENT_URI
        }
        else -> null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        when (matcher.match(uri)) {
            EVENT_ID -> {
                val id = ContentUris.parseId(uri)
                val row = events[id]
                if (row == null) {
                    0
                } else {
                    row.putAll(values ?: ContentValues())
                    row.put(Events.DIRTY, if (isSyncAdapter(uri)) row.getAsInteger(Events.DIRTY) ?: 0 else 1)
                    1
                }
            }
            else -> 0
        }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        when (matcher.match(uri)) {
            EVENT_ID -> {
                val id = ContentUris.parseId(uri)
                val row = events[id]
                if (row == null) {
                    0
                } else {
                    row.put(Events.DELETED, 1)
                    row.put(Events.DIRTY, if (isSyncAdapter(uri)) row.getAsInteger(Events.DIRTY) ?: 0 else 1)
                    1
                }
            }
            REMINDERS -> {
                val eventId = selectionArgs?.firstOrNull()?.toLongOrNull()
                if (eventId != null && reminders.remove(eventId) != null) 1 else 0
            }
            else -> 0
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (matcher.match(uri)) {
        EVENT_ID -> {
            val id = ContentUris.parseId(uri)
            val row = events[id]
            val cols = projection ?: row?.keySet()?.toTypedArray() ?: arrayOf()
            val cursor = MatrixCursor(cols)
            if (row != null) cursor.addRow(cols.map { col -> if (col == Events._ID) id else row.get(col) })
            cursor
        }
        REMINDERS -> {
            val eventId = selectionArgs?.firstOrNull()?.toLongOrNull()
            val list = reminders[eventId] ?: emptyList()
            val cols = projection ?: arrayOf(Reminders.MINUTES)
            val cursor = MatrixCursor(cols)
            list.forEach { values -> cursor.addRow(cols.map { values.get(it) }) }
            cursor
        }
        CALENDARS -> {
            val cols = projection ?: arrayOf(
                Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_COLOR,
            )
            val cursor = MatrixCursor(cols)
            calendars.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == Calendars._ID) id else values.get(col) })
            }
            cursor
        }
        CALENDAR_ID -> {
            val id = ContentUris.parseId(uri)
            val values = calendars[id]
            val cols = projection ?: arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)
            val cursor = MatrixCursor(cols)
            if (values != null) cursor.addRow(cols.map { col -> if (col == Calendars._ID) id else values.get(col) })
            cursor
        }
        COLORS -> {
            val cols = projection ?: arrayOf(Colors.COLOR_KEY, Colors.COLOR)
            val cursor = MatrixCursor(cols)
            colors.filter { matchesColorSelection(it, selectionArgs) }.forEach { values ->
                cursor.addRow(cols.map { values.get(it) })
            }
            cursor
        }
        else -> null
    }

    override fun getType(uri: Uri): String? = null

    private fun isSyncAdapter(uri: Uri): Boolean =
        uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) == "true"

    /** Matches EventStore.queryEventColors' own selection: "account_name=? AND account_type=?
     *  AND color_type=?", in that order. */
    private fun matchesColorSelection(values: ContentValues, args: Array<out String>?): Boolean {
        if (args == null || args.size < 3) return true
        return values.getAsString(Colors.ACCOUNT_NAME) == args[0] &&
            values.getAsString(Colors.ACCOUNT_TYPE) == args[1] &&
            values.getAsString(Colors.COLOR_TYPE) == args[2]
    }
}
