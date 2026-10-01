package life.furrer.c9.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.contacts.ContactGroups
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactStore.WritableAccount
import life.furrer.nine.contacts.MembershipResult
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

/** One checklist row: a group of a real address book, whether the contact is in it, and whether
 *  it could be (it has to have a raw contact in the group's account). */
private class ChecklistRow(val group: ContactGroup, val joinable: Boolean, var checked: Boolean, val favorite: Boolean)

/** The contact's groups as a checklist: every group in the real address books ([realAddressBooks]),
 *  checked when the contact is a member, OK toggles. Two modes:
 *
 *  - **Immediate** (detail screen's `*` → "Groups…", [EXTRA_CONTACT_ID] only): each toggle is
 *    written at once through [setGroupMember], so there is no Save step to forget on a keypad.
 *  - **Deferred** ([EXTRA_DEFERRED], the editor's Groups row): nothing is written; Back returns
 *    the checked group ids in [EXTRA_CHECKED_IDS] and the editor applies them when it saves
 *    its own changes. [EXTRA_ACCOUNT_NAME]/[EXTRA_ACCOUNT_TYPE] limit the list to one account —
 *    a new contact can only ever join the groups of the account it is created in; with
 *    [EXTRA_CONTACT_ID] too (an existing contact) groups the contact can't join are dimmed.
 *
 *  A group the contact can't join (it has no raw contact in the group's account) is dimmed with
 *  "(not in this address book)" and refuses to toggle. The favorites group is marked ★ and, in
 *  immediate mode, toggling it keeps STARRED in step (see [setGroupMember]). No text entry, so
 *  none of the editor's IME/Autofill defenses are needed. */
class GroupChecklistActivity : Activity() {

