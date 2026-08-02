package life.furrer.c9.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.PermissionScreen
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.SearchField
import life.furrer.nine.core.util.StringMatch
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.c9.settings.SettingsActivity
import life.furrer.nine.core.settings.NineSettingsActivity

private const val REQUEST_CONTACTS_PERMISSION = 100
private const val REQUEST_CALL_PHONE_PERMISSION = 101
private const val SIDE_PADDING_DP = 16

/** Reserved per §5.4/§7.4: Up/Down/OK are always this screen's own list navigation, never the
 *  search field's — a single-line search box has no use for them. C9's own deliberate deviation
 *  from L9's set additionally reserves Left/Right (letter-group jump), `*` (menu), `#`
 *  (favorites toggle), and the call key, so none of them ever leak into the T9 field either. */
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND,
    KeyEvent.KEYCODE_CALL,
)

/** The contacts list per §9.8/§9.9/§7.4 — M4 read scope: search, favorites, letter groups, and
 *  the call key all work; `*` only offers "Sync now" for now, since New/Edit/Delete need M5's
 *  editor and Groups/Accounts/Settings need M6's screens (same "only offer items with a real
 *  destination this milestone" scoping M2 used for the calendar's own `*` menu). */
class ContactsActivity : Activity() {

    private var allContacts: List<ContactEntry> = emptyList()
    private var rows: List<ContactRow> = emptyList()
    private var favoritesOnly = false
    private var pendingDialNumber: String? = null

    // `*`'s short-tap-vs-hold distinction (§7.2: hold `*` → Settings) is normally the framework's
    // own job via onKeyLongPress — but that relies on the key actually reaching onKeyDown via
    // the default dispatch, which this screen's dispatchKeyEvent override bypasses entirely for
    // reserved keys (see below). Tracked by hand instead, the same "start a delayed callback on
    // DOWN, cancel and check a flag on UP" shape the framework itself uses.
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var starLongPressFired = false
    private val starLongPressRunnable = Runnable {
        starLongPressFired = true
        startActivity(NineSettingsActivity.intentFor(this, SettingsActivity::class.java, "contacts"))
    }

    // Confirmed on-device (§12): once the search EditText has real IME focus, this device's
    // hardware T9 IME swallows KEYCODE_STAR for its own case-cycling before it ever reaches
    // dispatchKeyEvent — tap-`*` silently does nothing while actively searching (query non-empty).
    // §5.4's own documented fallback: hold-OK opens the menu too, since OK never goes near the IME
    // (it's reserved before the field ever sees it). Tap-OK still opens the detail screen as
    // before. Once the query goes back to empty, [SearchField.onDeactivated] below recovers `*`/
    // `#` properly instead of leaving this as the only way in — see its own doc comment.
    private var okLongPressFired = false
    private val okLongPressRunnable = Runnable {
        okLongPressFired = true
        openMenu()
    }

    // Selecting a menu item via OK closes the sheet as a side effect of handling that same key's
    // ACTION_DOWN (see the `menuSheet.isShowing` branch below) — so by the time the matching
    // ACTION_UP for that physical keypress arrives, `menuSheet.isShowing` is already false, and it
    // would otherwise fall through into this Activity's own OK-key ACTION_UP handling below and
    // fire openDetail() on top of whatever the chosen menu item just did (confirmed on-device:
    // choosing "New contact" via OK also opened the previously-selected contact's detail screen).
    // Tracked here instead of relying on isShowing so that specific trailing UP is swallowed
    // regardless of what the menu's visibility became while handling the DOWN.
    private var menuConsumedKeyUp: Int? = null

    // Guards STAR/OK's ACTION_UP handling below against a *different* physical keypress leaking
    // in — confirmed on-device: opening this screen from the launcher with a single OK press
    // could immediately fall through into openDetail() too, as if OK had been pressed twice. The
    // launcher consumes that press's ACTION_DOWN itself (it's what launches this Activity); if
    // this window doesn't finish gaining focus until after the physical key is released, only the
    // trailing ACTION_UP reaches this Activity's dispatchKeyEvent, with no DOWN of its own ever
    // having set `okLongPressFired` — indistinguishable, before this fix, from a genuine tap.
    // Same "only trust an UP whose matching DOWN this instance itself saw" shape as
    // [SearchField.seenKeyDowns], which this mirrors rather than shares (that one guards a
    // different window: the search field taking real focus, not this Activity's own launch).
    private val seenKeyDowns = mutableSetOf<Int>()

