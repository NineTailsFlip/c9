package life.furrer.c9.calendar

import android.Manifest
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `queryEventDetail reports dirty true for a freshly inserted, not-yet-synced event`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        assertTrue(EventStore.queryEventDetail(context, eventId)!!.dirty)
    }

    @Test
    fun `queryEventDetail reports dirty false once DAVx5 has cleared the flag after a sync`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        FakeCalendarProvider.events.getValue(eventId).put(Events.DIRTY, 0)
        assertFalse(EventStore.queryEventDetail(context, eventId)!!.dirty)
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
    fun `updateEvent with ALL_INSTANCES updates the parent row, marks it dirty, and returns the same id`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        val resultId = EventStore.updateEvent(
            context, eventId, instanceStartMillis = null, scope = EditScope.ALL_INSTANCES,
            draft = draft().copy(title = "Dentist (moved)"),
        )
        assertEquals(eventId, resultId)
        val row = FakeCalendarProvider.events.getValue(eventId)
        assertEquals("Dentist (moved)", row.getAsString(Events.TITLE))
        assertEquals(1, row.getAsInteger(Events.DIRTY))
    }

    @Test
    fun `updateEvent with THIS_INSTANCE inserts an exception row with the right ORIGINAL_INSTANCE_TIME and returns its own new id`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        val instanceBegin = 5_000_000L

        val resultId = EventStore.updateEvent(
            context, originalId, instanceStartMillis = instanceBegin, scope = EditScope.THIS_INSTANCE,
            draft = draft().copy(title = "Dentist (this week only)"),
        )
        assertNotNull(resultId)
        // The exception's own id is a *new*, distinct row — critically not the original's, since
        // a caller that needs to attach anything else (e.g. attendees) to the row this write
        // actually produced must use this id, not originalId.
        assertTrue(resultId != originalId)

        val exceptionRow = FakeCalendarProvider.events.getValue(resultId!!)
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
    fun `updateEvent with THIS_INSTANCE never writes EVENT_COLOR_KEY, confirmed on-device to crash the real provider`() {
        // Regression test: found live while testing THIS_INSTANCE attendee edits on an event
        // whose calendar has a color key set — eventValues() was writing EVENT_COLOR_KEY
        // unconditionally, which the real CalendarProvider2 rejects for an exception row exactly
        // like it rejects DTEND, crashing every "this event only" edit of a colored event.
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY", eventColorKey = "skyblue"))!!
        val resultId = EventStore.updateEvent(
            context, originalId, instanceStartMillis = 5_000_000L, scope = EditScope.THIS_INSTANCE,
            draft = draft(eventColorKey = "skyblue"),
        )
        assertNotNull(resultId)
        assertNull(FakeCalendarProvider.events.getValue(resultId!!).get(Events.EVENT_COLOR_KEY))
    }

    @Test
    fun `updateEvent with THIS_AND_FOLLOWING truncates the original series and returns the continuation's own new id`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        val instanceBegin = 5_000_000L

        val resultId = EventStore.updateEvent(
            context, originalId, instanceStartMillis = instanceBegin, scope = EditScope.THIS_AND_FOLLOWING,
            draft = draft(rrule = "FREQ=WEEKLY", startMillis = instanceBegin).copy(title = "Dentist (new time)"),
        )
        assertNotNull(resultId)
        assertTrue(resultId != originalId)

        // The original series is truncated in place (title untouched -- only RRULE changes) and
        // stays the same row/id; the edit itself lives in a brand-new continuation event.
        val originalRow = FakeCalendarProvider.events.getValue(originalId)
        assertEquals(
            Recurrence.withUntil("FREQ=WEEKLY", instanceBegin, allDay = false),
            originalRow.getAsString(Events.RRULE),
        )
        assertEquals("Dentist", originalRow.getAsString(Events.TITLE))
        assertEquals(1, originalRow.getAsInteger(Events.DIRTY))

        val continuation = FakeCalendarProvider.events.getValue(resultId!!)
        assertEquals("Dentist (new time)", continuation.getAsString(Events.TITLE))
        assertEquals("FREQ=WEEKLY", continuation.getAsString(Events.RRULE))
        assertEquals(1, continuation.getAsInteger(Events.DIRTY))
    }

    @Test
    fun `updateEvent with THIS_AND_FOLLOWING carries the original's description onto the continuation`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        FakeCalendarProvider.events.getValue(originalId).put(Events.DESCRIPTION, "Bring the X-rays")
        val resultId = EventStore.updateEvent(
            context, originalId, instanceStartMillis = 1_000_000L + 7 * 86_400_000L,
            scope = EditScope.THIS_AND_FOLLOWING, draft = draft(rrule = "FREQ=WEEKLY"),
        )!!
        assertEquals("Bring the X-rays", FakeCalendarProvider.events.getValue(resultId).getAsString(Events.DESCRIPTION))
        assertEquals("Bring the X-rays", FakeCalendarProvider.events.getValue(originalId).getAsString(Events.DESCRIPTION))
    }

    @Test
    fun `updateEvent with THIS_AND_FOLLOWING fails without WRITE_CALENDAR permission`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=DAILY"))!!
        val unpermitted = RuntimeEnvironment.getApplication()
        shadowOf(unpermitted).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val resultId = EventStore.updateEvent(
            unpermitted, originalId, instanceStartMillis = 1_000_000L, scope = EditScope.THIS_AND_FOLLOWING,
            draft = draft(),
        )
        assertNull(resultId)
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
    fun `queryEventDetail reads a synced description and updateEvent leaves it untouched`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        FakeCalendarProvider.events.getValue(eventId).put(Events.DESCRIPTION, "Agenda:\n1. Budget")
        assertEquals("Agenda:\n1. Budget", EventStore.queryEventDetail(context, eventId)?.description)

        EventStore.updateEvent(context, eventId, null, EditScope.ALL_INSTANCES, draft().copy(title = "Renamed"))
        assertEquals("Agenda:\n1. Budget", EventStore.queryEventDetail(context, eventId)?.description)
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

    // --- Attendees (RFC 5545 ATTENDEE/PARTSTAT) ----------------------------------------------

    @Test
    fun `addAttendee inserts a required attendee awaiting a response`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        assertTrue(EventStore.addAttendee(context, eventId, "bob@acme.ch", "Bob"))

        val attendee = EventStore.queryAttendees(context, eventId, calendarId = 1L).single()
        assertEquals("bob@acme.ch", attendee.email)
        assertEquals("Bob", attendee.name)
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, attendee.status)
        assertEquals(Attendees.RELATIONSHIP_ATTENDEE, attendee.relationship)
        assertTrue(!attendee.isOrganizer)
    }

    @Test
    fun `addAttendee fails without WRITE_CALENDAR permission`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        val unpermitted = RuntimeEnvironment.getApplication()
        shadowOf(unpermitted).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertTrue(!EventStore.addAttendee(unpermitted, eventId, "bob@acme.ch", "Bob"))
        assertTrue(EventStore.queryAttendees(context, eventId, calendarId = 1L).isEmpty())
    }

    @Test
    fun `queryAttendees marks the row matching the calendar's owner account as self`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        EventStore.addAttendee(context, eventId, "bob@acme.ch", "Bob")
        // Case differs from the calendar's own OWNER_ACCOUNT ("timo@fastmail.com", from setUp) —
        // matching must be case-insensitive since that's how email addresses actually compare.
        EventStore.addAttendee(context, eventId, "TIMO@fastmail.com", "Timo")

        val attendees = EventStore.queryAttendees(context, eventId, calendarId = 1L)
        assertEquals(false, attendees.single { it.email == "bob@acme.ch" }.isSelf)
        assertEquals(true, attendees.single { it.email == "TIMO@fastmail.com" }.isSelf)
    }

    @Test
    fun `removeAttendee deletes the row`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        EventStore.addAttendee(context, eventId, "bob@acme.ch", "Bob")
        val attendeeId = EventStore.queryAttendees(context, eventId, calendarId = 1L).single().id

        assertTrue(EventStore.removeAttendee(context, attendeeId))
        assertTrue(EventStore.queryAttendees(context, eventId, calendarId = 1L).isEmpty())
    }

    @Test
    fun `respondToInvite writes the status onto this account's own attendee row`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        // "timo@fastmail.com" matches the calendar's OWNER_ACCOUNT from setUp, so this is the
        // self row respondToInvite must find and update.
        EventStore.addAttendee(context, eventId, "timo@fastmail.com", "Timo")

        assertTrue(EventStore.respondToInvite(context, eventId, calendarId = 1L, Attendees.ATTENDEE_STATUS_ACCEPTED))

        val self = EventStore.queryAttendees(context, eventId, calendarId = 1L).single { it.isSelf }
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, self.status)
    }

    @Test
    fun `respondToInvite fails when there's no self attendee row to answer for`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        assertTrue(!EventStore.respondToInvite(context, eventId, calendarId = 1L, Attendees.ATTENDEE_STATUS_ACCEPTED))
    }

    @Test
    fun `respondToInvite fails without WRITE_CALENDAR permission`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        EventStore.addAttendee(context, eventId, "timo@fastmail.com", "Timo")
        val unpermitted = RuntimeEnvironment.getApplication()
        shadowOf(unpermitted).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertTrue(!EventStore.respondToInvite(unpermitted, eventId, calendarId = 1L, Attendees.ATTENDEE_STATUS_DECLINED))
    }

    @Test
    fun `queryEventDetail defaults selfAttendeeStatus to STATUS_NONE when never set`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        assertEquals(Attendees.ATTENDEE_STATUS_NONE, EventStore.queryEventDetail(context, eventId)!!.selfAttendeeStatus)
    }
}