    private var rows: List<ChecklistRow> = emptyList()
    private var selectedIndex = 0
    private var busy = false
    private val deferred get() = intent.getBooleanExtra(EXTRA_DEFERRED, false)
    private val contactId get() = intent.getLongExtra(EXTRA_CONTACT_ID, -1L).takeIf { it != -1L }

    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private val rowViews = mutableListOf<LinearLayout>()
    private val labelViews = mutableListOf<TextView>()
    private val statusViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            addView(rowContainer)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val hintBar = HintBar(this).apply { setText(if (deferred) "ok toggle · ‹ done" else "ok toggle · ‹ back") }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(hintBar)
            },
        )
        hideStatusBar()
        load()
    }

    private fun load() {
        val app = applicationContext
        val contact = contactId
        val onlyAccount = intent.getStringExtra(EXTRA_ACCOUNT_NAME)?.let { WritableAccount(it, intent.getStringExtra(EXTRA_ACCOUNT_TYPE).orEmpty()) }
        val initiallyChecked = intent.getLongArrayExtra(EXTRA_CHECKED_IDS)?.toSet()
        Async.run(
            background = {
                val real = realAddressBooks(app, ContactStore.queryWritableAccounts(app))
                val groups = ContactStore.queryGroups(app)
                    .filter { g -> real.any { it.name == g.accountName && it.type == g.accountType } }
                    .filter { g -> onlyAccount == null || (g.accountName == onlyAccount.name && g.accountType == onlyAccount.type) }
                    .sortedWith(compareBy({ it.accountName }, { it.title.lowercase() }))
                val member = initiallyChecked ?: contact?.let { id -> ContactGroups.queryGroupsOfContact(app, id).map { it.id }.toSet() }.orEmpty()
                groups.map { g ->
                    ChecklistRow(
                        g,
                        joinable = contact == null || ContactGroups.isJoinable(app, contact, g),
                        checked = g.id in member,
                        favorite = isFavoritesGroup(app, g),
                    )
                }
            },
            onResult = {
                rows = it
                buildRows()
                render()
            },
        )
    }

    private fun label(row: ChecklistRow) = "${if (row.checked) "[x]" else "[ ]"} ${if (row.favorite) "★ " else ""}${row.group.title}"

    private fun buildRows() {
        rowContainer.removeAllViews()
        rowViews.clear()
        labelViews.clear()
        statusViews.clear()
        if (rows.isEmpty()) {
            rowContainer.addView(
                TextView(this).apply {
                    text = "No groups yet"
                    textSize = 16f
                    setTextColor(Palette.GRAY_MUTED)
                    setPadding(dp(Dimens.SIDE_PADDING_DP), dp(16), dp(Dimens.SIDE_PADDING_DP), dp(16))
                },
            )
            return
        }
        val multipleAccounts = rows.map { it.group.accountName to it.group.accountType }.distinct().size > 1
        var lastAccount: String? = null
        rows.forEach { row ->
            if (multipleAccounts && row.group.accountName != lastAccount) {
                rowContainer.addView(
                    TextView(this).apply {
                        text = row.group.accountName.uppercase()
                        textSize = 11f
                        letterSpacing = 0.11f
                        setTextColor(Palette.GRAY_FAINT)
                        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(20), dp(Dimens.SIDE_PADDING_DP), dp(6))
                    },
                )
            }
            lastAccount = row.group.accountName
            val labelView = TextView(this).apply { textSize = 18f }
            val status = TextView(this).apply {
                textSize = 13f
                setTextColor(Palette.GRAY_MUTED)
                text = if (row.joinable) "" else "(not in this address book)"
            }
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(10))
                addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(status)
            }
            rowViews.add(view)
            labelViews.add(labelView)
            statusViews.add(status)
            rowContainer.addView(view)
        }
    }

    private fun render() {
        labelViews.forEachIndexed { index, view ->
            val row = rows[index]
            val isSelectedRow = index == selectedIndex
            view.text = label(row)
            view.setTextColor(
                when {
                    !row.joinable -> Palette.GRAY_FAINT
                    isSelectedRow -> Color.WHITE
                    else -> Palette.GRAY_DIM
                },
            )
            view.paintFlags = if (isSelectedRow) view.paintFlags or Paint.UNDERLINE_TEXT_FLAG else view.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
        rowViews.getOrNull(selectedIndex)?.let { row -> scrollView.post { scrollView.smoothScrollTo(0, row.top) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (rows.isNotEmpty()) selectedIndex = (selectedIndex - 1 + rows.size) % rows.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (rows.isNotEmpty()) selectedIndex = (selectedIndex + 1) % rows.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggle()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (deferred) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CHECKED_IDS, rows.filter { it.checked }.map { it.group.id }.toLongArray()))
                }
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggle() {
        val row = rows.getOrNull(selectedIndex) ?: return
        if (busy) return
        if (!row.joinable) {
            Toast.makeText(this, "Not in ${row.group.accountName}, can't join", Toast.LENGTH_SHORT).show()
            return
        }
        val contact = contactId
        if (deferred || contact == null) {
            row.checked = !row.checked
            render()
            return
        }
        busy = true
        val app = applicationContext
        val member = !row.checked
        Async.run(
            background = { setGroupMember(app, contact, row.group, member) },
            onResult = { result ->
                busy = false
                when (result) {
                    MembershipResult.CHANGED, MembershipResult.UNCHANGED -> row.checked = member
                    MembershipResult.NOT_JOINABLE -> Toast.makeText(this, "Not in ${row.group.accountName}, can't join", Toast.LENGTH_SHORT).show()
                    MembershipResult.FAILED -> Toast.makeText(this, "Couldn't update", Toast.LENGTH_SHORT).show()
                }
                render()
            },
        )
    }

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_DEFERRED = "deferred"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_ACCOUNT_TYPE = "account_type"
        /** Deferred mode: the checked group ids going in (long array) and, as the result, coming out. */
        const val EXTRA_CHECKED_IDS = "checked_ids"

        /** Immediate mode for [contactId]. */
        fun intent(context: Context, contactId: Long): Intent =
            Intent(context, GroupChecklistActivity::class.java).putExtra(EXTRA_CONTACT_ID, contactId)

        /** Deferred mode for the editor: [contactId] null for a new contact, which can only join
         *  groups of [account]. */
        fun deferredIntent(context: Context, contactId: Long?, account: WritableAccount?, checked: Collection<Long>): Intent =
            Intent(context, GroupChecklistActivity::class.java)
                .putExtra(EXTRA_DEFERRED, true)
                .putExtra(EXTRA_CHECKED_IDS, checked.toLongArray())
                .apply {
                    contactId?.let { putExtra(EXTRA_CONTACT_ID, it) }
                    account?.let { putExtra(EXTRA_ACCOUNT_NAME, it.name).putExtra(EXTRA_ACCOUNT_TYPE, it.type) }
                }
    }
}
