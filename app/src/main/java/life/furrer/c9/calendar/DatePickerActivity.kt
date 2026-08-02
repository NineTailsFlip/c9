package life.furrer.c9.calendar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.util.DateFmt
import life.furrer.c9.core.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.c9.core.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar

private const val GRID_ROWS = 6
private const val GRID_COLS = 7
private const val CELL_SIZE_DP = 34
private val OUTSIDE_MONTH_COLOR = 0xFF2E2E2E.toInt()

private enum class Focus { GRID, QUICK_TOMORROW, QUICK_NEXT_MONDAY }

/** The month-grid date picker from §9.5, opened by holding `0`. Returns the chosen date as an
 *  epoch day via [EXTRA_EPOCH_DAY] on RESULT_OK. */
class DatePickerActivity : Activity() {

    private var visibleMonth: YearMonth = YearMonth.now()
    private var cursorDate: LocalDate = LocalDate.now()
    private var gridStart: LocalDate = LocalDate.now()
    private var eventDates: Set<LocalDate> = emptySet()
    private var typedDigits: String = ""
    private var focus: Focus = Focus.GRID

    private lateinit var titleView: TextView
    private val cellViews = mutableListOf<TextView>()
    private lateinit var jumpToView: TextView
    private lateinit var quickTomorrowView: TextView
    private lateinit var quickNextMondayView: TextView
    private lateinit var hintBar: HintBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        cursorDate = LocalDate.now()
        visibleMonth = YearMonth.from(cursorDate)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        titleView = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE) }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val weekdayHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        DayOfWeek.values().forEach { dow ->
            weekdayHeader.addView(
                TextView(this).apply {
                    text = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2).uppercase()
                    textSize = 11f
                    setTextColor(Palette.GRAY_FAINT)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        root.addView(weekdayHeader)

        val gridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(GRID_ROWS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(GRID_COLS) {
                val cell = TextView(this).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER
                }
                cellViews.add(cell)
                row.addView(cell, LinearLayout.LayoutParams(0, dp(CELL_SIZE_DP), 1f))
            }
            gridContainer.addView(row)
        }
        root.addView(gridContainer)

        val rule = View(this).apply { setBackgroundColor(Palette.DIVIDER) }
        root.addView(
            rule,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )

        jumpToView = TextView(this).apply {
            textSize = 16f
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        root.addView(jumpToView)

        root.addView(
            TextView(this).apply {
                text = "QUICK"
                textSize = 11f
                letterSpacing = 0.11f
                setTextColor(Palette.GRAY_FAINT)
                setPadding(dp(16), dp(10), dp(16), dp(2))
            },
        )

        quickTomorrowView = TextView(this).apply { textSize = 16f; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        root.addView(quickTomorrowView)
        quickNextMondayView = TextView(this).apply { textSize = 16f; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        root.addView(quickNextMondayView)

        hintBar = HintBar(this)
        val scroll = ScrollView(this).apply {
            addView(root)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        setContentView(outer)
        hideStatusBar()

        loadMonthEvents()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun loadMonthEvents() {
        gridStart = firstGridDate(visibleMonth)
        val zone = ZoneId.systemDefault()
        val startMillis = gridStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = gridStart.plusDays((GRID_ROWS * GRID_COLS).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        Async.run(
            background = {
                if (!EventStore.hasPermission(this)) {
                    emptySet()
                } else {
                    val selectedIds = CalendarStore.getSelectedCalendarIds(this)
                    EventStore.queryRange(this, startMillis, endMillis, selectedIds).map { event ->
                        if (event.allDay) {
                            DateFmt.allDayToLocalDate(event.startMillis)
                        } else {
                            Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate()
                        }
                    }.toSet()
                }
            },
            onResult = { dates ->
                eventDates = dates
                render()
            },
        )
    }

    private fun firstGridDate(month: YearMonth): LocalDate {
        val firstOfMonth = month.atDay(1)
        val daysFromMonday = (firstOfMonth.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return firstOfMonth.minusDays(daysFromMonday.toLong())
    }

    private fun render() {
        titleView.text = "${visibleMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${visibleMonth.year}"

        val today = LocalDate.now()
        for (i in 0 until GRID_ROWS * GRID_COLS) {
            val date = gridStart.plusDays(i.toLong())
            val cell = cellViews[i]
            val isToday = date == today
            val isSelected = focus == Focus.GRID && date == cursorDate
            val outsideMonth = YearMonth.from(date) != visibleMonth
            cell.text = if (date in eventDates) "${date.dayOfMonth}·" else "${date.dayOfMonth}"
            val color = when {
                isToday -> Palette.GREEN
                outsideMonth -> OUTSIDE_MONTH_COLOR
                else -> Palette.GRAY_DIM
            }
            cell.setTextColor(if (isSelected && !isToday) Color.WHITE else color)
            cell.paintFlags = if (isSelected) {
                cell.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                cell.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }

        jumpToView.text = "Jump to:   ${formatJumpTo()}"
        applyRowFocusStyle(jumpToView, selected = false)

        val tomorrow = today.plusDays(1)
        quickTomorrowView.text = "Tomorrow · ${formatQuickDate(tomorrow)}"
        applyRowFocusStyle(quickTomorrowView, focus == Focus.QUICK_TOMORROW)

        val nextMonday = nextMonday(today)
        quickNextMondayView.text = "Next Monday · ${formatQuickDate(nextMonday)}"
        applyRowFocusStyle(quickNextMondayView, focus == Focus.QUICK_NEXT_MONDAY)

        // "ddmm" spells out the pattern (day digits then month digits) rather than a specific
        // example like "3007", which reads as an arbitrary number until you've already figured
        // out what it means. "↓ quick" points at Tomorrow/Next Monday below the grid, since
        // there's nothing else on screen suggesting they're reachable at all.
        hintBar.setText("type ddmm · ↓ quick · # month · ok go")
    }

    private fun applyRowFocusStyle(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
        view.paintFlags = if (selected) {
            view.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            view.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun formatQuickDate(date: LocalDate): String {
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return "$dow ${date.dayOfMonth} $month"
    }

    private fun formatJumpTo(): String {
        if (typedDigits.isEmpty()) return cursorDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val padded = typedDigits.padEnd(8, '_')
        return "${padded.substring(0, 2)}.${padded.substring(2, 4)}.${padded.substring(4, 8)}"
    }

    private fun nextMonday(from: LocalDate): LocalDate {
        var date = from.plusDays(1)
        while (date.dayOfWeek != DayOfWeek.MONDAY) date = date.plusDays(1)
        return date
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (typedDigits.length < 8) typedDigits += event.number
            render()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveFocusUp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveFocusDown()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focus == Focus.GRID) moveCursor(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focus == Focus.GRID) moveCursor(1)
                return true
            }
            KeyEvent.KEYCODE_POUND -> {
                pageMonth(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                confirm()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (typedDigits.isNotEmpty()) {
                    typedDigits = ""
                    render()
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveFocusUp() {
        when (focus) {
            Focus.GRID -> moveCursor(-GRID_COLS)
            Focus.QUICK_TOMORROW -> focus = Focus.GRID
            Focus.QUICK_NEXT_MONDAY -> focus = Focus.QUICK_TOMORROW
        }
        render()
    }

    private fun moveFocusDown() {
        when (focus) {
            Focus.GRID -> {
                // Exit to the quick picks once the cursor is on the visible month's own last
                // week, not the grid's fixed last row — the grid always shows 6 weeks, but most
                // months only need 5, and the trailing week is next month's own first few days.
                // Requiring the true last grid row meant Down from today (usually mid-grid) had
                // to first walk through that dead trailing week before reaching Tomorrow/Next
                // Monday at all.
                val rowIndex = ChronoUnit.DAYS.between(gridStart, cursorDate).toInt() / GRID_COLS
                val lastRowOfVisibleMonth =
                    ChronoUnit.DAYS.between(gridStart, visibleMonth.atEndOfMonth()).toInt() / GRID_COLS
                if (rowIndex >= lastRowOfVisibleMonth) {
                    focus = Focus.QUICK_TOMORROW
                } else {
                    moveCursor(GRID_COLS)
                }
            }
            Focus.QUICK_TOMORROW -> focus = Focus.QUICK_NEXT_MONDAY
            Focus.QUICK_NEXT_MONDAY -> {}
        }
        render()
    }

    private fun moveCursor(deltaDays: Int) {
        cursorDate = cursorDate.plusDays(deltaDays.toLong())
        if (YearMonth.from(cursorDate) != visibleMonth) {
            visibleMonth = YearMonth.from(cursorDate)
            // Updates gridStart synchronously (event dots lag a moment behind, async) — render()
            // right after still reflects the new cursor/grid position immediately rather than
            // waiting on that query, which is what made Left/Right silently do nothing within
            // the same month (no month change meant no render at all until this fix).
            loadMonthEvents()
        }
        render()
    }

    private fun pageMonth(direction: Int) {
        visibleMonth = visibleMonth.plusMonths(direction.toLong())
        cursorDate = visibleMonth.atDay(cursorDate.dayOfMonth.coerceAtMost(visibleMonth.lengthOfMonth()))
        loadMonthEvents()
        render()
    }

    private fun confirm() {
        if (typedDigits.isNotEmpty()) {
            parseTypedDate()?.let { finishWith(it) }
            return
        }
        when (focus) {
            Focus.GRID -> finishWith(cursorDate)
            Focus.QUICK_TOMORROW -> finishWith(LocalDate.now().plusDays(1))
            Focus.QUICK_NEXT_MONDAY -> finishWith(nextMonday(LocalDate.now()))
        }
    }

    private fun parseTypedDate(): LocalDate? {
        if (typedDigits.length < 4) return null
        return try {
            val day = typedDigits.substring(0, 2).toInt()
            val month = typedDigits.substring(2, 4).toInt()
            val year = if (typedDigits.length >= 8) typedDigits.substring(4, 8).toInt() else visibleMonth.year
            LocalDate.of(year, month, day)
        } catch (e: Exception) {
            null
        }
    }

    private fun finishWith(date: LocalDate) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_EPOCH_DAY, date.toEpochDay()))
        finish()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val EXTRA_EPOCH_DAY = "epoch_day"
    }
}
