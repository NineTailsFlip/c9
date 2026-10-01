package life.furrer.c9.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.contacts.ContactGroups
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactStore.WritableAccount
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

/** The name entry behind Groups → "New group" and "Rename": one text field, OK saves, Back
 *  cancels. It does the write itself (via [ContactGroups]) and closes; the groups list notices
 *  the provider change on its own.
 *
 *  Same defenses as [ContactEditActivity]'s inline editor, minus the `recreate()` self-heal: that
 *  one repairs key dispatch after the Autofill popup steals focus *mid-screen*, and here the
 *  screen ends right after the one commit, so there is nothing left to repair. What does apply:
 *  the field is excluded from Autofill, OK/Back are taken in [dispatchKeyEvent] before the
 *  focused EditText can swallow them, and the Autofill session is cancelled *before* [finish]
 *  (not only in onDestroy) so no "Save to password manager?" prompt follows the screen. */
class GroupNameActivity : Activity() {

    private lateinit var editText: EditText
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        val renaming = intent.hasExtra(EXTRA_GROUP_ID)
        val heading = TextView(this).apply {
            text = if (renaming) "Rename group" else "New group in ${intent.getStringExtra(EXTRA_ACCOUNT_NAME)}"
            textSize = 13f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(16), dp(Dimens.SIDE_PADDING_DP), dp(6))
        }
        editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            textSize = 18f
            setTextColor(Color.WHITE)
            hint = "Name"
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(6), dp(Dimens.SIDE_PADDING_DP), dp(6))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(savedInstanceState?.getString(STATE_TEXT) ?: intent.getStringExtra(EXTRA_TITLE).orEmpty())
            setSelection(text.length)
        }
        val hintBar = HintBar(this).apply { setText("ok save · ‹ cancel") }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                addView(heading)
                addView(editText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(View(this@GroupNameActivity), LinearLayout.LayoutParams(0, 0, 1f))
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
        cancelAutofillSession()
    }

    private fun cancelAutofillSession() {
        getSystemService(AutofillManager::class.java)?.cancel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    save()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    cancelAutofillSession()
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun save() {
        if (saving) return
        val title = editText.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, "Needs a name", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        val app = applicationContext
        val renaming = intent.hasExtra(EXTRA_GROUP_ID)
        val group = if (renaming) groupFromIntent() else null
        val account = if (renaming) null else WritableAccount(
            intent.getStringExtra(EXTRA_ACCOUNT_NAME).orEmpty(), intent.getStringExtra(EXTRA_ACCOUNT_TYPE).orEmpty(),
        )
        Async.run(
            background = {
                if (group != null) ContactGroups.renameGroup(app, group, title) else ContactGroups.createGroup(app, account!!, title) != null
            },
            onResult = { ok ->
                if (ok) {
                    cancelAutofillSession()
                    finish()
                } else {
                    saving = false
                    Toast.makeText(this, if (renaming) "Couldn't rename" else "Couldn't create group", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    private fun groupFromIntent() = ContactGroup(
        intent.getLongExtra(EXTRA_GROUP_ID, -1L),
        intent.getStringExtra(EXTRA_TITLE).orEmpty(),
        intent.getStringExtra(EXTRA_ACCOUNT_NAME).orEmpty(),
        intent.getStringExtra(EXTRA_ACCOUNT_TYPE).orEmpty(),
    )

    companion object {
        private const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ACCOUNT_NAME = "account_name"
        private const val EXTRA_ACCOUNT_TYPE = "account_type"
        private const val STATE_TEXT = "text"

        /** Asks for a name and creates the group in [account]. */
        fun createIntent(context: Context, account: WritableAccount): Intent =
            Intent(context, GroupNameActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_NAME, account.name)
                .putExtra(EXTRA_ACCOUNT_TYPE, account.type)

        /** Asks for a new name for [group], prefilled with the current one. */
        fun renameIntent(context: Context, group: ContactGroup): Intent =
            Intent(context, GroupNameActivity::class.java)
                .putExtra(EXTRA_GROUP_ID, group.id)
                .putExtra(EXTRA_TITLE, group.title)
                .putExtra(EXTRA_ACCOUNT_NAME, group.accountName)
                .putExtra(EXTRA_ACCOUNT_TYPE, group.accountType)
    }
}
