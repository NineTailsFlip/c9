package life.furrer.c9.contacts

import android.app.Activity
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.contacts.ContactDetail
import life.furrer.nine.contacts.ContactDetailRow
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.DataAction
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.contacts.DataFormat
import life.furrer.nine.contacts.PhotoLoader
import life.furrer.nine.contacts.action
import life.furrer.nine.contacts.buildDetailRows

private const val SIDE_PADDING_DP = 16
private const val PHOTO_SIZE_DP = 68
private const val REQUEST_CALL_PHONE_PERMISSION = 101

/** Read-only contact detail per §9.10 — every row is numbered 1-9 and is a verb: pressing its
 *  digit performs the row's [DataAction] directly (dial/mail/map/open), matching §7.4's "no
 *  search field here, so 1-9 are free". OK performs the currently *selected* row's action (same
 *  verb, reached via Up/Down instead of a digit) — the two are equivalent, not two different
 *  features. Edit/Delete aren't offered yet (M5 owns the editor); `*` only toggles the
 *  favorite, the one real write this read milestone can still offer safely. */
class ContactDetailActivity : Activity() {

    private var contactId: Long = -1L
    private var detail: ContactDetail? = null
    private var rows: List<ContactDetailRow> = emptyList()
    private var items: List<ContactDetailRow.Item> = emptyList()
    private var selectedIndex: Int = 0
    private var pendingDialNumber: String? = null

    private lateinit var photoView: ImageView
    private lateinit var initialsView: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var rowContainer: LinearLayout
    private lateinit var linkedLine: TextView
    private lateinit var scrollView: android.widget.ScrollView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private val rowViews = mutableListOf<LinearLayout>()

