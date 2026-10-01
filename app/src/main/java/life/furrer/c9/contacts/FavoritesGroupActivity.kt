package life.furrer.c9.contacts

import android.app.Activity
import android.content.ContentResolver
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import life.furrer.c9.core.Prefs
import life.furrer.nine.contacts.ContactGroups
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactStore.WritableAccount
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

internal const val DEFAULT_FAVORITES_TITLE = "Favorites"

/** One selectable row of the picker. */
internal sealed interface FavoritesChoice {
    data object None : FavoritesChoice
    data class Existing(val group: ContactGroup) : FavoritesChoice
    data class Create(val account: WritableAccount) : FavoritesChoice
}

/** The picker's rows in display order: [FavoritesChoice.None] first, then per writable account
 *  its groups and (unless it already has a "Favorites" group, which is just selected instead of
 *  duplicated, or [createIn] rules the account out) a Create row. Groups in read-only accounts are
 *  left out: membership can only be written, and so synced, in an account C9 may write to. */
internal fun favoritesChoices(
    groups: List<ContactGroup>,
    accounts: List<WritableAccount>,
    createIn: (WritableAccount) -> Boolean = { true },
): List<Pair<WritableAccount?, FavoritesChoice>> {
    val rows = mutableListOf<Pair<WritableAccount?, FavoritesChoice>>(null to FavoritesChoice.None)
    accounts.sortedBy { it.name }.forEach { account ->
        val inAccount = groups.filter { it.accountName == account.name && it.accountType == account.type }
        inAccount.forEach { rows.add(account to FavoritesChoice.Existing(it)) }
        if (createIn(account) && inAccount.none { it.title.equals(DEFAULT_FAVORITES_TITLE, ignoreCase = true) }) {
            rows.add(account to FavoritesChoice.Create(account))
        }
    }
    return rows
}

/** Settings → Contacts → Favorites group. Same plain LinearLayout/ScrollView, grouped-by-account
 *  keypad shape as [ContactGroupsActivity], but a single choice: OK picks the row and closes. */
class FavoritesGroupActivity : Activity() {

    private var choices: List<Pair<WritableAccount?, FavoritesChoice>> = emptyList()
    private var currentGroupId: Long? = null
    private var selectedIndex = 0
    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    private val rows = mutableListOf<LinearLayout>()
    private val labelViews = mutableListOf<TextView>()

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
        val hintBar = HintBar(this).apply { setText("ok choose · ‹ back") }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(hintBar)
            },
        )
        hideStatusBar()

        val groups = ContactStore.queryGroups(this)
        val accounts = ContactStore.queryWritableAccounts(this)
        choices = favoritesChoices(groups, accounts, createTargets(accounts))
        currentGroupId = (FavoritesSync.favoritesGroupState(this) as? FavoritesSync.GroupState.Present)?.group?.id
        selectedIndex = choices.indexOfFirst { (_, c) ->
            if (currentGroupId == null) c is FavoritesChoice.None else (c as? FavoritesChoice.Existing)?.group?.id == currentGroupId
        }.coerceAtLeast(0)
        buildRows()
        render()
    }

    /** Where "Create" is offered. "Writable" also covers messaging apps' mirror accounts (WhatsApp,
     *  Signal) — confirmed on-device, where both showed up — and a group there would never reach a
     *  real address book. The Default address book setting is the user's own answer to "which of
     *  these is my real address book", so when set it's the only target. Without one, fall back to
     *  accounts whose sync adapter uploads contacts at all: that drops WhatsApp (declared
     *  read-only) but not Signal, which on-device claims supportsUploading=true; app names are
     *  deliberately never hardcoded. */
    private fun createTargets(accounts: List<WritableAccount>): (WritableAccount) -> Boolean {
        val default = accounts.firstOrNull { it.name == Prefs.defaultAccountName(this) && it.type == Prefs.defaultAccountType(this) }
        if (default != null) return { it == default }
        val uploading = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }
            .toSet()
        return { it.type in uploading }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun label(choice: FavoritesChoice, account: WritableAccount?): String = when (choice) {
        FavoritesChoice.None -> "None — use starred only"
        is FavoritesChoice.Existing -> choice.group.title
        is FavoritesChoice.Create -> "Create “$DEFAULT_FAVORITES_TITLE” in ${account?.name}"
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
                text = if (choice is FavoritesChoice.Existing && choice.group.id == currentGroupId) "current" else ""
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
            FavoritesChoice.None -> applyGroup(null)
            is FavoritesChoice.Existing -> applyGroup(choice.group)
            is FavoritesChoice.Create -> Async.run(
                background = { createOrReuse(app, choice.account) },
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
        FavoritesSync.setFavoritesGroup(this, group)
        FavoritesScheduler.update(this)
        if (group != null) FavoritesScheduler.lineUpAsync(this)
        finish()
    }

    internal companion object {
        /** Blocking. Reuses an existing "Favorites" group in [account] rather than duplicating it. */
        fun createOrReuse(context: android.content.Context, account: WritableAccount): ContactGroup? =
            ContactStore.queryGroups(context).firstOrNull {
                it.accountName == account.name && it.accountType == account.type &&
                    it.title.equals(DEFAULT_FAVORITES_TITLE, ignoreCase = true)
            } ?: ContactGroups.createGroup(context, account, DEFAULT_FAVORITES_TITLE)
    }
}
