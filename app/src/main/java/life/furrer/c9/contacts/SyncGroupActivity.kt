package life.furrer.c9.contacts

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.contacts.ContactGroups
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactStore.WritableAccount
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.contacts.GroupSync
import life.furrer.nine.contacts.VoicemailSync
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.Async

/** What C9 says about one [GroupSync] in Settings and its group picker. */
internal enum class SyncedProperty(
    val sync: GroupSync,
    /** The Settings row. */
    val settingsLabel: String,
    /** The title a group C9 creates for it gets. */
    val defaultTitle: String,
    /** The picker's "no group" row, and the Settings value for it. */
    val noneChoice: String,
    val noneValue: String,
    /** The confirm row before choosing an existing group with (count) members, when the first
     *  line-up giving all of them the property would hurt; null chooses straight away. */
    val confirmMembers: ((Int) -> String)? = null,
) {
    FAVORITES(FavoritesSync, "Favorites group", "Favorites", "None — use starred only", "none (starred only)"),

    // Picking "Family" by mistake would turn the whole family's calls away unannounced.
    VOICEMAIL(
        VoicemailSync, "Voicemail group", "Send to voicemail", "None — this phone only", "none (this phone only)",
        confirmMembers = { count -> "Yes, send calls from $count ${if (count == 1) "contact" else "contacts"} to voicemail" },
    ),
    ;

    /** What the Settings row shows: the group and where it lives, or why there isn't one. */
    fun label(state: GroupSync.GroupState): String = when (state) {
        GroupSync.GroupState.None -> noneValue
        is GroupSync.GroupState.Present -> "${state.group.title} · ${state.group.accountName}"
        is GroupSync.GroupState.Missing -> "${state.stored.title}: not found"
    }

    companion object {
        /** The property whose chosen group [group] is, if any. Stored settings only, no query. */
        fun ofGroup(context: Context, group: ContactGroup): SyncedProperty? = entries.firstOrNull { it.sync.isGroup(context, group) }
    }
}

/** One selectable row of the picker. */
internal sealed interface GroupChoice {
    data object None : GroupChoice
    data class Existing(val group: ContactGroup) : GroupChoice
    data class Create(val account: WritableAccount) : GroupChoice
}

/** The picker's rows in display order: [GroupChoice.None] first, then per writable account its
 *  groups and (unless it already has a group titled [defaultTitle], which is just selected
 *  instead of duplicated, or [createIn] rules the account out) a Create row. Groups in read-only
 *  accounts are left out: membership can only be written, and so synced, in an account C9 may
 *  write to. [taken] (another property's chosen group) is left out too: one group carrying two
 *  properties would make each sync's writes the other's changes. */
internal fun groupChoices(
    groups: List<ContactGroup>,
    accounts: List<WritableAccount>,
    defaultTitle: String,
    createIn: (WritableAccount) -> Boolean = { true },
    taken: (ContactGroup) -> Boolean = { false },
): List<Pair<WritableAccount?, GroupChoice>> {
    val rows = mutableListOf<Pair<WritableAccount?, GroupChoice>>(null to GroupChoice.None)
    accounts.sortedBy { it.name }.forEach { account ->
        val inAccount = groups.filter { it.accountName == account.name && it.accountType == account.type }
        inAccount.filterNot(taken).forEach { rows.add(account to GroupChoice.Existing(it)) }
        if (createIn(account) && inAccount.none { it.title.equals(defaultTitle, ignoreCase = true) }) {
            rows.add(account to GroupChoice.Create(account))
        }
    }
    return rows
}

/** Settings → Contacts → Favorites group / Voicemail group ([EXTRA_PROPERTY]). A plain
 *  LinearLayout/ScrollView keypad list grouped by account (like [ContactAccountsActivity]),
 *  single choice: OK picks the row and closes. */
class SyncGroupActivity : NineActivity() {

