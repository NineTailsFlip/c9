package life.furrer.c9.contacts

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.telephony.PhoneNumberUtils
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import java.time.LocalDate
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.c9.core.Prefs
import life.furrer.c9.ui.InlineRowEditor
import life.furrer.c9.ui.columnHighlight
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.ui.DatePickerActivity
import life.furrer.nine.contacts.ContactDetail
import life.furrer.nine.contacts.ContactGroups
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactValuePickerActivity
import life.furrer.nine.contacts.DataFormat
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.contacts.DataRow
import life.furrer.nine.contacts.NameParts
import life.furrer.nine.contacts.OrganizationParts
import life.furrer.nine.contacts.PostalParts
import life.furrer.nine.contacts.RawContactAccount
import life.furrer.nine.contacts.StructuredEdit
import life.furrer.nine.contacts.canonicalValue
import life.furrer.nine.contacts.changeableTypeLabels
import life.furrer.nine.contacts.emailTypeFromLabel
import life.furrer.nine.contacts.imProtocolFromLabel
import life.furrer.nine.contacts.phoneTypeFromLabel
import life.furrer.nine.contacts.typeFromLabel

private const val REQUEST_PICK_EVENT_DATE = 500
private const val REQUEST_PICK_CONTACT = 501
private const val REQUEST_CONTACTS_PERMISSIONS = 502
private const val REQUEST_PICK_GROUPS = 503

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
     *  editor can't round-trip exactly (a custom label spelled like a preset, say) survives an
     *  unrelated edit. */
    val loadedTypeLabel: String? = null,
    /** [text] as loaded (null for a field added here). Together with [loadedTypeLabel] it is how
     *  save knows a row is untouched: an untouched row is not written at all, so nothing this
     *  editor can't represent in one line (a structured address, a name's prefix, a custom label's
     *  case) can be damaged by an unrelated edit - or by none. */
    val loadedText: String? = null,
    /** The structured columns of a Name/Address/Organisation row as loaded (see
     *  [StructuredEdit]); an edit is turned into new parts relative to these and only the columns
     *  that differ are written. */
    val loadedParts: Map<String, String>? = null,
    /** The parts after the user edited them one by one (the Name and Address parts menus); null
     *  while the row is only changed, if at all, through its one-line [text]. [text] is always
     *  re-derived from these when they change. */
    var parts: Map<String, String>? = null,
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

    // The Groups row. Group membership isn't a field of the contact but rows in other tables, so
    // it is held apart from [fields]' text: [realGroups] is every group of a real address book
    // (what the checklist can offer), [originalGroupIds] what the contact was in when loaded
    // (empty for a new contact) and [pendingGroupIds] what the user has chosen since. Nothing is
    // written until save, like every other edit here.
    private var realGroups: List<ContactStore.ContactGroup> = emptyList()
    private var originalGroupIds: Set<Long> = emptySet()
    private var pendingGroupIds: Set<Long> = emptySet()
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
    private lateinit var inlineEditor: InlineRowEditor

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
        inlineEditor = InlineRowEditor(this, rowContainer, scrollView, onMove = ::moveSelection, onCommitted = ::render)

        hintBar = HintBar(this).apply { setText("* menu · ok edit field · ‹ done") }
        menuSheet = MenuSheet(this)
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        inlineEditor.attachTo(root)
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
            realGroups = retained.realGroups
            originalGroupIds = retained.originalGroupIds
            pendingGroupIds = retained.pendingGroupIds
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

    /** All in-memory edits not yet written to the contacts provider — carried across the
     *  [recreate] every inline edit's commit does (see [InlineRowEditor]) via
     *  [onRetainNonConfigurationInstance], so that self-heal doesn't discard unsaved work. See
     *  EventEditActivity.RetainedState for the matching pattern there. */
    private data class RetainedState(
        val contactId: Long?,
        val rawContactId: Long?,
        val account: ContactStore.WritableAccount?,
        val preservedUnknownRows: List<DataRow>,
        val deletedDataRowIds: MutableList<Long>,
        val fields: MutableList<EditorField>,
        val selectedIndex: Int,
        val eventColumn: EventColumn,
        val realGroups: List<ContactStore.ContactGroup>,
        val originalGroupIds: Set<Long>,
        val pendingGroupIds: Set<Long>,
    )

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = RetainedState(
        contactId, rawContactId, account, preservedUnknownRows, deletedDataRowIds, fields, selectedIndex, eventColumn,
        realGroups, originalGroupIds, pendingGroupIds,
    )

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        inlineEditor.cancelAutofill()
    }

    private fun loadInitialData() {
        val id = contactId
        if (id != null) {
            Async.run(
                background = { ContactStore.queryContactDetail(this, id) to loadGroupsInfo(id) },
                onResult = { (detail, groups) ->
                    if (detail == null) {
                        finish()
                    } else {
                        realGroups = groups.groups
                        originalGroupIds = groups.memberOf
                        pendingGroupIds = groups.memberOf
                        loadExisting(detail)
                    }
                },
            )
        } else {
            fields = mutableListOf(EditorField(DataKind.NAME, dataRowId = null, rawContactId = null, text = prefillName.orEmpty(), typeLabel = "Name"))
            prefillPhone?.let { (number, label) ->
                prefillPhone = null
                fields.add(EditorField(DataKind.PHONE, dataRowId = null, rawContactId = null, text = number, typeLabel = label))
            }
            Async.run(
                background = { ContactStore.queryWritableAccounts(this) to loadGroupsInfo(null) },
                onResult = { (accounts, groups) ->
                    realGroups = groups.groups
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
                    syncGroupsField()
                    loaded = true
                    rebuildRows()
                },
            )
        }
    }

    private class GroupsInfo(val groups: List<ContactStore.ContactGroup>, val memberOf: Set<Long>)

    /** Blocking. The groups the Groups row can offer — those of the real address books, see
     *  [realAddressBooks] — and, for an existing contact, which of them it is in. */
    private fun loadGroupsInfo(contactId: Long?): GroupsInfo {
        val real = realAddressBooks(this, ContactStore.queryWritableAccounts(this))
        val groups = ContactStore.queryGroups(this).filter { g -> real.any { it.name == g.accountName && it.type == g.accountType } }
        val ids = groups.map { it.id }.toSet()
        val memberOf = contactId?.let { id -> ContactGroups.queryGroupsOfContact(this, id).map { it.id }.filter { it in ids }.toSet() }.orEmpty()
        return GroupsInfo(groups, memberOf)
    }

    /** What the Groups row can offer: for an existing contact every real group (the checklist
     *  dims the ones it can't join), for a new one only the chosen account's — a contact can only
     *  ever be in groups of the account it is created in. Empty (no row) until a new contact's
     *  account is known. */
    private fun availableGroups(): List<ContactStore.ContactGroup> {
        if (contactId != null) return realGroups
        val chosen = account ?: return emptyList()
        return realGroups.filter { it.accountName == chosen.name && it.accountType == chosen.type }
    }

    /** (Re)creates the Groups row from [availableGroups], or drops it when there is nothing to
     *  choose from — which is also what keeps the editor unchanged for anyone without groups. A
     *  new contact's account can change after the row first appeared, hence the pending choice
     *  is cut down to what is still on offer. Callers rebuild the rows afterwards. */
    private fun syncGroupsField() {
        fields.removeAll { it.kind == DataKind.GROUP_MEMBERSHIP }
        val available = availableGroups()
        pendingGroupIds = pendingGroupIds intersect available.map { it.id }.toSet()
        if (available.isEmpty()) return
        fields.add(EditorField(DataKind.GROUP_MEMBERSHIP, dataRowId = null, rawContactId = null, text = groupsSummary(available), typeLabel = "Groups"))
        sortFieldsByKind()
    }

    private fun groupsSummary(available: List<ContactStore.ContactGroup>): String =
        available.filter { it.id in pendingGroupIds }.joinToString(", ") { it.title }.ifEmpty { "none" }

    private fun openGroupsChecklist() {
        startActivityForResult(
            GroupChecklistActivity.deferredIntent(this, contactId, account, pendingGroupIds),
            REQUEST_PICK_GROUPS,
        )
    }

    private fun loadExisting(detail: ContactDetail) {
        // The real address book's raw contact, not a messaging-app mirror: new fields ("Add
        // field", a handed-over number) are written here. "Whichever the provider lists first"
        // picked Signal's copy on the phone once an address book re-download renumbered the rest.
        rawContactId = detail.primaryRawContact?.rawContactId
        val newFields = mutableListOf<EditorField>()
        val unknown = mutableListOf<DataRow>()
        detail.rows.forEach { row ->
            when (val kind = DataKind.fromMimetype(row.mimetype)) {
                // Handled separately below, from a single row chosen across every raw contact —
                // not here, where iteration order is whatever the provider happens to return.
                DataKind.NAME -> {}
                DataKind.ORGANIZATION -> {
                    val org = DataFormat.valueText(DataKind.ORGANIZATION, row.values)
                    newFields.add(
                        EditorField(
                            DataKind.ORGANIZATION, row.id, row.rawContactId, org, "Organisation",
                            loadedText = org, loadedParts = StructuredEdit.read(row.values, OrganizationParts.COLUMNS),
                        ),
                    )
                }
                DataKind.PHONE, DataKind.EMAIL, DataKind.WEBSITE, DataKind.NICKNAME, DataKind.NOTE,
                DataKind.POSTAL, DataKind.RELATION, DataKind.IM, DataKind.SIP -> {
                    // An IM row's label is its protocol (see fieldToKindValues); its home/work type is
                    // not editable here, so it must not be read back as if it were the protocol.
                    // As stored, not DataFormat's uppercased display form: this is what gets edited
                    // and written back, and a custom "GitLab" must not come back as "GITLAB".
                    val label = (if (kind == DataKind.IM) DataFormat.imProtocolName(resources, row.values) else null)
                        ?: DataFormat.typeName(resources, kind, row.values) ?: kind.sectionLabel
                    val text = DataFormat.valueText(kind, row.values)
                    val loadedParts = if (kind == DataKind.POSTAL) StructuredEdit.read(row.values, PostalParts.COLUMNS) else null
                    newFields.add(
                        EditorField(
                            kind, row.id, row.rawContactId, text, label,
                            loadedTypeLabel = label, loadedText = text, loadedParts = loadedParts,
                        ),
                    )
                }
                DataKind.EVENT -> {
                    // Edited as the raw vCard date string ("1990-05-12" or the no-year
                    // "--05-12"), not [DataFormat.formatEventDate]'s "12 May 1990" — that
                    // formatter is one-way (no parser back to the raw form), and round-tripping
                    // through it would either lose the no-year case or require guessing a year.
                    val label = DataFormat.typeName(resources, kind, row.values) ?: kind.sectionLabel
                    val raw = row.values.getAsString(CDK.Event.START_DATE).orEmpty()
                    newFields.add(EditorField(kind, row.id, row.rawContactId, raw, label, loadedTypeLabel = label, loadedText = raw))
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
            .minWithOrNull(compareBy({ it.rawContactId != rawContactId }, { it.rawContactId }))
        val name = primaryNameRow?.let { DataFormat.valueText(DataKind.NAME, it.values) } ?: ""
        fields = (
            mutableListOf(
                EditorField(
                    DataKind.NAME, primaryNameRow?.id, primaryNameRow?.rawContactId ?: rawContactId, name, "Name",
                    loadedText = name.takeIf { primaryNameRow != null },
                    loadedParts = primaryNameRow?.let { StructuredEdit.read(it.values, NameParts.COLUMNS) },
                ),
            ) +
                // The primary raw contact's copy of a duplicated value goes first, so it's the one
                // that stays editable and a mirror's copy is the one shown read-only — on the
                // phone it was the other way round (Signal's number editable, the Family one not).
                markDuplicates(newFields.sortedBy { it.rawContactId != rawContactId }, detail.rawContacts)
            ).toMutableList()
        sortFieldsByKind()
        prefillPhone?.let { (number, label) ->
            prefillPhone = null
            appendPrefilledPhone(number, label)
        }
        syncGroupsField()
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
    // [EditorField.typeLabel] is kept as stored/typed; a type is uppercased only here, for display
    // (matching the detail screen), while a kind-name fallback ("Name", "Organisation") stays as is.
    private fun labelFor(field: EditorField): String =
        (if (field.typeLabel == field.kind.sectionLabel) field.typeLabel else field.typeLabel.uppercase()).padEnd(11)

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
        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(3))
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
     *  whole-row white+underline: the same [columnHighlight] as EventEditActivity's own
     *  renderDateTimeRow, just splitting on the label/value boundary instead of date/time. */
    private fun renderEventRow(view: TextView, field: EditorField, selected: Boolean) {
        view.text = columnHighlight(
            "${labelFor(field)}:  " to (selected && eventColumn == EventColumn.TYPE),
            field.text.ifBlank { "(empty)" } to (selected && eventColumn == EventColumn.DATE),
        )
    }

    private fun makeRow(): TextView = TextView(this).apply {
        textSize = 16f
        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(6), dp(Dimens.SIDE_PADDING_DP), dp(6))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inlineEditor.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

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
            field.kind == DataKind.GROUP_MEMBERSHIP -> openGroupsChecklist()
            field.kind == DataKind.EVENT -> onSelectEventField(field)
            field.kind == DataKind.POSTAL -> openPartsMenu(selectedIndex)
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
        // Case-insensitive: a preset can be typed or stored in any case ("BIRTHDAY", "birthday").
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
        if (requestCode == REQUEST_PICK_GROUPS) {
            val ids = data?.getLongArrayExtra(GroupChecklistActivity.EXTRA_CHECKED_IDS)
            if (resultCode != RESULT_OK || ids == null) return
            pendingGroupIds = ids.toSet()
            syncGroupsField()
            rebuildRows()
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
     *  already free-typed, see [onSelectEventField]) instead of its value, and [part] one part of
     *  a Name/Address row — same EditText/commit machinery either way, just a different
     *  [EditorField] property ends up written. A type starts blank the first time (switching from a
     *  preset like Birthday — nothing sensible to pre-fill with, see
     *  [EditorField.hasFreeTypedLabel]), but pre-fills with the current text on every re-entry
     *  after that, so fixing a typo doesn't mean retyping the whole label. */
    private fun beginInlineEdit(index: Int, editTypeLabel: Boolean = false, part: StructuredEdit.Part? = null) {
        val field = fields.getOrNull(index) ?: return
        // Header rows mean `fields[index]` no longer sits at `rowContainer`'s own child `index`
        // — the editor finds the row's *actual* position in the container instead.
        val currentView = fieldRowViews.getOrNull(index) ?: return
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(
                when {
                    part != null -> currentParts(field)[part.column].orEmpty()
                    editTypeLabel -> if (field.hasFreeTypedLabel()) field.typeLabel else ""
                    else -> field.text
                },
            )
            setSelection(text.length)
        }
        val prefix = when {
            part != null -> "${part.label}:  "
            editTypeLabel -> "${field.kind.sectionLabel} type:  "
            else -> "${labelFor(field)}:  "
        }
        val editRow = inlineEditor.begin(
            currentView, prefix, editText,
            onCommit = { commitInlineEdit(index, it.trim(), editTypeLabel, part) },
            restoreRow = { makeRow().also { fieldRowViews[index] = it } },
        ) ?: return
        fieldRowViews[index] = editRow
    }

    private fun commitInlineEdit(index: Int, typed: String, editTypeLabel: Boolean, part: StructuredEdit.Part?) {
        if (part != null) {
            fields.getOrNull(index)?.let { field ->
                val updated = currentParts(field).toMutableMap().also { it[part.column] = typed }
                field.parts = updated
                field.text = if (field.kind == DataKind.NAME) NameParts.display(updated) else PostalParts.line(updated)
            }
        } else if (editTypeLabel) {
            // A blank custom type makes no sense -- leave the field's previous type alone rather
            // than saving an empty label.
            if (typed.isNotEmpty()) fields.getOrNull(index)?.typeLabel = typed
        } else {
            fields.getOrNull(index)?.let {
                it.text = typed
                it.parts = null // the line is what the user last said; the parts follow from it on save
            }
        }
    }

    private fun promptAccountChoice(accounts: List<ContactStore.WritableAccount>) {
        menuSheet.show(
            accounts.map { candidate ->
                MenuItem("${candidate.name} (${candidate.type.substringAfterLast('.')})") {
                    account = candidate
                    syncGroupsField()
                    rebuildRows()
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
        if (selected?.kind != DataKind.NAME && selected?.kind != DataKind.GROUP_MEMBERSHIP) {
            items.add(MenuItem("Delete this field") { deleteSelectedField() })
        }
        if (selected != null && selected.kind == DataKind.NAME) {
            items.add(MenuItem("Edit name parts") { openPartsMenu(selectedIndex) })
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

    private fun partDefs(kind: DataKind): List<StructuredEdit.Part> =
        if (kind == DataKind.NAME) NameParts.PARTS else PostalParts.PARTS

    /** The row's parts as they currently stand, edited or not. */
    private fun currentParts(field: EditorField): Map<String, String> {
        field.parts?.let { return it }
        val loaded = field.loadedParts
        return when {
            field.kind == DataKind.NAME && (loaded == null || field.text != field.loadedText) ->
                NameParts.fromText(loaded.orEmpty(), field.text)
            else -> loaded.orEmpty()
        }
    }

    /** A name or an address is edited as its parts - a menu listing each with its value, OK on one
     *  edits just that part inline - because the one-line text the row shows can't be turned back
     *  into parts without guessing, which is how addresses were flattened and prefixes became
     *  given names. */
    private fun openPartsMenu(index: Int) {
        val field = fields.getOrNull(index) ?: return
        val current = currentParts(field)
        menuSheet.show(
            partDefs(field.kind).map { part ->
                MenuItem("${part.label}: ${current[part.column].orEmpty()}") { beginInlineEdit(index, part = part) }
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
        val groupsToJoin = availableGroups().filter { it.id in pendingGroupIds }
        Async.run(
            background = {
                val newId = ContactStore.insertContact(this, chosenAccount, fieldValues)
                // Only now is there a contact to put in a group; a failure here must not undo
                // the contact that was just saved.
                val groupsOk = newId == null || groupsToJoin.isEmpty() || applyGroupChanges(this, newId, groupsToJoin, emptyList())
                newId to groupsOk
            },
            onResult = { (newId, groupsOk) ->
                if (newId == null) {
                    Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show()
                } else if (!groupsOk) {
                    Toast.makeText(this, "Saved, but couldn't update groups", Toast.LENGTH_SHORT).show()
                }
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
        val id = contactId
        val available = availableGroups()
        val joining = available.filter { it.id in pendingGroupIds && it.id !in originalGroupIds }
        val leaving = available.filter { it.id !in pendingGroupIds && it.id in originalGroupIds }
        Async.run(
            background = {
                val saved = ContactStore.applyFieldChanges(this, changes)
                // After the field changes, and on their own result: a membership problem is
                // reported as that, not as the fields not having been saved.
                val groupsOk = !saved || id == null || (joining.isEmpty() && leaving.isEmpty()) || applyGroupChanges(this, id, joining, leaving)
                saved to groupsOk
            },
            onResult = { (saved, groupsOk) ->
                if (!saved) {
                    Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show()
                } else if (!groupsOk) {
                    Toast.makeText(this, "Saved, but couldn't update groups", Toast.LENGTH_SHORT).show()
                }
                finish()
            },
        )
    }

    /** The values to write for [field]: everything for a field added here, but for a loaded row
     *  only the columns the user actually changed - null when nothing was, so the row isn't
     *  written at all. (This used to rebuild every column from the one-line text: names were
     *  re-split, structured addresses flattened, an IM row's type taken for its protocol, a lone
     *  job title made the company and custom labels uppercased, on any save of the contact.) */
    private fun fieldToKindValues(field: EditorField): Pair<DataKind, ContentValues>? {
        if (field.text.isBlank()) return null
        val isNew = field.dataRowId == null
        val typeChanged = isNew || field.typeLabel != field.loadedTypeLabel
        val textChanged = isNew || field.text != field.loadedText
        val values = ContentValues()
        when (field.kind) {
            DataKind.NAME -> {
                // The five parts, edited relative to the loaded ones: only a part that really
                // changed is written, and a prefix/middle/suffix still in the text stays one.
                val old = field.loadedParts.orEmpty()
                val edited = field.parts ?: if (textChanged) NameParts.fromText(old, field.text) else null
                if (edited != null) {
                    val changed = StructuredEdit.diff(old, edited)
                    if (textChanged || changed.size() > 0) {
                        values.putAll(changed)
                        values.put(CDK.StructuredName.DISPLAY_NAME, field.text)
                    }
                }
            }
            DataKind.ORGANIZATION -> if (textChanged) {
                val old = field.loadedParts.orEmpty()
                values.putAll(StructuredEdit.diff(old, OrganizationParts.fromText(old, field.text)))
            }
            DataKind.PHONE -> {
                if (textChanged) values.put(CDK.Phone.NUMBER, field.text)
                // Used to map anything but Mobile/Work/Home to Mobile, silently retyping every
                // Other/Main/Fax/custom number on any save of its contact.
                if (typeChanged) {
                    val type = phoneTypeFromLabel(resources, field.typeLabel)
                    values.put(CDK.Phone.TYPE, type)
                    values.put(CDK.Phone.LABEL, if (type == CDK.Phone.TYPE_CUSTOM) field.typeLabel else null)
                }
            }
            DataKind.EMAIL -> {
                if (textChanged) values.put(CDK.Email.ADDRESS, field.text)
                // Same fix as PHONE above: used to turn every Other/custom address into Home.
                if (typeChanged) {
                    val type = emailTypeFromLabel(resources, field.typeLabel)
                    values.put(CDK.Email.TYPE, type)
                    values.put(CDK.Email.LABEL, if (type == CDK.Email.TYPE_CUSTOM) field.typeLabel else null)
                }
            }
            DataKind.WEBSITE -> if (textChanged) values.put(CDK.Website.URL, field.text)
            DataKind.NICKNAME -> if (textChanged) values.put(CDK.Nickname.NAME, field.text)
            DataKind.NOTE -> if (textChanged) values.put(CDK.Note.NOTE, field.text)
            DataKind.POSTAL -> {
                // Edited part by part (see openPartsMenu); the line shown in the row is derived from
                // the parts and never written back. Changed parts invalidate the stored formatted
                // address, which the provider then builds again from the parts.
                val old = field.loadedParts.orEmpty()
                val edited = field.parts ?: if (textChanged) mapOf(CDK.StructuredPostal.STREET to field.text) else null
                if (edited != null) {
                    val changed = StructuredEdit.diff(old, edited)
                    if (changed.size() > 0) {
                        values.putAll(changed)
                        values.putNull(CDK.StructuredPostal.FORMATTED_ADDRESS)
                    }
                }
                if (typeChanged) putType(values, field, CDK.StructuredPostal.TYPE, CDK.StructuredPostal.LABEL)
            }
            DataKind.EVENT -> {
                if (textChanged) values.put(CDK.Event.START_DATE, field.text)
                // A custom event type (anything past Birthday/Anniversary/Other) needs its typed
                // label stored too, or the text is silently lost.
                if (typeChanged) putType(values, field, CDK.Event.TYPE, CDK.Event.LABEL)
            }
            DataKind.RELATION -> {
                if (textChanged) values.put(CDK.Relation.NAME, field.text)
                if (typeChanged) putType(values, field, CDK.Relation.TYPE, CDK.Relation.LABEL)
            }
            DataKind.IM -> {
                if (textChanged) values.put(CDK.Im.DATA, field.text)
                // The row's label is its protocol (Skype/Jabber/...), matching how the detail
                // screen prioritizes it over the home/work type, which isn't editable here: a
                // new row gets a fixed default, a loaded row keeps whatever it has.
                if (typeChanged) {
                    val protocol = imProtocolFromLabel(field.typeLabel)
                    values.put(CDK.Im.PROTOCOL, protocol)
                    values.put(CDK.Im.CUSTOM_PROTOCOL, if (protocol == CDK.Im.PROTOCOL_CUSTOM) field.typeLabel else null)
                }
                if (isNew) values.put(CDK.Im.TYPE, CDK.Im.TYPE_HOME)
            }
            DataKind.SIP -> {
                if (textChanged) values.put(CDK.SipAddress.SIP_ADDRESS, field.text)
                if (typeChanged) putType(values, field, CDK.SipAddress.TYPE, CDK.SipAddress.LABEL)
            }
            else -> return null
        }
        if (!isNew && values.size() == 0) return null
        return field.kind to values
    }

    private fun putType(values: ContentValues, field: EditorField, typeColumn: String, labelColumn: String) {
        val type = typeFromLabel(field.kind, field.typeLabel)
        values.put(typeColumn, type)
        values.put(labelColumn, if (type == CDK.BaseTypes.TYPE_CUSTOM) field.typeLabel else null)
    }

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
    }
}
