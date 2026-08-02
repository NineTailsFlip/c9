package life.furrer.c9.core

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.ui.Palette

private const val TEXT_SIZE_SP = 11f
private const val SIDE_PADDING_DP = 16
private const val VERTICAL_PADDING_DP = 7

/** The bottom dim key-hint row every screen ends in (§7.2/§8) — a one-line reminder of the two
 *  or three keys that matter on the current screen. */
class HintBar(context: Context) : LinearLayout(context) {

    private val textView: TextView

    init {
        orientation = VERTICAL
        val divider = View(context).apply { setBackgroundColor(Palette.DIVIDER) }
        addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))

        textView = TextView(context).apply {
            textSize = TEXT_SIZE_SP
            setTextColor(Palette.GRAY_FAINT)
            maxLines = 1
            setPadding(dp(SIDE_PADDING_DP), dp(VERTICAL_PADDING_DP), dp(SIDE_PADDING_DP), dp(VERTICAL_PADDING_DP))
        }
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** §7.2: "it can be turned off in Settings" — checked on every call rather than observed
     *  live, since every call site already re-invokes this on every render/state change, so a
     *  setting flipped while this screen is in the background still takes effect the next time
     *  it renders anything (its own onResume, if nothing else). */
    fun setText(text: String) {
        textView.text = text
        visibility = if (Prefs.showHintBar(context)) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
