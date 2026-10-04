package life.furrer.c9.settings

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.calendar.CalendarInfo
import life.furrer.c9.calendar.CalendarStore
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar

private const val REQUEST_CALENDAR_PERMISSION = 100
private const val ROW_SIDE_PADDING_DP = Dimens.SIDE_PADDING_DP

/** Calendar visibility, grouped by account (§9.7) — same shape as L9's own
 *  CalendarSelectionActivity (plain LinearLayout/ScrollView; calendar counts are always small
 *  enough that RowListView's recycling isn't needed), over [CalendarStore] instead of L9's
 *  CalendarRepository. */
class CalendarSelectionActivity : Activity() {

    private var calendars: List<CalendarInfo> = emptyList()
    private var selected: MutableSet<Long> = mutableSetOf()
    private var selectedIndex = 0
    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    // rows[i]/labelViews[i]/statusViews[i] correspond 1:1 with calendars[i]; section headers are
    // separate, non-selectable views interspersed directly into rowContainer.
    private val rows = mutableListOf<LinearLayout>()
    private val labelViews = mutableListOf<TextView>()
    private val statusViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            addView(rowContainer)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        setContentView(scrollView)
        hideStatusBar()

        if (CalendarStore.hasPermission(this)) {
            loadCalendars()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR_PERMISSION)
        }
        buildRows()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR_PERMISSION) {
            loadCalendars()
            buildRows()
            render()
        }
    }

    private fun loadCalendars() {
        calendars = CalendarStore.queryCalendars(this)
        selected = (CalendarStore.getSelectedCalendarIds(this) ?: calendars.map { it.id }.toSet()).toMutableSet()
    }

    private fun buildRows() {
        rowContainer.removeAllViews()
        rows.clear()
        labelViews.clear()
        statusViews.clear()

        if (calendars.isEmpty()) {
            val emptyMessage = if (CalendarStore.hasPermission(this)) "No calendars found" else "Calendar permission denied"
            val message = TextView(this).apply {
                text = emptyMessage
                textSize = 16f
                setTextColor(Palette.GRAY_DIM)
                setPadding(dp(ROW_SIDE_PADDING_DP), dp(30), dp(ROW_SIDE_PADDING_DP), dp(30))
            }
            rowContainer.addView(message)
            return
        }

        CalendarStore.groupByAccount(calendars).forEach { (accountName, accountCalendars) ->
            rowContainer.addView(
                TextView(this).apply {
                    text = accountName.uppercase()
                    textSize = 11f
                    letterSpacing = 0.11f
                    setTextColor(Palette.GRAY_FAINT)
                    setPadding(dp(ROW_SIDE_PADDING_DP), dp(20), dp(ROW_SIDE_PADDING_DP), dp(6))
                },
            )
            accountCalendars.forEach { calendar ->
                val bar = View(this).apply { setBackgroundColor(Palette.pastelize(calendar.color)) }
                val label = TextView(this).apply {
                    text = calendar.displayName
                    textSize = 18f
                }
                val status = TextView(this).apply { textSize = 13f }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ROW_SIDE_PADDING_DP), dp(10), dp(ROW_SIDE_PADDING_DP), dp(10))
                    addView(bar, LinearLayout.LayoutParams(dp(3), dp(20)).apply { marginEnd = dp(10) })
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(status)
                }
                rows.add(row)
                labelViews.add(label)
                statusViews.add(status)
                rowContainer.addView(row)
            }
        }
    }

    private fun render() {
        calendars.forEachIndexed { index, calendar ->
            val isSelectedRow = index == selectedIndex
            labelViews[index].apply {
                setTextColor(if (isSelectedRow) Color.WHITE else Palette.GRAY_DIM)
                paintFlags = if (isSelectedRow) {
                    paintFlags or Paint.UNDERLINE_TEXT_FLAG
                } else {
                    paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                }
            }
            statusViews[index].apply {
                text = if (calendar.id in selected) "shown" else "hidden"
                setTextColor(Palette.GRAY_MUTED)
            }
        }
        rows.getOrNull(selectedIndex)?.let { row ->
            scrollView.post { scrollView.smoothScrollTo(0, row.top) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (rows.isEmpty()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                selectedIndex = (selectedIndex - 1 + rows.size) % rows.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                selectedIndex = (selectedIndex + 1) % rows.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleSelected()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleSelected() {
        val calendar = calendars.getOrNull(selectedIndex) ?: return
        if (calendar.id in selected) selected.remove(calendar.id) else selected.add(calendar.id)
        CalendarStore.setSelectedCalendarIds(this, selected)
        render()
    }
}
