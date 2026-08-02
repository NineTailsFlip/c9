package life.furrer.c9.contacts

import android.app.Activity
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
import life.furrer.c9.contacts.ContactStore.ContactGroup
import life.furrer.nine.core.ui.Palette
import life.furrer.c9.core.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar

private const val ROW_SIDE_PADDING_DP = 16

/** The Groups visibility filter (§7.4 `*` → Groups) — same plain LinearLayout/ScrollView shape
 *  as [life.furrer.c9.settings.CalendarSelectionActivity], grouped by account, over
 *  [ContactStore]'s own group query and selected-group-ids prefs instead of [CalendarStore]. */
class ContactGroupsActivity : Activity() {

    private var groups: List<ContactGroup> = emptyList()
    private var selected: MutableSet<Long> = mutableSetOf()
    private var selectedIndex = 0
    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView

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

        groups = ContactStore.queryGroups(this)
        selected = (ContactStore.getSelectedGroupIds(this) ?: groups.map { it.id }.toSet()).toMutableSet()
        buildRows()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun buildRows() {
        rowContainer.removeAllViews()
        rows.clear()
        labelViews.clear()
        statusViews.clear()

        if (groups.isEmpty()) {
            val message = TextView(this).apply {
                text = if (ContactStore.hasPermission(this@ContactGroupsActivity)) "No groups found" else "Contacts permission denied"
                textSize = 16f
                setTextColor(Palette.GRAY_DIM)
                setPadding(dp(ROW_SIDE_PADDING_DP), dp(30), dp(ROW_SIDE_PADDING_DP), dp(30))
            }
            rowContainer.addView(message)
            return
        }

        ContactStore.groupsByAccount(groups).forEach { (accountName, accountGroups) ->
            rowContainer.addView(
                TextView(this).apply {
                    text = accountName.uppercase()
                    textSize = 11f
                    letterSpacing = 0.11f
                    setTextColor(Palette.GRAY_FAINT)
                    setPadding(dp(ROW_SIDE_PADDING_DP), dp(20), dp(ROW_SIDE_PADDING_DP), dp(6))
                },
            )
            accountGroups.forEach { group ->
                val label = TextView(this).apply {
                    text = group.title
                    textSize = 18f
                }
                val status = TextView(this).apply { textSize = 13f }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ROW_SIDE_PADDING_DP), dp(10), dp(ROW_SIDE_PADDING_DP), dp(10))
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
        groups.forEachIndexed { index, group ->
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
                text = if (group.id in selected) "shown" else "hidden"
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
        val group = groups.getOrNull(selectedIndex) ?: return
        if (group.id in selected) selected.remove(group.id) else selected.add(group.id)
        ContactStore.setSelectedGroupIds(this, selected)
        render()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
