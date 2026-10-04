package life.furrer.c9.calendar

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.nine.core.ui.DatePickerActivity
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.DateFmt

private const val REQUEST_PICK_UNTIL = 300
private const val TYPE_RESET_MS = 1500L
private const val DEFAULT_COUNT = 10

/** The editor's "Repeat" screen (OK on its Repeats row): the parts of a rule the one-word
 *  Left/Right cycle can't say — every N units, which weekdays, which day of the month, and when
 *  it stops. Same shape as the editor itself (Label: value rows, Up/Down select, Left/Right
 *  change the value) with a live summary line on top, so the result reads as the sentence that
 *  will show in the editor and on the detail screen.
 *
 *  Back finishes with the built rule in [EXTRA_RRULE] — like the editor, there is no separate
 *  save step on a keypad. It only ever edits rules [Recurrence.toSpec] can show completely; the
 *  editor doesn't open it for anything else, so a synced "every 2nd Tuesday of March" is never
 *  silently rewritten here. */
class RepeatActivity : NineActivity() {

    private enum class Row { REPEAT, EVERY, ON, ENDS, UNTIL, TIMES }

    private lateinit var spec: RepeatSpec
    private lateinit var start: LocalDate
    private var allDay = false
    private var selected = Row.REPEAT
    private var visible: List<Row> = emptyList()

    private lateinit var summary: TextView
    private lateinit var rowContainer: LinearLayout
    private lateinit var hintBar: HintBar
    private val rowViews = mutableMapOf<Row, TextView>()
    private var typed = ""
    private var typedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allDay = intent.getBooleanExtra(EXTRA_ALL_DAY, false)
        val startMillis = intent.getLongExtra(EXTRA_START_MILLIS, System.currentTimeMillis())
        start = if (allDay) DateFmt.allDayToLocalDate(startMillis) else Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        spec = savedInstanceState?.getString(STATE_RRULE)?.let { Recurrence.toSpec(it, start) }
            ?: intent.getStringExtra(EXTRA_RRULE)?.let { Recurrence.toSpec(it, start) }
            ?: RepeatSpec(RecurrenceFrequency.WEEKLY)

