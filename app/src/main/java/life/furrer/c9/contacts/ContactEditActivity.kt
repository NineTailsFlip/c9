package life.furrer.c9.contacts

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.text.InputType
import android.util.TypedValue
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
import android.widget.Toast
import life.furrer.nine.core.util.Async
import life.furrer.c9.core.HintBar
import life.furrer.c9.core.MenuItem
import life.furrer.c9.core.MenuSheet
import life.furrer.c9.core.Prefs
import life.furrer.c9.core.applyConfiguredTheme
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.hideStatusBar

private const val SIDE_PADDING_DP = 16

/** One editable row. Every field here is a single plain-text value — §9.11's own mockup already
 *  simplifies StructuredName down to just First/Last, and this editor goes one step further,
 *  treating Name and Organisation as one combined text each ("First Last", "Company · Title",
 *  split back apart on save) rather than separate sub-fields — a deliberate scope cut to keep
 *  this editor's row model uniform (one row = one field = one editable string) given the
 *  milestone's real bar — a correct write path that preserves everything it doesn't touch — not
 *  full parity with every structured sub-field the provider supports. Rows *are* grouped under a
 *  section header per kind though, matching §9.11's mockup and the read-only detail screen
 *  (ContactDetailRows.kt) applied here too — see [sortFieldsByKind]. */
private data class EditorField(
    val kind: DataKind,
    var dataRowId: Long?,
    var rawContactId: Long?,
    var text: String,
    var typeLabel: String,
)

/** Fields this editor knows how to add via `*` → "Add field" (§9.12) — every officially
 *  supported CardDAV-relevant kind except Identity (a sync-provider-internal identifier, never
 *  user-authored) and GroupMembership/Photo (their own dedicated screens/flows). Each kind can
 *  be added more than once — a second Phone, a second Date with a different type (Birthday vs.
 *  Anniversary vs. Other), etc. — [addField] never checks for an existing row of the same kind
 *  before appending. */
private val ADDABLE_KINDS = listOf(
    DataKind.PHONE, DataKind.EMAIL, DataKind.WEBSITE, DataKind.NICKNAME, DataKind.NOTE,
    DataKind.ORGANIZATION, DataKind.POSTAL, DataKind.EVENT, DataKind.RELATION, DataKind.IM, DataKind.SIP,
)

class ContactEditActivity : Activity() {

    private var contactId: Long? = null
    private var rawContactId: Long? = null
    private var account: ContactStore.WritableAccount? = null
    private var preservedUnknownRows: List<DataRow> = emptyList()
    private var deletedDataRowIds: MutableList<Long> = mutableListOf()

    private var fields: MutableList<EditorField> = mutableListOf()
    private val fieldRowViews = mutableListOf<View>()

    // Parallel to `fields`/`fieldRowViews` — the section header view immediately preceding a
    // field's own row, or null when it isn't the first field of its kind. Lets [render] scroll a
    // group's header into view together with its first field, instead of pinning the field's own
    // top to y=0 and scrolling the header (which sits just above it) off-screen.
    private val headerBeforeField = mutableListOf<View?>()
    private var selectedIndex = 0
    private var loaded = false

    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var keyFocusAnchor: View

