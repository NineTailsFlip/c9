package life.furrer.c9.calendar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.util.DateFmt
import life.furrer.c9.core.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.c9.core.RowBinder
import life.furrer.c9.core.RowListView
import life.furrer.c9.core.SearchField
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar

private const val SIDE_PADDING_DP = 16
private const val ROW_HEIGHT_DP = 32
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
)

/** The calendar's own Search screen (§7.3's `*` menu item) — a live search field over
 *  [EventStore.querySearchWindow], matching by title/location (§6.3-style word-prefix, via
 *  [EventSearch]) across a rolling ~2-year window. A matched recurring series shows its single
 *  next-upcoming (or, failing that, most recent past) occurrence, not one row per instance. */
class EventSearchActivity : Activity() {

    private var allEvents: List<EventEntry> = emptyList()
    private var results: List<EventEntry> = emptyList()

    private lateinit var searchField: SearchField
    private lateinit var rowListView: RowListView
    private lateinit var hintBar: HintBar

    private val binder = object : RowBinder {
        override fun rowCount(): Int = results.size
        override fun createRowView(context: Context): View = TextView(context).apply {
            textSize = 16f
            setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), 0)
        }
        override fun bind(view: View, index: Int, selected: Boolean) {
            val event = results[index]
            (view as TextView).apply {
                text = "${event.title} — ${formatWhen(event)}"
                setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        override fun rowHeight(index: Int): Int = ROW_HEIGHT_DP
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        searchField = SearchField(this).apply {
            onQueryChanged = { rebuildResults() }
            editText.apply {
                hint = "Search events"
                textSize = 16f
                setTextColor(Color.WHITE)
                setHintTextColor(Palette.GRAY_FAINT)
                setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(6))
            }
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        hintBar = HintBar(this).apply { setText("‹ clear · ok open") }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchField.editText)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()

        loadEvents()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            searchField.onWindowFocusGained()
            hideStatusBar()
        }
    }

    override fun onPause() {
        super.onPause()
        searchField.onPause()
    }

    private fun loadEvents() {
        Async.run(
            background = {
                val selectedIds = CalendarStore.getSelectedCalendarIds(this)
                EventStore.querySearchWindow(this, selectedIds)
            },
            onResult = { events ->
                allEvents = events
                rebuildResults()
            },
        )
    }

    private fun rebuildResults() {
        results = EventSearch.buildResults(allEvents, searchField.query, System.currentTimeMillis())
        rowListView.notifyDataChanged()
    }

    private fun formatWhen(event: EventEntry): String {
        val date = if (event.allDay) DateFmt.allDayToLocalDate(event.startMillis) else Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val dateText = "$dow ${date.dayOfMonth} $month"
        return if (event.allDay) dateText else "$dateText, ${DateFmt.formatTime(event.startMillis, is24Hour = true)}"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
            onBackNotConsumedByIme()
            return true
        }
        if (searchField.handleKeyEvent(event, RESERVED_KEYS)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in RESERVED_KEYS) {
                handleReservedKeyDown(event.keyCode)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBackNotConsumedByIme() {
        if (searchField.query.isEmpty()) {
            finish()
        } else {
            searchField.clear()
        }
    }

    private fun handleReservedKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openSelected()
        }
    }

    private fun openSelected() {
        val event = results.getOrNull(rowListView.selectedIndex) ?: return
        startActivity(
            Intent(this, EventDetailActivity::class.java).apply {
                putExtra(EventDetailActivity.EXTRA_EVENT_ID, event.eventId)
                putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, event.startMillis)
            },
        )
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
