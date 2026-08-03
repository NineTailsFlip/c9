package life.furrer.c9.calendar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.contacts.CONTACT_ROW_HEIGHT_DP
import life.furrer.nine.contacts.ContactEntry
import life.furrer.nine.contacts.ContactRow
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.DataFormat
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.contacts.bindContactRow
import life.furrer.nine.contacts.buildContactRows
import life.furrer.nine.contacts.contactOrNull
import life.furrer.nine.contacts.contactRowHeightDp
import life.furrer.nine.contacts.createContactRowContainer
import life.furrer.nine.contacts.isSelectable
import life.furrer.nine.contacts.letterGroupJumpIndex
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.SearchField
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

private const val SIDE_PADDING_DP = 16
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
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

/** Picks an RFC 5545 ATTENDEE's email — from the contacts list (by name) or typed directly, per
 *  the calendar invite feature's own "choose from contacts, or provide an email address"
 *  requirement. Reuses the contacts module's own [ContactRow]/[buildContactRows]/[bindContactRow]
 *  rather than a bespoke flat list, so this looks and drives exactly like the real contacts
 *  picker — letter-grouped browsing, Left/Right letter-jump, underline-on-selected — with one
 *  extra pseudo-row (typed-email) prepended when the query looks like an address, matching the
 *  card AppPickerActivity's own picker already does with a pseudo-row.
 *
 *  A contact with more than one email address doesn't get a chooser menu *here* — confirmed
 *  on-device that showing one while [searchField]'s EditText still holds a real, non-empty-query
 *  IME connection lets this hardware's T9 IME keep composing into that field independently of
 *  the menu (the same "processes keys independently of Activity dispatch" quirk [SearchField]'s
 *  own doc describes elsewhere, just a different symptom: composing garbage into the field
 *  instead of merely swallowing a key). Returning every candidate via [EXTRA_EMAILS] and letting
 *  the caller — [EventDetailActivity]/[EventEditActivity], neither of which has a search field of
 *  their own to fight with — show that chooser instead sidesteps the whole quirk rather than
 *  working around it here. A contact with no email address can't be picked at all (there's
 *  nothing to invite). */
class AttendeePickerActivity : Activity() {

    private var contacts: List<ContactEntry> = emptyList()
    private var contactRows: List<ContactRow> = emptyList()
    /** The typed-query pseudo-row's text, or null when the query doesn't look like an email —
     *  occupies row 0 ahead of [contactRows] whenever non-null. */
    private var typedEmailQuery: String? = null

    private lateinit var searchField: SearchField
    private lateinit var rowListView: RowListView
    private lateinit var hintBar: HintBar

    private val rowOffset: Int get() = if (typedEmailQuery != null) 1 else 0

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rowOffset + contactRows.size
        override fun createRowView(context: Context): View = createContactRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            val typed = typedEmailQuery
            if (typed != null && index == 0) {
                bindTypedEmailRow(view as LinearLayout, typed, selected)
            } else {
                bindContactRow(view as LinearLayout, contactRows[index - rowOffset], selected)
            }
        }
        override fun rowHeight(index: Int): Int {
            val typed = typedEmailQuery
            return if (typed != null && index == 0) CONTACT_ROW_HEIGHT_DP else contactRowHeightDp(contactRows[index - rowOffset])
        }
        override fun isSelectable(index: Int): Boolean {
            val typed = typedEmailQuery
            return (typed != null && index == 0) || contactRows[index - rowOffset].isSelectable()
        }
    }

    private fun bindTypedEmailRow(container: LinearLayout, email: String, selected: Boolean) {
        container.removeAllViews()
        container.addView(
            TextView(container.context).apply {
                text = "Add \"$email\""
                textSize = 18f
                setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), 0)
                applyRowSelectionStyle(selected)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
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
        hintBar = HintBar(this)

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
        updateHint()

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
        contactRows = buildContactRows(contacts, query, favoritesOnly = false)
        typedEmailQuery = query.takeIf { looksLikeEmail(it) }
        rowListView.notifyDataChanged()
        updateHint()
    }

    private fun updateHint() {
        hintBar.setText(if (searchField.query.isEmpty()) "‹ clear · ok add · ← → letter" else "‹ clear · ok add")
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
            KeyEvent.KEYCODE_DPAD_LEFT -> jumpLetterGroup(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> jumpLetterGroup(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> confirmSelected()
        }
    }

    /** Offsets into/out of [contactRows] around the typed-email pseudo-row exactly like [binder]
     *  does, so a jump always lands on a real header's first entry regardless of whether that
     *  pseudo-row is currently occupying index 0. */
    private fun jumpLetterGroup(direction: Int) {
        val next = letterGroupJumpIndex(contactRows, rowListView.selectedIndex - rowOffset, direction) ?: return
        rowListView.setSelectedIndex(next + rowOffset)
    }

    private fun confirmSelected() {
        val typed = typedEmailQuery
        val index = rowListView.selectedIndex
        if (typed != null && index == 0) {
            returnSingleResult(typed, name = null)
            return
        }
        val contact = contactRows.getOrNull(index - rowOffset)?.contactOrNull() ?: return
        resolveEmails(contact)
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
