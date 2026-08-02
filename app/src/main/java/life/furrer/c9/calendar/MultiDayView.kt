package life.furrer.c9.calendar

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import life.furrer.nine.core.util.NineAction
import life.furrer.c9.core.RowBinder
import life.furrer.c9.core.RowListView

private const val DEFAULT_DAY_SPAN = 3L

/** [daySpan] days from the focused date, with a selectable [CalendarRow.DayHeader] before each
 *  day's rows (§9.2) — 3-Day and Week (§7.5: "7 days... compact") are the same view differing
 *  only in span and [compact], not a separate implementation each. OK on a header switches to
 *  Day view focused on that date. [compact] (Week) never grows the selected row for its metadata
 *  line — with seven days of events on screen at once there's less room to spend on it than
 *  3-Day has. */
class MultiDayView(
    context: Context,
    private val daySpan: Long = DEFAULT_DAY_SPAN,
    private val compact: Boolean = false,
) : CalendarView {

    private var rows: List<CalendarRow> = emptyList()
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
            val expanded = !compact && index == rowListView.selectedIndex && rows[index] is CalendarRow.EventRow
            return calendarRowHeightDp(rows[index], expanded)
        }
        override fun isSelectable(index: Int): Boolean =
            rows[index] is CalendarRow.EventRow || rows[index] is CalendarRow.DayHeader
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
        this.is24Hour = is24Hour
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()

        val built = mutableListOf<CalendarRow>()
        for (offset in 0 until daySpan) {
            val date = focusedDate.plusDays(offset)
            built.add(CalendarRow.DayHeader(date, isToday = date == today))
            built.addAll(CalendarRows.buildDayRows(date, eventsByDay[date] ?: emptyList(), nowMillis))
        }
        rows = CalendarRows.assignDisplayIndices(built)
        rowListView.notifyDataChanged()
    }

    override fun onKey(action: NineAction): CalendarViewResult = when (action) {
        NineAction.Up -> moveOrRoll(-1)
        NineAction.Down -> moveOrRoll(1)
        NineAction.Select -> {
            when (val row = rows.getOrNull(rowListView.selectedIndex)) {
                is CalendarRow.DayHeader -> CalendarViewResult.OpenDay(row.date)
                is CalendarRow.EventRow -> CalendarViewResult.OpenEvent(row.event.eventId, row.event.startMillis)
                else -> CalendarViewResult.Unhandled
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

    override fun selectedDate(): LocalDate? = CalendarRows.dateForRow(rows, rowListView.selectedIndex)

    override fun jumpToDisplayIndex(n: Int): Boolean {
        val target = rows.indexOfFirst { it is CalendarRow.EventRow && it.displayIndex == n }
        if (target == -1) return false
        while (rowListView.selectedIndex != target) {
            val before = rowListView.selectedIndex
            rowListView.moveSelection(if (target > before) 1 else -1)
            if (rowListView.selectedIndex == before) break
        }
        return rowListView.selectedIndex == target
    }
}
