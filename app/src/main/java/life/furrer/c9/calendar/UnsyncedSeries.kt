package life.furrer.c9.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events

/** Works around CalendarProvider2 keeping Instances wrong for a recurring event without a
 *  `_sync_id` - a local calendar's, or one C9 created that DAVx5 hasn't uploaded yet (#122).
 *  Confirmed on-device, and in AOSP's CalendarInstancesHelper: it matches an exception to its
 *  series only by ORIGINAL_SYNC_ID, so for such a series
 *  - inserting an exception deletes the Instances of the series and of every earlier exception
 *    and re-expands just the new exception row;
 *  - rewriting the series (DTSTART+RRULE) brings all its occurrences back - the excepted ones
 *    too, never cancelled - and leaves every exception out.
 *  With a `_sync_id` (every synced series, and the exceptions on it, which the provider gives its
 *  ORIGINAL_SYNC_ID) the provider is right and none of this applies.
 *
 *  So after writing an exception C9 rewrites the series ([restoreSeries]), and every Instances
 *  read in C9 goes through [repair]: the series' occurrences an exception replaces are dropped,
 *  and the exceptions themselves come from the Events table instead. Other apps (L9 included)
 *  still see the provider's Instances until the series syncs. */
internal object UnsyncedSeries {

    /** An exception row of a series without a `_sync_id`, with its own times read from Events. */
    data class SeriesException(
        val id: Long,
        val originalId: Long,
        val originalInstanceTime: Long,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        /** "Delete this event only": it only cancels the series' occurrence. */
        val canceled: Boolean,
        val title: String?,
        val location: String?,
        val calendarId: Long,
        val displayColor: Int,
        val visible: Boolean,
        val selfAttendeeStatus: Int,
    )

    private val PROJECTION = arrayOf(
        Events._ID, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.DTSTART, Events.DTEND,
        Events.DURATION, Events.LAST_DATE, Events.ALL_DAY, Events.STATUS, Events.TITLE,
        Events.EVENT_LOCATION, Events.CALENDAR_ID, Events.DISPLAY_COLOR, Events.VISIBLE,
        Events.SELF_ATTENDEE_STATUS,
    )
    private const val SELECTION =
        "${Events.ORIGINAL_ID} IS NOT NULL AND ${Events.ORIGINAL_SYNC_ID} IS NULL AND ${Events.DELETED}=0"

    /** Every such exception - normally none, and only ever a local calendar's handful otherwise.
     *  Null if the provider gave no cursor. */
    fun queryExceptions(context: Context): List<SeriesException>? {
        val cursor = context.contentResolver.query(Events.CONTENT_URI, PROJECTION, SELECTION, null, null) ?: return null
        return cursor.use { c ->
            fun long(column: String): Long? = c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getLong(it) }
            fun int(column: String): Int? = c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getInt(it) }
            fun string(column: String): String? = c.getString(c.getColumnIndexOrThrow(column))
            buildList {
                while (c.moveToNext()) {
                    val originalId = long(Events.ORIGINAL_ID) ?: continue
                    val originalTime = long(Events.ORIGINAL_INSTANCE_TIME) ?: continue
                    val allDay = int(Events.ALL_DAY) == 1
                    val begin = long(Events.DTSTART) ?: originalTime
                    // C9 writes an exception with DURATION (the provider refuses DTEND on one);
                    // a synced client may have DTEND, and LAST_DATE is the provider's own end.
                    val end = long(Events.DTEND)
                        ?: string(Events.DURATION)?.let { IcalDuration.parseMillis(it) }?.let { begin + it }
                        ?: long(Events.LAST_DATE)
                        ?: begin
                    add(
                        SeriesException(
                            id = c.getLong(c.getColumnIndexOrThrow(Events._ID)),
                            originalId = originalId,
                            originalInstanceTime = originalTime,
                            begin = begin,
                            end = maxOf(end, begin),
                            allDay = allDay,
                            canceled = int(Events.STATUS) == Events.STATUS_CANCELED,
                            title = string(Events.TITLE),
                            location = string(Events.EVENT_LOCATION),
                            calendarId = long(Events.CALENDAR_ID) ?: -1L,
                            displayColor = int(Events.DISPLAY_COLOR) ?: 0,
                            visible = int(Events.VISIBLE) != 0,
                            selfAttendeeStatus = int(Events.SELF_ATTENDEE_STATUS) ?: Attendees.ATTENDEE_STATUS_NONE,
                        ),
                    )
                }
            }
        }
    }

    /** [rows] from an Instances query for [from]..[to], with [exceptions] put right: the series'
     *  occurrences they replace (or cancel) dropped, whatever the provider returned for the
     *  exceptions themselves dropped too, and each one overlapping the window added back as
     *  [toRow] makes it (null leaves it out, e.g. a calendar that isn't shown). Unsorted. */
    fun <T> repair(
        rows: List<T>,
        exceptions: List<SeriesException>,
        from: Long,
        to: Long,
        eventId: (T) -> Long,
        begin: (T) -> Long,
        toRow: (SeriesException) -> T?,
    ): List<T> {
        if (exceptions.isEmpty()) return rows
        val replaced = exceptions.map { it.originalId to it.originalInstanceTime }.toSet()
        val exceptionIds = exceptions.map { it.id }.toSet()
        val kept = rows.filter { row -> eventId(row) !in exceptionIds && (eventId(row) to begin(row)) !in replaced }
        val added = exceptions.filter { !it.canceled && it.begin <= to && it.end >= from }.mapNotNull(toRow)
        return kept + added
    }

    /** After an exception was written on [seriesId]: if the series has no `_sync_id`, rewrites its
     *  DTSTART and RRULE to themselves, which makes the provider expand its occurrences again
     *  (the same no-op rewrite [EventStore]'s truncation relies on). Nothing for a synced series,
     *  which the provider already keeps right - and whose row this would needlessly mark dirty.
     *  Never throws: the write it follows has already gone through, and a failure here only
     *  leaves the provider's Instances as wrong as they were. */
    fun restoreSeries(context: Context, seriesId: Long) {
        runCatching { rewriteSeries(context, seriesId) }
    }

    private fun rewriteSeries(context: Context, seriesId: Long) {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, seriesId)
        val values = context.contentResolver.query(uri, arrayOf(Events._SYNC_ID, Events.DTSTART, Events.RRULE), null, null, null)?.use { c ->
            if (!c.moveToFirst() || !c.isNull(0) || c.isNull(1) || c.isNull(2)) return
            ContentValues().apply {
                put(Events.DTSTART, c.getLong(1))
                put(Events.RRULE, c.getString(2))
            }
        } ?: return
        context.contentResolver.update(uri, values, null, null)
    }
}
