package life.furrer.c9.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

/** "OK edits in place" for the editors' Label:value rows (the contact and the event editor): a
 *  row of [rowContainer] is swapped for its label plus a real [EditText], and OK/Back/Up/Down all
 *  commit it - there's no cancel, an accidental edit is no worse than any other field change,
 *  since the editor itself still writes nothing until its own Back saves.
 *
 *  The host keeps everything about *what* is edited (its field model, how typed text becomes a
 *  value) and hands it in as [begin]'s `onCommit`; this owns the row swap, focus, key
 *  interception and the Autofill workaround below, which the host must cooperate with in three
 *  ways: call [attachTo] on its root layout, route [Activity.dispatchKeyEvent] through
 *  [dispatchKeyEvent], call [cancelAutofill] from `onDestroy`, and carry all its unsaved state
 *  across [Activity.recreate] via `onRetainNonConfigurationInstance` - every commit recreates
 *  the Activity.
 *
 *  Why every commit recreates: formerly documented as an unfixable T9-IME-level bug
 *  (DPAD_CENTER/STAR/BACK/# permanently undelivered to dispatchKeyEvent after any EditText took
 *  real focus, cleared only by a full force-stop + relaunch — arrow keys unaffected). Root-caused
 *  instead to Android's own Autofill Framework: this device queries its active AutofillService
 *  (confirmed: Bitwarden) the moment the EditText gets real focus, popping a real "Autofill UI"
 *  system window that steals the Activity's window focus, even though the field is excluded via
 *  importantForAutofill (confirmed on-device: that hint, and FLAG_SECURE, are both advisory
 *  here, not enforced — neither stopped the popup). That focus churn corrupts the window's
 *  key-dispatch state for the rest of the process. No purely-preventive fix was found (tried:
 *  importantForAutofill on the view and on an ancestor, FLAG_SECURE, deferring real focus to the
 *  first keystroke — none stopped the trigger). What *does* work, confirmed live: recreating the
 *  Activity — its dispatch state resets cleanly, exactly like the process-restart workaround but
 *  scoped to just this screen. So every commit unconditionally self-heals by recreating, with
 *  the host's onRetainNonConfigurationInstance keeping its in-memory (not-yet-saved-to-provider)
 *  edits. See KNOWN_ISSUE_T9_IME.md for the full investigation history. */
