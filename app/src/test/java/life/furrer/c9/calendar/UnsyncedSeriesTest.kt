package life.furrer.c9.calendar

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** #122: a series without a `_sync_id`, whose Instances the real provider keeps wrong. The fake's
 *  Instances are put in by hand the way the real provider leaves them. */
@RunWith(RobolectricTestRunner::class)
class UnsyncedSeriesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val day = 86_400_000L
    private val start = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        FakeCalendarProvider.copySeriesToExceptions = true
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.putCalendar(1L, "nine-test", "LOCAL")
    }

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Events.ACCOUNT_NAME, "nine-test")
        .appendQueryParameter(Events.ACCOUNT_TYPE, "LOCAL")
        .build()

    /** A weekly series of 4, written the way its sync adapter would (DIRTY 0), with [syncId]. */
    private fun series(syncId: String? = null): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, 1L)
            put(Events.TITLE, "Standup")
            put(Events.DTSTART, start)
            put(Events.DURATION, "PT1H")
            put(Events.RRULE, "FREQ=WEEKLY;COUNT=4")
            put(Events.EVENT_TIMEZONE, "UTC")
            syncId?.let { put(Events._SYNC_ID, it) }
        }
        val uri = context.contentResolver.insert(asSyncAdapter(Events.CONTENT_URI), values)!!
        return android.content.ContentUris.parseId(uri)
    }

    private fun occurrence(n: Int) = start + n * 7 * day

    /** Every occurrence of [seriesId] as an Instances row - what the provider has after the series
     *  is rewritten: the excepted ones never cancelled. */
    private fun allInstances(seriesId: Long) = (0..3).forEach { FakeCalendarProvider.putInstance(seriesId, occurrence(it), occurrence(it) + hour) }

    private fun moveOccurrence(seriesId: Long, n: Int, by: Long): Long = EventStore.updateEvent(
        context, seriesId, occurrence(n), EditScope.THIS_INSTANCE,
        EventDraft(
            calendarId = 1L, title = "Standup (moved)", location = null,
            startMillis = occurrence(n) + by, endMillis = occurrence(n) + by + hour, allDay = false,
            rrule = null, reminders = emptyList(), eventColorKey = null,
        ),
    )!!

    private fun range() = EventStore.queryRange(context, start - day, occurrence(3) + day, null)

    @Test
    fun `a moved occurrence shows once, at its new time, and the rest of the series stays`() {
        val seriesId = series()
        allInstances(seriesId)
        val exceptionId = moveOccurrence(seriesId, 1, 2 * hour)

        val rows = range()
        assertEquals(
            listOf(seriesId to occurrence(0), exceptionId to occurrence(1) + 2 * hour, seriesId to occurrence(2), seriesId to occurrence(3)),
            rows.map { it.eventId to it.startMillis },
        )
        val moved = rows.single { it.eventId == exceptionId }
        assertEquals("Standup (moved)", moved.title)
        assertEquals(occurrence(1) + 3 * hour, moved.endMillis)
        assertEquals(1L, moved.calendarId)
    }

    @Test
    fun `an exception the provider still lists isn't shown twice`() {
        val seriesId = series()
        val exceptionId = moveOccurrence(seriesId, 1, 2 * hour)
        // Right after an exception insert the provider lists only the new exception.
        FakeCalendarProvider.putInstance(exceptionId, occurrence(1) + 2 * hour, occurrence(1) + 3 * hour)
        allInstances(seriesId)

        assertEquals(1, range().count { it.eventId == exceptionId })
    }

    @Test
    fun `a deleted occurrence only hides the series' own`() {
        val seriesId = series()
        allInstances(seriesId)
        EventStore.deleteEvent(context, seriesId, occurrence(2), EditScope.THIS_INSTANCE)

        assertEquals(listOf(occurrence(0), occurrence(1), occurrence(3)), range().map { it.startMillis })
    }

    @Test
    fun `a synced series is left as the provider has it`() {
        val seriesId = series(syncId = "standup.ics")
        // The provider cancels the excepted occurrence and lists the exception itself.
        listOf(0, 2, 3).forEach { FakeCalendarProvider.putInstance(seriesId, occurrence(it), occurrence(it) + hour) }
        val exceptionId = moveOccurrence(seriesId, 1, 2 * hour)
        FakeCalendarProvider.putInstance(exceptionId, occurrence(1) + 2 * hour, occurrence(1) + 3 * hour)

        assertEquals(
            listOf(seriesId to occurrence(0), exceptionId to occurrence(1) + 2 * hour, seriesId to occurrence(2), seriesId to occurrence(3)),
            range().map { it.eventId to it.startMillis },
        )
    }

    @Test
    fun `an exception written on an unsynced series rewrites the series, a synced one's is left alone`() {
        val unsynced = series()
        val synced = series(syncId = "standup.ics")
        moveOccurrence(unsynced, 1, hour)
        moveOccurrence(synced, 1, hour)
        EventStore.deleteEvent(context, synced, occurrence(2), EditScope.THIS_INSTANCE)

        val unsyncedRow = FakeCalendarProvider.events.getValue(unsynced)
        assertEquals(1, unsyncedRow.getAsInteger(Events.DIRTY))
        assertEquals(start, unsyncedRow.getAsLong(Events.DTSTART))
        assertEquals("FREQ=WEEKLY;COUNT=4", unsyncedRow.getAsString(Events.RRULE))
        assertEquals(0, FakeCalendarProvider.events.getValue(synced).getAsInteger(Events.DIRTY))
    }

    @Test
    fun `editing a moved occurrence again rewrites the unsynced series too`() {
        val seriesId = series()
        val exceptionId = moveOccurrence(seriesId, 1, hour)
        FakeCalendarProvider.events.getValue(seriesId).put(Events.DIRTY, 0)
        EventStore.updateEvent(
            context, exceptionId, null, EditScope.ALL_INSTANCES,
            EventDraft(
                calendarId = 1L, title = "Standup (moved again)", location = null,
                startMillis = occurrence(1) + 3 * hour, endMillis = occurrence(1) + 4 * hour, allDay = false,
                rrule = null, reminders = emptyList(), eventColorKey = null,
            ),
        )
        assertEquals(1, FakeCalendarProvider.events.getValue(seriesId).getAsInteger(Events.DIRTY))
    }

    @Test
    fun `deleting a moved occurrence cancels it rather than bringing back its original time`() {
        val seriesId = series()
        allInstances(seriesId)
        val exceptionId = moveOccurrence(seriesId, 1, 2 * hour)
        EventStore.deleteEvent(context, exceptionId, null, EditScope.ALL_INSTANCES)

        val row = FakeCalendarProvider.events.getValue(exceptionId)
        assertEquals(Events.STATUS_CANCELED, row.getAsInteger(Events.STATUS))
        assertEquals(listOf(occurrence(0), occurrence(2), occurrence(3)), range().map { it.startMillis })
    }

    @Test
    fun `deleting a plain event still deletes it`() {
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Lunch", location = null, startMillis = start, endMillis = start + hour,
                allDay = false, rrule = null, reminders = emptyList(), eventColorKey = null,
            ),
        )!!
        assertTrue(EventStore.deleteEvent(context, eventId, null, EditScope.ALL_INSTANCES))
        assertEquals(1, FakeCalendarProvider.events.getValue(eventId).getAsInteger(Events.DELETED))
    }

    @Test
    fun `a hidden calendar's exceptions stay out of the range`() {
        FakeCalendarProvider.putCalendar(2L, "nine-test", "LOCAL")
        val seriesId = series()
        allInstances(seriesId)
        moveOccurrence(seriesId, 1, hour)

        assertEquals(emptyList<Long>(), EventStore.queryRange(context, start - day, occurrence(3) + day, setOf(2L)).map { it.eventId })
    }
}