    private lateinit var rowListView: RowListView
    private lateinit var searchField: SearchField
    private lateinit var countLabel: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var permissionScreen: PermissionScreen
    private var contentObserver: ContentObserver? = null

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = createContactRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            bindContactRow(view as LinearLayout, rows[index], selected)
        }
        override fun rowHeight(index: Int): Int = contactRowHeightDp(rows[index])
        override fun isSelectable(index: Int): Boolean = rows[index].isSelectable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        favoritesOnly = intent.getBooleanExtra(EXTRA_FAVORITES_ONLY, false)

        searchField = SearchField(this).apply {
            onQueryChanged = { rebuildRows() }
            onDeactivated = { restartFresh() }
            editText.apply {
                hint = "Type to search"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(Palette.GRAY_FAINT)
                setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(6))
            }
        }

        rowListView = RowListView(this).apply { setBinder(binder) }
        countLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(SIDE_PADDING_DP), dp(4), dp(SIDE_PADDING_DP), dp(4))
            visibility = View.GONE
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        permissionScreen = PermissionScreen(this).apply {
            onGrant = { requestContactsPermission() }
            onLeave = { finish() }
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchField.editText)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(countLabel)
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(permissionScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()
        updateHint()

        if (ContactStore.hasPermission(this) && ContactStore.hasWritePermission(this)) {
            loadContacts()
        } else {
            // §9.13's "first run" screen — shown here rather than requesting permission
            // straight away, and shown again from onRequestPermissionsResult on denial, so the
            // same screen covers both "explain before the very first ask" and "explain again and
            // offer to retry after a denial" without a separate persisted flag.
            permissionScreen.show(
                "C9 Contacts needs access to your Contacts to show, search, and edit your address book.\n\n" +
                    "C9 has no network permission — it never talks to a server. Syncing (if you use it) is handled separately, e.g. by DAVx⁵.",
            )
        }
    }

    // Both requested together, not just READ — confirmed on-device (same gap found and fixed
    // for WRITE_CALENDAR during M3) that WRITE_CONTACTS is not auto-granted alongside
    // READ_CONTACTS, and New/Edit/Delete need it from the first save attempt.
    private fun requestContactsPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            REQUEST_CONTACTS_PERMISSION,
        )
    }

    override fun onResume() {
        super.onResume()
        if (ContactStore.hasPermission(this) && ContactStore.hasWritePermission(this)) {
            permissionScreen.hide()
            registerContentObserver()
            // Picks up a changed Groups/Accounts filter (§7.4) on return from either screen —
            // those are local prefs, not a provider write, so the ContentObserver never fires.
            loadContacts()
        } else if (!permissionScreen.isShowing) {
            // Covers permission revoked via system Settings while this Activity was backgrounded
            // — onCreate's own explainer only runs once, so this is the other path back to it.
            permissionScreen.show(
                "C9 Contacts needs access to your Contacts to show, search, and edit your address book.\n\n" +
                    "C9 has no network permission — it never talks to a server. Syncing (if you use it) is handled separately, e.g. by DAVx⁵.",
            )
        }
    }

    override fun onPause() {
        super.onPause()
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        searchField.onPause()
        seenKeyDowns.clear()
    }

    /** [SearchField.onDeactivated]'s fix for the device's captured `*`/`#` swallow needs a
     *  genuinely fresh window, not an in-place `recreate()` (see that doc comment for what was
     *  ruled out first) — so this rebuilds the Activity via `finish()` + a fresh `startActivity`
     *  rather than a config-change-style relaunch, and `favoritesOnly` (the one piece of state
     *  worth keeping; everything else is either cheap to reload from the provider or, like the
     *  search query itself, already empty at the point this fires) rides along as an Intent extra
     *  instead of `onRetainNonConfigurationInstance` — that API only carries state across an
     *  in-place relaunch, which this deliberately isn't. `overridePendingTransition(0, 0)` keeps
     *  it invisible rather than a flicker. */
    private fun restartFresh() {
        finish()
        overridePendingTransition(0, 0)
        startActivity(Intent(this, ContactsActivity::class.java).putExtra(EXTRA_FAVORITES_ONLY, favoritesOnly))
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            searchField.onWindowFocusGained()
            hideStatusBar()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CONTACTS_PERMISSION -> {
                if (ContactStore.hasPermission(this) && ContactStore.hasWritePermission(this)) {
                    permissionScreen.hide()
                    loadContacts()
                } else {
                    permissionScreen.show(
                        "C9 Contacts still needs access to your Contacts to show, search, and edit your address book.\n\n" +
                            "C9 has no network permission — it never talks to a server. Syncing (if you use it) is handled separately, e.g. by DAVx⁵.",
                    )
                }
            }
            REQUEST_CALL_PHONE_PERMISSION -> {
                val number = pendingDialNumber
                pendingDialNumber = null
                if (number != null && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    placeCall(number)
                }
            }
        }
    }

    private fun registerContentObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = loadContacts()
        }
        contentObserver = observer
        contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
    }

    private fun loadContacts() {
        Async.run(
            background = {
                val contacts = ContactStore.queryContacts(this)
                val groupIds = ContactStore.getSelectedGroupIds(this)
                val accounts = ContactStore.getSelectedAccounts(this)
                // Both filters are "null means everyone" (§7.4's Groups/Accounts), matching
                // CalendarStore's own default-to-all convention — a fresh install filters nothing.
                val allowedByGroup = groupIds?.let { ContactStore.queryContactIdsInGroups(this, it) }
                val allowedByAccount = accounts?.let { ContactStore.queryContactIdsInAccounts(this, it) }
                contacts.filter { contact ->
                    (allowedByGroup == null || contact.id in allowedByGroup) &&
                        (allowedByAccount == null || contact.id in allowedByAccount)
                }
            },
            onResult = { result ->
                allContacts = result
                rebuildRows()
            },
        )
    }

    private fun rebuildRows() {
        rows = buildContactRows(allContacts, searchField.query, favoritesOnly)
        rowListView.notifyDataChanged()
        val query = searchField.query
        countLabel.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        if (query.isNotEmpty()) {
            val matchCount = rows.count { it.isSelectable() }
            countLabel.text = "$matchCount of ${allContacts.size} contacts"
        }
        updateHint()
    }

    private fun updateHint() {
        hintBar.setText(
            if (searchField.query.isEmpty()) {
                val toggleLabel = if (favoritesOnly) "# all" else "# favorites"
                "* menu · $toggleLabel · ← → letter"
            } else {
                "call dial · ‹ clear · ok open"
            },
        )
    }

    /** See [SearchField]'s own doc comment for the pattern this follows. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (permissionScreen.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) permissionScreen.handleKeyDown(event.keyCode)
            return true
        }
        if (menuSheet.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                menuSheet.handleKeyDown(event.keyCode, event)
                menuConsumedKeyUp = event.keyCode
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == menuConsumedKeyUp) {
            menuConsumedKeyUp = null
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
            onBackNotConsumedByIme()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_STAR) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    seenKeyDowns.add(event.keyCode)
                    starLongPressFired = false
                    longPressHandler.postDelayed(starLongPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                KeyEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(starLongPressRunnable)
                    if (seenKeyDowns.remove(event.keyCode) && !starLongPressFired) openMenu()
                }
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    seenKeyDowns.add(event.keyCode)
                    okLongPressFired = false
                    longPressHandler.postDelayed(okLongPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                KeyEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(okLongPressRunnable)
                    if (seenKeyDowns.remove(event.keyCode) && !okLongPressFired) openDetail()
                }
            }
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

    /** Back clears the search; on an already-empty search it leaves (§7.4) — `0` can't mean
     *  "clear" here since digits belong to the live search field. */
    private fun onBackNotConsumedByIme() {
        if (searchField.query.isEmpty()) {
            finish()
        } else {
            searchField.clear()
        }
    }

    // KEYCODE_STAR and KEYCODE_DPAD_CENTER/ENTER are handled directly in dispatchKeyEvent
    // (each needs its own DOWN/UP-tracked long-press timer, see [starLongPressRunnable] and
    // [okLongPressRunnable]) rather than through this generic ACTION_DOWN-only dispatch.
    private fun handleReservedKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> jumpLetterGroup(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> jumpLetterGroup(1)
            KeyEvent.KEYCODE_POUND -> toggleFavoritesOnly()
            KeyEvent.KEYCODE_CALL -> dial()
        }
    }

    /** Jumps to the previous/next letter-group header regardless of where within the current
     *  group the selection currently sits (§7.4) — a fast scrub, not a same-group no-op. */
    private fun jumpLetterGroup(direction: Int) {
        val current = rowListView.selectedIndex
        val headerIndices = rows.withIndex().filter { it.value is ContactRow.SectionHeader }.map { it.index }
        if (headerIndices.isEmpty()) return
        if (direction > 0) {
            val next = headerIndices.firstOrNull { it > current } ?: return
            rowListView.setSelectedIndex(next + 1)
        } else {
            val currentGroupStart = headerIndices.lastOrNull { it <= current } ?: return
            val prev = headerIndices.lastOrNull { it < currentGroupStart } ?: return
            rowListView.setSelectedIndex(prev + 1)
        }
    }

    private fun toggleFavoritesOnly() {
        favoritesOnly = !favoritesOnly
        rebuildRows()
    }

    private fun openDetail() {
        val contact = rows.getOrNull(rowListView.selectedIndex)?.contactOrNull() ?: return
        startActivity(Intent(this, ContactDetailActivity::class.java).apply {
            putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contact.id)
        })
    }

    private fun openMenu() {
        val selected = rows.getOrNull(rowListView.selectedIndex)?.contactOrNull()
        val items = mutableListOf(MenuItem("New contact") { launchNew() })
        if (selected != null) {
            items.add(MenuItem("Edit \"${selected.displayName}\"") { launchEdit(selected) })
            items.add(MenuItem("Delete \"${selected.displayName}\"") { confirmDelete(selected) })
        }
        items.add(MenuItem("Groups") { startActivity(Intent(this, ContactGroupsActivity::class.java)) })
        items.add(MenuItem("Accounts") { startActivity(Intent(this, ContactAccountsActivity::class.java)) })
        items.add(MenuItem("Sync now") { ContactStore.requestSync(this) })
        items.add(MenuItem("Settings") { startActivity(NineSettingsActivity.intentFor(this, SettingsActivity::class.java, "contacts")) })
        menuSheet.show(items)
    }

    private fun launchNew() {
        startActivity(Intent(this, ContactEditActivity::class.java))
    }

    private fun launchEdit(contact: ContactEntry) {
        startActivity(
            Intent(this, ContactEditActivity::class.java).apply {
                putExtra(ContactEditActivity.EXTRA_CONTACT_ID, contact.id)
            },
        )
    }

    private fun confirmDelete(contact: ContactEntry) {
        menuSheet.show(
            listOf(
                MenuItem("Yes, delete \"${contact.displayName}\"") { deleteAndReload(contact.id) },
                MenuItem("Keep contact") {},
            ),
        )
    }

    private fun deleteAndReload(id: Long) {
        Async.run(
            background = { ContactStore.deleteContact(this, id) },
            onResult = { success ->
                if (!success) android.widget.Toast.makeText(this, "Couldn't delete", android.widget.Toast.LENGTH_SHORT).show()
                loadContacts()
            },
        )
    }

    /** The call key's target always follows §7.4: the selected contact's primary number (asking
     *  first if more than one and none is marked primary — "never guess"), or, when nothing at
     *  all is selected/matched, the typed digits verbatim if they're dial-able — so the list
     *  doubles as a plain dialer for numbers not in any contact's address book. */
    private fun dial() {
        when (val selected = rows.getOrNull(rowListView.selectedIndex)) {
            is ContactRow.Entry -> dialContact(selected.contact)
            is ContactRow.NumberMatch -> placeCallOrRequestPermission(selected.matchedNumber)
            else -> {
                val query = searchField.query
                if (StringMatch.looksDialable(query)) placeCallOrRequestPermission(query)
            }
        }
    }

    private fun dialContact(contact: ContactEntry) {
        val numbers = contact.phoneNumbers
        when {
            numbers.isEmpty() -> {}
            numbers.size == 1 -> placeCallOrRequestPermission(numbers.first().number)
            else -> {
                val primary = numbers.firstOrNull { it.isSuperPrimary }
                if (primary != null) {
                    placeCallOrRequestPermission(primary.number)
                } else {
                    menuSheet.show(
                        numbers.map { n -> MenuItem("${n.label ?: "Number"}: ${n.number}") { placeCallOrRequestPermission(n.number) } },
                    )
                }
            }
        }
    }

    private fun placeCallOrRequestPermission(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            placeCall(number)
        } else {
            pendingDialNumber = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE_PERMISSION)
        }
    }

    private fun placeCall(number: String) {
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private companion object {
        const val EXTRA_FAVORITES_ONLY = "favorites_only"
    }
}
