package life.furrer.c9.contacts

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.telephony.PhoneNumberUtils
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import java.time.LocalDate
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.ui.DatePickerActivity
import life.furrer.nine.contacts.ContactDetail
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactValuePickerActivity
import life.furrer.nine.contacts.DataFormat
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.contacts.DataRow
import life.furrer.nine.contacts.RawContactAccount
import life.furrer.nine.contacts.canonicalValue
import life.furrer.nine.contacts.changeableTypeLabels
import life.furrer.nine.contacts.emailTypeFromLabel
import life.furrer.nine.contacts.imProtocolFromLabel
import life.furrer.nine.contacts.phoneTypeFromLabel
import life.furrer.nine.contacts.typeFromLabel

private const val SIDE_PADDING_DP = 16
private const val REQUEST_PICK_EVENT_DATE = 500
private const val REQUEST_PICK_CONTACT = 501
private const val REQUEST_CONTACTS_PERMISSIONS = 502

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
    /** Non-null when this field's value duplicates another raw contact's own row of the same
     *  kind (e.g. WhatsApp mirroring a synced phone number onto its own linked raw contact) —
     *  the name of the account this specific copy lives on. Editing a duplicate is a dead end
     *  (it never touches the real synced contact, and a messaging app's own sync is liable to
     *  overwrite it back anyway), so it's shown read-only instead of a normal editable row; the
     *  one thing worth doing with it is deleting it, which still works normally. Only ever set
     *  on the *second and later* row sharing a canonical value — the first stays a normal,
     *  fully editable field so the kind never becomes entirely uneditable. */
    val duplicateSourceLabel: String? = null,
    /** [typeLabel] exactly as loaded from the provider (null for a field added here). A PHONE or
     *  EMAIL field whose type was never touched writes back no TYPE/LABEL at all, so a type this
     *  editor can't round-trip exactly (an uppercased custom label, say) survives an unrelated
     *  edit. */
    val loadedTypeLabel: String? = null,
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

/** Also C9's answer to the platform's "save this number" hand-offs, which is how P9 (or any other
 *  app) files a number without holding WRITE_CONTACTS itself:
 *
 *  - `ACTION_INSERT` (on the contacts directory): a new contact, prefilled from
 *    [ContactsContract.Intents.Insert.PHONE]/[ContactsContract.Intents.Insert.PHONE_TYPE] (and
 *    [ContactsContract.Intents.Insert.NAME], if given).
 *  - `ACTION_INSERT_OR_EDIT`: asks which contact first ([ContactValuePickerActivity] in its
 *    whole-contact mode), then opens that contact with the number appended as a new phone field
 *    of the requested type — or just selects the existing field if the contact already has it.
 *
 *  Either way nothing is written until the user leaves the editor, exactly as for an in-app
 *  edit. In-app launches carry no action and behave exactly as before. */
class ContactEditActivity : Activity() {

    /** The number (and its type label) an external hand-off asked to file, applied once the
     *  contact it goes into — new or picked — has loaded. */
    private var prefillPhone: Pair<String, String>? = null
    private var prefillName: String? = null

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

    // Which half of the selected Event row Left/Right has focused — same "Left/Right picks a
    // sub-field, only the active one highlights" convention as EventEditActivity's Starts/Ends
    // rows (renderDateTimeRow), needed here because an Event's type and date go through
    // completely different edit flows (inline text vs. the date picker) and both need to be
    // reachable without a trip through the `*` menu. Meaningless for every other kind.
    private enum class EventColumn { TYPE, DATE }
    private var eventColumn: EventColumn = EventColumn.DATE

    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var keyFocusAnchor: View

