package life.furrer.c9.calendar

import android.content.Intent
import android.os.Looper
import android.provider.CalendarContract.Events
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.core.Prefs
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

    /** Simulates the framework delivering an AttendeePickerActivity result — onActivityResult
     *  itself is protected (inherited from Activity), so it can't be called directly from here. */
    private fun deliverAttendeePickerResult(activity: EventEditActivity, email: String, name: String?) {
        shadowOf(activity).receiveResult(
            Intent(activity, AttendeePickerActivity::class.java),
            android.app.Activity.RESULT_OK,
            Intent().apply {
                putExtra(AttendeePickerActivity.EXTRA_EMAIL, email)
                putExtra(AttendeePickerActivity.EXTRA_NAME, name)
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
                reminderMinutes = null,
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
                reminderMinutes = null,
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
        // 10 fields total minus the hidden Color row = 9 visible rows.
        assertEquals(9, rowContainer(activity).let { container -> (0 until container.childCount).count { container.getChildAt(it).visibility == android.view.View.VISIBLE } })
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
                allDay = false, rrule = null, reminderMinutes = null, eventColorKey = null,
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
                allDay = false, rrule = "FREQ=WEEKLY", reminderMinutes = null, eventColorKey = null,
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
        // The new exception carries the full staged set (the kept original plus the newly added
        // one), since it started with zero attendees of its own.
        assertEquals(
            setOf("alice@acme.ch", "bob@acme.ch"),
            EventStore.queryAttendees(context, exceptionId, calendarId = 1L).map { it.email }.toSet(),
        )
    }

    @Test
    fun `a new event's time of day matches now regardless of which date was requested`() {
        val withoutDate = buildActivity()
        val withFutureDate = buildActivity(initialDate = java.time.LocalDate.now().plusDays(5))

        fun timeOfDay(row: String) = row.substringAfterLast(", ")
        assertEquals(timeOfDay(rowText(withoutDate, 1)), timeOfDay(rowText(withFutureDate, 1)))
    }
}
