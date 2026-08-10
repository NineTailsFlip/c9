package life.furrer.c9.calendar

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.nine.core.util.DateFmt
import life.furrer.nine.core.ui.Palette

const val CALENDAR_ROW_HEIGHT_DP = 32
const val CALENDAR_ROW_EXPANDED_HEIGHT_DP = 53
private const val GAP_ROW_HEIGHT_DP = 20
private const val NOW_LINE_HEIGHT_DP = 20
private const val HEADER_ROW_HEIGHT_DP = 24
private const val EMPTY_ROW_HEIGHT_DP = 32

private const val IDX_WIDTH_DP = 12
private const val BAR_WIDTH_DP = 3
private const val TIME_WIDTH_DP = 50
private const val ELEMENT_GAP_DP = 7
private const val ROW_SIDE_PADDING_DP = 16
private const val META_LEFT_PADDING_DP = 55

private val DAY_HEADER_FORMAT = DateTimeFormatter.ofPattern("d")

/** Row height in dp for [row] — every row is [CALENDAR_ROW_HEIGHT_DP] except the selected event
 *  row, which grows to [CALENDAR_ROW_EXPANDED_HEIGHT_DP] to fit its metadata line (§5.2/§8).
 *  [expanded] is true only for a selected [CalendarRow.EventRow]. */
fun calendarRowHeightDp(row: CalendarRow, expanded: Boolean): Int = when (row) {
    is CalendarRow.EventRow -> if (expanded) CALENDAR_ROW_EXPANDED_HEIGHT_DP else CALENDAR_ROW_HEIGHT_DP
    is CalendarRow.GapLabel -> GAP_ROW_HEIGHT_DP
    is CalendarRow.NowLine -> NOW_LINE_HEIGHT_DP
    is CalendarRow.DayHeader -> HEADER_ROW_HEIGHT_DP
    is CalendarRow.EmptyDay -> EMPTY_ROW_HEIGHT_DP
}

/** A blank vertical container [RowBinder.createRowView] hands back — [bindCalendarRow] rebuilds
 *  its children on every bind rather than trying to keep one fixed sub-element structure shared
 *  across every row kind (event/gap/now-line/header/empty all look very different). At the
 *  handful of rows a calendar screen ever has bound at once, rebuilding a few child TextViews
 *  per bind is negligible — nothing like the 2,000-row scale that made RowListView's own view
 *  recycling worth building in the first place. */
fun createCalendarRowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_VERTICAL
}

/** [expanded] must match whatever height the row's [RowBinder.rowHeight] gave this row — it
 *  governs whether the metadata line gets added at all, independently of [selected]'s styling
 *  (title color/underline). They coincide everywhere except [MultiDayView]'s `compact` (Week)
 *  mode, which keeps every row at the same height even when selected (§7.5/§9.2) — passing plain
 *  [selected] there would still add the metadata line into a container with no extra height
 *  budget for it, squeezing the whole row into an overlapping mess. */
fun bindCalendarRow(container: LinearLayout, row: CalendarRow, selected: Boolean, expanded: Boolean = selected) {
    container.removeAllViews()
    val context = container.context
    when (row) {
        is CalendarRow.EventRow -> bindEventRow(container, row, selected, expanded)
        is CalendarRow.GapLabel -> container.addView(centeredText(context, row.label, Palette.GRAY_FAINT))
        is CalendarRow.NowLine -> bindNowLine(container)
        is CalendarRow.DayHeader -> bindDayHeader(container, row, selected)
        is CalendarRow.EmptyDay -> container.addView(centeredText(context, "— nothing —", Palette.GRAY_FAINT))
    }
}

private fun bindEventRow(container: LinearLayout, row: CalendarRow.EventRow, selected: Boolean, expanded: Boolean) {
    val context = container.context
    val event = row.event
    val titleColor = when {
        selected -> Color.WHITE
        row.isPast -> Palette.GRAY_FAINT
        else -> Palette.GRAY_DIM
    }
    val timeColor = when {
        selected -> Palette.GRAY_DIM
        row.isPast -> 0xFF3A3A3A.toInt()
        else -> Palette.GRAY_MUTED
    }

    val topLine = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, ROW_SIDE_PADDING_DP), 0, dp(context, ROW_SIDE_PADDING_DP), 0)
    }

    val indexText = row.displayIndex?.toString() ?: ""
    topLine.addView(
        TextView(context).apply {
            text = indexText
            textSize = 13f
            maxLines = 1
            setTextColor(Palette.GRAY_FAINT)
        },
        marginEndLayoutParams(context, dp(context, IDX_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val bar = View(context).apply {
        setBackgroundColor(Palette.pastelize(event.displayColor))
        alpha = if (row.isPast) 0.45f else 1f
    }
    topLine.addView(bar, marginEndLayoutParams(context, dp(context, BAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))

    val timeLabel = if (event.allDay) "all day" else DateFmt.formatTime(event.startMillis)
    topLine.addView(
        TextView(context).apply {
            text = timeLabel
            textSize = 13f
            maxLines = 1
            minWidth = dp(context, TIME_WIDTH_DP)
            setTextColor(timeColor)
        },
        marginEndLayoutParams(context, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    topLine.addView(
        TextView(context).apply {
            text = event.title
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(titleColor)
            paintFlags = if (selected) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )

    container.addView(topLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    if (expanded) {
        val metaParts = listOfNotNull(
            if (!event.allDay) DateFmt.formatDuration(event.endMillis - event.startMillis) else null,
            event.location?.takeIf { it.isNotBlank() },
        )
        if (metaParts.isNotEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = metaParts.joinToString(" · ")
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Palette.GRAY_MUTED)
                    setPadding(dp(context, META_LEFT_PADDING_DP), 0, dp(context, ROW_SIDE_PADDING_DP), dp(context, 4))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }
}

private fun bindNowLine(container: LinearLayout) {
    val context = container.context
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, ROW_SIDE_PADDING_DP), 0, dp(context, ROW_SIDE_PADDING_DP), 0)
    }
    row.addView(
        TextView(context).apply {
            text = DateFmt.formatTime(System.currentTimeMillis())
            textSize = 13f
            setTextColor(Palette.GREEN)
        },
        marginEndLayoutParams(context, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    row.addView(
        View(context).apply { setBackgroundColor(Palette.GREEN) },
        LinearLayout.LayoutParams(0, dp(context, 1), 1f),
    )
    container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

private fun bindDayHeader(container: LinearLayout, row: CalendarRow.DayHeader, selected: Boolean) {
    val context = container.context
    val label = if (row.isToday) {
        "TODAY · ${row.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${row.date.format(DAY_HEADER_FORMAT)}"
    } else {
        "${row.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${row.date.format(DAY_HEADER_FORMAT)}"
    }
    val text = TextView(context).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.11f
        setPadding(dp(context, ROW_SIDE_PADDING_DP), 0, dp(context, ROW_SIDE_PADDING_DP), 0)
        if (selected) {
            setTextColor(Color.WHITE)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            setTextColor(Palette.GRAY_FAINT)
        }
    }
    container.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

private fun centeredText(context: Context, text: String, color: Int): TextView = TextView(context).apply {
    this.text = text
    textSize = 13f
    setTextColor(color)
    gravity = Gravity.CENTER
}

private fun marginEndLayoutParams(context: Context, width: Int, height: Int): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height).apply { marginEnd = dp(context, ELEMENT_GAP_DP) }

private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
).toInt()
