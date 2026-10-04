package life.furrer.c9.calendar

import android.Manifest
import android.content.Intent
import android.os.Looper
import android.provider.CalendarContract.Reminders
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import life.furrer.nine.core.util.DateFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The detail screen against [FakeCalendarProvider]. */
@RunWith(RobolectricTestRunner::class)
class EventDetailActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid")
    }

    private fun texts(view: View): List<String> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view.text.toString()) else emptyList()

    private fun awaitText(activity: EventDetailActivity, part: String): List<String> {
        val deadline = System.currentTimeMillis() + 3000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val all = texts(activity.findViewById(android.R.id.content))
            if (all.any { it.contains(part) } || System.currentTimeMillis() > deadline) return all
            Thread.sleep(5)
        }
    }

    @Test
    fun `the detail screen lists every reminder, naming a method that isn't an alert`() {
        val start = 1_800_000_000_000L
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Standup", location = null,
                startMillis = start, endMillis = start + 900_000L, allDay = false, rrule = null,
                reminders = listOf(EventReminder(1), EventReminder(10), EventReminder(60), EventReminder(1440, Reminders.METHOD_EMAIL)),
                eventColorKey = null,
            ),
        )!!
        val intent = Intent(context, EventDetailActivity::class.java)
            .putExtra(EventDetailActivity.EXTRA_EVENT_ID, eventId)
            .putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, start)
        val activity = Robolectric.buildActivity(EventDetailActivity::class.java, intent).setup().get()

        val all = awaitText(activity, "1 day before")
        assertTrue(all.toString(), "REMINDERS" in all)
        assertTrue(all.toString(), "1 minute before\n10 minutes before\n1 hour before\n1 day before (email)" in all)
    }

    @Test
    fun `after editing one occurrence the screen shows the exception, not the series`() {
        val start = 1_800_000_000_000L
        val seriesId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Standup", location = null,
                startMillis = start, endMillis = start + 900_000L, allDay = false, rrule = "FREQ=WEEKLY",
                reminders = emptyList(), eventColorKey = null,
            ),
        )!!
        val occurrence = start + 7 * 86_400_000L
        val intent = Intent(context, EventDetailActivity::class.java)
            .putExtra(EventDetailActivity.EXTRA_EVENT_ID, seriesId)
            .putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, occurrence)
        val controller = Robolectric.buildActivity(EventDetailActivity::class.java, intent).setup()
        awaitText(controller.get(), "Standup")

        val exceptionId = EventStore.updateEvent(
            context, seriesId, occurrence, EditScope.THIS_INSTANCE,
            EventDraft(
                calendarId = 1L, title = "Standup (moved)", location = null,
                startMillis = occurrence + 3_600_000L, endMillis = occurrence + 4_500_000L, allDay = false, rrule = null,
                reminders = emptyList(), eventColorKey = null,
            ),
        )!!
        // Stands in for Edit -> "This event": the editor started for a result, then handing back
        // the row it saved to, which the framework delivers before onResume.
        val editor = Intent(controller.get(), EventEditActivity::class.java)
        controller.get().startActivityForResult(editor, 1)
        controller.pause()
        shadowOf(controller.get()).receiveResult(
            editor,
            android.app.Activity.RESULT_OK,
            Intent().putExtra(EventEditActivity.EXTRA_RESULT_EVENT_ID, exceptionId),
        )
        controller.resume()

        val all = awaitText(controller.get(), "Standup (moved)")
        assertTrue(all.toString(), "Standup (moved)" in all)
        val expectedTime = DateFmt.formatTime(occurrence + 3_600_000L)
        assertTrue(all.toString(), all.any { it.contains(expectedTime) })
    }

    @Test
    fun `the calendar section is the very bottom one`() {
        val start = 1_800_000_000_000L
        val eventId = EventStore.insertEvent(
            context,
            EventDraft(
                calendarId = 1L, title = "Standup", location = "Room 4",
                startMillis = start, endMillis = start + 900_000L, allDay = false, rrule = "FREQ=WEEKLY",
                reminders = listOf(EventReminder(10)), eventColorKey = null, description = "Agenda",
            ),
        )!!
        val intent = Intent(context, EventDetailActivity::class.java)
            .putExtra(EventDetailActivity.EXTRA_EVENT_ID, eventId)
            .putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, start)
        val activity = Robolectric.buildActivity(EventDetailActivity::class.java, intent).setup().get()

        val all = awaitText(activity, "CALENDAR")
        val headings = listOf("WHERE", "NOTES", "REPEATS", "REMINDER", "CALENDAR")
        assertEquals(all.toString(), headings, all.filter { it in headings })
    }
}
