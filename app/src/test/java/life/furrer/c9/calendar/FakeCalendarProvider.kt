package life.furrer.c9.calendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders

private const val AUTHORITY = "com.android.calendar"
private const val EVENTS = 1
private const val EVENT_ID = 2
private const val EVENT_EXCEPTION_ID = 3
private const val REMINDERS = 4
private const val REMINDER_ID = 14
private const val CALENDARS = 5
private const val CALENDAR_ID = 6
private const val COLORS = 7
private const val ATTENDEES = 8
private const val ATTENDEE_ID = 9
private const val INSTANCES = 10

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
        val attendees = mutableMapOf<Long, ContentValues>()
        /** Instances rows (EVENT_ID, BEGIN, END) a test puts in by hand — this fake does no
         *  recurrence expansion of its own, just as EventStore never does (§6.1). */
        val instances = mutableListOf<ContentValues>()
        /** CalendarProvider2's handleInsertException copies the series' reminders onto the new
         *  exception row; switched off only to cover a provider that doesn't. */
        var copyRemindersToExceptions = true
        private var nextEventId = 1L
        private var nextReminderId = 1L
        private var nextAttendeeId = 1L

        fun reset() {
            events.clear()
            reminders.clear()
            calendars.clear()
            colors.clear()
            attendees.clear()
            instances.clear()
            copyRemindersToExceptions = true
            nextEventId = 1L
            nextReminderId = 1L
            nextAttendeeId = 1L
        }

        fun putCalendar(
            id: Long,
            accountName: String,
            accountType: String,
            displayName: String = "Calendar",
            color: Int = 0xFF0000FF.toInt(),
            ownerAccount: String? = null,
            maxReminders: Int? = null,
        ) {
            calendars[id] = ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, accountName)
                put(Calendars.ACCOUNT_TYPE, accountType)
                put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(Calendars.CALENDAR_COLOR, color)
                put(Calendars.OWNER_ACCOUNT, ownerAccount ?: accountName)
                maxReminders?.let { put(Calendars.MAX_REMINDERS, it) }
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
        addURI(AUTHORITY, "reminders/#", REMINDER_ID)
        addURI(AUTHORITY, "calendars", CALENDARS)
        addURI(AUTHORITY, "calendars/#", CALENDAR_ID)
        addURI(AUTHORITY, "colors", COLORS)
        addURI(AUTHORITY, "attendees", ATTENDEES)
        addURI(AUTHORITY, "attendees/#", ATTENDEE_ID)
        addURI(AUTHORITY, "instances/when/#/#", INSTANCES)
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
            // Same story, confirmed on-device the same way: EVENT_COLOR_KEY is just as forbidden
            // on an exception row ("Exceptions can't overwrite eventColor_key") — an exception
            // always inherits its color from the series it excepts.
            require(values?.containsKey(Events.EVENT_COLOR_KEY) != true) { "Exceptions can't overwrite eventColor_key" }
            val originalId = ContentUris.parseId(uri)
            val id = nextEventId++
            val row = ContentValues(values)
            row.put(Events.ORIGINAL_ID, originalId)
            row.put(Events.DIRTY, if (isSyncAdapter(uri)) 0 else 1)
            events[id] = row
            if (copyRemindersToExceptions) {
                reminders[originalId]?.forEach { copy -> addReminder(id, copy) }
            }
            ContentUris.withAppendedId(Events.CONTENT_URI, id)
        }
        REMINDERS -> {
            val eventId = values?.getAsLong(Reminders.EVENT_ID)
            if (eventId != null) addReminder(eventId, values)
            Reminders.CONTENT_URI
        }
        ATTENDEES -> {
            val id = nextAttendeeId++
            attendees[id] = ContentValues(values)
            ContentUris.withAppendedId(Attendees.CONTENT_URI, id)
        }
        else -> null
    }

    private fun addReminder(eventId: Long, values: ContentValues) {
        val row = ContentValues(values)
        row.put(Reminders.EVENT_ID, eventId)
        row.put(Reminders._ID, nextReminderId++)
        reminders.getOrPut(eventId) { mutableListOf() }.add(row)
        events[eventId]?.put(Events.HAS_ALARM, 1)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        when (matcher.match(uri)) {
            EVENT_ID -> {
                val id = ContentUris.parseId(uri)
                val row = events[id]
                if (row == null) {
                    0
                } else {
                    // HAS_ALARM is read-only to an update, as in CalendarProvider2.
                    row.putAll(ContentValues(values ?: ContentValues()).apply { remove(Events.HAS_ALARM) })
                    row.put(Events.DIRTY, if (isSyncAdapter(uri)) row.getAsInteger(Events.DIRTY) ?: 0 else 1)
                    1
                }
            }
            ATTENDEE_ID -> {
                val id = ContentUris.parseId(uri)
                val row = attendees[id]
                if (row == null) 0 else { row.putAll(values ?: ContentValues()); 1 }
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
            REMINDER_ID -> {
                val id = ContentUris.parseId(uri)
                var removed = 0
                reminders.forEach { (eventId, rows) ->
                    if (rows.removeAll { it.getAsLong(Reminders._ID) == id }) {
                        removed = 1
                        if (rows.isEmpty()) events[eventId]?.put(Events.HAS_ALARM, 0)
                    }
                }
                removed
            }
            // Confirmed on the real CalendarProvider2 (not just a Robolectric gap): a bare
            // Attendees.CONTENT_URI delete with a selection crashes it —
            // "SQLiteException: ambiguous column name: _id" — since its delete path joins
            // Attendees/Events/Calendars first and every one of those has its own _id column.
            // Modeled as a thrown exception here too, not just omitted, so a regression back to
            // the selection-based form fails a test instead of silently working under the fake.
            ATTENDEES -> throw android.database.sqlite.SQLiteException(
                "ambiguous column name: _id — use the ID-appended URI instead (see EventStore.removeAttendee)",
            )
            ATTENDEE_ID -> {
                val id = ContentUris.parseId(uri)
                if (attendees.remove(id) != null) 1 else 0
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
        ATTENDEES -> {
            val eventId = selectionArgs?.firstOrNull()?.toLongOrNull()
            val cols = projection ?: arrayOf(
                Attendees._ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL,
                Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP,
            )
            val cursor = MatrixCursor(cols)
            attendees.filter { (_, values) -> eventId == null || values.getAsLong(Attendees.EVENT_ID) == eventId }
                .forEach { (id, values) ->
                    cursor.addRow(cols.map { col -> if (col == Attendees._ID) id else values.get(col) })
                }
            cursor
        }
        // Only EventStore.countOccurrencesBefore queries these two, each with its own fixed
        // selection ("event_id=? AND begin<?" and "original_id=? AND originalInstanceTime<? AND
        // deleted=0"), matched positionally the same way as matchesColorSelection.
        INSTANCES -> {
            val windowStart = uri.pathSegments[2].toLong()
            val windowEnd = uri.pathSegments[3].toLong()
            val cols = projection ?: arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END)
            val cursor = MatrixCursor(cols)
            instances.filter { values ->
                val begin = values.getAsLong(Instances.BEGIN)
                val overlaps = begin <= windowEnd && values.getAsLong(Instances.END) >= windowStart
                overlaps && (
                    selectionArgs == null ||
                        (values.getAsLong(Instances.EVENT_ID) == selectionArgs[0].toLong() && begin < selectionArgs[1].toLong())
                    )
            }.forEach { values -> cursor.addRow(cols.map { values.get(it) }) }
            cursor
        }
        EVENTS -> {
            val cols = projection ?: arrayOf(Events._ID)
            val cursor = MatrixCursor(cols)
            events.filter { (_, values) ->
                selectionArgs == null || (
                    values.getAsLong(Events.ORIGINAL_ID) == selectionArgs[0].toLong() &&
                        (values.getAsLong(Events.ORIGINAL_INSTANCE_TIME) ?: Long.MAX_VALUE) < selectionArgs[1].toLong() &&
                        values.getAsInteger(Events.DELETED) != 1
                    )
            }.forEach { (id, values) -> cursor.addRow(cols.map { col -> if (col == Events._ID) id else values.get(col) }) }
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
