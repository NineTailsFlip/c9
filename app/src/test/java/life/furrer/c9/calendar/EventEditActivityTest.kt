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

    private fun buildActivity(eventId: Long? = null, initialDate: java.time.LocalDate? = null): EventEditActivity {
        val intent = Intent(context, EventEditActivity::class.java)
        if (eventId != null) intent.putExtra(EventEditActivity.EXTRA_EVENT_ID, eventId)
        if (initialDate != null) intent.putExtra(EventEditActivity.EXTRA_INITIAL_DATE, initialDate.toEpochDay())
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
        // 9 fields total minus the hidden Color row = 8 visible rows.
        assertEquals(8, rowContainer(activity).let { container -> (0 until container.childCount).count { container.getChildAt(it).visibility == android.view.View.VISIBLE } })
    }

    @Test
    fun `the Color row appears once the calendar's account has event colors`() {
        FakeCalendarProvider.putColor("timo@fastmail.com", "bitfire.at.davdroid", "aliceblue", 0xFFF0F8FF.toInt())
        val activity = buildActivity()
        awaitUntil { rowText(activity, 7).contains("Color") }
        assertTrue(rowText(activity, 7).contains("calendar default"))
    }

    @Test
    fun `a new event defaults to the calendar and duration configured in Settings`() {
        FakeCalendarProvider.putCalendar(2L, "personal@example.com", "bitfire.at.davdroid", displayName = "Personal")
        Prefs.setDefaultCalendarId(context, 2L)
        Prefs.setDefaultEventDurationMinutes(context, 90)

        val activity = buildActivity()

        assertTrue(rowText(activity, 8).contains("Personal"))

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

        assertTrue(rowText(activity, 8).contains("Calendar"))
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

    @Test
    fun `a new event's time of day matches now regardless of which date was requested`() {
        val withoutDate = buildActivity()
        val withFutureDate = buildActivity(initialDate = java.time.LocalDate.now().plusDays(5))

        fun timeOfDay(row: String) = row.substringAfterLast(", ")
        assertEquals(timeOfDay(rowText(withoutDate, 1)), timeOfDay(rowText(withFutureDate, 1)))
    }
}
