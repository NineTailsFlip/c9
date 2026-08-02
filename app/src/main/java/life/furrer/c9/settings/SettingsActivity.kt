package life.furrer.c9.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.calendar.CalendarInfo
import life.furrer.c9.calendar.CalendarStore
import life.furrer.c9.contacts.ContactStore
import life.furrer.nine.core.ui.Palette
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.prefs.NinePrefs
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar

private const val ROW_SIDE_PADDING_DP = 16
private const val MIN_DURATION_MINUTES = 15
private const val MAX_DURATION_MINUTES = 480
private const val DURATION_STEP_MINUTES = 15
private const val EXTRA_APP = "app"

/** Which launcher entry opened this screen — Calendar and Contacts each get their own settings
 *  (own Time/Calendar or Contacts sections), but share Theme (Font, Hint bar), since those apply
 *  to both apps' rendering equally. */
enum class SettingsApp { CALENDAR, CONTACTS }

/** L9's own SettingsActivity.kt shape, reused verbatim (§3): a `Label: value` row list, OK/Left/
 *  Right adjusting the focused row, Up/Down moving. Reached by holding `*` from either app's main
 *  screen (§7.2), or as the last "* menu" item. One Activity class handles both launcher entries
 *  (via [SettingsApp], passed through [intentFor]) rather than two, since the row-list/selection/
 *  key-handling plumbing is identical either way — only which rows get built differs. */
class SettingsActivity : Activity() {

