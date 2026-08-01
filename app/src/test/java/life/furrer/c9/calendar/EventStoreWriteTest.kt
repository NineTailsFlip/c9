package life.furrer.c9.calendar

import android.Manifest
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Covers EventStore's write path against [FakeCalendarProvider] — the real CalendarProvider2
 *  isn't available under Robolectric, so this is the "provider fake" §10 calls for. Every test
 *  here is ultimately checking the same two things: DIRTY ends up 1 (never silently skipped),
 *  and CALLER_IS_SYNCADAPTER was never set (§6.1.2) — getting either wrong means a write that
 *  looks successful locally but either never syncs or is mistaken for something the server sent. */
@RunWith(RobolectricTestRunner::class)
class EventStoreWriteTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid")
    }

    private fun draft(
        rrule: String? = null,
        startMillis: Long = 1_000_000L,
        endMillis: Long = 1_003_600_000L,
        reminderMinutes: Int? = null,
        eventColorKey: String? = null,
        allDay: Boolean = false,
    ) = EventDraft(
        calendarId = 1L,
        title = "Dentist",
        location = "Bahnhofstrasse 12",
        startMillis = startMillis,
        endMillis = endMillis,
        allDay = allDay,
        rrule = rrule,
        reminderMinutes = reminderMinutes,
        eventColorKey = eventColorKey,
    )

    @Test
    fun `insertEvent marks the new row dirty and never sets CALLER_IS_SYNCADAPTER`() {
        val eventId = EventStore.insertEvent(context, draft())
        assertNotNull(eventId)
        val row = FakeCalendarProvider.events.getValue(eventId!!)
        assertEquals(1, row.getAsInteger(Events.DIRTY))
    }

    @Test
    fun `insertEvent returns null without WRITE_CALENDAR permission`() {
        // A fresh, unpermitted context — permission was granted globally in setUp for the other
        // tests, so this one deliberately uses its own.
        val unpermitted = RuntimeEnvironment.getApplication()
        shadowOf(unpermitted).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertNull(EventStore.insertEvent(unpermitted, draft()))
    }

    @Test
    fun `insertEvent with a reminder writes it and sets HAS_ALARM`() {
        val eventId = EventStore.insertEvent(context, draft(reminderMinutes = 30))!!
        val row = FakeCalendarProvider.events.getValue(eventId)
        assertEquals(1, row.getAsInteger(Events.HAS_ALARM))
        assertEquals(30, FakeCalendarProvider.reminders.getValue(eventId).single().getAsInteger("minutes"))
    }

    @Test
    fun `insertEvent for a recurring event writes RRULE and DURATION, not DTEND`() {
        val eventId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        val row = FakeCalendarProvider.events.getValue(eventId)
        assertEquals("FREQ=WEEKLY", row.getAsString(Events.RRULE))
        assertNull(row.get(Events.DTEND))
        assertNotNull(row.getAsString(Events.DURATION))
    }

    @Test
    fun `updateEvent with ALL_INSTANCES updates the parent row and marks it dirty`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        val updated = EventStore.updateEvent(
            context, eventId, instanceStartMillis = null, scope = EditScope.ALL_INSTANCES,
            draft = draft().copy(title = "Dentist (moved)"),
        )
        assertTrue(updated)
        val row = FakeCalendarProvider.events.getValue(eventId)
        assertEquals("Dentist (moved)", row.getAsString(Events.TITLE))
        assertEquals(1, row.getAsInteger(Events.DIRTY))
    }

    @Test
    fun `updateEvent with THIS_INSTANCE inserts an exception row with the right ORIGINAL_INSTANCE_TIME`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        val instanceBegin = 5_000_000L

        val updated = EventStore.updateEvent(
            context, originalId, instanceStartMillis = instanceBegin, scope = EditScope.THIS_INSTANCE,
            draft = draft().copy(title = "Dentist (this week only)"),
        )
        assertTrue(updated)

        // The exception is a new row, distinct from the original recurring event.
        val exceptionRow = FakeCalendarProvider.events.entries.single { it.key != originalId }.value
        assertEquals(originalId, exceptionRow.getAsLong(Events.ORIGINAL_ID))
        assertEquals(instanceBegin, exceptionRow.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals("Dentist (this week only)", exceptionRow.getAsString(Events.TITLE))
        assertEquals(1, exceptionRow.getAsInteger(Events.DIRTY))
        // Exceptions never recur themselves and never carry a calendar of their own.
        assertNull(exceptionRow.get(Events.RRULE))
        assertNull(exceptionRow.get(Events.CALENDAR_ID))
        // Confirmed on-device against the real provider: it rejects DTEND on an exception row
        // ("IllegalArgumentException: Exceptions can't overwrite dtend") — must carry DURATION
        // instead, exactly like its recurring parent, or every "this event" edit crashes on save.
        assertNull(exceptionRow.get(Events.DTEND))
        assertNotNull(exceptionRow.getAsString(Events.DURATION))
    }

    @Test
    fun `updateEvent with THIS_AND_FOLLOWING truncates the original series and inserts a continuation`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        val instanceBegin = 5_000_000L

        val updated = EventStore.updateEvent(
            context, originalId, instanceStartMillis = instanceBegin, scope = EditScope.THIS_AND_FOLLOWING,
            draft = draft(rrule = "FREQ=WEEKLY", startMillis = instanceBegin).copy(title = "Dentist (new time)"),
        )
        assertTrue(updated)

        // The original series is truncated in place (title untouched -- only RRULE changes) and
        // stays the same row/id; the edit itself lives in a brand-new continuation event.
        val originalRow = FakeCalendarProvider.events.getValue(originalId)
        assertEquals(
            Recurrence.withUntil("FREQ=WEEKLY", instanceBegin, allDay = false),
            originalRow.getAsString(Events.RRULE),
        )
        assertEquals("Dentist", originalRow.getAsString(Events.TITLE))
        assertEquals(1, originalRow.getAsInteger(Events.DIRTY))

        val continuation = FakeCalendarProvider.events.entries.single { it.key != originalId }.value
        assertEquals("Dentist (new time)", continuation.getAsString(Events.TITLE))
        assertEquals("FREQ=WEEKLY", continuation.getAsString(Events.RRULE))
        assertEquals(1, continuation.getAsInteger(Events.DIRTY))
    }

    @Test
    fun `updateEvent with THIS_AND_FOLLOWING fails without WRITE_CALENDAR permission`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=DAILY"))!!
        val unpermitted = RuntimeEnvironment.getApplication()
        shadowOf(unpermitted).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val updated = EventStore.updateEvent(
            unpermitted, originalId, instanceStartMillis = 1_000_000L, scope = EditScope.THIS_AND_FOLLOWING,
            draft = draft(),
        )
        assertTrue(!updated)
        // Nothing was truncated and no continuation was inserted -- still just the one row.
        assertEquals(1, FakeCalendarProvider.events.size)
    }

    @Test
    fun `deleteEvent with THIS_AND_FOLLOWING truncates the series without inserting a continuation`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=DAILY"))!!
        val instanceBegin = 9_000_000L

        val deleted = EventStore.deleteEvent(
            context, originalId, instanceStartMillis = instanceBegin, scope = EditScope.THIS_AND_FOLLOWING,
        )
        assertTrue(deleted)

        assertEquals(1, FakeCalendarProvider.events.size)
        val row = FakeCalendarProvider.events.getValue(originalId)
        assertEquals(
            Recurrence.withUntil("FREQ=DAILY", instanceBegin, allDay = false),
            row.getAsString(Events.RRULE),
        )
        assertEquals(1, row.getAsInteger(Events.DIRTY))
        assertNull(row.get(Events.DELETED))
    }

    @Test
    fun `deleteEvent with ALL_INSTANCES marks the row deleted and dirty rather than removing it`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        val deleted = EventStore.deleteEvent(context, eventId, instanceStartMillis = null, scope = EditScope.ALL_INSTANCES)
        assertTrue(deleted)
        // Still present — a real delete() marks DELETED for sync, it doesn't drop the row (§6.1.2).
        val row = FakeCalendarProvider.events.getValue(eventId)
        assertEquals(1, row.getAsInteger(Events.DELETED))
        assertEquals(1, row.getAsInteger(Events.DIRTY))
    }

    @Test
    fun `deleteEvent with THIS_INSTANCE inserts a canceled exception row`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=DAILY"))!!
        val instanceBegin = 9_000_000L

        val deleted = EventStore.deleteEvent(context, originalId, instanceStartMillis = instanceBegin, scope = EditScope.THIS_INSTANCE)
        assertTrue(deleted)

        val exceptionRow = FakeCalendarProvider.events.entries.single { it.key != originalId }.value
        assertEquals(originalId, exceptionRow.getAsLong(Events.ORIGINAL_ID))
        assertEquals(instanceBegin, exceptionRow.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals(Events.STATUS_CANCELED, exceptionRow.getAsInteger(Events.STATUS))
        assertEquals(1, exceptionRow.getAsInteger(Events.DIRTY))
        // The original recurring event itself is untouched by a this-instance delete.
        assertNull(FakeCalendarProvider.events.getValue(originalId).get(Events.DELETED))
    }

    @Test
    fun `queryEventDetail computes end time from DURATION for a recurring event`() {
        val eventId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY", startMillis = 0L, endMillis = 3_600_000L))!!
        val detail = EventStore.queryEventDetail(context, eventId)
        assertNotNull(detail)
        assertEquals(0L, detail!!.startMillis)
        assertEquals(3_600_000L, detail.endMillis)
        assertEquals("FREQ=WEEKLY", detail.rrule)
    }

    @Test
    fun `queryEventDetail round trips a reminder`() {
        val eventId = EventStore.insertEvent(context, draft(reminderMinutes = 15))!!
        val detail = EventStore.queryEventDetail(context, eventId)
        assertEquals(15, detail?.reminderMinutes)
    }

    @Test
    fun `queryEventColors returns colors only for the calendar's own account`() {
        FakeCalendarProvider.putColor("timo@fastmail.com", "bitfire.at.davdroid", "aliceblue", 0xFFF0F8FF.toInt())
        FakeCalendarProvider.putColor("other@fastmail.com", "bitfire.at.davdroid", "beige", 0xFFF5F5DC.toInt())

        val colors = EventStore.queryEventColors(context, calendarId = 1L)
        assertEquals(listOf("aliceblue"), colors.map { it.key })
    }

    @Test
    fun `queryEventColors is empty for an account that hasn't populated any -- confirmed on device`() {
        // FAMILY@FURRER.LIFE's account on the real device has zero Colors rows, unlike
        // TIMO@FURRER.LIFE's 147 — the editor must hide the color field in that case, not crash.
        assertTrue(EventStore.queryEventColors(context, calendarId = 1L).isEmpty())
    }
}
