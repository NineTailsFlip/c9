package life.furrer.c9.calendar

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import java.time.LocalDate
import life.furrer.c9.core.Action
import life.furrer.c9.core.RowBinder
import life.furrer.c9.core.RowListView

/** One day: all-day band, then timed events with gap labels and a now-line, no headers.
 *  Rolls to the adjacent day when Up/Down exhausts the day's own rows at either end (§7.3). */
class DayView(context: Context) : CalendarView {

    private var day: LocalDate = LocalDate.now()
    private var rows: List<CalendarRow> = listOf(CalendarRow.EmptyDay(day))
    private var is24Hour = true

    private val rowListView = RowListView(context)

    override val rootView: View get() = rowListView

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = createCalendarRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            bindCalendarRow(view as LinearLayout, rows[index], selected, is24Hour)
        }
        override fun rowHeight(index: Int): Int {
            val expanded = index == rowListView.selectedIndex && rows[index] is CalendarRow.EventRow
            return calendarRowHeightDp(rows[index], expanded)
        }
        override fun isSelectable(index: Int): Boolean = rows[index] is CalendarRow.EventRow
    }

    init {
        rowListView.setBinder(binder)
    }

    override fun render(
        focusedDate: LocalDate,
        eventsByDay: Map<LocalDate, List<EventEntry>>,
        nowMillis: Long,
        is24Hour: Boolean,
    ) {
        day = focusedDate
        this.is24Hour = is24Hour
        val events = eventsByDay[focusedDate] ?: emptyList()
        rows = CalendarRows.assignDisplayIndices(CalendarRows.buildDayRows(focusedDate, events, nowMillis))
        rowListView.notifyDataChanged()
    }

    override fun onKey(action: Action): CalendarViewResult = when (action) {
        Action.Up -> moveOrRoll(-1)
        Action.Down -> moveOrRoll(1)
        Action.Select -> {
            val row = rows.getOrNull(rowListView.selectedIndex)
            if (row is CalendarRow.EventRow) {
                CalendarViewResult.OpenEvent(row.event.eventId, row.event.startMillis)
            } else {
                CalendarViewResult.Unhandled
            }
        }
        else -> CalendarViewResult.Unhandled
    }

    private fun moveOrRoll(direction: Int): CalendarViewResult = if (rowListView.canMoveSelection(direction)) {
        rowListView.moveSelection(direction)
        CalendarViewResult.Handled
    } else if (direction < 0) {
        CalendarViewResult.RollBackward
    } else {
        CalendarViewResult.RollForward
    }

    override fun selectEdge(atStart: Boolean) {
        if (atStart) rowListView.selectFirstSelectable() else rowListView.selectLastSelectable()
    }

    override fun selectedEvent(): EventEntry? = (rows.getOrNull(rowListView.selectedIndex) as? CalendarRow.EventRow)?.event

    override fun selectedDate(): LocalDate = day

    override fun jumpToDisplayIndex(n: Int): Boolean {
        val target = rows.indexOfFirst { it is CalendarRow.EventRow && it.displayIndex == n }
        if (target == -1) return false
        while (rowListView.selectedIndex != target) {
            val before = rowListView.selectedIndex
            rowListView.moveSelection(if (target > before) 1 else -1)
            if (rowListView.selectedIndex == before) break // shouldn't happen; guards against a stall
        }
        return rowListView.selectedIndex == target
    }
}
