package life.furrer.c9.ui

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import life.furrer.nine.core.ui.Palette

/** One line of independently selectable sub-fields ("columns" Left/Right picks between, like the
 *  event editor's Starts date and time, or a contact Event's type and date): each segment's text
 *  in order, white and underlined when its flag is true, dim when false, and left in the row's own
 *  colour when null (a separator). The sub-string version of `applyRowSelectionStyle`'s whole-row
 *  white + underline. */
fun columnHighlight(vararg segments: Pair<String, Boolean?>): SpannableString {
    val spannable = SpannableString(segments.joinToString("") { it.first })
    var start = 0
    segments.forEach { (text, highlight) ->
        val end = start + text.length
        if (highlight != null) {
            spannable.setSpan(ForegroundColorSpan(if (highlight) Color.WHITE else Palette.GRAY_DIM), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (highlight) spannable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        start = end
    }
    return spannable
}
