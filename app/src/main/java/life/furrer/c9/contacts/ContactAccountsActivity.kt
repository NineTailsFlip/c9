package life.furrer.c9.contacts

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactStore.WritableAccount
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar

private const val ROW_SIDE_PADDING_DP = 16

/** The Accounts visibility filter (§7.4 `*` → Accounts) — lets a device with several raw-contact
 *  accounts (a real CardDAV address book alongside WhatsApp/Signal's own shadow accounts,
 *  confirmed to coexist on the real device during M5) hide the ones that aren't a real address
 *  book. Same plain LinearLayout/ScrollView shape as [FavoritesGroupActivity], just without
 *  section headers — there's no natural grouping one level above "account" itself. */
class ContactAccountsActivity : Activity() {

    private var accounts: List<WritableAccount> = emptyList()
    private var selected: MutableSet<WritableAccount> = mutableSetOf()
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

        accounts = ContactStore.queryAccounts(this)
        selected = (ContactStore.getSelectedAccounts(this) ?: accounts.toSet()).toMutableSet()
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

        if (accounts.isEmpty()) {
            val message = TextView(this).apply {
                text = if (ContactStore.hasPermission(this@ContactAccountsActivity)) "No accounts found" else "Contacts permission denied"
                textSize = 16f
                setTextColor(Palette.GRAY_DIM)
                setPadding(dp(ROW_SIDE_PADDING_DP), dp(30), dp(ROW_SIDE_PADDING_DP), dp(30))
            }
            rowContainer.addView(message)
            return
        }

        accounts.forEach { account ->
            val label = TextView(this).apply {
                text = account.name
                textSize = 18f
            }
            val status = TextView(this).apply { textSize = 13f }
            val row = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
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

    private fun render() {
        accounts.forEachIndexed { index, account ->
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
                text = if (account in selected) "shown" else "hidden"
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
        val account = accounts.getOrNull(selectedIndex) ?: return
        if (account in selected) selected.remove(account) else selected.add(account)
        ContactStore.setSelectedAccounts(this, selected)
        render()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
