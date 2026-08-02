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
class AgendaViewTest {

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

    private fun layout(view: AgendaView, heightPx: Int = 3000) {
        view.rootView.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.rootView.layout(0, 0, 300, heightPx)
    }

    @Test
    fun `a day with no events produces no header at all`() {
        val view = AgendaView(context)
        // Three days in the map, only the first and third have events — the empty middle day
        // (§7.5: "just the events") must not appear, not even as a placeholder or bare header.
        view.render(
            day,
            mapOf(
                day to listOf(event(1, day, 9, 10)),
                day.plusDays(1) to emptyList(),
                day.plusDays(2) to listOf(event(2, day.plusDays(2), 9, 10)),
            ),
            millisAt(day, 8),
            is24Hour = true,
        )
        layout(view)

        // Down from day one's header -> its event; Down again -> day three's header directly,
        // skipping the empty middle day entirely.
        view.onKey(NineAction.Down)
        view.onKey(NineAction.Down)
        assertEquals(CalendarViewResult.OpenDay(day.plusDays(2)), view.onKey(NineAction.Select))
    }

    @Test
    fun `selecting an event opens it`() {
        val view = AgendaView(context)
        view.render(day, mapOf(day to listOf(event(1, day, 9, 10))), millisAt(day, 8), is24Hour = true)
        layout(view)

        view.onKey(NineAction.Down)
        assertEquals(CalendarViewResult.OpenEvent(1L, millisAt(day, 9)), view.onKey(NineAction.Select))
    }

    @Test
    fun `all-empty days yield an empty view with nothing selectable`() {
        val view = AgendaView(context)
        view.render(day, mapOf(day to emptyList(), day.plusDays(1) to emptyList()), millisAt(day, 8), is24Hour = true)
        layout(view)

        assertEquals(CalendarViewResult.RollForward, view.onKey(NineAction.Down))
        assertEquals(CalendarViewResult.RollBackward, view.onKey(NineAction.Up))
    }

    @Test
    fun `days are shown in date order regardless of map iteration order`() {
        val view = AgendaView(context)
        view.render(
            day,
            mapOf(
                day.plusDays(2) to listOf(event(2, day.plusDays(2), 9, 10)),
                day to listOf(event(1, day, 9, 10)),
            ),
            millisAt(day, 8),
            is24Hour = true,
        )
        layout(view)

        assertEquals(CalendarViewResult.OpenDay(day), view.onKey(NineAction.Select))
    }

    @Test
    fun `selectedDate follows the selected event's own section, not the anchor day`() {
        val view = AgendaView(context)
        view.render(
            day,
            mapOf(day to listOf(event(1, day, 9, 10)), day.plusDays(2) to listOf(event(2, day.plusDays(2), 9, 10))),
            millisAt(day, 8),
            is24Hour = true,
        )
        layout(view)

        view.onKey(NineAction.Down) // day one's event
        assertEquals(day, view.selectedDate())
        view.onKey(NineAction.Down) // day three's header
        view.onKey(NineAction.Down) // day three's event
        assertEquals(day.plusDays(2), view.selectedDate())
    }

    @Test
    fun `selectedDate is null when there is nothing selectable at all`() {
        val view = AgendaView(context)
        view.render(day, mapOf(day to emptyList()), millisAt(day, 8), is24Hour = true)
        layout(view)

        assertEquals(null, view.selectedDate())
    }
}
