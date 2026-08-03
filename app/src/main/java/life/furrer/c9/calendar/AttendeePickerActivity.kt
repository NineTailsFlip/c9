package life.furrer.c9.calendar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.contacts.ContactEntry
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.DataFormat
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.SearchField
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.util.StringMatch

private const val SIDE_PADDING_DP = 16
private const val ROW_HEIGHT_DP = 32
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
)

/** A typed query counts as "add this address directly" once it has an `@` and something that
 *  looks like a domain after it — deliberately loose (no real RFC 5322 validation) since the
 *  provider/sync adapter is the real authority on whether an address is well-formed, the same
 *  way [life.furrer.nine.core.util.StringMatch.looksDialable] leaves real validation to the
 *  dialer. */
private fun looksLikeEmail(query: String): Boolean {
    val at = query.indexOf('@')
    if (at <= 0 || at == query.length - 1) return false
    return query.substring(at + 1).contains('.')
}

private sealed interface PickerRow {
    data class TypedEmail(val email: String) : PickerRow
    data class Contact(val entry: ContactEntry) : PickerRow
}

/** Picks an RFC 5545 ATTENDEE's email — from the contacts list (by name) or typed directly, per
 *  the calendar invite feature's own "choose from contacts, or provide an email address"
 *  requirement.
 *
 *  A contact with more than one email address doesn't get a chooser menu *here* — confirmed
 *  on-device that showing one while [searchField]'s EditText still holds a real, non-empty-query
 *  IME connection lets this hardware's T9 IME keep composing into that field independently of
 *  the menu (the same "processes keys independently of Activity dispatch" quirk [SearchField]'s
 *  own doc describes elsewhere, just a different symptom: composing garbage into the field
 *  instead of merely swallowing a key). Returning every candidate via [EXTRA_EMAILS] and letting
 *  the caller — [EventDetailActivity], which has no search field of its own to fight with —
 *  show that chooser instead sidesteps the whole quirk rather than working around it here. A
 *  contact with no email address can't be picked at all (there's nothing to invite). */
class AttendeePickerActivity : Activity() {

    private var contacts: List<ContactEntry> = emptyList()
    private var rows: List<PickerRow> = emptyList()

    private lateinit var searchField: SearchField
    private lateinit var rowListView: RowListView
    private lateinit var hintBar: HintBar

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = TextView(context).apply {
            textSize = 16f
            setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), 0)
        }
        override fun bind(view: View, index: Int, selected: Boolean) {
            val row = rows[index]
            (view as TextView).apply {
                text = when (row) {
                    is PickerRow.TypedEmail -> "Add \"${row.email}\""
                    is PickerRow.Contact -> row.entry.displayName
                }
                setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        override fun rowHeight(index: Int): Int = ROW_HEIGHT_DP
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        searchField = SearchField(this).apply {
            onQueryChanged = { rebuildRows() }
            editText.apply {
                hint = "Name or email"
                textSize = 16f
                setTextColor(Color.WHITE)
                setHintTextColor(Palette.GRAY_FAINT)
                setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(6))
            }
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        hintBar = HintBar(this).apply { setText("‹ clear · ok add") }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchField.editText)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()

        loadContacts()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            searchField.onWindowFocusGained()
            hideStatusBar()
        }
    }

    override fun onPause() {
        super.onPause()
        searchField.onPause()
    }

    private fun loadContacts() {
        Async.run(
            background = { ContactStore.queryContacts(this) },
            onResult = { loaded ->
                contacts = loaded
                rebuildRows()
            },
        )
    }

    private fun rebuildRows() {
        val query = searchField.query
        val matches = contacts.filter { StringMatch.matchesName(it.displayName, query) }
            .map<ContactEntry, PickerRow> { PickerRow.Contact(it) }
        rows = if (looksLikeEmail(query)) {
            listOf(PickerRow.TypedEmail(query)) + matches
        } else {
            matches
        }
        rowListView.notifyDataChanged()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
            onBackNotConsumedByIme()
            return true
        }
        if (searchField.handleKeyEvent(event, RESERVED_KEYS)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in RESERVED_KEYS) {
                handleReservedKeyDown(event.keyCode)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBackNotConsumedByIme() {
        if (searchField.query.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
        } else {
            searchField.clear()
        }
    }

    private fun handleReservedKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> confirmSelected()
        }
    }

    private fun confirmSelected() {
        when (val row = rows.getOrNull(rowListView.selectedIndex) ?: return) {
            is PickerRow.TypedEmail -> returnSingleResult(row.email, name = null)
            is PickerRow.Contact -> resolveEmails(row.entry)
        }
    }

    private fun resolveEmails(entry: ContactEntry) {
        Async.run(
            background = {
                val detail = ContactStore.queryContactDetail(this, entry.id)
                detail?.rows
                    ?.filter { DataKind.fromMimetype(it.mimetype) == DataKind.EMAIL }
                    ?.map { DataFormat.valueText(DataKind.EMAIL, it.values) }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?: emptyList()
            },
            onResult = { emails -> onEmailsResolved(entry, emails) },
        )
    }

    private fun onEmailsResolved(entry: ContactEntry, emails: List<String>) {
        when {
            emails.isEmpty() -> Toast.makeText(this, "${entry.displayName} has no email address", Toast.LENGTH_SHORT).show()
            emails.size == 1 -> returnSingleResult(emails.single(), entry.displayName)
            else -> returnMultipleResult(emails, entry.displayName)
        }
    }

    private fun returnSingleResult(email: String, name: String?) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_EMAIL, email)
                putExtra(EXTRA_NAME, name)
            },
        )
        finish()
    }

    private fun returnMultipleResult(emails: List<String>, name: String) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_EMAILS, emails.toTypedArray())
                putExtra(EXTRA_NAME, name)
            },
        )
        finish()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        /** Set when exactly one email was resolvable — the whole answer, nothing more to ask. */
        const val EXTRA_EMAIL = "email"
        /** Set instead of [EXTRA_EMAIL] when a contact has more than one — the caller must ask
         *  which. */
        const val EXTRA_EMAILS = "emails"
        const val EXTRA_NAME = "name"
    }
}