class InlineRowEditor(
    private val activity: Activity,
    private val rowContainer: LinearLayout,
    /** Blocks descendant focus while nothing is edited, so the d-pad's own row navigation never
     *  fights Android's focus search — lifted only while an EditText needs to take focus. */
    private val scrollView: ScrollView,
    /** Up/Down after a commit: the host's own row navigation. */
    private val onMove: (direction: Int) -> Unit,
    /** After a commit has swapped the row back, just before the recreate: the host redraws. */
    private val onCommitted: () -> Unit,
) {

    private class Session(val editRow: View, val editText: EditText, val onCommit: (String) -> Unit, val restoreRow: () -> View)

    private var session: Session? = null

    // No row TextView is ever focusable (see [scrollView]), so once an inline-edit EditText is
    // removed there is nothing else in the layout for Android's focus system to hand focus to —
    // confirmed on-device that View.clearFocus() then just re-grants the same (now-detached)
    // EditText focus, silently swallowing every later DPAD_CENTER/STAR press (they reach the dead
    // EditText's own key handling instead of the Activity's onKeyDown) while arrow keys kept
    // working, since plain Views don't specially consume those. This always-present,
    // never-removed anchor is the reclaim target instead.
    private val keyFocusAnchor = View(activity).apply {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    val isEditing: Boolean get() = session != null

    /** Adds the focus anchor to [root] — last, after the host's own children (as a root sibling,
     *  so it doesn't shift any existing child index the editors' tests walk by fixed
     *  getChildAt() positions) — and marks the whole screen not important for Autofill. None of
     *  the editors' EditTexts are ever credentials, but Android's Autofill framework doesn't know
     *  that. That hint alone does NOT stop a service from offering a suggestion (confirmed
     *  on-device: a real "Autofill UI" popup still appeared despite it, and despite the same flag
     *  set directly on the EditText too). Kept as a best-effort default anyway; the actual fix for
     *  the popup's fallout is the recreate() on every commit — see the class comment. */
    fun attachTo(root: FrameLayout) {
        root.addView(keyFocusAnchor, FrameLayout.LayoutParams(0, 0))
        root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    /** Swaps [row] for [prefix] (e.g. "Starts:    Mon 3 Aug, " or "Title:     ", rendered as
     *  static text ahead of the editable value so the row keeps its label while being edited) and
     *  [editText], and gives the EditText focus. [onCommit] gets the typed text, untrimmed, when
     *  the edit ends; [restoreRow] is what then takes the edit row's place. Returns the edit row,
     *  or null (and nothing changes) when [row] isn't in the container. */
    fun begin(row: View, prefix: String, editText: EditText, onCommit: (String) -> Unit, restoreRow: () -> View): View? {
        val index = rowContainer.indexOfChild(row)
        if (index < 0) return null
        editText.apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            background = null
            setPadding(0, dp(6), dp(Dimens.SIDE_PADDING_DP), dp(6))
            // Kept as a best-effort hint even though it alone doesn't suppress the popup on this
            // device (confirmed live — see the class comment for the real fix).
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val editRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Dimens.SIDE_PADDING_DP), 0, 0, 0)
            addView(
                TextView(activity).apply {
                    text = prefix
                    textSize = 16f
                    setTextColor(Palette.GRAY_DIM)
                },
            )
            addView(editText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        rowContainer.removeViewAt(index)
        rowContainer.addView(editRow, index)
        session = Session(editRow, editText, onCommit, restoreRow)
        scrollView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        editText.requestFocus()
        return editRow
    }

    /** Ends the edit in progress, if any: the host's onCommit, the row swapped back, then
     *  [onCommitted] and the self-healing recreate() (see the class comment). */
    fun commit() {
        val active = session ?: return
        active.onCommit(active.editText.text.toString())
        // Confirmed on-device: [keyFocusAnchor]'s requestFocus() must happen *before* the
        // EditText is removed from the hierarchy — moving focus while both are still attached is
        // the reliable transfer; clearFocus() alone (tried first) just re-granted the same,
        // now-detached EditText focus since nothing else in the row layout is focusable.
        keyFocusAnchor.requestFocus()
        val index = rowContainer.indexOfChild(active.editRow)
        if (index >= 0) {
            rowContainer.removeViewAt(index)
            rowContainer.addView(active.restoreRow(), index)
        }
        scrollView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        session = null
        onCommitted()
        activity.recreate()
    }

    /** For the host's [Activity.dispatchKeyEvent], ahead of normal dispatch: while editing, the
     *  focused EditText would otherwise swallow Up/Down (cursor/focus search) and give OK/Back no
     *  defined meaning, so all four commit the field and (for Up/Down) move on. True when the
     *  event was taken. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (session == null || event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> commit()
            KeyEvent.KEYCODE_DPAD_UP -> {
                commit()
                onMove(-1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                commit()
                onMove(1)
            }
            else -> return false
        }
        return true
    }

    /** For the host's `onDestroy`. Distinct from the fill-suggestion focus-steal the recreate()
     *  works around — this is Android's separate Save flow, which pops "Save to Bitwarden?" after
     *  the screen's fields are committed and the window goes away, since none of these EditTexts
     *  are ever credentials but the platform's heuristic doesn't know that. cancel() (unlike
     *  commit()) explicitly discards the pending autofill context instead of letting it evaluate
     *  for a save prompt — called unconditionally on every destroy (including the mid-session
     *  recreate()) since that prompt is never wanted, however the Activity instance ends. */
    fun cancelAutofill() {
        activity.getSystemService(AutofillManager::class.java)?.cancel()
    }
}