    // Parallel to `rowViews`/`items` — the section header view immediately preceding an item's
    // own row, or null when it isn't the first of its kind. Lets applySelectionStyle() scroll a
    // group's header into view together with its first item, instead of pinning the item's own
    // top to y=0 and scrolling the header (which sits just above it) off-screen.
    private val headerBeforeItem = mutableListOf<View?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
        if (contactId == -1L) {
            finish()
            return
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(4))
        }
        header.addView(
            TextView(this).apply {
                text = "‹ contact"
                textSize = 13f
                setTextColor(Palette.GRAY_FAINT)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val photoSize = dp(PHOTO_SIZE_DP)
        val photoFrame = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        initialsView = TextView(this).apply {
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        photoFrame.addView(initialsView, FrameLayout.LayoutParams(photoSize, photoSize))
        photoFrame.addView(photoView, FrameLayout.LayoutParams(photoSize, photoSize))

        titleText = TextView(this).apply {
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
        }
        subtitleText = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.GRAY_DIM)
            visibility = View.GONE
        }
        val nameColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(titleText)
            addView(subtitleText)
        }

        val identityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE_PADDING_DP), dp(8), dp(SIDE_PADDING_DP), dp(8))
            addView(photoFrame, LinearLayout.LayoutParams(photoSize, photoSize))
            addView(nameColumn)
        }

        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linkedLine = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(Palette.GRAY_FAINT)
            setPadding(dp(SIDE_PADDING_DP), dp(12), dp(SIDE_PADDING_DP), dp(8))
            visibility = View.GONE
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(identityRow)
            addView(rowContainer)
            addView(linkedLine)
        }
        scrollView = android.widget.ScrollView(this).apply {
            addView(content)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        hintBar = HintBar(this).apply { setText("* menu · 1-9 act · call dial") }
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
        setContentView(root)
        hideStatusBar()
    }

    override fun onResume() {
        super.onResume()
        if (contactId != -1L) load()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALL_PHONE_PERMISSION) {
            val number = pendingDialNumber
            pendingDialNumber = null
            if (number != null && checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                placeCall(number)
            }
        }
    }

    private fun load() {
        val id = contactId
        Async.run(
            background = { ContactStore.queryContactDetail(this, id) },
            onResult = { loaded ->
                if (loaded == null) {
                    finish()
                } else {
                    detail = loaded
                    rows = buildDetailRows(resources, loaded)
                    items = rows.filterIsInstance<ContactDetailRow.Item>()
                    selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    render(loaded)
                    PhotoLoader.load(this, id) { bitmap -> if (bitmap != null) showPhoto(bitmap) }
                }
            },
        )
    }

    private fun render(detail: ContactDetail) {
        titleText.text = detail.displayName
        initialsView.text = initialsOf(detail.displayName)
        initialsView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.pastelize(pastelSeedColor(detail.displayName)))
        }
        photoView.visibility = View.GONE
        initialsView.visibility = View.VISIBLE

        val org = detail.rows.firstOrNull { DataKind.fromMimetype(it.mimetype) == DataKind.ORGANIZATION }
        val orgText = org?.let { DataFormat.valueText(DataKind.ORGANIZATION, it.values) }?.takeIf { it.isNotBlank() }
        subtitleText.text = orgText
        subtitleText.visibility = if (orgText != null) View.VISIBLE else View.GONE

        rowContainer.removeAllViews()
        rowViews.clear()
        headerBeforeItem.clear()
        var pendingHeader: View? = null
        rows.forEach { row ->
            when (row) {
                is ContactDetailRow.SectionHeader -> {
                    val headerView = buildSectionHeaderView(row.label)
                    rowContainer.addView(headerView)
                    pendingHeader = headerView
                }
                is ContactDetailRow.Item -> {
                    val rowView = buildRowView(row)
                    rowViews.add(rowView)
                    headerBeforeItem.add(pendingHeader)
                    pendingHeader = null
                    rowContainer.addView(rowView)
                }
            }
        }
        applySelectionStyle()

        linkedLine.visibility = if (detail.isLinked) View.VISIBLE else View.GONE
        if (detail.isLinked) {
            linkedLine.text = "LINKED · " + detail.rawContacts.mapNotNull { it.accountName }.distinct().joinToString(", ").uppercase()
        }
    }

    private fun showPhoto(bitmap: android.graphics.Bitmap) {
        photoView.setImageBitmap(bitmap)
        photoView.visibility = View.VISIBLE
        initialsView.visibility = View.GONE
    }

    // A section header per kind, matching mockups.html's `.sect` exactly (10dp top / 16dp side /
    // 3dp bottom padding, 11sp, letter-spacing .11em) — the same convention already used for the
    // contact list's own letter-group headers (ContactRowRenderer.bindHeader).
    private fun buildSectionHeaderView(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.11f
        setTextColor(Palette.GRAY_FAINT)
        setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(3))
    }

    // Dimensions/type sizes match mockups.html's `.arow` exactly (§9.10's own reference render:
    // idx 12dp/13sp, label 52dp/11sp with letter-spacing, value 15sp, 4dp vertical row padding,
    // 7dp gaps) — every value gets its own numbered row with its own specific type label
    // (mobile/work/email/...), never merged into one multi-line "Phone" row.
    private fun buildRowView(row: ContactDetailRow.Item): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(SIDE_PADDING_DP), dp(4), dp(SIDE_PADDING_DP), dp(4))
        addView(
            TextView(this@ContactDetailActivity).apply {
                text = row.displayIndex?.toString().orEmpty()
                textSize = 13f
                setTextColor(Palette.GRAY_FAINT)
                minWidth = dp(12)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) },
        )
        addView(
            TextView(this@ContactDetailActivity).apply {
                text = row.typeLabel
                textSize = 11f
                letterSpacing = 0.06f
                minWidth = dp(52)
                setTextColor(Palette.GRAY_MUTED)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) },
        )
        addView(
            TextView(this@ContactDetailActivity).apply {
                text = row.value
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Palette.GRAY_DIM)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun applySelectionStyle() {
        rowViews.forEachIndexed { index, rowView ->
            val selected = index == selectedIndex
            for (i in 0 until rowView.childCount) {
                val child = rowView.getChildAt(i) as TextView
                if (i == rowView.childCount - 1) {
                    child.setTextColor(if (selected) android.graphics.Color.WHITE else Palette.GRAY_DIM)
                    child.paintFlags = if (selected) {
                        child.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    } else {
                        child.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
                    }
                }
            }
        }
        // Unlike RowListView's own recycling window, this screen's rows are plain views in a
        // ScrollView (deliberately — the row count here is small, per §5.2's "detail screens...
        // stay plain LinearLayout in a ScrollView"), so nothing scrolls to follow the selection
        // unless asked explicitly — a contact with more fields than fit on screen would otherwise
        // let the selection move silently off-screen.
        val scrollTarget = headerBeforeItem.getOrNull(selectedIndex) ?: rowViews.getOrNull(selectedIndex)
        scrollTarget?.let { view -> scrollView.post { scrollView.smoothScrollTo(0, view.top) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
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
                items.getOrNull(selectedIndex)?.let { performAction(it) }
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                openMenu()
                return true
            }
            KeyEvent.KEYCODE_CALL -> {
                dialPrimaryNumber()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val n = keyCode - KeyEvent.KEYCODE_0
            items.firstOrNull { it.displayIndex == n }?.let { row ->
                selectedIndex = items.indexOf(row)
                applySelectionStyle()
                performAction(row)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveSelection(direction: Int) {
        if (items.isEmpty()) return
        selectedIndex = (selectedIndex + direction + items.size) % items.size
        applySelectionStyle()
    }

    private fun performAction(row: ContactDetailRow.Item) {
        val kind = row.kind ?: return
        when (kind.action()) {
            DataAction.DIAL -> placeCallOrRequestPermission(row.value)
            DataAction.EMAIL -> startActivitySafely(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${row.value}")))
            DataAction.MAP -> startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(row.value)}")))
            DataAction.WEB -> {
                val url = if (row.value.contains("://")) row.value else "http://${row.value}"
                startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            DataAction.NONE -> {}
        }
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Nothing can handle that", Toast.LENGTH_SHORT).show()
        }
    }

    /** The call key always dials the contact's primary number regardless of which row is
     *  selected (§7.4's blanket rule for the call key, not conditioned on row selection). */
    private fun dialPrimaryNumber() {
        val phoneRows = items.filter { it.kind == DataKind.PHONE }
        if (phoneRows.isEmpty()) return
        val target = phoneRows.firstOrNull { it.value == items.getOrNull(selectedIndex)?.takeIf { r -> r.kind == DataKind.PHONE }?.value }
            ?: phoneRows.first()
        placeCallOrRequestPermission(target.value)
    }

    private fun placeCallOrRequestPermission(number: String) {
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            placeCall(number)
        } else {
            pendingDialNumber = number
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE_PERMISSION)
        }
    }

    private fun placeCall(number: String) {
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }

    private fun openMenu() {
        val current = detail ?: return
        val favoriteLabel = if (current.starred) "Remove from favorites" else "Add to favorites"
        menuSheet.show(
            listOf(
                MenuItem("Edit") { launchEdit(current) },
                MenuItem(favoriteLabel) { toggleFavorite(current) },
                MenuItem("Delete") { confirmDelete(current) },
            ),
        )
    }

    private fun launchEdit(current: ContactDetail) {
        startActivity(
            Intent(this, ContactEditActivity::class.java).apply {
                putExtra(ContactEditActivity.EXTRA_CONTACT_ID, current.id)
            },
        )
    }

    private fun confirmDelete(current: ContactDetail) {
        menuSheet.show(
            listOf(
                MenuItem("Yes, delete \"${current.displayName}\"") { deleteAndFinish(current.id) },
                MenuItem("Keep contact") {},
            ),
        )
    }

    private fun deleteAndFinish(id: Long) {
        Async.run(
            background = { ContactStore.deleteContact(this, id) },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show()
                finish()
            },
        )
    }

    private fun toggleFavorite(current: ContactDetail) {
        val newValue = !current.starred
        Async.run(
            background = { ContactStore.setStarred(this, current.id, newValue) },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't update", Toast.LENGTH_SHORT).show()
                load()
            },
        )
    }

    private fun initialsOf(displayName: String): String {
        val words = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
    }

    private fun pastelSeedColor(displayName: String): Int {
        val hue = (displayName.hashCode() and 0xFFFFFF) % 360
        val (r, g, b) = Palette.hslToRgb(hue.toDouble(), 0.6, 0.5)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
    }
}
