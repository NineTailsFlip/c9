package life.furrer.c9.calendar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar

/** The event editor's Notes field (`Events.DESCRIPTION`, ticket #17): one multi-line text field
 *  that keeps the line breaks a synced description (agendas, meeting links) comes with. It only
 *  hands the text back in [EXTRA_TEXT]; the editor writes it with the rest of the event on save.
 *
 *  Every way out keeps the text, like the editor's own inline fields: OK finishes, and so does
 *  Back - which this hardware's IME consumes as a backspace while there's text (see M9's
 *  `ConversationActivity`), so it only reaches here once the field is empty. A line break comes
 *  from the IME's own line-break key, since OK is taken. Same Autofill defenses as `GroupNameActivity`. */
class EventNotesActivity : Activity() {

    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        val heading = TextView(this).apply {
            text = "Notes"
            textSize = 13f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(16), dp(Dimens.SIDE_PADDING_DP), dp(6))
        }
        editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            setTextColor(Color.WHITE)
            background = null
            hint = "No notes"
            setHintTextColor(Palette.GRAY_FAINT)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(6), dp(Dimens.SIDE_PADDING_DP), dp(6))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(savedInstanceState?.getString(STATE_TEXT) ?: intent.getStringExtra(EXTRA_TEXT).orEmpty())
            setSelection(text.length)
        }
        val scrollView = ScrollView(this).apply { addView(editText) }
        val hintBar = HintBar(this).apply { setText("ok done") }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                addView(heading)
                addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(hintBar)
            },
        )
        hideStatusBar()
        editText.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TEXT, editText.text.toString())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(AutofillManager::class.java)?.cancel()
    }

    /** Taken ahead of normal dispatch: the focused multi-line EditText would otherwise turn OK
     *  into a line break, and the screen would have no way out while it has text. ENTER too:
     *  confirmed on-device that TT9 takes OK first and hands a multi-line field ENTER instead
     *  (M9's reply field catches both for the same reason); TT9's own line break (0 twice) is
     *  committed as text and still works. A held Back's repeats are ignored, or holding it to
     *  wipe the text would also close the screen and clear the notes the moment it ran empty. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    done()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.repeatCount == 0) done()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun done() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, editText.text.toString()))
        // Before finish(), not only in onDestroy, so no "Save to password manager?" follows.
        getSystemService(AutofillManager::class.java)?.cancel()
        finish()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        private const val STATE_TEXT = "text"

        fun intent(context: Context, text: String): Intent =
            Intent(context, EventNotesActivity::class.java).putExtra(EXTRA_TEXT, text)
    }
}
