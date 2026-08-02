package life.furrer.c9.calendar

import android.view.View
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import life.furrer.nine.core.util.NineAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MultiDayViewTest {

    private val context = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 7, 30)
    private val zone = ZoneId.systemDefault()

    private fun millisAt(date: LocalDate, hour: Int): Long =
        date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private fun event(id: Long, date: LocalDate, startHour: Int, endHour: Int): EventEntry = EventEntry(
        eventId = id,
        title = "Event $id",
        startMillis = millisAt(date, startHour),
        endMillis = millisAt(date, endHour),
        allDay = false,
        location = null,
        calendarId = 1,
        displayColor = 0xFFFF0000.toInt(),
        rrule = null,
    )

    private fun layout(view: MultiDayView, heightPx: Int = 3000) {
        view.rootView.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.rootView.layout(0, 0, 300, heightPx)
    }

    @Test
    fun `selecting the first day header opens that day`() {
        val view = MultiDayView(context)
        view.render(day, emptyMap(), millisAt(day, 8), is24Hour = true)
        layout(view)

        // No events on any of the 3 days, so every row is a header or an empty-day placeholder —
        // the initial selection lands on the first (and only selectable) row: day one's header.
        assertEquals(CalendarViewResult.OpenDay(day), view.onKey(NineAction.Select))
    }

    @Test
    fun `up from the first header rolls backward`() {
        val view = MultiDayView(context)
        view.render(day, emptyMap(), millisAt(day, 8), is24Hour = true)
        layout(view)

        assertEquals(CalendarViewResult.RollBackward, view.onKey(NineAction.Up))
    }

    @Test
    fun `down past the third day's header rolls forward`() {
        val view = MultiDayView(context)
        view.render(day, emptyMap(), millisAt(day, 8), is24Hour = true)
        layout(view)

        // Only the 3 day headers are selectable (every day is empty); two Downs reach the third.
        view.onKey(NineAction.Down)
        view.onKey(NineAction.Down)
        assertEquals(CalendarViewResult.RollForward, view.onKey(NineAction.Down))
    }

    @Test
    fun `selecting the second day's header opens that day, not the first`() {
        val view = MultiDayView(context)
        view.render(day, emptyMap(), millisAt(day, 8), is24Hour = true)
        layout(view)

        view.onKey(NineAction.Down)
        assertEquals(CalendarViewResult.OpenDay(day.plusDays(1)), view.onKey(NineAction.Select))
    }

    @Test
    fun `selecting an event row opens that event`() {
        val view = MultiDayView(context)
        view.render(day, mapOf(day to listOf(event(1, day, 9, 10))), millisAt(day, 8), is24Hour = true)
        layout(view)

        // Down from day one's header lands on its event row.
        view.onKey(NineAction.Down)
        assertEquals(CalendarViewResult.OpenEvent(1L, millisAt(day, 9)), view.onKey(NineAction.Select))
    }

    @Test
    fun `daySpan controls how many day headers are built, for Week`() {
        val view = MultiDayView(context, daySpan = 7L)
        view.render(day, emptyMap(), millisAt(day, 8), is24Hour = true)
        layout(view)

        // 7 empty days -> 7 selectable headers, index 0..6; 5 Downs from the first (index 0)
        // reaches the sixth (index 5, day+5) — proving there are more than the default 3.
        repeat(5) { view.onKey(NineAction.Down) }
        assertEquals(CalendarViewResult.OpenDay(day.plusDays(5)), view.onKey(NineAction.Select))
    }

    @Test
    fun `daySpan defaults to 3 when not specified`() {
        val view = MultiDayView(context)
        view.render(day, emptyMap(), millisAt(day, 8), is24Hour = true)
        layout(view)

        view.onKey(NineAction.Down)
        view.onKey(NineAction.Down)
        assertEquals(CalendarViewResult.RollForward, view.onKey(NineAction.Down))
    }

    @Test
    fun `selectedDate follows the selected row's own day, not the view's anchor day`() {
        val view = MultiDayView(context)
        view.render(day, emptyMap(), millisAt(day, 8), is24Hour = true)
        layout(view)

        assertEquals(day, view.selectedDate())
        view.onKey(NineAction.Down) // second day's header
        assertEquals(day.plusDays(1), view.selectedDate())
    }

    @Test
    fun `selectedDate follows an event row's own day section too`() {
        val view = MultiDayView(context)
        view.render(day, mapOf(day.plusDays(1) to listOf(event(1, day.plusDays(1), 9, 10))), millisAt(day, 8), is24Hour = true)
        layout(view)

        view.onKey(NineAction.Down) // second day's header
        view.onKey(NineAction.Down) // second day's event row
        assertEquals(day.plusDays(1), view.selectedDate())
    }
}
