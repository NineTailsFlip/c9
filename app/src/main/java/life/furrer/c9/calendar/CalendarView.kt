package life.furrer.c9.calendar

import android.view.View
import java.time.LocalDate
import life.furrer.nine.core.util.NineAction

sealed interface CalendarViewResult {
    data object Handled : CalendarViewResult
    data object Unhandled : CalendarViewResult
    data object RollBackward : CalendarViewResult
    data object RollForward : CalendarViewResult
    data class OpenDay(val date: LocalDate) : CalendarViewResult
    data class OpenEvent(val eventId: Long, val instanceStartMillis: Long) : CalendarViewResult
}

/** Implemented by [DayView] and [MultiDayView] — each owns its own [life.furrer.c9.core.RowListView]
 *  internally and turns (focused date, events, now) into rows, so [CalendarActivity] never touches
 *  rows directly (§5.1). */
interface CalendarView {
    val rootView: View

    fun render(focusedDate: LocalDate, eventsByDay: Map<LocalDate, List<EventEntry>>, nowMillis: Long)

    /** Up/Down/Select delegate here. Left/Right paging is handled by [CalendarActivity] itself
     *  (Day ±1 day, 3-Day ±3 days) since the page size differs per view mode. */
    fun onKey(action: NineAction): CalendarViewResult

    /** Jumps directly to the nth rendered row (§7.3's numbered indices), if one exists.
     *  @return true if a row was found and selected. */
    fun jumpToDisplayIndex(n: Int): Boolean

    /** Called right after [render] following a roll into this view from an adjacent day/page —
     *  [atStart] true lands on the first selectable row (rolled forward), false on the last
     *  (rolled backward), so browsing continues in the same direction it was already moving. */
    fun selectEdge(atStart: Boolean)

    /** The event under the current selection, if any — lets the `*` menu name its target
     *  ("Edit “Dentist”", §9.6) without duplicating row-selection state in the activity. */
    fun selectedEvent(): EventEntry?

    /** The date the current selection belongs to — a single-day view (Day) is trivially always
     *  its one day; a multi-day view (3-Day/Week/Agenda) resolves it from whichever day's section
     *  the selected row falls under. Used so "New event" lands on the day the user is actually
     *  looking at rather than always today. Null only if there's no selection to resolve at all
     *  (e.g. no rows yet), in which case callers fall back to the view's own focused/anchor date. */
    fun selectedDate(): LocalDate?
}