    private var inlineEditIndex: Int? = null
    private var inlineEditText: EditText? = null
    // True while the active inline edit is retyping a field's *type label* ("Custom" from the
    // Change type menu) rather than its value — same EditText/commit machinery, just a different
    // EditorField property gets the result. See beginInlineEdit/commitInlineEdit.
    private var inlineEditTargetsTypeLabel = false

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
            eventColumn = retained.eventColumn
            loaded = true
            rebuildRows()
        } else {
            startFromIntent()
        }
    }

    private fun startFromIntent() {
        if (intent.action == null) {
            loadInitialData()
            return
        }
        prefillPhone = intent.getStringExtra(ContactsContract.Intents.Insert.PHONE)
            ?.takeIf { it.isNotBlank() }
            ?.let { it.trim() to phoneTypeLabelFromExtra() }
        prefillName = intent.getStringExtra(ContactsContract.Intents.Insert.NAME)?.takeIf { it.isNotBlank() }
        // Reached from another app, so C9's own permission screen (ContactsActivity's) may never
        // have run.
        if (ContactStore.hasPermission(this) && ContactStore.hasWritePermission(this)) {
            startExternal()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                REQUEST_CONTACTS_PERMISSIONS,
            )
        }
    }

    /** [ContactsContract.Intents.Insert.PHONE_TYPE] is documented as either a `Phone.TYPE_*` int
     *  or a free-text label; absent means Mobile, the same default "Add field" uses. */
    private fun phoneTypeLabelFromExtra(): String =
        when (val raw = intent.extras?.get(ContactsContract.Intents.Insert.PHONE_TYPE)) {
            is Int -> CDK.Phone.getTypeLabel(resources, raw, null).toString()
            is String -> raw.takeIf { it.isNotBlank() } ?: "Mobile"
            else -> "Mobile"
        }

    private fun startExternal() {
        if (intent.action == Intent.ACTION_INSERT_OR_EDIT) {
            startActivityForResult(
                Intent(this, ContactValuePickerActivity::class.java).putExtra(ContactValuePickerActivity.EXTRA_PICK_CONTACT, true),
                REQUEST_PICK_CONTACT,
            )
        } else {
            loadInitialData()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CONTACTS_PERMISSIONS) return
        if (ContactStore.hasPermission(this) && ContactStore.hasWritePermission(this)) {
            startExternal()
        } else {
            Toast.makeText(this, "C9 needs access to your Contacts to save this", Toast.LENGTH_LONG).show()
            finish()
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
        val eventColumn: EventColumn,
    )

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = RetainedState(
        contactId, rawContactId, account, preservedUnknownRows, deletedDataRowIds, fields, selectedIndex, eventColumn,
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
            fields = mutableListOf(EditorField(DataKind.NAME, dataRowId = null, rawContactId = null, text = prefillName.orEmpty(), typeLabel = "Name"))
            prefillPhone?.let { (number, label) ->
                prefillPhone = null
                fields.add(EditorField(DataKind.PHONE, dataRowId = null, rawContactId = null, text = number, typeLabel = label))
            }
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
        detail.rows.forEach { row ->
            when (val kind = DataKind.fromMimetype(row.mimetype)) {
                // Handled separately below, from a single row chosen across every raw contact —
                // not here, where iteration order is whatever the provider happens to return.
                DataKind.NAME -> {}
                DataKind.ORGANIZATION -> {
                    val org = DataFormat.valueText(DataKind.ORGANIZATION, row.values)
                    newFields.add(EditorField(DataKind.ORGANIZATION, row.id, row.rawContactId, org, "Organisation"))
                }
                DataKind.PHONE, DataKind.EMAIL, DataKind.WEBSITE, DataKind.NICKNAME, DataKind.NOTE,
                DataKind.POSTAL, DataKind.RELATION, DataKind.IM, DataKind.SIP -> {
                    val label = DataFormat.typeLabel(resources, kind, row.values) ?: kind.sectionLabel
                    newFields.add(
                        EditorField(kind, row.id, row.rawContactId, DataFormat.valueText(kind, row.values), label, loadedTypeLabel = label),
                    )
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
        // Same raw contact ContactStore.queryContactDetail prefers for [ContactDetail.displayName]
        // ("Dr. med. Dr. med. Mara Furrer" bug): a synced contact and a messaging app's own linked
        // mirror can each carry their own StructuredName row, so picking "whichever happens to be
        // first" here — independent of that choice — could load and then save back a *different*
        // raw contact's name than the one actually shown as the contact's title.
        val primaryNameRow = detail.rows
            .filter { DataKind.fromMimetype(it.mimetype) == DataKind.NAME }
            .minByOrNull { it.rawContactId }
        val name = primaryNameRow?.let { DataFormat.valueText(DataKind.NAME, it.values) } ?: ""
        fields = (
            mutableListOf(EditorField(DataKind.NAME, primaryNameRow?.id, primaryNameRow?.rawContactId ?: rawContactId, name, "Name")) +
                markDuplicates(newFields, detail.rawContacts)
            ).toMutableList()
        sortFieldsByKind()
        prefillPhone?.let { (number, label) ->
            prefillPhone = null
            appendPrefilledPhone(number, label)
        }
        loaded = true
        rebuildRows()
    }

    /** INSERT_OR_EDIT's half of the hand-off: the number lands as a new phone field of the
     *  requested type, selected so it's the first thing the user sees — unless this contact
     *  already has it (in any formatting, `079…` vs `+4179…`), in which case that existing field is
     *  selected instead of saving a duplicate. */
    private fun appendPrefilledPhone(number: String, label: String) {
        val existing = fields.firstOrNull { it.kind == DataKind.PHONE && PhoneNumberUtils.compare(it.text, number) }
        if (existing != null) {
            selectedIndex = fields.indexOf(existing)
            Toast.makeText(this, "Already saved on this contact", Toast.LENGTH_SHORT).show()
            return
        }
        val newField = EditorField(DataKind.PHONE, dataRowId = null, rawContactId = rawContactId, text = number, typeLabel = label)
        fields.add(newField)
        sortFieldsByKind()
        selectedIndex = fields.indexOf(newField)
    }

    /** Groups same-kind fields together (a stable sort — multiple rows of one kind keep their
     *  own relative order) so [rebuildRows] can emit one section header per contiguous run,
     *  regardless of the order fields were loaded from the provider or added via "Add field".
     *  Callers that change which fields exist (load, add, delete) call this and must then locate
     *  the field they care about by identity, not by whatever index it used to be at. */
    private fun sortFieldsByKind() {
        fields.sortBy { it.kind.ordinal }
    }

    /** Flags every field past the *first* one sharing a (kind, canonical value) pair — see
     *  [DataKind]'s own [canonicalValue] for what "same value" means per kind — as a read-only
     *  duplicate carrying its raw contact's account name, the same underlying notion the
     *  read-only detail screen's own dedup (ContactDetailRows.buildDetailRows) uses, just kept
     *  visible here (with its source) instead of dropped, since the editor needs every row
     *  addressable to delete individually. [fields] is a freshly-loaded list with no duplicate
     *  flags of its own yet — this never needs to run again afterward, since [addField] only ever
     *  appends one field at a time and a single fresh field can't already duplicate anything. */
    private fun markDuplicates(fields: List<EditorField>, rawContacts: List<RawContactAccount>): List<EditorField> {
        val accountByRawId = rawContacts.associateBy { it.rawContactId }
        val seenCanonicalValues = mutableSetOf<Pair<DataKind, String>>()
        return fields.map { field ->
            val key = field.kind to canonicalValue(field.kind, field.text)
            if (!seenCanonicalValues.add(key)) {
                val account = field.rawContactId?.let { accountByRawId[it] }
                val label = account?.accountName ?: account?.accountType?.substringAfterLast('.') ?: "linked account"
                field.copy(duplicateSourceLabel = label)
            } else {
                field
            }
        }
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
            row.text = rowText(field)
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

    /** A duplicate field's row names its source account (e.g. "· WhatsApp") after the value, so
     *  it's clear at a glance why the same number/address shows up twice and which copy this
     *  particular row is — same shape as the WHERE/CALENDAR-style suffix elsewhere in the suite,
     *  just inline instead of on its own line since these rows are otherwise identical to a
     *  normal field row. */
    private fun rowText(field: EditorField): String {
        val base = "${labelFor(field)}:  ${field.text.ifBlank { "(empty)" }}"
        return field.duplicateSourceLabel?.let { "$base  · $it" } ?: base
    }

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
            if (view !is TextView) return@forEachIndexed
            if (field.kind == DataKind.EVENT && field.duplicateSourceLabel == null) {
                renderEventRow(view, field, i == selectedIndex)
                return@forEachIndexed
            }
            view.text = rowText(field)
            view.applyRowSelectionStyle(i == selectedIndex)
            // A linked duplicate is never actually editable here (onSelectField just points
            // you at the real row instead) — dimmed even while selected so it never looks
            // like a normal, writable field, matching the muted-subtitle convention used
            // elsewhere in the suite for de-emphasized secondary text.
            if (field.duplicateSourceLabel != null) view.setTextColor(Palette.GRAY_MUTED)
        }
        val scrollTarget = headerBeforeField.getOrNull(selectedIndex) ?: fieldRowViews.getOrNull(selectedIndex)
        scrollTarget?.let { view -> scrollView.post { scrollView.smoothScrollTo(0, view.top) } }
    }

    /** An Event row is two independently selectable sub-fields — type label and date — rather
     *  than one, so it needs sub-string highlighting instead of [applyRowSelectionStyle]'s
     *  whole-row white+underline: same [SpannableString]-per-segment approach as
     *  EventEditActivity's own renderDateTimeRow, just splitting on the label/value boundary
     *  instead of date/time. */
    private fun renderEventRow(view: TextView, field: EditorField, selected: Boolean) {
        val label = "${labelFor(field)}:  "
        val value = field.text.ifBlank { "(empty)" }
        val full = label + value
        val spannable = SpannableString(full)
        fun style(start: Int, end: Int, highlight: Boolean) {
            val color = if (highlight) Color.WHITE else Palette.GRAY_DIM
            spannable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (highlight) spannable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        style(0, label.length, selected && eventColumn == EventColumn.TYPE)
        style(label.length, full.length, selected && eventColumn == EventColumn.DATE)
        view.text = spannable
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
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                selectEventColumn(EventColumn.TYPE)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                selectEventColumn(EventColumn.DATE)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                onSelectField()
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
        eventColumn = EventColumn.DATE
        render()
    }

    private fun selectEventColumn(column: EventColumn) {
        if (fields.getOrNull(selectedIndex)?.kind != DataKind.EVENT) return
        eventColumn = column
        render()
    }

    private fun onSelectField() {
        val field = fields.getOrNull(selectedIndex) ?: return
        val source = field.duplicateSourceLabel
        when {
            source != null -> Toast.makeText(this, "Managed by $source — delete it here, or edit your real contact instead", Toast.LENGTH_LONG).show()
            field.kind == DataKind.EVENT -> onSelectEventField(field)
            else -> beginInlineEdit(selectedIndex)
        }
    }

    /** OK's meaning on an Event row depends on which sub-field Left/Right last focused: the DATE
     *  column always opens the month-grid picker, same as before this field became two
     *  sub-fields; the TYPE column reopens the preset menu when the field is still one of the
     *  fixed Birthday/Anniversary/Other choices (so switching to Custom, or another preset, stays
     *  reachable without the `*` menu), or edits the type text directly once it's already a
     *  free-typed Custom label — matching [openChangeTypeMenu]'s own "Custom" branch. */
    private fun onSelectEventField(field: EditorField) {
        when (eventColumn) {
            EventColumn.DATE -> openDatePickerForSelectedField()
            EventColumn.TYPE -> if (field.hasFreeTypedLabel()) beginInlineEdit(selectedIndex, editTypeLabel = true) else openChangeTypeMenu(selectedIndex)
        }
    }

    /** True once [EditorField.typeLabel] has been set to something other than one of the kind's
     *  own pre-declared choices — i.e. after "Custom" was chosen and a real label typed in. Used
     *  both to decide OK's behavior on the TYPE column ([onSelectEventField]) and whether
     *  [beginInlineEdit] should pre-fill that free-typed text or start blank. */
    private fun EditorField.hasFreeTypedLabel(): Boolean {
        val presets = changeableTypeLabels(kind) - "Custom"
        // Case-insensitive: labels read back from the provider are uppercased (DataFormat.typeLabel).
        return presets.isNotEmpty() && presets.none { it.equals(typeLabel, ignoreCase = true) }
    }

    /** Reuses the calendar module's own month-grid [DatePickerActivity] instead of free-typed
     *  digits, so entering a birthday/anniversary date is the same interaction as picking any
     *  other date in the suite — including that picker's own existing limitation of always
     *  opening on today's date rather than the field's current value (matches
     *  EventEditActivity's own date fields, which have the same gap). Always writes back a full
     *  "YYYY-MM-DD" (the picker has no concept of a year-unknown date, only
     *  [LocalDate]s) — an existing no-year "--MM-DD" value is left alone until the user
     *  actually re-picks that field's date, at which point it gains a year like any freshly-picked
     *  one. */
    private fun openDatePickerForSelectedField() {
        startActivityForResult(
            Intent(this, DatePickerActivity::class.java),
            REQUEST_PICK_EVENT_DATE,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_CONTACT) {
            val picked = data?.getLongExtra(ContactValuePickerActivity.EXTRA_CONTACT_ID, -1L)?.takeIf { it != -1L }
            if (resultCode != RESULT_OK || picked == null) {
                finish()
                return
            }
            contactId = picked
            loadInitialData()
            return
        }
        if (requestCode != REQUEST_PICK_EVENT_DATE || resultCode != RESULT_OK || data == null) return
        val epochDay = data.getLongExtra(DatePickerActivity.EXTRA_EPOCH_DAY, -1L)
        if (epochDay < 0) return
        fields.getOrNull(selectedIndex)?.text = LocalDate.ofEpochDay(epochDay).toString()
        rebuildRows()
    }

    /** [editTypeLabel] retypes the field's *type* (reachable via the Change type menu's "Custom"
     *  entry, see [openChangeTypeMenu], or directly via OK on an Event row's TYPE column once it's
     *  already free-typed, see [onSelectEventField]) instead of its value — same EditText/commit
     *  machinery either way, just a different [EditorField] property ends up written. Starts blank
     *  the first time (switching from a preset like Birthday — nothing sensible to pre-fill with,
     *  see [EditorField.hasFreeTypedLabel]), but pre-fills with the current text on every
     *  re-entry after that, so fixing a typo doesn't mean retyping the whole label. */
    private fun beginInlineEdit(index: Int, editTypeLabel: Boolean = false) {
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
            setText(if (editTypeLabel) { if (field.hasFreeTypedLabel()) field.typeLabel else "" } else field.text)
            setSelection(text.length)
            // Kept as a best-effort hint even though it alone doesn't suppress the popup on this
            // device (confirmed live — see commitInlineEdit's recreate() call for the real fix).
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val prefixLabel = TextView(this).apply {
            text = if (editTypeLabel) "${field.kind.sectionLabel} type:  " else "${labelFor(field)}:  "
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
        inlineEditTargetsTypeLabel = editTypeLabel
        scrollView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        editText.requestFocus()
    }

    private fun commitInlineEdit() {
        val index = inlineEditIndex ?: return
        val editText = inlineEditText ?: return
        val currentView = fieldRowViews.getOrNull(index) ?: return
        val containerIndex = rowContainer.indexOfChild(currentView)
        val typed = editText.text.toString().trim()
        if (inlineEditTargetsTypeLabel) {
            // A blank custom type makes no sense -- leave the field's previous type alone rather
            // than saving an empty label.
            if (typed.isNotEmpty()) fields.getOrNull(index)?.typeLabel = typed
        } else {
            fields.getOrNull(index)?.text = typed
        }
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
        inlineEditTargetsTypeLabel = false
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
        val selected = fields.getOrNull(selectedIndex)
        if (selected != null && selected.duplicateSourceLabel == null && changeableTypeLabels(selected.kind).isNotEmpty()) {
            items.add(MenuItem("Change type") { openChangeTypeMenu(selectedIndex) })
        }
        if (selected?.kind != DataKind.NAME) {
            items.add(MenuItem("Delete this field") { deleteSelectedField() })
        }
        items.add(MenuItem("Discard") { finish() })
        if (contactId != null) items.add(MenuItem("Delete contact") { confirmDeleteContact() })
        menuSheet.show(items)
    }

    /** [changeableTypeLabels] names every pre-declared choice for the field's kind plus a
     *  synthetic "Custom" entry — picking a real choice sets [EditorField.typeLabel] directly and
     *  hands off to [onPresetChosen] (used by [addEventField] to jump straight to the date
     *  picker), picking "Custom" instead starts an inline edit of the type itself (see
     *  [beginInlineEdit]'s `editTypeLabel` parameter) so the user can type whatever they mean
     *  ("Graduation", "First day of work", ...) rather than being stuck with the literal word
     *  "Custom" as their type. Also sets [eventColumn] so the row's sub-highlight lands on
     *  whichever half the user just landed on — meaningless for non-Event kinds, since only Event
     *  currently has any [changeableTypeLabels] at all. */
    private fun openChangeTypeMenu(index: Int, onPresetChosen: (() -> Unit)? = null) {
        val field = fields.getOrNull(index) ?: return
        menuSheet.show(
            changeableTypeLabels(field.kind).map { label ->
                MenuItem(label) {
                    if (label.equals("Custom", ignoreCase = true)) {
                        eventColumn = EventColumn.TYPE
                        beginInlineEdit(index, editTypeLabel = true)
                    } else {
                        field.typeLabel = label
                        eventColumn = EventColumn.DATE
                        render()
                        onPresetChosen?.invoke()
                    }
                }
            },
        )
    }

    private fun openAddFieldMenu() {
        menuSheet.show(
            ADDABLE_KINDS.map { kind ->
                MenuItem(kind.sectionLabel) { addField(kind) }
            },
        )
    }

    private fun addField(kind: DataKind) {
        if (kind == DataKind.EVENT) {
            addEventField()
            return
        }
        val defaultLabel = when (kind) {
            DataKind.PHONE -> "Mobile"
            DataKind.EMAIL, DataKind.WEBSITE, DataKind.POSTAL, DataKind.SIP -> "Home"
            DataKind.RELATION -> "Spouse"
            DataKind.IM -> "Custom"
            else -> kind.sectionLabel
        }
        val newField = EditorField(kind, dataRowId = null, rawContactId = rawContactId, text = "", typeLabel = defaultLabel)
        fields.add(newField)
        sortFieldsByKind()
        selectedIndex = fields.indexOf(newField)
        rebuildRows()
        // Goes through the same kind-aware dispatch OK does on an existing row (onSelectField),
        // not a direct beginInlineEdit.
        onSelectField()
    }

    /** Unlike every other addable kind, a new Event field asks which type it is *before* asking
     *  for a value: it used to default straight to Birthday and jump into the date picker, and
     *  fixing the type afterward meant a trip through the `*` menu's "Change type" → "Custom" →
     *  full-row inline edit — confusing since that inline edit replaced the row's value entirely
     *  rather than living alongside it. [openChangeTypeMenu]'s preset branch now jumps straight to
     *  the date picker once a real type is chosen (same "pick type, then pick date" shape the old
     *  flow had for the common case), and its Custom branch starts an inline edit of just the type
     *  name — the date stays independently reachable afterward via [onSelectEventField]'s DATE
     *  column, reached with Right. The placeholder "Birthday" typeLabel here is only a fallback in
     *  case the user backs out of the menu without choosing. */
    private fun addEventField() {
        val newField = EditorField(DataKind.EVENT, dataRowId = null, rawContactId = rawContactId, text = "", typeLabel = "Birthday")
        fields.add(newField)
        sortFieldsByKind()
        val index = fields.indexOf(newField)
        selectedIndex = index
        eventColumn = EventColumn.TYPE
        rebuildRows()
        openChangeTypeMenu(index) { openDatePickerForSelectedField() }
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
                // Used to map anything but Mobile/Work/Home to Mobile, silently retyping every
                // Other/Main/Fax/custom number on any save of its contact.
                if (field.dataRowId == null || field.typeLabel != field.loadedTypeLabel) {
                    val type = phoneTypeFromLabel(resources, field.typeLabel)
                    values.put(CDK.Phone.TYPE, type)
                    values.put(CDK.Phone.LABEL, if (type == CDK.Phone.TYPE_CUSTOM) field.typeLabel else null)
                }
            }
            DataKind.EMAIL -> {
                values.put(CDK.Email.ADDRESS, field.text)
                // Same fix as PHONE above: used to turn every Other/custom address into Home.
                if (field.dataRowId == null || field.typeLabel != field.loadedTypeLabel) {
                    val type = emailTypeFromLabel(resources, field.typeLabel)
                    values.put(CDK.Email.TYPE, type)
                    values.put(CDK.Email.LABEL, if (type == CDK.Email.TYPE_CUSTOM) field.typeLabel else null)
                }
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
                val type = typeFromLabel(field.kind, field.typeLabel)
                values.put(CDK.Event.TYPE, type)
                // Matches Relation's own TYPE_CUSTOM handling just below — without this, a
                // custom event type (anything past Birthday/Anniversary/Other) saved with no
                // label at all, silently losing the typed text.
                if (type == CDK.BaseTypes.TYPE_CUSTOM) values.put(CDK.Event.LABEL, field.typeLabel)
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