        summary = TextView(this).apply {
            textSize = 15f
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(16), dp(Dimens.SIDE_PADDING_DP), dp(12))
        }
        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hintBar = HintBar(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(summary)
                addView(rowContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(hintBar)
            },
        )
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_RRULE, currentRule())
    }

    private fun currentRule(): String = Recurrence.build(spec, start, allDay)

    private fun visibleRows(): List<Row> = buildList {
        add(Row.REPEAT)
        add(Row.EVERY)
        if (spec.frequency == RecurrenceFrequency.WEEKLY || spec.frequency == RecurrenceFrequency.MONTHLY) add(Row.ON)
        add(Row.ENDS)
        when (spec.end) {
            is RepeatEnd.OnDate -> add(Row.UNTIL)
            is RepeatEnd.AfterCount -> add(Row.TIMES)
            RepeatEnd.Never -> Unit
        }
    }

    private fun render() {
        visible = visibleRows()
        if (selected !in visible) selected = visible.first()
        summary.text = Recurrence.describe(currentRule())
        rowContainer.removeAllViews()
        rowViews.clear()
        visible.forEach { row ->
            val view = TextView(this).apply {
                textSize = 18f
                setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(10))
                text = "${label(row).padEnd(8)}${value(row)}"
                applyRowSelectionStyle(row == selected)
            }
            rowViews[row] = view
            rowContainer.addView(view)
        }
        hintBar.setText(
            when (selected) {
                Row.ON -> if (spec.frequency == RecurrenceFrequency.WEEKLY) "1-7 mon-sun · ‹ done" else "‹ › change · ‹ done"
                Row.UNTIL -> "ok pick date · ‹ done"
                Row.EVERY, Row.TIMES -> "‹ › or digits · back done"
                else -> "‹ › change · back done"
            },
        )
    }

    private fun label(row: Row) = when (row) {
        Row.REPEAT -> "Repeat:"
        Row.EVERY -> "Every:"
        Row.ON -> "On:"
        Row.ENDS -> "Ends:"
        Row.UNTIL -> "Until:"
        Row.TIMES -> "Times:"
    }

    private fun unit() = when (spec.frequency) {
        RecurrenceFrequency.DAILY -> "day"
        RecurrenceFrequency.WEEKLY -> "week"
        RecurrenceFrequency.MONTHLY -> "month"
        else -> "year"
    }

    private fun value(row: Row): String = when (row) {
        Row.REPEAT -> when (spec.frequency) {
            RecurrenceFrequency.DAILY -> "daily"
            RecurrenceFrequency.WEEKLY -> "weekly"
            RecurrenceFrequency.MONTHLY -> "monthly"
            else -> "yearly"
        }
        Row.EVERY -> "${spec.interval} ${unit()}${if (spec.interval == 1) "" else "s"}"
        Row.ON -> if (spec.frequency == RecurrenceFrequency.WEEKLY) weekdaysText() else monthlyText()
        Row.ENDS -> when (spec.end) {
            RepeatEnd.Never -> "never"
            is RepeatEnd.OnDate -> "on a date"
            is RepeatEnd.AfterCount -> "after a number"
        }
        Row.UNTIL -> (spec.end as? RepeatEnd.OnDate)?.date?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())).orEmpty()
        Row.TIMES -> (spec.end as? RepeatEnd.AfterCount)?.count?.let { "$it ${if (it == 1) "time" else "times"}" }.orEmpty()
    }

    /** Selected days in capitals, the rest dimmed to lowercase initials: "MO tu WE th fr sa su".
     *  With none picked the rule follows the start date's own weekday, which is said outright. */
    private fun weekdaysText(): String {
        if (spec.weekdays.isEmpty()) return "${shortDay(start.dayOfWeek)} (start day)"
        return DayOfWeek.entries.joinToString(" ") { day ->
            val name = shortDay(day).take(2)
            if (day in spec.weekdays) name.uppercase() else name.lowercase()
        }
    }

    private fun shortDay(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun monthlyText(): String = when (spec.monthly) {
        MonthlyMode.DAY_OF_MONTH -> "day ${start.dayOfMonth}"
        MonthlyMode.NTH_WEEKDAY -> "the ${ordinal(Recurrence.nthOf(start))} ${shortDay(start.dayOfWeek)}"
        MonthlyMode.LAST_WEEKDAY -> "the last ${shortDay(start.dayOfWeek)}"
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    private fun monthlyModes(): List<MonthlyMode> = buildList {
        add(MonthlyMode.DAY_OF_MONTH)
        if (Recurrence.nthOf(start) <= 4) add(MonthlyMode.NTH_WEEKDAY)
        add(MonthlyMode.LAST_WEEKDAY)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> adjust(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> adjust(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (selected == Row.UNTIL) pickUntil() else adjust(1)
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> digit(keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_BACK -> {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RRULE, currentRule()))
                finish()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun move(direction: Int) {
        val index = visible.indexOf(selected)
        selected = visible[(index + direction + visible.size) % visible.size]
        typed = ""
        render()
    }

    private fun adjust(direction: Int) {
        when (selected) {
            Row.REPEAT -> {
                val all = listOf(RecurrenceFrequency.DAILY, RecurrenceFrequency.WEEKLY, RecurrenceFrequency.MONTHLY, RecurrenceFrequency.YEARLY)
                val next = all[(all.indexOf(spec.frequency) + direction + all.size) % all.size]
                // The parts that only made sense for the old frequency go with it.
                spec = spec.copy(frequency = next, weekdays = emptySet(), monthly = MonthlyMode.DAY_OF_MONTH)
            }
            Row.EVERY -> spec = spec.copy(interval = (spec.interval + direction).coerceIn(1, 99))
            Row.ON -> if (spec.frequency == RecurrenceFrequency.MONTHLY) {
                val modes = monthlyModes()
                spec = spec.copy(monthly = modes[(modes.indexOf(spec.monthly) + direction + modes.size) % modes.size])
            }
            Row.ENDS -> {
                val order = listOf("never", "date", "count")
                val current = when (spec.end) { RepeatEnd.Never -> 0; is RepeatEnd.OnDate -> 1; is RepeatEnd.AfterCount -> 2 }
                spec = spec.copy(
                    end = when (order[(current + direction + order.size) % order.size]) {
                        "never" -> RepeatEnd.Never
                        "date" -> RepeatEnd.OnDate(defaultUntil())
                        else -> RepeatEnd.AfterCount(DEFAULT_COUNT)
                    },
                )
            }
            Row.TIMES -> (spec.end as? RepeatEnd.AfterCount)?.let { spec = spec.copy(end = RepeatEnd.AfterCount((it.count + direction).coerceIn(1, 999))) }
            Row.UNTIL -> (spec.end as? RepeatEnd.OnDate)?.let { spec = spec.copy(end = RepeatEnd.OnDate(it.date.plusDays(direction.toLong()).coerceAtLeast(start))) }
        }
        render()
    }

    /** A first "until" worth showing: one period of the rule past the start. */
    private fun defaultUntil(): LocalDate = when (spec.frequency) {
        RecurrenceFrequency.DAILY -> start.plusWeeks(1)
        RecurrenceFrequency.WEEKLY -> start.plusMonths(1)
        RecurrenceFrequency.MONTHLY -> start.plusYears(1)
        else -> start.plusYears(5)
    }

    private fun digit(d: Int) {
        when (selected) {
            Row.ON -> if (spec.frequency == RecurrenceFrequency.WEEKLY && d in 1..7) {
                val day = DayOfWeek.of(d)
                spec = spec.copy(weekdays = if (day in spec.weekdays) spec.weekdays - day else spec.weekdays + day)
                render()
            }
            Row.EVERY, Row.TIMES -> {
                val now = System.currentTimeMillis()
                typed = if (now - typedAt > TYPE_RESET_MS) d.toString() else (typed + d).takeLast(if (selected == Row.EVERY) 2 else 3)
                typedAt = now
                val value = typed.toInt()
                if (selected == Row.EVERY) {
                    if (value >= 1) spec = spec.copy(interval = value)
                } else if (value >= 1) {
                    spec = spec.copy(end = RepeatEnd.AfterCount(value))
                }
                render()
            }
            else -> Unit
        }
    }

    private fun pickUntil() {
        startActivityForResult(Intent(this, DatePickerActivity::class.java), REQUEST_PICK_UNTIL)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_UNTIL && resultCode == RESULT_OK && data != null) {
            val epochDay = data.getLongExtra(DatePickerActivity.EXTRA_EPOCH_DAY, -1L)
            if (epochDay >= 0) {
                spec = spec.copy(end = RepeatEnd.OnDate(LocalDate.ofEpochDay(epochDay).coerceAtLeast(start)))
                render()
            }
        }
    }

    companion object {
        const val EXTRA_RRULE = "rrule"
        const val EXTRA_START_MILLIS = "start_millis"
        const val EXTRA_ALL_DAY = "all_day"
        private const val STATE_RRULE = "state_rrule"
    }
}
