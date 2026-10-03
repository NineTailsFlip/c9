package life.furrer.c9.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** CalendarActivity reached from another app: `ACTION_VIEW` on an event or a time Uri. */
@RunWith(RobolectricTestRunner::class)
class CalendarActivityIntentTest {

    private val context = RuntimeEnvironment.getApplication()

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

    private fun intentFor(action: String, uri: android.net.Uri): Intent =
        Intent(action, uri).setClass(context, CalendarActivity::class.java)

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun dayHeader(date: LocalDate): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"

    @Test
    fun `VIEW on an event hands over to that event's screen and gets out of the way`() {
        grantCalendar()
        val begin = 1_800_000_000_000L
        val activity = Robolectric.buildActivity(
            CalendarActivity::class.java,
            intentFor(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, 7L))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin),
        ).setup().get()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(EventDetailActivity::class.java.name, started.component?.className)
        assertEquals(7L, started.getLongExtra(EventDetailActivity.EXTRA_EVENT_ID, -1L))
        assertEquals(begin, started.getLongExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, -1L))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `VIEW on an event waits for calendar permission first`() {
        val controller = Robolectric.buildActivity(
            CalendarActivity::class.java,
            intentFor(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, 7L)),
        ).setup()
        assertNull(shadowOf(controller.get()).nextStartedActivity)

        grantCalendar()
        controller.pause().resume()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals(7L, started.getLongExtra(EventDetailActivity.EXTRA_EVENT_ID, -1L))
        assertFalse("no occurrence named", started.hasExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS))
    }

    @Test
    fun `VIEW on a time opens the calendar on that date, also when already open`() {
        grantCalendar()
        val zone = ZoneId.systemDefault()
        val first = LocalDate.of(2026, 12, 24)
        val firstUri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .appendPath(first.atTime(15, 0).atZone(zone).toInstant().toEpochMilli().toString()).build()
        val controller = Robolectric.buildActivity(CalendarActivity::class.java, intentFor(Intent.ACTION_VIEW, firstUri)).setup()
        val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
        awaitUntil { texts(root).contains(dayHeader(first)) }
        assertFalse(controller.get().isFinishing)

        val second = LocalDate.of(2027, 1, 5)
        val secondUri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .appendPath(second.atStartOfDay(zone).toInstant().toEpochMilli().toString()).build()
        // The framework pauses a singleTop activity before onNewIntent and resumes it after.
        controller.pause().newIntent(intentFor(Intent.ACTION_VIEW, secondUri)).resume()
        awaitUntil { texts(root).contains(dayHeader(second)) }
    }
}
