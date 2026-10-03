package life.furrer.c9.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** EventEditActivity reached from another app: `ACTION_INSERT` ("Add to calendar") and
 *  `ACTION_EDIT` on an event Uri, against [FakeCalendarProvider] like EventEditActivityTest. */
@RunWith(RobolectricTestRunner::class)
class EventEditActivityIntentTest {

    private val context = RuntimeEnvironment.getApplication()
    private val begin = 1_800_000_000_000L

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid")
    }

    private fun grantCalendar() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun start(intent: Intent): EventEditActivity =
        Robolectric.buildActivity(EventEditActivity::class.java, intent.setClass(context, EventEditActivity::class.java)).setup().get()

    private fun rowText(activity: EventEditActivity, index: Int): String {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val rows = ((root.getChildAt(0) as LinearLayout).getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        return (rows.getChildAt(index) as TextView).text.toString()
    }

    private fun back(activity: EventEditActivity) {
        activity.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
    }

    private fun insertIntent(): Intent = Intent(Intent.ACTION_INSERT, Events.CONTENT_URI)
        .putExtra(Events.TITLE, "Concert")
        .putExtra(Events.DESCRIPTION, "Doors at 19:00")
        .putExtra(Events.EVENT_LOCATION, "Hallenstadion")
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 7_200_000L)
        .putExtra(Intent.EXTRA_EMAIL, "anna@example.com,ben@example.com")

    @Test
    fun `Add to calendar prefills the editor and saves every field, description included`() {
        grantCalendar()
        val activity = start(insertIntent())
        awaitUntil { rowText(activity, 0).contains("Concert") }

        assertTrue(rowText(activity, 5).contains("Hallenstadion"))
        assertTrue(rowText(activity, 6).startsWith("Attendees: 2\n"))
        assertTrue(rowText(activity, 6).contains("ben@example.com"))

        back(activity)
        awaitUntil { FakeCalendarProvider.events.isNotEmpty() && FakeCalendarProvider.attendees.size == 2 }

        val (id, row) = FakeCalendarProvider.events.entries.single()
        assertEquals("Concert", row.getAsString(Events.TITLE))
        assertEquals("Doors at 19:00", row.getAsString(Events.DESCRIPTION))
        assertEquals("Hallenstadion", row.getAsString(Events.EVENT_LOCATION))
        assertEquals(begin, row.getAsLong(Events.DTSTART))
        assertEquals(begin + 7_200_000L, row.getAsLong(Events.DTEND))
        assertEquals(
            setOf("anna@example.com", "ben@example.com"),
            FakeCalendarProvider.attendees.values.filter { it.getAsLong(Attendees.EVENT_ID) == id }.map { it.getAsString(Attendees.ATTENDEE_EMAIL) }.toSet(),
        )
        awaitUntil { activity.isFinishing }
    }

    @Test
    fun `Add to calendar keeps the caller's full repeat rule`() {
        grantCalendar()
        val activity = start(insertIntent().putExtra(Events.RRULE, "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10"))
        awaitUntil { rowText(activity, 0).contains("Concert") }

        back(activity)
        awaitUntil { FakeCalendarProvider.events.isNotEmpty() }

        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10", FakeCalendarProvider.events.values.single().getAsString(Events.RRULE))
    }

    @Test
    fun `an all-day insert is stored on UTC midnights`() {
        grantCalendar()
        val day = LocalDate.of(2026, 10, 3)
        val localMidnight = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val activity = start(
            Intent(Intent.ACTION_INSERT)
                .setType("vnd.android.cursor.dir/event")
                .putExtra(Events.TITLE, "Holiday")
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, localMidnight)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true),
        )
        awaitUntil { rowText(activity, 0).contains("Holiday") }
        assertTrue(rowText(activity, 3).contains("yes"))

        back(activity)
        awaitUntil { FakeCalendarProvider.events.isNotEmpty() }

        val row = FakeCalendarProvider.events.values.single()
        assertEquals(1, row.getAsInteger(Events.ALL_DAY))
        assertEquals(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), row.getAsLong(Events.DTSTART))
        assertEquals(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), row.getAsLong(Events.DTEND))
    }

    @Test
    fun `without calendar permission the request is asked for first, and the prefill survives it`() {
        val activity = start(insertIntent())

        val requested = shadowOf(activity).lastRequestedPermission
        assertTrue(requested.requestedPermissions.contains(Manifest.permission.WRITE_CALENDAR))
        assertEquals("", rowText(activity, 0))

        grantCalendar()
        activity.onRequestPermissionsResult(
            requested.requestCode,
            requested.requestedPermissions,
            IntArray(requested.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
        )
        awaitUntil { rowText(activity, 0).contains("Concert") }
    }

    @Test
    fun `a denied permission leaves without writing anything`() {
        val activity = start(insertIntent())
        val requested = shadowOf(activity).lastRequestedPermission
        activity.onRequestPermissionsResult(
            requested.requestCode,
            requested.requestedPermissions,
            IntArray(requested.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        assertTrue(activity.isFinishing)
        assertTrue(FakeCalendarProvider.events.isEmpty())
    }

    @Test
    fun `EDIT on an event Uri opens that event, and an external insert ignores the in-app event id`() {
        grantCalendar()
        val id = EventStore.insertEvent(
            context,
            EventDraft(1L, "Dentist", "Bahnhofstrasse 12", begin, begin + 2_700_000L, false, null, null, null),
        )!!

        val edit = start(Intent(Intent.ACTION_EDIT, ContentUris.withAppendedId(Events.CONTENT_URI, id)))
        awaitUntil { rowText(edit, 0).contains("Dentist") }
        assertTrue(rowText(edit, 5).contains("Bahnhofstrasse 12"))

        val insert = start(insertIntent().putExtra(EventEditActivity.EXTRA_EVENT_ID, id))
        awaitUntil { rowText(insert, 0).contains("Concert") }
    }
}
