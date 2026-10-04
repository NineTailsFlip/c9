package life.furrer.c9.calendar

import android.content.Intent
import android.os.Looper
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.core.Prefs
import life.furrer.nine.contacts.ContactValuePickerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Covers EventEditActivity end-to-end against [FakeCalendarProvider] — including a direct
 *  regression test for the ALL_DAY toggle bug found during review (it wasn't reconverting
 *  start/end between local-zone and UTC-midnight representations). Async's background executor
 *  is a real thread, so [awaitUntil] polls rather than assuming a single idle() call is enough —
 *  a blind single idle() would be a race against that thread actually finishing. */
@RunWith(RobolectricTestRunner::class)
class EventEditActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid")
    }

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun buildActivity(
        eventId: Long? = null,
        initialDate: java.time.LocalDate? = null,
        editScope: EditScope? = null,
        instanceStartMillis: Long? = null,
    ): EventEditActivity {
        val intent = Intent(context, EventEditActivity::class.java)
        if (eventId != null) intent.putExtra(EventEditActivity.EXTRA_EVENT_ID, eventId)
        if (initialDate != null) intent.putExtra(EventEditActivity.EXTRA_INITIAL_DATE, initialDate.toEpochDay())
        if (editScope != null) intent.putExtra(EventEditActivity.EXTRA_EDIT_SCOPE, editScope.name)
        if (instanceStartMillis != null) intent.putExtra(EventEditActivity.EXTRA_INSTANCE_START_MILLIS, instanceStartMillis)
        val activity = Robolectric.buildActivity(EventEditActivity::class.java, intent).setup().get()
        // render() only ever runs after loadInitialData()'s Async round trip completes, so the
        // Title row's text (empty until then) is a reliable "has it loaded yet" signal — unlike
        // checking FakeCalendarProvider's own fixture data, which is populated synchronously in
        // @Before regardless of whether the activity's async load has actually run yet.
        awaitUntil { rowText(activity, 0).startsWith("Title:") }
        return activity
    }

    private fun rowContainer(activity: EventEditActivity): LinearLayout {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val mainColumn = root.getChildAt(0) as LinearLayout
        val scrollView = mainColumn.getChildAt(0) as ScrollView
        return scrollView.getChildAt(0) as LinearLayout
    }

    /** Row order matches FieldSlot's declaration order in EventEditActivity. */
    private fun rowText(activity: EventEditActivity, index: Int): String =
        (rowContainer(activity).getChildAt(index) as TextView).text.toString()

    private fun down(activity: EventEditActivity, keyCode: Int) {
        activity.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    /** Simulates the framework delivering a ContactValuePickerActivity result — onActivityResult
     *  itself is protected (inherited from Activity), so it can't be called directly from here. */
    private fun deliverAttendeePickerResult(activity: EventEditActivity, email: String, name: String?) {
        shadowOf(activity).receiveResult(
            Intent(activity, ContactValuePickerActivity::class.java),
            android.app.Activity.RESULT_OK,
            Intent().apply {
                putExtra(ContactValuePickerActivity.EXTRA_VALUE, email)
                putExtra(ContactValuePickerActivity.EXTRA_NAME, name)
            },
        )
    }

    @Test
    fun `Back on a new, still-untitled event prompts to discard instead of saving or crashing`() {
        val activity = buildActivity()
        down(activity, KeyEvent.KEYCODE_BACK)

        // A "Discard event?" dialog should be showing, awaiting the user's choice — the
        // activity itself neither saved (nothing in FakeCalendarProvider) nor finished yet.
        assertTrue(!activity.isFinishing)
        assertTrue(FakeCalendarProvider.events.isEmpty())
    }

    @Test
    fun `editing an existing event pre-populates its title and location`() {
        val startMillis = 1_800_000_000_000L
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L,
                title = "Dentist",
                location = "Bahnhofstrasse 12",
                startMillis = startMillis,
                endMillis = startMillis + 2_700_000L,
                allDay = false,
                rrule = null,
                reminders = emptyList(),
                eventColorKey = null,
            ),
        )!!

        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Dentist") }

        assertTrue(rowText(activity, 0).contains("Dentist"))
        assertTrue(rowText(activity, 5).contains("Bahnhofstrasse 12"))
    }

    @Test
    fun `toggling All day reconverts a timed start into a UTC-midnight boundary, not a relabeled timestamp`() {
        // A timed event starting at a known local wall-clock time.
        val zone = java.time.ZoneId.systemDefault()
        val localDate = java.time.LocalDate.of(2026, 7, 30)
        val timedStart = localDate.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L,
                title = "Standup",
                location = null,
                startMillis = timedStart,
                endMillis = timedStart + 1_800_000L,
                allDay = false,
                rrule = null,
                reminders = emptyList(),
                eventColorKey = null,
            ),
        )!!

        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }

        // Navigate to the All day row (index 3) and toggle it on.
        repeat(3) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_RIGHT)

        assertTrue(rowText(activity, 3).contains("yes"))

        // Now save (Back, with a non-blank title) and verify the stored event is a proper
        // whole-day UTC-midnight-anchored all-day event for the same calendar date, not the
        // original timed millis merely relabeled ALL_DAY=1.
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { FakeCalendarProvider.events.getValue(eventId).getAsInteger(Events.ALL_DAY) == 1 }

        val row = FakeCalendarProvider.events.getValue(eventId)
        val expectedStart = localDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val expectedEnd = localDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedStart, row.getAsLong(Events.DTSTART))
        assertEquals(expectedEnd, row.getAsLong(Events.DTEND))
    }

    @Test
    fun `the Color row is hidden when the calendar's account has no event colors`() {
        val activity = buildActivity()
        // 13 fields total minus the hidden Color row = 12 visible rows.
        assertEquals(12, rowContainer(activity).let { container -> (0 until container.childCount).count { container.getChildAt(it).visibility == android.view.View.VISIBLE } })
    }

    @Test
    fun `the Color row appears once the calendar's account has event colors`() {
        FakeCalendarProvider.putColor("timo@fastmail.com", "bitfire.at.davdroid", "aliceblue", 0xFFF0F8FF.toInt())
        val activity = buildActivity()
        awaitUntil { rowText(activity, 8).contains("Color") }
        assertTrue(rowText(activity, 8).contains("calendar default"))
    }

    @Test
    fun `a new event defaults to the calendar and duration configured in Settings`() {
        FakeCalendarProvider.putCalendar(2L, "personal@example.com", "bitfire.at.davdroid", displayName = "Personal")
        Prefs.setDefaultCalendarId(context, 2L)
        Prefs.setDefaultEventDurationMinutes(context, 90)

        val activity = buildActivity()

        assertTrue(rowText(activity, 9).contains("Personal"))

        fun minutesOfDay(row: String): Int {
            val (hours, minutes) = row.substringAfterLast(", ").split(":").map { it.toInt() }
            return hours * 60 + minutes
        }
        val diff = (minutesOfDay(rowText(activity, 2)) - minutesOfDay(rowText(activity, 1)) + 1440) % 1440
        assertEquals(90, diff)
    }

    @Test
    fun `a new event falls back to the first calendar when the configured default no longer exists`() {
        Prefs.setDefaultCalendarId(context, 999L)

        val activity = buildActivity()

        assertTrue(rowText(activity, 9).contains("Calendar"))
    }

    @Test
    fun `a new event lands on the date the user was looking at, not today`() {
        val futureDate = java.time.LocalDate.now().plusDays(5)
        val activity = buildActivity(initialDate = futureDate)

        val dow = futureDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val month = futureDate.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val expectedDateText = "$dow ${futureDate.dayOfMonth} $month"

        assertTrue(rowText(activity, 1).contains(expectedDateText)) // Starts
        assertTrue(rowText(activity, 2).contains(expectedDateText)) // Ends (same day, well under the default duration)
    }

    @Test
    fun `a new event without an initial date defaults to today, as before`() {
        val activity = buildActivity()

        val today = java.time.LocalDate.now()
        val dow = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val month = today.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        assertTrue(rowText(activity, 1).contains("$dow ${today.dayOfMonth} $month"))
    }

    // --- Attendees (RFC 5545 ATTENDEE/PARTSTAT) ----------------------------------------------

    @Test
    fun `Attendees row starts at none and reflects a picked contact via onActivityResult`() {
        val activity = buildActivity()
        assertTrue(rowText(activity, 6).contains("Attendees: none"))

        // Opening the picker (via the Attendees row's menu) is what registers the pending
        // startActivityForResult Robolectric's receiveResult below needs to find.
        repeat(6) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        down(activity, KeyEvent.KEYCODE_1)
        deliverAttendeePickerResult(activity, "bob@acme.ch", "Bob")

        assertTrue(rowText(activity, 6).contains("Attendees: 1"))
    }

    @Test
    fun `an existing event's attendees load, and removing then re-adding reconciles correctly on save`() {
        val startMillis = 1_800_000_000_000L
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Team sync", location = null,
                startMillis = startMillis, endMillis = startMillis + 1_800_000L,
                allDay = false, rrule = null, reminders = emptyList(), eventColorKey = null,
            ),
        )!!
        EventStore.addAttendee(context, eventId, "alice@acme.ch", "Alice")

        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Team sync") }
        assertTrue(rowText(activity, 6).contains("Attendees: 1"))

        // Navigate to the Attendees row and open its menu: "1 Add attendee", "2 Remove Alice".
        repeat(6) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        down(activity, KeyEvent.KEYCODE_2)
        assertTrue(rowText(activity, 6).contains("Attendees: none"))

        // Nothing is actually removed from the provider yet -- still staged, like every other
        // field on this screen, until Back saves.
        assertEquals(1, EventStore.queryAttendees(context, eventId, calendarId = 1L).size)

        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        down(activity, KeyEvent.KEYCODE_1)
        deliverAttendeePickerResult(activity, "bob@acme.ch", "Bob")
        assertTrue(rowText(activity, 6).contains("Attendees: 1"))

        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { EventStore.queryAttendees(context, eventId, calendarId = 1L).any { it.email == "bob@acme.ch" } }

        assertEquals(listOf("bob@acme.ch"), EventStore.queryAttendees(context, eventId, calendarId = 1L).map { it.email })
    }

    @Test
    fun `editing a single occurrence with THIS_INSTANCE writes staged attendees to the new exception, not the master`() {
        // Regression test: EventStore.updateEvent used to return a plain Boolean, so save() had
        // no way to learn a scoped edit landed on a brand-new exception/continuation row rather
        // than the original id — attendee writes went to the *original* event's own Attendees
        // table regardless of scope, silently affecting the whole recurring series instead of
        // just the occurrence being edited.
        val instanceBegin = 1_800_000_000_000L
        val originalId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Team sync", location = null,
                startMillis = instanceBegin, endMillis = instanceBegin + 1_800_000L,
                allDay = false, rrule = "FREQ=WEEKLY", reminders = emptyList(), eventColorKey = null,
            ),
        )!!
        EventStore.addAttendee(context, originalId, "alice@acme.ch", "Alice")

        val activity = buildActivity(originalId, editScope = EditScope.THIS_INSTANCE, instanceStartMillis = instanceBegin)
        awaitUntil { rowText(activity, 0).contains("Team sync") }

        repeat(6) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        down(activity, KeyEvent.KEYCODE_1)
        deliverAttendeePickerResult(activity, "bob@acme.ch", "Bob")

        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { FakeCalendarProvider.events.size == 2 }

        val exceptionId = FakeCalendarProvider.events.keys.single { it != originalId }
        // The master's own attendees are untouched -- still just Alice, not affected by an edit
        // scoped to a single occurrence.
        assertEquals(listOf("alice@acme.ch"), EventStore.queryAttendees(context, originalId, calendarId = 1L).map { it.email })
        // The provider copied Alice onto the exception; only Bob is added, nobody twice.
        assertEquals(
            listOf("alice@acme.ch", "bob@acme.ch"),
            EventStore.queryAttendees(context, exceptionId, calendarId = 1L).map { it.email }.sorted(),
        )
    }

    @Test
    fun `editing a single occurrence removes an attendee the provider copied over, case-insensitively keeping others`() {
        val instanceBegin = 1_800_000_000_000L
        val originalId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Team sync", location = null,
                startMillis = instanceBegin, endMillis = instanceBegin + 1_800_000L,
                allDay = false, rrule = "FREQ=WEEKLY", reminders = emptyList(), eventColorKey = null,
            ),
        )!!
        EventStore.addAttendee(context, originalId, "Alice@acme.ch", "Alice")
        EventStore.addAttendee(context, originalId, "carol@acme.ch", "Carol")

        val activity = buildActivity(originalId, editScope = EditScope.THIS_INSTANCE, instanceStartMillis = instanceBegin)
        awaitUntil { rowText(activity, 0).contains("Team sync") }

        repeat(6) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        // Menu: "1 Add attendee", then a "Remove <name>" entry per attendee; remove Carol.
        down(activity, KeyEvent.KEYCODE_3)
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { FakeCalendarProvider.events.size == 2 }

        val exceptionId = FakeCalendarProvider.events.keys.single { it != originalId }
        awaitUntil { EventStore.queryAttendees(context, exceptionId, calendarId = 1L).size == 1 }
        assertEquals(listOf("alice@acme.ch"), EventStore.queryAttendees(context, exceptionId, calendarId = 1L).map { it.email.lowercase() })
        assertEquals(2, EventStore.queryAttendees(context, originalId, calendarId = 1L).size)
    }

    // --- Repeat rule preservation (ticket #2) -------------------------------------------------

    private val detailedRule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20261201T120000Z"

    private fun insertRecurring(rrule: String = detailedRule): Long {
        val startMillis = 1_800_000_000_000L
        return EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Standup", location = null,
                startMillis = startMillis, endMillis = startMillis + 900_000L,
                allDay = false, rrule = rrule, reminders = emptyList(), eventColorKey = null,
            ),
        )!!
    }

    private fun savedRule(eventId: Long): String? = FakeCalendarProvider.events.getValue(eventId).getAsString(Events.RRULE)

    @Test
    fun `saving a recurring event without touching Repeats writes its full rule back unchanged`() {
        val eventId = insertRecurring()
        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }
        assertTrue(rowText(activity, 4), rowText(activity, 4).startsWith("Repeats:   Every 2 weeks on Mon, Wed until "))

        // Change something else (Reminder, row 7) so this is a real edit, not a no-op save:
        // "Add reminder", then the 10-minute preset.
        repeat(7) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        down(activity, KeyEvent.KEYCODE_1)
        down(activity, KeyEvent.KEYCODE_3)
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { activity.isFinishing }

        assertEquals(detailedRule, savedRule(eventId))
        assertEquals(listOf(EventReminder(10)), EventStore.queryEventDetail(context, eventId)!!.reminders)
    }

    @Test
    fun `changing the frequency writes a plain rule for the new one`() {
        val eventId = insertRecurring()
        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }

        repeat(4) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Repeats:   Every month", rowText(activity, 4))
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { activity.isFinishing }

        assertEquals("FREQ=MONTHLY", savedRule(eventId))
    }

    @Test
    fun `cycling the frequency away and back restores the original rule`() {
        val eventId = insertRecurring()
        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }

        repeat(4) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        down(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { activity.isFinishing }

        assertEquals(detailedRule, savedRule(eventId))
    }

    @Test
    fun `a rule whose frequency the editor doesn't offer survives a save`() {
        val eventId = insertRecurring("FREQ=HOURLY;INTERVAL=4")
        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { activity.isFinishing }

        assertEquals("FREQ=HOURLY;INTERVAL=4", savedRule(eventId))
    }

    @Test
    fun `the original rule survives the activity being recreated`() {
        val eventId = insertRecurring()
        val intent = Intent(context, EventEditActivity::class.java).putExtra(EventEditActivity.EXTRA_EVENT_ID, eventId)
        val controller = Robolectric.buildActivity(EventEditActivity::class.java, intent).setup()
        awaitUntil { rowText(controller.get(), 0).contains("Standup") }

        controller.recreate()
        val recreated = controller.get()
        assertTrue(rowText(recreated, 4), rowText(recreated, 4).startsWith("Repeats:   Every 2 weeks on Mon, Wed"))
        down(recreated, KeyEvent.KEYCODE_BACK)
        awaitUntil { recreated.isFinishing }

        assertEquals(detailedRule, savedRule(eventId))
    }

    @Test
    fun `editing this and following keeps the full rule on the continuation`() {
        val eventId = insertRecurring()
        val splitMillis = 1_800_000_000_000L + 14 * 86_400_000L
        val activity = buildActivity(eventId, editScope = EditScope.THIS_AND_FOLLOWING, instanceStartMillis = splitMillis)
        awaitUntil { rowText(activity, 0).contains("Standup") }
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { FakeCalendarProvider.events.size == 2 }

        val continuationId = FakeCalendarProvider.events.keys.single { it != eventId }
        assertEquals(detailedRule, savedRule(continuationId))
        assertEquals(Recurrence.withUntil(detailedRule, splitMillis, allDay = false), savedRule(eventId))
    }

    @Test
    fun `a new event's time of day matches now regardless of which date was requested`() {
        val withoutDate = buildActivity()
        val withFutureDate = buildActivity(initialDate = java.time.LocalDate.now().plusDays(5))

        fun timeOfDay(row: String) = row.substringAfterLast(", ")
        assertEquals(timeOfDay(rowText(withoutDate, 1)), timeOfDay(rowText(withFutureDate, 1)))
    }

    // --- Several reminders per event (tickets #3, #16) ----------------------------------------

    private val reminderRow = 7

    private fun insertWithReminders(vararg reminders: EventReminder): Long {
        val startMillis = 1_800_000_000_000L
        return EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Standup", location = null,
                startMillis = startMillis, endMillis = startMillis + 900_000L,
                allDay = false, rrule = null, reminders = reminders.toList(), eventColorKey = null,
            ),
        )!!
    }

    private fun openReminderMenu(activity: EventEditActivity) {
        repeat(reminderRow) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun saveAndClose(activity: EventEditActivity) {
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { activity.isFinishing }
    }

    private fun savedReminders(eventId: Long) = EventStore.queryEventDetail(context, eventId)!!.reminders

    @Test
    fun `the Reminder row lists every reminder of the event`() {
        val eventId = insertWithReminders(EventReminder(1440), EventReminder(10))
        val activity = buildActivity(eventId)
        assertEquals("Reminder:  10 min, 1 day before", rowText(activity, reminderRow))
    }

    @Test
    fun `a new event has no reminder until one is added`() {
        val activity = buildActivity()
        assertEquals("Reminder:  none", rowText(activity, reminderRow))
    }

    @Test
    fun `saving without touching the reminders keeps them all and their methods`() {
        val email = EventReminder(1440, android.provider.CalendarContract.Reminders.METHOD_EMAIL)
        val eventId = insertWithReminders(EventReminder(10), email)
        val activity = buildActivity(eventId)
        assertEquals("Reminder:  10 min, 1 day (email) before", rowText(activity, reminderRow))

        saveAndClose(activity)

        assertEquals(listOf(EventReminder(10), email), savedReminders(eventId))
    }

    @Test
    fun `adding a reminder from the menu keeps the existing ones`() {
        val eventId = insertWithReminders(EventReminder(10))
        val activity = buildActivity(eventId)

        // "1 Add reminder", then 1 day, always the 7th preset.
        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_1)
        down(activity, KeyEvent.KEYCODE_7)
        assertEquals("Reminder:  10 min, 1 day before", rowText(activity, reminderRow))
        saveAndClose(activity)

        assertEquals(listOf(EventReminder(10), EventReminder(1440)), savedReminders(eventId))
    }

    @Test
    fun `changing a reminder keeps its method`() {
        val email = EventReminder(10, android.provider.CalendarContract.Reminders.METHOD_EMAIL)
        val eventId = insertWithReminders(email, EventReminder(1440))
        val activity = buildActivity(eventId)

        // "2 Change 10 min (email)", then the 30 minute preset (5th).
        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_2)
        down(activity, KeyEvent.KEYCODE_5)
        saveAndClose(activity)

        assertEquals(listOf(email.copy(minutes = 30), EventReminder(1440)), savedReminders(eventId))
    }

    @Test
    fun `removing a reminder drops only that one`() {
        val eventId = insertWithReminders(EventReminder(10), EventReminder(1440))
        val activity = buildActivity(eventId)

        // "3 Change 1 day", then "Remove" (after the seven presets).
        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_3)
        down(activity, KeyEvent.KEYCODE_8)
        assertEquals("Reminder:  10 min before", rowText(activity, reminderRow))
        saveAndClose(activity)

        assertEquals(listOf(EventReminder(10)), savedReminders(eventId))
    }

    @Test
    fun `new events only offer writable calendars and skip a read-only stored default`() {
        FakeCalendarProvider.putCalendar(2L, "Local", "LOCAL", displayName = "Birthdays", accessLevel = android.provider.CalendarContract.Calendars.CAL_ACCESS_READ)
        FakeCalendarProvider.putCalendar(3L, "timo@fastmail.com", "bitfire.at.davdroid", displayName = "Work")
        life.furrer.c9.core.Prefs.setDefaultCalendarId(context, 2L)
        val activity = buildActivity()
        val calendarRow = 9 // child index: the COLOR row (8) is GONE without event colors

        assertEquals("Calendar:  Calendar", rowText(activity, calendarRow))
        repeat(calendarRow - 1) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        down(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitUntil { rowText(activity, calendarRow) == "Calendar:  Work" }
        down(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitUntil { rowText(activity, calendarRow) == "Calendar:  Calendar" } // Birthdays never offered
    }

    @Test
    fun `an existing event in a read-only calendar still opens on that calendar`() {
        FakeCalendarProvider.putCalendar(2L, "Local", "LOCAL", displayName = "Birthdays", accessLevel = android.provider.CalendarContract.Calendars.CAL_ACCESS_READ)
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 2L, title = "Anna", location = null,
                startMillis = 1_800_000_000_000L, endMillis = 1_800_003_600_000L,
                allDay = false, rrule = null, reminders = emptyList(), eventColorKey = null,
            ),
        )!!
        val activity = buildActivity(eventId)

        assertEquals("Calendar:  Birthdays", rowText(activity, 9))
    }

    @Test
    fun `no reminder can be added past the calendar's MAX_REMINDERS`() {
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid", maxReminders = 1)
        val eventId = insertWithReminders(EventReminder(10))
        val activity = buildActivity(eventId)

        // The menu's first row is now "Change 10 min", not "Add reminder": pick the first preset.
        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_1)
        down(activity, KeyEvent.KEYCODE_1)

        assertEquals("Reminder:  at time of event", rowText(activity, reminderRow))
    }

    @Test
    fun `an edit scoped to this occurrence carries all the reminders to the exception`() {
        val startMillis = 1_800_000_000_000L
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Standup", location = null,
                startMillis = startMillis, endMillis = startMillis + 900_000L,
                allDay = false, rrule = "FREQ=WEEKLY", reminders = listOf(EventReminder(10), EventReminder(1440)),
                eventColorKey = null,
            ),
        )!!
        val activity = buildActivity(eventId, editScope = EditScope.THIS_INSTANCE, instanceStartMillis = startMillis + 7 * 86_400_000L)

        saveAndClose(activity)

        val exceptionId = FakeCalendarProvider.events.keys.single { it != eventId }
        assertEquals(listOf(EventReminder(10), EventReminder(1440)), savedReminders(exceptionId))
    }

    @Test
    fun `staged reminder changes survive the activity being recreated`() {
        val eventId = insertWithReminders(EventReminder(10))
        val intent = Intent(context, EventEditActivity::class.java).putExtra(EventEditActivity.EXTRA_EVENT_ID, eventId)
        val controller = Robolectric.buildActivity(EventEditActivity::class.java, intent).setup()
        awaitUntil { rowText(controller.get(), 0).contains("Standup") }
        openReminderMenu(controller.get())
        down(controller.get(), KeyEvent.KEYCODE_1)
        down(controller.get(), KeyEvent.KEYCODE_7)

        controller.recreate()
        val recreated = controller.get()
        assertEquals("Reminder:  10 min, 1 day before", rowText(recreated, reminderRow))
        saveAndClose(recreated)

        assertEquals(listOf(EventReminder(10), EventReminder(1440)), savedReminders(eventId))
    }

    @Test
    fun `changing one of two identical reminders leaves the other`() {
        val eventId = insertWithReminders(EventReminder(10), EventReminder(10))
        val activity = buildActivity(eventId)

        // "2 Change 10 min", then the 30 minute preset (5th).
        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_2)
        down(activity, KeyEvent.KEYCODE_5)
        assertEquals("Reminder:  10 min, 30 min before", rowText(activity, reminderRow))
        saveAndClose(activity)

        assertEquals(listOf(EventReminder(10), EventReminder(30)), savedReminders(eventId))
    }

    @Test
    fun `saving in the event's own calendar keeps reminders beyond its MAX_REMINDERS`() {
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid", maxReminders = 1)
        val eventId = insertWithReminders(EventReminder(10), EventReminder(1440))
        val activity = buildActivity(eventId)

        saveAndClose(activity)

        assertEquals(listOf(EventReminder(10), EventReminder(1440)), savedReminders(eventId))
    }

    @Test
    fun `a calendar that allows no reminders offers no Add`() {
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid", maxReminders = 0)
        val activity = buildActivity()

        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_1)

        assertEquals("Reminder:  none", rowText(activity, reminderRow))
    }

    private fun menuRows(activity: EventEditActivity): List<String> {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val sheet = root.getChildAt(1) as ViewGroup
        fun texts(view: View): List<String> =
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else if (view is TextView) listOf(view.text.toString()) else emptyList()
        return texts(sheet)
    }

    @Test
    fun `the Add presets keep their numbers and show what the event already has`() {
        val eventId = insertWithReminders(EventReminder(10))
        val activity = buildActivity(eventId)

        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_1)

        val rows = menuRows(activity)
        assertEquals("3 10 min before (set)", rows[2])
        assertEquals("7 1 day before", rows[6])
        // The taken one can't be picked: its key does nothing and the menu stays open.
        down(activity, KeyEvent.KEYCODE_3)
        assertEquals("Reminder:  10 min before", rowText(activity, reminderRow))
        down(activity, KeyEvent.KEYCODE_7)
        assertEquals("Reminder:  10 min, 1 day before", rowText(activity, reminderRow))
    }

    @Test
    fun `the Change presets mark the reminder's current lead time`() {
        val eventId = insertWithReminders(EventReminder(10), EventReminder(1440))
        val activity = buildActivity(eventId)

        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_2)

        val rows = menuRows(activity)
        assertEquals("3 10 min before (current)", rows[2])
        assertEquals("7 1 day before (set)", rows[6])
    }

    @Test
    fun `changing an email reminder judges taken presets by its own method`() {
        val eventId = insertWithReminders(
            EventReminder(10, Reminders.METHOD_EMAIL), EventReminder(30), EventReminder(60, Reminders.METHOD_EMAIL),
        )
        val activity = buildActivity(eventId)

        openReminderMenu(activity)
        down(activity, KeyEvent.KEYCODE_2)

        val rows = menuRows(activity)
        assertEquals("3 10 min before (current)", rows[2])
        // 30 min is only an alert, so an email one there is no duplicate; 1h is already an email.
        assertEquals("5 30 min before", rows[4])
        assertEquals("6 1h before (set)", rows[5])
    }

    // --- Show as / Privacy / Status (ticket #28) ----------------------------------------------

    // Child indices of the rows (Color is hidden here, so one fewer DPAD_DOWN reaches each).
    private val showAsRow = 10
    private val privacyRow = 11
    private val statusRow = 12

    private fun select(activity: EventEditActivity, row: Int) = repeat(row - 1) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }

    @Test
    fun `an unchanged save writes no availability, privacy or status`() {
        val eventId = insertWithReminders()
        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }
        assertEquals("Show as:   busy", rowText(activity, showAsRow))
        assertEquals("Privacy:   calendar default", rowText(activity, privacyRow))
        assertEquals("Status:    not set", rowText(activity, statusRow))
        saveAndClose(activity)
        val row = FakeCalendarProvider.events.getValue(eventId)
        assertTrue(!row.containsKey(Events.AVAILABILITY) && !row.containsKey(Events.ACCESS_LEVEL) && !row.containsKey(Events.STATUS))
    }

    @Test
    fun `Left and Right set availability, privacy and status, and only those are written`() {
        val eventId = insertWithReminders()
        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }
        select(activity, showAsRow)
        down(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Show as:   free", rowText(activity, showAsRow))
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        down(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Privacy:   public", rowText(activity, privacyRow))
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(2) { down(activity, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("Status:    tentative", rowText(activity, statusRow))
        saveAndClose(activity)

        val detail = EventStore.queryEventDetail(context, eventId)!!
        assertEquals(Events.AVAILABILITY_FREE, detail.availability)
        assertEquals(Events.ACCESS_PUBLIC, detail.accessLevel)
        assertEquals(Events.STATUS_TENTATIVE, detail.status)
    }

    @Test
    fun `a status the event came without can be cycled back to not set`() {
        val eventId = insertWithReminders()
        val activity = buildActivity(eventId)
        awaitUntil { rowText(activity, 0).contains("Standup") }
        select(activity, statusRow)
        repeat(3) { down(activity, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("Status:    not set", rowText(activity, statusRow))
    }
}
