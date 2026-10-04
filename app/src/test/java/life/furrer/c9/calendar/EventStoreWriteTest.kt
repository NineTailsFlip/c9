package life.furrer.c9.calendar

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.TimeZone

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

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    /** A weekly 09:00 America/New_York event, inserted while the device is in New York. */
    private fun insertNewYorkEvent(): Long {
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid")
        val id = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        FakeCalendarProvider.events.getValue(id).put(Events.EVENT_TIMEZONE, "America/New_York")
        return id
    }

    private fun zoneOf(id: Long) = FakeCalendarProvider.events.getValue(id).getAsString(Events.EVENT_TIMEZONE)

    @Test
    fun `editing a New York event in Zurich keeps America New_York for every scope`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        val id = insertNewYorkEvent()
        val edited = draft(rrule = "FREQ=WEEKLY").copy(title = "Standup (moved)")

        assertEquals(id, EventStore.updateEvent(context, id, null, EditScope.ALL_INSTANCES, edited))
        assertEquals("America/New_York", zoneOf(id))

        val exceptionId = EventStore.updateEvent(context, id, 5_000_000L, EditScope.THIS_INSTANCE, edited)!!
        assertEquals("America/New_York", zoneOf(exceptionId))

        val continuationId = EventStore.updateEvent(context, id, 5_000_000L, EditScope.THIS_AND_FOLLOWING, edited)!!
        assertEquals("America/New_York", zoneOf(continuationId))
    }

    @Test
    fun `an unchanged save of a New York event keeps its instant, rule and duration`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        val id = insertNewYorkEvent()
        val before = FakeCalendarProvider.events.getValue(id)
        val start = before.getAsLong(Events.DTSTART)
        val duration = before.getAsString(Events.DURATION)

        EventStore.updateEvent(context, id, null, EditScope.ALL_INSTANCES, draft(rrule = "FREQ=WEEKLY"))

        val row = FakeCalendarProvider.events.getValue(id)
        assertEquals("America/New_York", zoneOf(id))
        assertEquals(start, row.getAsLong(Events.DTSTART))
        assertEquals("FREQ=WEEKLY", row.getAsString(Events.RRULE))
        assertEquals(duration, row.getAsString(Events.DURATION))
        assertNull(row.get(Events.DTEND))
    }

    @Test
    fun `a new event gets the device zone and an all-day event stays UTC`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        assertEquals("Europe/Zurich", zoneOf(EventStore.insertEvent(context, draft())!!))
        assertEquals("UTC", zoneOf(EventStore.insertEvent(context, draft(allDay = true))!!))
    }

    @Test
    fun `an all-day event made timed takes the device zone, a timed one made all-day goes UTC`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        val allDayId = EventStore.insertEvent(context, draft(allDay = true))!!
        EventStore.updateEvent(context, allDayId, null, EditScope.ALL_INSTANCES, draft())
        assertEquals("Europe/Zurich", zoneOf(allDayId))

        val nyId = insertNewYorkEvent()
        EventStore.updateEvent(context, nyId, null, EditScope.ALL_INSTANCES, draft(allDay = true))
        assertEquals("UTC", zoneOf(nyId))
    }

    private fun draft(
        rrule: String? = null,
        startMillis: Long = 1_000_000L,
        endMillis: Long = 1_003_600_000L,
        reminders: List<EventReminder> = emptyList(),
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
        reminders = reminders,
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
        val eventId = EventStore.insertEvent(context, draft(reminders = listOf(EventReminder(30))))!!
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
    fun `updateEvent with THIS_AND_FOLLOWING keeps an UNTIL-bounded rule verbatim on the continuation`() {
        val rule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20261201T000000Z"
        val originalId = EventStore.insertEvent(context, draft(rrule = rule))!!
        val instanceBegin = 1_000_000L + 14 * 86_400_000L
        val resultId = EventStore.updateEvent(
            context, originalId, instanceStartMillis = instanceBegin, scope = EditScope.THIS_AND_FOLLOWING,
            draft = draft(rrule = rule, startMillis = instanceBegin),
        )!!
        assertEquals(rule, FakeCalendarProvider.events.getValue(resultId).getAsString(Events.RRULE))
        assertEquals(
            Recurrence.withUntil(rule, instanceBegin, allDay = false),
            FakeCalendarProvider.events.getValue(originalId).getAsString(Events.RRULE),
        )
    }

    @Test
    fun `updateEvent with THIS_AND_FOLLOWING lowers COUNT by the occurrences before the split, exceptions included`() {
        val day = 86_400_000L
        val seriesStart = 1_800_000_000_000L
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=DAILY;COUNT=10;BYHOUR=9", startMillis = seriesStart))!!
        val otherId = EventStore.insertEvent(context, draft(rrule = "FREQ=DAILY"))!!
        fun instance(eventId: Long, n: Int) = ContentValues().apply {
            put(Instances.EVENT_ID, eventId)
            put(Instances.BEGIN, seriesStart + n * day)
            put(Instances.END, seriesStart + n * day + 3_600_000L)
        }
        // Occurrences 0..9; #1 was moved and #2 canceled, so the provider's own expansion of the
        // master leaves both out -- they're exception rows instead, and still used up the COUNT.
        (0..9).filter { it != 1 && it != 2 }.forEach { FakeCalendarProvider.instances.add(instance(originalId, it)) }
        (0..9).forEach { FakeCalendarProvider.instances.add(instance(otherId, it)) }
        EventStore.updateEvent(
            context, originalId, instanceStartMillis = seriesStart + day, scope = EditScope.THIS_INSTANCE,
            draft = draft(startMillis = seriesStart + day + 3_600_000L),
        )
        EventStore.deleteEvent(context, originalId, instanceStartMillis = seriesStart + 2 * day, scope = EditScope.THIS_INSTANCE)

        // Splitting at occurrence #4 leaves #0..#3 behind it, so 6 remain for the continuation.
        val resultId = EventStore.updateEvent(
            context, originalId, instanceStartMillis = seriesStart + 4 * day, scope = EditScope.THIS_AND_FOLLOWING,
            draft = draft(rrule = "FREQ=DAILY;COUNT=10;BYHOUR=9", startMillis = seriesStart + 4 * day),
        )!!
        assertEquals("FREQ=DAILY;COUNT=6;BYHOUR=9", FakeCalendarProvider.events.getValue(resultId).getAsString(Events.RRULE))
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
    fun `updateEvent with THIS_AND_FOLLOWING from the first occurrence edits the whole series in place`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY;COUNT=5"))!!
        val resultId = EventStore.updateEvent(
            context, originalId, instanceStartMillis = 1_000_000L, scope = EditScope.THIS_AND_FOLLOWING,
            draft = draft(rrule = "FREQ=WEEKLY;COUNT=5").copy(title = "Dentist (new time)"),
        )
        assertEquals(originalId, resultId)
        assertEquals(1, FakeCalendarProvider.events.size)
        val row = FakeCalendarProvider.events.getValue(originalId)
        assertEquals("FREQ=WEEKLY;COUNT=5", row.getAsString(Events.RRULE))
        assertEquals("Dentist (new time)", row.getAsString(Events.TITLE))
    }

    @Test
    fun `deleteEvent with THIS_AND_FOLLOWING from the first occurrence deletes the whole series`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=DAILY"))!!
        assertTrue(EventStore.deleteEvent(context, originalId, instanceStartMillis = 1_000_000L, scope = EditScope.THIS_AND_FOLLOWING))
        val row = FakeCalendarProvider.events.getValue(originalId)
        assertEquals(1, row.getAsInteger(Events.DELETED))
        assertEquals("FREQ=DAILY", row.getAsString(Events.RRULE))
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

    /** Tickets #4: a foreign DURATION (P3600S from AOSP Calendar/Etar, P1W from DAVx5) must be
     *  displayed at its real length and survive an edit that doesn't touch the end time. */
    private fun savesForeignDurationUnchanged(foreign: String, expectedMillis: Long, expectedWritten: String) {
        val eventId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY", startMillis = 0L, endMillis = 60_000L))!!
        FakeCalendarProvider.events.getValue(eventId).put(Events.DURATION, foreign)

        val detail = EventStore.queryEventDetail(context, eventId)!!
        assertEquals(expectedMillis, detail.endMillis - detail.startMillis)

        EventStore.updateEvent(
            context, eventId, null, EditScope.ALL_INSTANCES,
            draft(rrule = detail.rrule, startMillis = detail.startMillis, endMillis = detail.endMillis).copy(title = "Renamed"),
        )
        val row = FakeCalendarProvider.events.getValue(eventId)
        assertEquals(expectedWritten, row.getAsString(Events.DURATION))
        val after = EventStore.queryEventDetail(context, eventId)!!
        assertEquals(expectedMillis, after.endMillis - after.startMillis)
    }

    @Test
    fun `a recurring event with a seconds duration is displayed and saved at its length`() =
        savesForeignDurationUnchanged("P3600S", 3_600_000L, "PT1H")

    @Test
    fun `a recurring event with a weeks duration is displayed and saved at its length`() =
        savesForeignDurationUnchanged("P1W", 7 * 86_400_000L, "PT168H")

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
        val eventId = EventStore.insertEvent(context, draft(reminders = listOf(EventReminder(15))))!!
        val detail = EventStore.queryEventDetail(context, eventId)
        assertEquals(listOf(EventReminder(15)), detail?.reminders)
    }

    // --- Several reminders per event (tickets #3, #16) ----------------------------------------

    private fun putReminder(eventId: Long, minutes: Int, method: Int) {
        context.contentResolver.insert(
            Reminders.CONTENT_URI,
            ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minutes)
                put(Reminders.METHOD, method)
            },
        )
    }

    private fun storedReminders(eventId: Long): List<Pair<Int, Int>> =
        FakeCalendarProvider.reminders[eventId].orEmpty().map { it.getAsInteger(Reminders.MINUTES) to it.getAsInteger(Reminders.METHOD) }

    @Test
    fun `queryEventDetail reads every reminder with its method, shortest lead first`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        putReminder(eventId, 1440, Reminders.METHOD_EMAIL)
        putReminder(eventId, 10, Reminders.METHOD_ALERT)

        assertEquals(
            listOf(EventReminder(10, Reminders.METHOD_ALERT), EventReminder(1440, Reminders.METHOD_EMAIL)),
            EventStore.queryEventDetail(context, eventId)?.reminders,
        )
    }

    @Test
    fun `saving with the reminders unchanged leaves every row and its method alone`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        putReminder(eventId, 10, Reminders.METHOD_ALERT)
        putReminder(eventId, 1440, Reminders.METHOD_EMAIL)
        putReminder(eventId, 30, Reminders.METHOD_SMS)
        val rowIds = FakeCalendarProvider.reminders.getValue(eventId).map { it.getAsLong(Reminders._ID) }

        val reminders = EventStore.queryEventDetail(context, eventId)!!.reminders
        EventStore.updateEvent(context, eventId, null, EditScope.ALL_INSTANCES, draft(reminders = reminders).copy(title = "Renamed"))

        assertEquals(rowIds, FakeCalendarProvider.reminders.getValue(eventId).map { it.getAsLong(Reminders._ID) })
        assertEquals(
            listOf(10 to Reminders.METHOD_ALERT, 1440 to Reminders.METHOD_EMAIL, 30 to Reminders.METHOD_SMS),
            storedReminders(eventId),
        )
    }

    @Test
    fun `adding and removing a reminder touches only that row`() {
        val eventId = EventStore.insertEvent(context, draft())!!
        putReminder(eventId, 10, Reminders.METHOD_ALERT)
        putReminder(eventId, 1440, Reminders.METHOD_EMAIL)

        EventStore.updateEvent(
            context, eventId, null, EditScope.ALL_INSTANCES,
            draft(reminders = listOf(EventReminder(1440, Reminders.METHOD_EMAIL), EventReminder(60))),
        )

        assertEquals(listOf(1440 to Reminders.METHOD_EMAIL, 60 to Reminders.METHOD_ALERT), storedReminders(eventId))
        assertEquals(1, FakeCalendarProvider.events.getValue(eventId).getAsInteger(Events.HAS_ALARM))
    }

    @Test
    fun `removing the last reminder clears HAS_ALARM`() {
        val eventId = EventStore.insertEvent(context, draft(reminders = listOf(EventReminder(10))))!!

        EventStore.updateEvent(context, eventId, null, EditScope.ALL_INSTANCES, draft())

        assertTrue(storedReminders(eventId).isEmpty())
        assertEquals(0, FakeCalendarProvider.events.getValue(eventId).getAsInteger(Events.HAS_ALARM))
    }

    @Test
    fun `THIS_INSTANCE writes the reminders once whether or not the provider copied them to the exception`() {
        val wanted = listOf(EventReminder(10), EventReminder(1440, Reminders.METHOD_EMAIL))
        listOf(false, true).forEach { copies ->
            FakeCalendarProvider.reset()
            FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid")
            FakeCalendarProvider.copyRemindersToExceptions = copies
            val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY", reminders = wanted))!!

            val exceptionId = EventStore.updateEvent(context, originalId, 5_000_000L, EditScope.THIS_INSTANCE, draft(reminders = wanted))!!

            assertEquals("copies=$copies", listOf(10 to Reminders.METHOD_ALERT, 1440 to Reminders.METHOD_EMAIL), storedReminders(exceptionId))
            assertEquals(2, storedReminders(originalId).size)
        }
    }

    @Test
    fun `changing a reminder for one occurrence leaves the series' own rows alone`() {
        val originalId = EventStore.insertEvent(
            context, draft(rrule = "FREQ=WEEKLY", reminders = listOf(EventReminder(10), EventReminder(1440, Reminders.METHOD_EMAIL))),
        )!!
        val seriesRowIds = FakeCalendarProvider.reminders.getValue(originalId).map { it.getAsLong(Reminders._ID) }

        val exceptionId = EventStore.updateEvent(
            context, originalId, 5_000_000L, EditScope.THIS_INSTANCE,
            draft(reminders = listOf(EventReminder(1440, Reminders.METHOD_EMAIL), EventReminder(30))),
        )!!

        assertEquals(listOf(1440 to Reminders.METHOD_EMAIL, 30 to Reminders.METHOD_ALERT), storedReminders(exceptionId))
        assertEquals(seriesRowIds, FakeCalendarProvider.reminders.getValue(originalId).map { it.getAsLong(Reminders._ID) })
        assertEquals(listOf(10 to Reminders.METHOD_ALERT, 1440 to Reminders.METHOD_EMAIL), storedReminders(originalId))
    }

    @Test
    fun `removing every reminder for one occurrence clears only the exception`() {
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY", reminders = listOf(EventReminder(10))))!!

        val exceptionId = EventStore.updateEvent(context, originalId, 5_000_000L, EditScope.THIS_INSTANCE, draft())!!

        assertTrue(storedReminders(exceptionId).isEmpty())
        assertEquals(0, FakeCalendarProvider.events.getValue(exceptionId).getAsInteger(Events.HAS_ALARM))
        assertEquals(listOf(10 to Reminders.METHOD_ALERT), storedReminders(originalId))
        assertEquals(1, FakeCalendarProvider.events.getValue(originalId).getAsInteger(Events.HAS_ALARM))
    }

    @Test
    fun `THIS_AND_FOLLOWING gives the continuation every reminder and leaves the original's`() {
        val wanted = listOf(EventReminder(10), EventReminder(1440, Reminders.METHOD_EMAIL))
        val originalId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY", reminders = wanted))!!

        val continuationId = EventStore.updateEvent(context, originalId, 5_000_000L, EditScope.THIS_AND_FOLLOWING, draft(rrule = "FREQ=WEEKLY", reminders = wanted))!!

        assertEquals(listOf(10 to Reminders.METHOD_ALERT, 1440 to Reminders.METHOD_EMAIL), storedReminders(continuationId))
        assertEquals(2, storedReminders(originalId).size)
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
    fun `respondToOccurrence answers on an exception for that occurrence and leaves the series alone`() {
        val seriesId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        EventStore.addAttendee(context, seriesId, "timo@fastmail.com", "Timo")
        EventStore.addAttendee(context, seriesId, "bob@acme.ch", "Bob")
        val occurrence = 1_000_000L + 7 * 86_400_000L

        val exceptionId = EventStore.respondToOccurrence(context, seriesId, calendarId = 1L, occurrence, Attendees.ATTENDEE_STATUS_DECLINED)!!

        val exception = FakeCalendarProvider.events.getValue(exceptionId)
        assertEquals(seriesId, exception.getAsLong(Events.ORIGINAL_ID))
        assertEquals(occurrence, exception.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals(occurrence, exception.getAsLong(Events.DTSTART))
        assertEquals(1, exception.getAsInteger(Events.DIRTY))
        val answered = EventStore.queryAttendees(context, exceptionId, calendarId = 1L)
        assertEquals(Attendees.ATTENDEE_STATUS_DECLINED, answered.single { it.isSelf }.status)
        assertEquals(2, answered.size)
        val series = EventStore.queryAttendees(context, seriesId, calendarId = 1L)
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, series.single { it.isSelf }.status)
    }

    @Test
    fun `respondToOccurrence writes no exception when there's no self attendee to answer for`() {
        val seriesId = EventStore.insertEvent(context, draft(rrule = "FREQ=WEEKLY"))!!
        EventStore.addAttendee(context, seriesId, "bob@acme.ch", "Bob")
        assertNull(EventStore.respondToOccurrence(context, seriesId, calendarId = 1L, 1_000_000L, Attendees.ATTENDEE_STATUS_ACCEPTED))
        assertEquals(1, FakeCalendarProvider.events.size)
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