    private var inlineEditIndex: Int? = null
    private var inlineEditText: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L).takeIf { it != -1L }

        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            addView(rowContainer)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        hintBar = HintBar(this).apply { setText("* menu · ok edit field · ‹ done") }
        menuSheet = MenuSheet(this)
        // See EventEditActivity's own keyFocusAnchor for why this exists: without a real,
        // always-focusable anchor to reclaim focus, an inline EditText that's removed from the
        // hierarchy can leave a dangling focused-view reference that silently swallows the next
        // OK/`*` press — confirmed on-device during M3, fixed the same way here up front.
        keyFocusAnchor = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(keyFocusAnchor, FrameLayout.LayoutParams(0, 0))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()

        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedState
        if (retained != null) {
            contactId = retained.contactId
            rawContactId = retained.rawContactId
            account = retained.account
            preservedUnknownRows = retained.preservedUnknownRows
            deletedDataRowIds = retained.deletedDataRowIds
            fields = retained.fields
            selectedIndex = retained.selectedIndex
            loaded = true
            rebuildRows()
        } else {
            loadInitialData()
        }
    }

    /** All in-memory edits not yet written to the contacts provider — carried across [recreate]
     *  by [commitInlineEdit] via [onRetainNonConfigurationInstance], so that self-heal doesn't
     *  discard unsaved work. See EventEditActivity.RetainedState for the matching pattern there. */
    private data class RetainedState(
        val contactId: Long?,
        val rawContactId: Long?,
        val account: ContactStore.WritableAccount?,
        val preservedUnknownRows: List<DataRow>,
        val deletedDataRowIds: MutableList<Long>,
        val fields: MutableList<EditorField>,
        val selectedIndex: Int,
    )

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = RetainedState(
        contactId, rawContactId, account, preservedUnknownRows, deletedDataRowIds, fields, selectedIndex,
    )

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    // Distinct from the fill-suggestion focus-steal bug fixed via recreate() in commitInlineEdit
    // (KNOWN_ISSUE_T9_IME.md) — this is Android's separate Save flow, which pops "Save to
    // Bitwarden?" after this screen's fields are committed and the window goes away, since none
    // of this screen's EditTexts are ever credentials but the platform's heuristic doesn't know
    // that. cancel() (unlike commit()) explicitly discards the pending autofill context instead of
    // letting it evaluate for a save prompt — called unconditionally on every destroy (including
    // the mid-session recreate()) since we never want that prompt regardless of how this Activity
    // instance ends.
    override fun onDestroy() {
        super.onDestroy()
        getSystemService(AutofillManager::class.java)?.cancel()
    }

    private fun loadInitialData() {
        val id = contactId
        if (id != null) {
            Async.run(
                background = { ContactStore.queryContactDetail(this, id) },
                onResult = { detail -> if (detail == null) finish() else loadExisting(detail) },
            )
        } else {
            fields = mutableListOf(EditorField(DataKind.NAME, dataRowId = null, rawContactId = null, text = "", typeLabel = "Name"))
            Async.run(
                background = { ContactStore.queryWritableAccounts(this) },
                onResult = { accounts ->
                    when (accounts.size) {
                        // §6.2: "asks which account/address book to write to... if exactly one
                        // writable account exists, don't ask." Confirmed on-device why this
                        // matters: on a phone whose only raw-contact accounts happen to be
                        // WhatsApp's and Signal's own shadow accounts alongside the real CardDAV
                        // one, silently picking "the first available account" can pick one of
                        // those instead of the real address book — asking, rather than guessing,
                        // is the only way to get this right without hardcoding knowledge of any
                        // particular account type (an explicit non-goal, §1: "do not hardcode its
                        // package name"). Settings' "Default address book" lets a user opt out of
                        // being asked every time once they know which account they want.
                        0 -> {}
                        1 -> account = accounts.single()
                        else -> {
                            val defaultName = Prefs.defaultAccountName(this)
                            val defaultType = Prefs.defaultAccountType(this)
                            val default = accounts.find { it.name == defaultName && it.type == defaultType }
                            if (default != null) account = default else promptAccountChoice(accounts)
                        }
                    }
                    loaded = true
                    rebuildRows()
                },
            )
        }
    }

    private fun loadExisting(detail: ContactDetail) {
        rawContactId = detail.rawContacts.firstOrNull()?.rawContactId
        val newFields = mutableListOf<EditorField>()
        val unknown = mutableListOf<DataRow>()
        var name = ""
        detail.rows.forEach { row ->
            when (val kind = DataKind.fromMimetype(row.mimetype)) {
                DataKind.NAME -> name = DataFormat.valueText(DataKind.NAME, row.values)
                DataKind.ORGANIZATION -> {
                    val org = DataFormat.valueText(DataKind.ORGANIZATION, row.values)
                    newFields.add(EditorField(DataKind.ORGANIZATION, row.id, row.rawContactId, org, "Organisation"))
                }
                DataKind.PHONE, DataKind.EMAIL, DataKind.WEBSITE, DataKind.NICKNAME, DataKind.NOTE,
                DataKind.POSTAL, DataKind.RELATION, DataKind.IM, DataKind.SIP -> {
                    val label = DataFormat.typeLabel(resources, kind, row.values) ?: kind.sectionLabel
                    newFields.add(EditorField(kind, row.id, row.rawContactId, DataFormat.valueText(kind, row.values), label))
                }
                DataKind.EVENT -> {
                    // Edited as the raw vCard date string ("1990-05-12" or the no-year
                    // "--05-12"), not [DataFormat.formatEventDate]'s "12 May 1990" — that
                    // formatter is one-way (no parser back to the raw form), and round-tripping
                    // through it would either lose the no-year case or require guessing a year.
                    val label = DataFormat.typeLabel(resources, kind, row.values) ?: kind.sectionLabel
                    val raw = row.values.getAsString(CDK.Event.START_DATE).orEmpty()
                    newFields.add(EditorField(kind, row.id, row.rawContactId, raw, label))
                }
                DataKind.PHOTO, DataKind.GROUP_MEMBERSHIP -> {}
                null -> unknown.add(row)
                else -> unknown.add(row) // Identity — sync-provider internal, never user-editable
            }
        }
        preservedUnknownRows = unknown
        fields = (
            mutableListOf(EditorField(DataKind.NAME, detail.rows.firstOrNull { DataKind.fromMimetype(it.mimetype) == DataKind.NAME }?.id, rawContactId, name, "Name")) +
                newFields
            ).toMutableList()
        sortFieldsByKind()
        loaded = true
        rebuildRows()
    }

    /** Groups same-kind fields together (a stable sort — multiple rows of one kind keep their
     *  own relative order) so [rebuildRows] can emit one section header per contiguous run,
     *  regardless of the order fields were loaded from the provider or added via "Add field".
     *  Callers that change which fields exist (load, add, delete) call this and must then locate
     *  the field they care about by identity, not by whatever index it used to be at. */
    private fun sortFieldsByKind() {
        fields.sortBy { it.kind.ordinal }
    }

    private fun rebuildRows() {
        rowContainer.removeAllViews()
        fieldRowViews.clear()
        headerBeforeField.clear()
        var lastKind: DataKind? = null
        fields.forEach { field ->
            var header: View? = null
            if (field.kind != lastKind) {
                header = makeSectionHeader(field.kind.sectionLabel)
                rowContainer.addView(header)
                lastKind = field.kind
            }
            val row = makeRow()
            row.text = "${labelFor(field)}:  ${field.text.ifBlank { "(empty)" }}"
            fieldRowViews.add(row)
            headerBeforeField.add(header)
            rowContainer.addView(row)
        }
        selectedIndex = selectedIndex.coerceIn(0, (fields.size - 1).coerceAtLeast(0))
        render()
    }

    // The section header now names the kind (matching mockups.html §9.11's own render), so every
    // row's own label can always be its most specific type — Mobile/Work/Home, Birthday/
    // Anniversary/Other — never the generic kind name a lone field used to fall back to.
    private fun labelFor(field: EditorField): String = field.typeLabel.padEnd(11)

    // Matches mockups.html's `.sect` exactly (10dp top / 16dp side / 3dp bottom padding, 11sp,
    // letter-spacing .11em) — the same convention already used by the contact list's own
    // letter-group headers and the detail screen's per-kind headers.
    private fun makeSectionHeader(label: String): TextView = TextView(this).apply {
        text = label.uppercase()
        textSize = 11f
        letterSpacing = 0.11f
        setTextColor(Palette.GRAY_FAINT)
        setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(3))
    }

    private fun render() {
        fieldRowViews.forEachIndexed { i, view ->
            val field = fields.getOrNull(i) ?: return@forEachIndexed
            if (view is TextView) {
                view.text = "${labelFor(field)}:  ${field.text.ifBlank { "(empty)" }}"
                view.applyRowSelectionStyle(i == selectedIndex)
            }
        }
        val scrollTarget = headerBeforeField.getOrNull(selectedIndex) ?: fieldRowViews.getOrNull(selectedIndex)
        scrollTarget?.let { view -> scrollView.post { scrollView.smoothScrollTo(0, view.top) } }
    }

    private fun makeRow(): TextView = TextView(this).apply {
        textSize = 16f
        setPadding(dp(SIDE_PADDING_DP), dp(6), dp(SIDE_PADDING_DP), dp(6))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inlineEditIndex != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    commitInlineEdit()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    commitInlineEdit()
                    moveSelection(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    commitInlineEdit()
                    moveSelection(1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        if (!loaded) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                beginInlineEdit(selectedIndex)
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                openMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                attemptSaveAndClose()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveSelection(direction: Int) {
        if (fields.isEmpty()) return
        selectedIndex = (selectedIndex + direction + fields.size) % fields.size
        render()
    }

    private fun beginInlineEdit(index: Int) {
        val field = fields.getOrNull(index) ?: return
        val currentView = fieldRowViews.getOrNull(index) ?: return
        // Header rows mean `fields[index]` no longer sits at `rowContainer`'s own child `index`
        // — find the row's *actual* position in the container rather than assuming they match.
        val containerIndex = rowContainer.indexOfChild(currentView)
        if (containerIndex < 0) return
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            textSize = 16f
            setTextColor(Color.WHITE)
            background = null
            setPadding(0, dp(6), dp(SIDE_PADDING_DP), dp(6))
            setText(field.text)
            setSelection(text.length)
            // Kept as a best-effort hint even though it alone doesn't suppress the popup on this
            // device (confirmed live — see commitInlineEdit's recreate() call for the real fix).
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val prefixLabel = TextView(this).apply {
            text = "${labelFor(field)}:  "
            textSize = 16f
            setTextColor(Palette.GRAY_DIM)
        }
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE_PADDING_DP), 0, 0, 0)
            addView(prefixLabel)
            addView(editText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        rowContainer.removeViewAt(containerIndex)
        rowContainer.addView(rowView, containerIndex)
        fieldRowViews[index] = rowView
        inlineEditIndex = index
        inlineEditText = editText
        scrollView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        editText.requestFocus()
    }

    private fun commitInlineEdit() {
        val index = inlineEditIndex ?: return
        val editText = inlineEditText ?: return
        val currentView = fieldRowViews.getOrNull(index) ?: return
        val containerIndex = rowContainer.indexOfChild(currentView)
        fields.getOrNull(index)?.text = editText.text.toString().trim()
        keyFocusAnchor.requestFocus()
        if (containerIndex >= 0) {
            val row = makeRow()
            rowContainer.removeViewAt(containerIndex)
            rowContainer.addView(row, containerIndex)
            fieldRowViews[index] = row
        }
        scrollView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        inlineEditIndex = null
        inlineEditText = null
        render()
        // See EventEditActivity.commitInlineEdit for the full root-cause writeup: this was an
        // Autofill-popup-triggered focus-stealing bug (Bitwarden as the active AutofillService),
        // not a T9-IME-level issue, and no purely-preventive fix was found — recreate() here
        // self-heals the corrupted key-dispatch state every time, restoring in-memory (not yet
        // saved to the provider) state via onRetainNonConfigurationInstance/RetainedState.
        recreate()
    }

    private fun promptAccountChoice(accounts: List<ContactStore.WritableAccount>) {
        menuSheet.show(
            accounts.map { candidate ->
                MenuItem("${candidate.name} (${candidate.type.substringAfterLast('.')})") {
                    account = candidate
                }
            },
        )
    }

    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        items.add(MenuItem("Add field") { openAddFieldMenu() })
        if (fields.getOrNull(selectedIndex)?.kind != DataKind.NAME) {
            items.add(MenuItem("Delete this field") { deleteSelectedField() })
        }
        items.add(MenuItem("Discard") { finish() })
        if (contactId != null) items.add(MenuItem("Delete contact") { confirmDeleteContact() })
        menuSheet.show(items)
    }

    private fun openAddFieldMenu() {
        menuSheet.show(
            ADDABLE_KINDS.map { kind ->
                MenuItem(kind.sectionLabel) { addField(kind) }
            },
        )
    }

    private fun addField(kind: DataKind) {
        val defaultLabel = when (kind) {
            DataKind.PHONE -> "Mobile"
            DataKind.EMAIL, DataKind.WEBSITE, DataKind.POSTAL, DataKind.SIP -> "Home"
            DataKind.EVENT -> "Birthday"
            DataKind.RELATION -> "Spouse"
            DataKind.IM -> "Custom"
            else -> kind.sectionLabel
        }
        val newField = EditorField(kind, dataRowId = null, rawContactId = rawContactId, text = "", typeLabel = defaultLabel)
        fields.add(newField)
        sortFieldsByKind()
        selectedIndex = fields.indexOf(newField)
        rebuildRows()
        beginInlineEdit(selectedIndex)
    }

    private fun deleteSelectedField() {
        val field = fields.getOrNull(selectedIndex) ?: return
        field.dataRowId?.let { deletedDataRowIds.add(it) }
        fields.removeAt(selectedIndex)
        rebuildRows()
    }

    private fun confirmDeleteContact() {
        menuSheet.show(
            listOf(
                MenuItem("Yes, delete") { deleteContactAndFinish() },
                MenuItem("Keep contact") {},
            ),
        )
    }

    private fun deleteContactAndFinish() {
        val id = contactId ?: return
        Async.run(
            background = { ContactStore.deleteContact(this, id) },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show()
                finish()
            },
        )
    }

    /** A new contact needs a name before it can be written (see [saveNewContact]) — Back used to
     *  just Toast "Needs at least a name" and stop there with no way to leave the screen (a real
     *  bug: pressing Back repeatedly did nothing else). Mirrors EventEditActivity's own
     *  attemptSaveAndClose()/blank-title handling: offer Discard instead of refusing to leave. */
    private fun attemptSaveAndClose() {
        val nameField = fields.firstOrNull { it.kind == DataKind.NAME }
        if (contactId == null && (nameField == null || nameField.text.isBlank())) {
            menuSheet.show(
                listOf(
                    MenuItem("Discard (no name)") { finish() },
                    MenuItem("Keep editing") {},
                ),
            )
        } else {
            save()
        }
    }

    private fun save() {
        if (contactId == null) saveNewContact() else saveExistingContact()
    }

    private fun saveNewContact() {
        val nameField = fields.firstOrNull { it.kind == DataKind.NAME }
        if (nameField == null || nameField.text.isBlank()) {
            Toast.makeText(this, "Needs at least a name", Toast.LENGTH_SHORT).show()
            return
        }
        val fieldValues = fields.mapNotNull { fieldToKindValues(it) }
        val chosenAccount = account
        Async.run(
            background = { ContactStore.insertContact(this, chosenAccount, fieldValues) },
            onResult = { newId ->
                if (newId == null) Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show()
                finish()
            },
        )
    }

    private fun saveExistingContact() {
        val rawId = rawContactId
        val changes = mutableListOf<ContactStore.FieldChange>()
        deletedDataRowIds.forEach { changes.add(ContactStore.FieldChange.Delete(it)) }
        fields.forEach { field ->
            if (field.text.isBlank() && field.kind != DataKind.NAME) {
                field.dataRowId?.let { changes.add(ContactStore.FieldChange.Delete(it)) }
                return@forEach
            }
            val values = fieldToKindValues(field)?.second ?: return@forEach
            val existingId = field.dataRowId
            if (existingId != null) {
                changes.add(ContactStore.FieldChange.Update(existingId, values))
            } else if (rawId != null) {
                changes.add(ContactStore.FieldChange.Insert(rawId, field.kind, values))
            }
        }
        Async.run(
            background = { ContactStore.applyFieldChanges(this, changes) },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show()
                finish()
            },
        )
    }

    private fun fieldToKindValues(field: EditorField): Pair<DataKind, ContentValues>? {
        if (field.text.isBlank()) return null
        val values = ContentValues()
        when (field.kind) {
            DataKind.NAME -> {
                val parts = field.text.trim().split(Regex("\\s+"), limit = 2)
                values.put(CDK.StructuredName.GIVEN_NAME, parts.getOrNull(0))
                values.put(CDK.StructuredName.FAMILY_NAME, parts.getOrNull(1))
                values.put(CDK.StructuredName.DISPLAY_NAME, field.text)
            }
            DataKind.ORGANIZATION -> {
                val parts = field.text.split(" · ", limit = 2)
                values.put(CDK.Organization.COMPANY, parts.getOrNull(0))
                values.put(CDK.Organization.TITLE, parts.getOrNull(1))
            }
            DataKind.PHONE -> {
                values.put(CDK.Phone.NUMBER, field.text)
                values.put(
                    CDK.Phone.TYPE,
                    when (field.typeLabel.trim().uppercase()) {
                        "MOBILE" -> CDK.Phone.TYPE_MOBILE
                        "WORK" -> CDK.Phone.TYPE_WORK
                        "HOME" -> CDK.Phone.TYPE_HOME
                        else -> CDK.Phone.TYPE_MOBILE
                    },
                )
            }
            DataKind.EMAIL -> {
                values.put(CDK.Email.ADDRESS, field.text)
                values.put(
                    CDK.Email.TYPE,
                    when (field.typeLabel.trim().uppercase()) {
                        "WORK" -> CDK.Email.TYPE_WORK
                        "MOBILE" -> CDK.Email.TYPE_MOBILE
                        "HOME" -> CDK.Email.TYPE_HOME
                        else -> CDK.Email.TYPE_HOME
                    },
                )
            }
            DataKind.WEBSITE -> values.put(CDK.Website.URL, field.text)
            DataKind.NICKNAME -> values.put(CDK.Nickname.NAME, field.text)
            DataKind.NOTE -> values.put(CDK.Note.NOTE, field.text)
            DataKind.POSTAL -> {
                // Written as one flattened FORMATTED_ADDRESS, matching this editor's uniform
                // "one row = one editable string" model (see class doc) — and the structured
                // sub-columns (STREET/CITY/...) are explicitly nulled out rather than left
                // stale, since otherwise an edit here would leave the old structured value
                // sitting alongside a now-inconsistent FORMATTED_ADDRESS.
                values.put(CDK.StructuredPostal.FORMATTED_ADDRESS, field.text)
                values.putNull(CDK.StructuredPostal.STREET)
                values.putNull(CDK.StructuredPostal.POBOX)
                values.putNull(CDK.StructuredPostal.NEIGHBORHOOD)
                values.putNull(CDK.StructuredPostal.CITY)
                values.putNull(CDK.StructuredPostal.REGION)
                values.putNull(CDK.StructuredPostal.POSTCODE)
                values.putNull(CDK.StructuredPostal.COUNTRY)
                values.put(CDK.StructuredPostal.TYPE, typeFromLabel(field.kind, field.typeLabel))
            }
            DataKind.EVENT -> {
                values.put(CDK.Event.START_DATE, field.text)
                values.put(CDK.Event.TYPE, typeFromLabel(field.kind, field.typeLabel))
            }
            DataKind.RELATION -> {
                values.put(CDK.Relation.NAME, field.text)
                val type = typeFromLabel(field.kind, field.typeLabel)
                values.put(CDK.Relation.TYPE, type)
                if (type == CDK.BaseTypes.TYPE_CUSTOM) values.put(CDK.Relation.LABEL, field.typeLabel)
            }
            DataKind.IM -> {
                values.put(CDK.Im.DATA, field.text)
                val protocol = imProtocolFromLabel(field.typeLabel)
                values.put(CDK.Im.PROTOCOL, protocol)
                if (protocol == CDK.Im.PROTOCOL_CUSTOM) values.put(CDK.Im.CUSTOM_PROTOCOL, field.typeLabel)
                // Home/work/other isn't independently editable here — [typeLabel] is already
                // spoken for by the protocol (Skype/Jabber/...), matching how the detail screen
                // itself prioritizes the protocol label over the home/work one (§ DataFormat.
                // imProtocolLabel) — a fixed default rather than a second, unreachable sub-field.
                values.put(CDK.Im.TYPE, CDK.Im.TYPE_HOME)
            }
            DataKind.SIP -> {
                values.put(CDK.SipAddress.SIP_ADDRESS, field.text)
                values.put(CDK.SipAddress.TYPE, typeFromLabel(field.kind, field.typeLabel))
            }
            else -> return null
        }
        return field.kind to values
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
    }
}