    private lateinit var property: SyncedProperty
    private var choices: List<Pair<WritableAccount?, GroupChoice>> = emptyList()
    private var currentGroupId: Long? = null
    private var selectedIndex = 0
    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    private val rows = mutableListOf<LinearLayout>()
    private val labelViews = mutableListOf<TextView>()
    private var memberCounts: Map<Long, Int> = emptyMap()
    private lateinit var menuSheet: MenuSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        property = intent.getStringExtra(EXTRA_PROPERTY)?.let { name -> SyncedProperty.entries.firstOrNull { it.name == name } }
            ?: SyncedProperty.FAVORITES

        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply {
            addView(rowContainer)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val hintBar = HintBar(this).apply { setText("ok choose · ‹ back") }
        menuSheet = MenuSheet(this)
        setContentView(
            FrameLayout(this).apply {
                addView(
                    LinearLayout(this@SyncGroupActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                        addView(hintBar)
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )

        val groups = ContactStore.queryGroups(this)
        memberCounts = ContactGroups.queryMemberCounts(this)
        val accounts = ContactStore.queryWritableAccounts(this)
        val others = SyncedProperty.entries - property
        choices = groupChoices(groups, accounts, property.defaultTitle, createTargets(accounts)) { group ->
            others.any { it.sync.isGroup(this, group) }
        }
        currentGroupId = (property.sync.groupState(this) as? GroupSync.GroupState.Present)?.group?.id
        selectedIndex = choices.indexOfFirst { (_, c) ->
            if (currentGroupId == null) c is GroupChoice.None else (c as? GroupChoice.Existing)?.group?.id == currentGroupId
        }.coerceAtLeast(0)
        buildRows()
        render()
    }

    /** Where "Create" is offered: real address books only, see [realAddressBooks]. */
    private fun createTargets(accounts: List<WritableAccount>): (WritableAccount) -> Boolean {
        val real = realAddressBooks(this, accounts).toSet()
        return { it in real }
    }

    private fun label(choice: GroupChoice, account: WritableAccount?): String = when (choice) {
        GroupChoice.None -> property.noneChoice
        is GroupChoice.Existing -> choice.group.title
        is GroupChoice.Create -> "Create “${property.defaultTitle}” in ${account?.name}"
    }

    private fun buildRows() {
        var lastAccount: WritableAccount? = null
        choices.forEach { (account, choice) ->
            if (account != null && account != lastAccount) {
                rowContainer.addView(
                    TextView(this).apply {
                        text = account.name.uppercase()
                        textSize = 11f
                        letterSpacing = 0.11f
                        setTextColor(Palette.GRAY_FAINT)
                        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(20), dp(Dimens.SIDE_PADDING_DP), dp(6))
                    },
                )
            }
            lastAccount = account
            val labelView = TextView(this).apply {
                text = label(choice, account)
                textSize = 18f
            }
            val status = TextView(this).apply {
                textSize = 13f
                setTextColor(Palette.GRAY_MUTED)
                text = if (choice is GroupChoice.Existing) {
                    listOfNotNull(
                        memberCounts[choice.group.id]?.toString(),
                        "current".takeIf { choice.group.id == currentGroupId },
                    ).joinToString("  ")
                } else {
                    ""
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(10))
                addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(status)
            }
            rows.add(row)
            labelViews.add(labelView)
            rowContainer.addView(row)
        }
    }

    private fun render() {
        labelViews.forEachIndexed { index, view ->
            val isSelectedRow = index == selectedIndex
            view.setTextColor(if (isSelectedRow) Color.WHITE else Palette.GRAY_DIM)
            view.paintFlags = if (isSelectedRow) {
                view.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                view.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
        rows.getOrNull(selectedIndex)?.let { row -> scrollView.post { scrollView.smoothScrollTo(0, row.top) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                selectedIndex = (selectedIndex - 1 + rows.size) % rows.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                selectedIndex = (selectedIndex + 1) % rows.size
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                choose()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun choose() {
        val choice = choices.getOrNull(selectedIndex)?.second ?: return
        val app = applicationContext
        when (choice) {
            GroupChoice.None -> applyGroup(null)
            is GroupChoice.Existing -> {
                val count = memberCounts[choice.group.id] ?: 0
                val confirm = property.confirmMembers
                if (confirm != null && count > 0 && choice.group.id != currentGroupId) {
                    menuSheet.show(listOf(MenuItem(confirm(count)) { applyGroup(choice.group) }, MenuItem("Keep current") {}))
                } else {
                    applyGroup(choice.group)
                }
            }
            is GroupChoice.Create -> Async.run(
                background = { createOrReuse(app, choice.account, property.defaultTitle) },
                onResult = { group ->
                    if (group == null) {
                        Toast.makeText(this, "Couldn't create group", Toast.LENGTH_SHORT).show()
                    } else {
                        applyGroup(group)
                    }
                },
            )
        }
    }

    /** Stores the choice, (re)arms or cancels the background job, runs one line-up now so the
     *  two sides meet without waiting for the next contacts change, and closes. */
    private fun applyGroup(group: ContactGroup?) {
        if (group != null && !ContactStore.hasWritePermission(this)) {
            Toast.makeText(this, "Contacts write permission needed", Toast.LENGTH_SHORT).show()
            return
        }
        property.sync.setGroup(this, group)
        GroupSyncScheduler.update(this)
        if (group != null) GroupSyncScheduler.lineUpAsync(this)
        finish()
    }

    internal companion object {
        /** The [SyncedProperty] name whose group is picked; Favorites when missing. */
        const val EXTRA_PROPERTY = "property"

        fun intent(context: Context, property: SyncedProperty): Intent =
            Intent(context, SyncGroupActivity::class.java).putExtra(EXTRA_PROPERTY, property.name)

        /** Blocking. Reuses an existing group titled [title] in [account] rather than duplicating it. */
        fun createOrReuse(context: Context, account: WritableAccount, title: String): ContactGroup? =
            ContactStore.queryGroups(context).firstOrNull {
                it.accountName == account.name && it.accountType == account.type && it.title.equals(title, ignoreCase = true)
            } ?: ContactGroups.createGroup(context, account, title)
    }
}