    companion object {
        fun intentFor(context: Context, app: SettingsApp): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_APP, app.name)
    }

    /** One row = its TextView plus the two closures that drive it: [render] recomputes its label
     *  text from current state, [adjust] mutates that state (and persists it) for a Left/Right/OK
     *  press. Letting each row own its own logic is what lets [buildRows] include a different
     *  subset per [SettingsApp] without every other piece of this class needing to know which
     *  rows exist at which fixed index — unlike L9's own SettingsActivity, whose row set never
     *  varies. */
    private class Row(val view: TextView, val render: () -> String, val adjust: (direction: Int) -> Unit)

    private lateinit var app: SettingsApp
    private var selectedIndex = 0
    private lateinit var scrollView: ScrollView
    private val rows = mutableListOf<Row>()

    /** Set only for the Theme section's Font row — [adjust] special-cases it (see there) since a
     *  font change needs `recreate()`, not just a re-render. */
    private var fontRowView: TextView? = null

    private var useMonospaceFont = true
    private var showHintBar = true
    private var calendars: List<CalendarInfo> = emptyList()
    private var defaultCalendarId: Long? = null
    private var defaultEventDurationMinutes = 60
    private var accounts: List<ContactStore.WritableAccount> = emptyList()
    private var defaultAccountName: String? = null
    private var defaultAccountType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()
        app = intent.getStringExtra(EXTRA_APP)?.let { SettingsApp.valueOf(it) } ?: SettingsApp.CALENDAR

        val rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            addView(rowContainer)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        setContentView(scrollView)
        hideStatusBar()

        buildRows(rowContainer)
    }

    override fun onResume() {
        super.onResume()
        reloadState()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun reloadState() {
        useMonospaceFont = NinePrefs.useMonospaceFont(this)
        showHintBar = NinePrefs.showHintBar(this)
        if (app == SettingsApp.CALENDAR) {
            calendars = CalendarStore.queryCalendars(this)
            defaultCalendarId = Prefs.defaultCalendarId(this)
            defaultEventDurationMinutes = Prefs.defaultEventDurationMinutes(this)
        }
        if (app == SettingsApp.CONTACTS) {
            accounts = ContactStore.queryWritableAccounts(this)
            defaultAccountName = Prefs.defaultAccountName(this)
            defaultAccountType = Prefs.defaultAccountType(this)
        }
    }

    /** The calendar this row is currently pointing at — the same "unset falls back to the first
     *  one" resolution [life.furrer.c9.calendar.EventEditActivity] applies when actually creating
     *  an event, so what's shown here always matches what a new event would get. */
    private fun resolvedCalendarIndex(): Int =
        calendars.indexOfFirst { it.id == defaultCalendarId }.coerceAtLeast(0)

    /** -1 means "ask each time" (the pre-existing behavior); 0..accounts.size-1 pins to that
     *  account, matched by name+type since list position isn't a stable identity. */
    private fun resolvedAccountIndex(): Int =
        accounts.indexOfFirst { it.name == defaultAccountName && it.type == defaultAccountType }

    private fun buildRows(rowContainer: LinearLayout) {
        var isFirstSection = true
        fun header(title: String) {
            addSectionHeader(rowContainer, title, isFirst = isFirstSection)
            isFirstSection = false
        }

        header("Theme")
        fontRowView = addRow(
            rowContainer,
            render = { "Font:          ${if (useMonospaceFont) "monospace" else "proportional"}" },
            // Never invoked directly — [adjust] special-cases fontRowView before reaching here.
            adjust = {},
        )
        addRow(
            rowContainer,
            render = { "Hint bar:      ${if (showHintBar) "shown" else "hidden"}" },
            adjust = { showHintBar = !showHintBar; NinePrefs.setShowHintBar(this, showHintBar) },
        )

        if (app == SettingsApp.CALENDAR) {
            header("Calendar")
            addRow(
                rowContainer,
                render = { "Default calendar:   ${calendars.getOrNull(resolvedCalendarIndex())?.displayName ?: "none available"}" },
                adjust = { direction ->
                    if (calendars.isNotEmpty()) {
                        val nextIndex = (resolvedCalendarIndex() + direction + calendars.size) % calendars.size
                        defaultCalendarId = calendars[nextIndex].id
                        Prefs.setDefaultCalendarId(this, defaultCalendarId)
                    }
                },
            )
            addRow(
                rowContainer,
                render = { "Default duration:   ${formatDuration(defaultEventDurationMinutes)}" },
                adjust = { direction ->
                    defaultEventDurationMinutes = (defaultEventDurationMinutes + direction * DURATION_STEP_MINUTES)
                        .coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
                    Prefs.setDefaultEventDurationMinutes(this, defaultEventDurationMinutes)
                },
            )
        }

        if (app == SettingsApp.CONTACTS) {
            header("Contacts")
            addRow(
                rowContainer,
                render = {
                    if (accounts.isEmpty()) {
                        "Default address book: none available"
                    } else {
                        "Default address book: ${accounts.getOrNull(resolvedAccountIndex())?.name ?: "ask each time"}"
                    }
                },
                adjust = { direction ->
                    if (accounts.isNotEmpty()) {
                        // Positions shift by one to fit "ask each time" in as position 0 ahead of
                        // the accounts themselves, then back by one after wrapping — see
                        // [resolvedAccountIndex]'s -1-means-ask convention.
                        val optionCount = accounts.size + 1
                        val nextPosition = (resolvedAccountIndex() + 1 + direction + optionCount) % optionCount
                        val nextIndex = nextPosition - 1
                        if (nextIndex == -1) {
                            defaultAccountName = null
                            defaultAccountType = null
                        } else {
                            defaultAccountName = accounts[nextIndex].name
                            defaultAccountType = accounts[nextIndex].type
                        }
                        Prefs.setDefaultAccount(this, defaultAccountName, defaultAccountType)
                    }
                },
            )
        }
    }

    private fun addRow(rowContainer: LinearLayout, render: () -> String, adjust: (direction: Int) -> Unit): TextView {
        val view = makeRow()
        rows.add(Row(view, render, adjust))
        rowContainer.addView(view)
        return view
    }

    private fun addSectionHeader(rowContainer: LinearLayout, title: String, isFirst: Boolean = false) {
        val header = TextView(this).apply {
            text = title.uppercase()
            textSize = 11f
            letterSpacing = 0.11f
            setTextColor(Palette.GRAY_FAINT)
            setPadding(dp(ROW_SIDE_PADDING_DP), dp(if (isFirst) 14 else 24), dp(ROW_SIDE_PADDING_DP), dp(4))
        }
        rowContainer.addView(header)
    }

    private fun makeRow(): TextView = TextView(this).apply {
        textSize = 16f
        setPadding(dp(ROW_SIDE_PADDING_DP), dp(6), dp(ROW_SIDE_PADDING_DP), dp(6))
    }

    private fun render() {
        rows.forEachIndexed { index, row ->
            row.view.text = row.render()
            val selected = index == selectedIndex
            row.view.setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
            row.view.paintFlags = if (selected) {
                row.view.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                row.view.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
        rows.getOrNull(selectedIndex)?.let { row ->
            scrollView.post { scrollView.smoothScrollTo(0, if (selectedIndex == 0) 0 else row.view.top) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                adjust(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                adjust(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                adjust(1)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun adjust(direction: Int) {
        val row = rows.getOrNull(selectedIndex) ?: return
        if (row.view === fontRowView) {
            useMonospaceFont = !useMonospaceFont
            NinePrefs.setUseMonospaceFont(this, useMonospaceFont)
            // The theme's default TextView style only applies to views created after it's set
            // (§3's Theming.kt note), so this screen needs rebuilding to show it live.
            recreate()
            return
        }
        row.adjust(direction)
        render()
    }

    private fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            remainder == 0 -> "${hours}h"
            hours == 0 -> "${remainder}m"
            else -> "${hours}h ${remainder}m"
        }
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
