package life.furrer.c9.calendar

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import life.furrer.c9.core.Action
import life.furrer.c9.core.RowBinder
import life.furrer.c9.core.RowListView

/** Rolling, unbounded per §7.5's own table — "date only when it changes; empty days skipped."
 *  Reuses [CalendarRows.buildDayRows] per day (the same isPast/gap-label-free event rows Day and
 *  3-Day already build and test) but keeps only the [CalendarRow.EventRow]s from it — Agenda is
 *  explicitly "just the events" (§7.5), with none of Day/3-Day's now-line, gap-label, or
 *  empty-day-placeholder machinery that exists specifically to "buy back" a sense of time for a
 *  bare list; a day with nothing on it simply doesn't appear at all, header included. */
class AgendaView(context: Context) : CalendarView {

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
            val expanded = index == rowListView.selectedIndex && rows[index] is CalendarRow.EventRow
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
        eventsByDay.keys.sorted().forEach { date ->
            val eventRows = CalendarRows.buildDayRows(date, eventsByDay[date] ?: emptyList(), nowMillis)
                .filterIsInstance<CalendarRow.EventRow>()
            if (eventRows.isNotEmpty()) {
                built.add(CalendarRow.DayHeader(date, isToday = date == today))
                built.addAll(eventRows)
            }
        }
        rows = CalendarRows.assignDisplayIndices(built)
        rowListView.notifyDataChanged()
    }

    override fun onKey(action: Action): CalendarViewResult = when (action) {
        Action.Up -> moveOrRoll(-1)
        Action.Down -> moveOrRoll(1)
        Action.Select -> {
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
