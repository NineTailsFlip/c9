package life.furrer.c9.calendar

import android.view.View
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import life.furrer.nine.core.util.NineAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DayViewTest {

    private val context = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 7, 30)
    private val zone = ZoneId.systemDefault()

    private fun millisAt(hour: Int): Long = day.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private fun event(id: Long, startHour: Int, endHour: Int): EventEntry = EventEntry(
        eventId = id,
        title = "Event $id",
        startMillis = millisAt(startHour),
        endMillis = millisAt(endHour),
        allDay = false,
        location = null,
        calendarId = 1,
        displayColor = 0xFFFF0000.toInt(),
        rrule = null,
    )

    private fun layout(view: DayView, heightPx: Int = 2000) {
        view.rootView.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.rootView.layout(0, 0, 300, heightPx)
    }

    @Test
    fun `up at the first event rolls backward instead of stopping`() {
        val view = DayView(context)
        view.render(day, mapOf(day to listOf(event(1, 9, 10), event(2, 12, 13))), millisAt(8), is24Hour = true)
        layout(view)

        assertEquals(CalendarViewResult.RollBackward, view.onKey(NineAction.Up))
    }

    @Test
    fun `down at the last event rolls forward instead of stopping`() {
        val view = DayView(context)
        view.render(day, mapOf(day to listOf(event(1, 9, 10), event(2, 12, 13))), millisAt(8), is24Hour = true)
        layout(view)

        view.onKey(NineAction.Down) // move from event 1 onto event 2
        assertEquals(CalendarViewResult.RollForward, view.onKey(NineAction.Down))
    }

    @Test
    fun `jumpToDisplayIndex selects the matching numbered row`() {
        val view = DayView(context)
        view.render(day, mapOf(day to listOf(event(1, 9, 10), event(2, 12, 13))), millisAt(8), is24Hour = true)
        layout(view)

        assertTrue(view.jumpToDisplayIndex(2))
    }

    @Test
    fun `jumpToDisplayIndex fails for a number with no matching row`() {
        val view = DayView(context)
        view.render(day, mapOf(day to listOf(event(1, 9, 10))), millisAt(8), is24Hour = true)
        layout(view)

        assertTrue(!view.jumpToDisplayIndex(5))
    }

    @Test
    fun `Select on an event row opens that event`() {
        val view = DayView(context)
        view.render(day, mapOf(day to listOf(event(1, 9, 10))), millisAt(8), is24Hour = true)
        layout(view)

        assertEquals(CalendarViewResult.OpenEvent(1L, millisAt(9)), view.onKey(NineAction.Select))
    }

    @Test
    fun `selectedDate is always the one day this view is showing`() {
        val view = DayView(context)
        view.render(day, mapOf(day to listOf(event(1, 9, 10))), millisAt(8), is24Hour = true)
        layout(view)

        assertEquals(day, view.selectedDate())
    }
}
