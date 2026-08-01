package life.furrer.c9.core

import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

private const val GRAY_DIM = 0xFF8A8A8A.toInt()

/** L9's selection convention (white + underline vs. dim gray — see AppPickerActivity.render())
 *  for a plain single-line row. Rows with more than one styled element (calendar's bar/time/
 *  title) don't fit this and style themselves directly instead. */
fun TextView.applyRowSelectionStyle(selected: Boolean) {
    setTextColor(if (selected) Color.WHITE else GRAY_DIM)
    paintFlags = if (selected) {
        paintFlags or Paint.UNDERLINE_TEXT_FLAG
    } else {
        paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }
}
