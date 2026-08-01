package life.furrer.c9.core

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

data class MenuItem(val label: String, val action: () -> Unit)

/** Black at ~82% opacity — leaves the content behind at roughly the ~18% visibility §9.6 calls
 *  for, without touching that content view's own alpha (and having to restore it on close). */
private const val SCRIM_COLOR = 0xD2000000.toInt()
private const val ROW_TEXT_SIZE_SP = 18f
private const val ROW_SIDE_PADDING_DP = 16
private const val ROW_VERTICAL_PADDING_DP = 10
private const val HINT_TEXT_SIZE_SP = 11f

/** The numbered `*` menu overlay from §9.6. Hosting activities must be FrameLayout-rooted so
 *  this can sit on top as an extra child added once in onCreate, and must check
 *  [handleKeyDown] before their own key handling while [isShowing] — the same "modal intercepts
 *  input first" shape as an AlertDialog, just drawn in-window instead of a separate window
 *  (needed so the screen behind stays visible, dimmed, rather than being covered opaquely). */
class MenuSheet(context: Context) : FrameLayout(context) {

    private var items: List<MenuItem> = emptyList()
    private var selectedIndex: Int = 0
    private val listContainer: LinearLayout
    private val hintText: TextView

    var isShowing: Boolean = false
        private set

    init {
        setBackgroundColor(SCRIM_COLOR)
        visibility = View.GONE

        listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        hintText = TextView(context).apply {
            text = "press a number · ↑↓ ok · ‹ close"
            textSize = HINT_TEXT_SIZE_SP
            setTextColor(Palette.GRAY_FAINT)
            setPadding(dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP), dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP))
        }
        val rule = View(context).apply { setBackgroundColor(Palette.DIVIDER) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BLACK)
            addView(rule, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            addView(listContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hintText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    /** Shows [items] numbered 1..n. Per §9.6, callers should only pass items that apply right
     *  now (an empty selection means no Edit/Delete row) — the menu numbers what's shown, not a
     *  fixed slot list. */
    fun show(items: List<MenuItem>) {
        this.items = items
        this.selectedIndex = 0
        renderItems()
        visibility = View.VISIBLE
        isShowing = true
    }

    private fun renderItems() {
        listContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = TextView(context).apply {
                text = "${index + 1} ${item.label}"
                textSize = ROW_TEXT_SIZE_SP
                setPadding(dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP), dp(ROW_SIDE_PADDING_DP), dp(ROW_VERTICAL_PADDING_DP))
                applyRowSelectionStyle(selected = index == selectedIndex)
            }
            listContainer.addView(row)
        }
    }

    fun hide() {
        visibility = View.GONE
        isShowing = false
    }

    /** @return true if the key was consumed — callers check this before their own onKeyDown
     *  handling while [isShowing]. Swallows everything while shown, even keys it doesn't
     *  otherwise act on, so nothing behind the scrim can react to a keypress meant for the menu.
     *  A number jumps straight to and invokes that row; Up/Down move the highlight and OK invokes
     *  whichever row is currently highlighted — both are equally valid ways to choose. */
    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isShowing) return false
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> hide()
            KeyEvent.KEYCODE_DPAD_UP -> if (items.isNotEmpty()) {
                selectedIndex = (selectedIndex - 1 + items.size) % items.size
                renderItems()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (items.isNotEmpty()) {
                selectedIndex = (selectedIndex + 1) % items.size
                renderItems()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val item = items.getOrNull(selectedIndex)
                hide()
                item?.action?.invoke()
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val index = (event.number.digitToIntOrNull() ?: 0) - 1
                val item = items.getOrNull(index)
                hide()
                item?.action?.invoke()
            }
        }
        return true
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
