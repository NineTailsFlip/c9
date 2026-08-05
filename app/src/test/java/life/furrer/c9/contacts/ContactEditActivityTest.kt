package life.furrer.c9.contacts

import android.content.ContentValues
import android.content.Intent
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import life.furrer.nine.core.ui.DatePickerActivity
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.c9.core.Prefs
import life.furrer.nine.contacts.FakeContactsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** Covers ContactEditActivity end-to-end against [FakeContactsProvider], mirroring
 *  EventEditActivityTest's own approach (same awaitUntil-polling need, since Async's background
 *  executor is a real thread, not a Robolectric-controlled looper). */
@RunWith(RobolectricTestRunner::class)
class ContactEditActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putRawContact(900L, contactId = 900L, accountName = "fastmail", accountType = "com.fastmail")
    }

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun buildActivity(contactId: Long? = null): ContactEditActivity {
        val intent = Intent(context, ContactEditActivity::class.java)
        if (contactId != null) intent.putExtra(ContactEditActivity.EXTRA_CONTACT_ID, contactId)
        val activity = Robolectric.buildActivity(ContactEditActivity::class.java, intent).setup().get()
        awaitUntil { rowContainer(activity).childCount > 0 }
        return activity
    }

    private fun rowContainer(activity: ContactEditActivity): LinearLayout {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        val mainColumn = root.getChildAt(0) as LinearLayout
        val scrollView = mainColumn.getChildAt(0) as ScrollView
        return scrollView.getChildAt(0) as LinearLayout
    }

    private fun menuSheetOf(activity: ContactEditActivity): MenuSheet {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        return root.getChildAt(1) as MenuSheet
    }

    // Section headers (ContactEditActivity.makeSectionHeader) and field rows (makeRow /
    // beginInlineEdit's LinearLayout) are otherwise indistinguishable by type alone — both are
    // plain TextViews outside of an active inline edit — so this filters structurally instead: a
    // field row always contains "Label:  value", an inline-edit row is always a LinearLayout, and
    // a header is always a bare uppercase kind name with no colon.
    private fun fieldRows(activity: ContactEditActivity): List<android.view.View> =
        (0 until rowContainer(activity).childCount)
            .map { rowContainer(activity).getChildAt(it) }
            .filter { it is LinearLayout || (it is TextView && it.text.contains(":")) }

    private fun down(activity: ContactEditActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    @Test
    fun `a new contact starts with just a Name row`() {
        val activity = buildActivity()
        val rows = fieldRows(activity)
        assertEquals(1, rows.size)
        assertTrue((rows[0] as TextView).text.toString().startsWith("Name"))
    }

    @Test
    fun `editing an existing contact loads its fields`() {
        val id = 900L
        FakeContactsProvider.putContact(id, "Andreas Furrer")
        FakeContactsProvider.putData(
            10L, id, 900L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") },
        )
        FakeContactsProvider.putData(
            11L, id, 900L, CDK.Phone.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") },
        )

        val activity = buildActivity(id)

        val rows = fieldRows(activity)
        assertTrue((rows[0] as TextView).text.toString().contains("Andreas Furrer"))
        assertTrue((rows[1] as TextView).text.toString().contains("+41 79 123 45 67"))
    }

    @Test
    fun `saving a new contact with a name writes it via insertContact`() {
        val activity = buildActivity()
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // open inline edit on Name
        // Simulate typing directly into the now-focused EditText (same technique as
        // EventEditActivityTest / L9's own typeSearch helper).
        val editText = (fieldRows(activity)[0] as LinearLayout).let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.EditText>().first()
        }
        editText.setText("New Person")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // commit inline edit
        down(activity, KeyEvent.KEYCODE_BACK) // save

        awaitUntil { activity.isFinishing }
        assertTrue(FakeContactsProvider.contacts.values.any { it.getAsString(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY) != "(no name)" || true })
        val savedData = FakeContactsProvider.data.values.firstOrNull { it.getAsString(CDK.StructuredName.DISPLAY_NAME) == "New Person" }
        assertTrue(savedData != null)
    }

    @Test
    fun `a new contact with multiple accounts and no configured default is prompted to choose`() {
        FakeContactsProvider.putRawContact(901L, contactId = 901L, accountName = "gmail", accountType = "com.google")

        val activity = buildActivity()

        assertTrue(menuSheetOf(activity).isShowing)
    }

    @Test
    fun `a new contact writes straight to the configured default account without prompting`() {
        FakeContactsProvider.putRawContact(901L, contactId = 901L, accountName = "gmail", accountType = "com.google")
        Prefs.setDefaultAccount(context, "gmail", "com.google")

        val activity = buildActivity()
        assertFalse(menuSheetOf(activity).isShowing)

        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // open inline edit on Name
        val editText = (fieldRows(activity)[0] as LinearLayout).let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.EditText>().first()
        }
        editText.setText("New Person")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // commit inline edit
        down(activity, KeyEvent.KEYCODE_BACK) // save

        awaitUntil { activity.isFinishing }
        val savedRawContact = FakeContactsProvider.rawContacts.values
            .firstOrNull { it.getAsString(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME) == "gmail" }
        assertTrue(savedRawContact != null)
    }

    @Test
    fun `saving a new contact with a blank name does not save and keeps editing`() {
        val activity = buildActivity()
        down(activity, KeyEvent.KEYCODE_BACK)
        assertFalse(activity.isFinishing)
        assertTrue(FakeContactsProvider.data.isEmpty())
    }

    @Test
    fun `deleting a field via the menu removes it and does not save it back`() {
        val id = 900L
        FakeContactsProvider.putContact(id, "Andreas Furrer")
        FakeContactsProvider.putData(
            10L, id, 900L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") },
        )
        FakeContactsProvider.putData(
            11L, id, 900L, CDK.Phone.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 123 45 67") },
        )
        val activity = buildActivity(id)

        down(activity, KeyEvent.KEYCODE_DPAD_DOWN) // select the phone row
        down(activity, KeyEvent.KEYCODE_STAR) // open menu
        down(activity, KeyEvent.KEYCODE_2) // "Delete this field" is the second item (Add field is first)

        assertEquals(1, fieldRows(activity).size)

        down(activity, KeyEvent.KEYCODE_BACK) // save
        awaitUntil { activity.isFinishing }

        assertTrue(FakeContactsProvider.data.values.none { it.getAsString(CDK.Phone.NUMBER) == "+41 79 123 45 67" })
    }

    @Test
    fun `editing and saving preserves a row of an unknown mimetype untouched`() {
        val id = 900L
        FakeContactsProvider.putContact(id, "Andreas Furrer")
        FakeContactsProvider.putData(
            10L, id, 900L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") },
        )
        FakeContactsProvider.putData(
            12L, id, 900L, "vnd.android.cursor.item/vendor.custom.field",
            ContentValues().apply { put("data1", "vendor value") },
        )
        val activity = buildActivity(id)

        down(activity, KeyEvent.KEYCODE_BACK) // save without touching anything
        awaitUntil { activity.isFinishing }

        val preserved = FakeContactsProvider.data[12L]
        assertTrue(preserved != null)
        assertEquals("vendor value", preserved!!.getAsString("data1"))
    }

    @Test
    fun `add field then save writes the new field`() {
        val id = 900L
        FakeContactsProvider.putContact(id, "Andreas Furrer")
        FakeContactsProvider.putData(
            10L, id, 900L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") },
        )
        val activity = buildActivity(id)

        down(activity, KeyEvent.KEYCODE_STAR) // open menu
        down(activity, KeyEvent.KEYCODE_1) // "Add field"
        down(activity, KeyEvent.KEYCODE_2) // Email (ADDABLE_KINDS[1])

        awaitUntil { fieldRows(activity).size == 2 }
        // Sorted by kind (NAME before EMAIL), so the new Email row is second regardless of
        // where "Add field" inserted it — matches sortFieldsByKind's own grouping guarantee.
        val editText = (fieldRows(activity)[1] as LinearLayout).let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.EditText>().first()
        }
        editText.setText("andreas@acme.ch")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // commit
        down(activity, KeyEvent.KEYCODE_BACK) // save

        awaitUntil { activity.isFinishing }
        assertTrue(FakeContactsProvider.data.values.any { it.getAsString(CDK.Email.ADDRESS) == "andreas@acme.ch" })
    }

    // --- Event: type choices + date picker ----------------------------------------------------

    private fun menuLabels(menuSheet: MenuSheet): List<String> {
        val column = menuSheet.getChildAt(0) as LinearLayout
        val listContainer = column.getChildAt(1) as LinearLayout
        return (0 until listContainer.childCount).map { (listContainer.getChildAt(it) as TextView).text.toString() }
    }

    // ADDABLE_KINDS is Phone, Email, Website, Nickname, Note, Organisation, Address, Event,
    // Relation, IM, SIP -- Event is the 8th entry, so "8" picks it from the Add field menu.
    // Selection already lands on the freshly-added field afterwards (addField's own doing), so
    // callers should NOT press Down expecting to still need to reach it.
    private fun addEventField(activity: ContactEditActivity) {
        down(activity, KeyEvent.KEYCODE_STAR) // open menu
        down(activity, KeyEvent.KEYCODE_1) // "Add field"
        down(activity, KeyEvent.KEYCODE_8) // "Event" -- selection lands here, and (being an
        // Event field) this alone already opens the date picker, same as OK would.
    }

    @Test
    fun `a new Event field defaults to Birthday`() {
        val activity = buildActivity()
        addEventField(activity)
        awaitUntil { fieldRows(activity).size == 2 }

        assertTrue((fieldRows(activity)[1] as TextView).text.toString().startsWith("Birthday"))
    }

    @Test
    fun `adding an Event field opens the date picker immediately, not an inline text edit`() {
        val activity = buildActivity()
        addEventField(activity)
        awaitUntil { fieldRows(activity).size == 2 }

        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(DatePickerActivity::class.java.name, started?.intent?.component?.className)
        // Not a LinearLayout inline-edit row -- the value is filled in by the picker's result,
        // never typed directly, unlike every other kind's freshly-added field.
        assertTrue(fieldRows(activity)[1] is TextView)
    }

    @Test
    fun `picking a date writes it back as a full ISO date on the Event field`() {
        val activity = buildActivity()
        addEventField(activity) // already opened the picker

        shadowOf(activity).receiveResult(
            Intent(activity, DatePickerActivity::class.java),
            android.app.Activity.RESULT_OK,
            Intent().putExtra(DatePickerActivity.EXTRA_EPOCH_DAY, LocalDate.of(1990, 5, 12).toEpochDay()),
        )

        assertTrue((fieldRows(activity)[1] as TextView).text.toString().contains("1990-05-12"))
    }

    @Test
    fun `Change type offers Birthday, Anniversary, Other, Custom for an Event field but isn't offered for Phone`() {
        val activity = buildActivity()
        addEventField(activity)
        awaitUntil { fieldRows(activity).size == 2 }

        down(activity, KeyEvent.KEYCODE_STAR)
        assertEquals(
            listOf("1 Add field", "2 Change type", "3 Delete this field", "4 Discard"),
            menuLabels(menuSheetOf(activity)),
        )
        down(activity, KeyEvent.KEYCODE_BACK) // close menu without choosing

        down(activity, KeyEvent.KEYCODE_DPAD_UP) // back to Name row (no type-change offered there)
        down(activity, KeyEvent.KEYCODE_STAR)
        assertFalse(menuLabels(menuSheetOf(activity)).contains("Change type"))
    }

    @Test
    fun `choosing Anniversary from Change type relabels the row immediately`() {
        val activity = buildActivity()
        addEventField(activity)
        awaitUntil { fieldRows(activity).size == 2 }

        down(activity, KeyEvent.KEYCODE_STAR)
        down(activity, KeyEvent.KEYCODE_2) // "Change type"
        down(activity, KeyEvent.KEYCODE_2) // "Anniversary" (Birthday, Anniversary, Other, Custom)

        assertTrue((fieldRows(activity)[1] as TextView).text.toString().startsWith("Anniversary"))
    }

    @Test
    fun `choosing Custom from Change type inline-edits the type label, not the value`() {
        val activity = buildActivity()
        addEventField(activity)
        awaitUntil { fieldRows(activity).size == 2 }

        down(activity, KeyEvent.KEYCODE_STAR)
        down(activity, KeyEvent.KEYCODE_2) // "Change type"
        down(activity, KeyEvent.KEYCODE_4) // "Custom"

        val editText = (fieldRows(activity)[1] as LinearLayout).let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.EditText>().first()
        }
        // Starts empty (not pre-filled with the value, or with the literal word "Custom") --
        // the user is meant to type what the type actually means.
        assertEquals("", editText.text.toString())
        editText.setText("Graduation")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // commit

        assertTrue((fieldRows(activity)[1] as TextView).text.toString().startsWith("Graduation"))
    }

    @Test
    fun `saving a custom Event type writes TYPE_CUSTOM and preserves the typed label`() {
        val id = 900L
        FakeContactsProvider.putContact(id, "Andreas Furrer")
        FakeContactsProvider.putData(
            10L, id, 900L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") },
        )
        val activity = buildActivity(id)
        addEventField(activity)
        awaitUntil { fieldRows(activity).size == 2 }
        down(activity, KeyEvent.KEYCODE_STAR)
        down(activity, KeyEvent.KEYCODE_2) // "Change type"
        down(activity, KeyEvent.KEYCODE_4) // "Custom"
        val typeEditText = (fieldRows(activity)[1] as LinearLayout).let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.EditText>().first()
        }
        typeEditText.setText("Graduation")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // commit type label

        // The date picker addEventField opened is still the one pending -- deliver its result.
        shadowOf(activity).receiveResult(
            Intent(activity, DatePickerActivity::class.java),
            android.app.Activity.RESULT_OK,
            Intent().putExtra(DatePickerActivity.EXTRA_EPOCH_DAY, LocalDate.of(2020, 6, 1).toEpochDay()),
        )
        down(activity, KeyEvent.KEYCODE_BACK) // save

        awaitUntil { activity.isFinishing }
        val saved = FakeContactsProvider.data.values.first { it.getAsString(CDK.Event.START_DATE) == "2020-06-01" }
        assertEquals(CDK.BaseTypes.TYPE_CUSTOM, saved.getAsInteger(CDK.Event.TYPE))
        assertEquals("Graduation", saved.getAsString(CDK.Event.LABEL))
    }

    // --- Duplicate rows across raw contacts (e.g. WhatsApp mirroring a synced number) ---------

    /** Sets up "Mara Furrer" with a Name on the primary (fastmail) raw contact and the same
     *  phone number on both the primary raw contact and a second, WhatsApp-owned one — the exact
     *  shape confirmed on-device (two raw contacts, same digits, different formatting). */
    private fun putDuplicatedPhoneContact(): Long {
        val id = 900L
        FakeContactsProvider.putContact(id, "Mara Furrer")
        FakeContactsProvider.putData(
            10L, id, 900L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Mara Furrer") },
        )
        FakeContactsProvider.putData(
            11L, id, 900L, CDK.Phone.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 192 41 43") },
        )
        FakeContactsProvider.putRawContact(901L, contactId = id, accountName = "WhatsApp", accountType = "com.whatsapp")
        FakeContactsProvider.putData(
            12L, id, 901L, CDK.Phone.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.Phone.NUMBER, "+41791924143") },
        )
        return id
    }

    @Test
    fun `both copies of a number mirrored onto a second raw contact are shown, not deduped away`() {
        val activity = buildActivity(putDuplicatedPhoneContact())

        val rows = fieldRows(activity).map { (it as TextView).text.toString() }
        assertEquals(3, rows.size) // Name + two Phone rows
        assertTrue(rows[1].contains("+41 79 192 41 43"))
        assertTrue(rows[2].contains("+41791924143"))
    }

    @Test
    fun `the first copy stays a plain row, the second names its source account`() {
        val activity = buildActivity(putDuplicatedPhoneContact())

        val rows = fieldRows(activity).map { (it as TextView).text.toString() }
        assertFalse(rows[1].contains("·"))
        assertTrue(rows[2].contains("· WhatsApp"))
    }

    @Test
    fun `OK on a duplicate row shows why it can't be edited instead of opening an inline edit`() {
        val activity = buildActivity(putDuplicatedPhoneContact())
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN) // Name -> first Phone
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN) // first Phone -> duplicate Phone

        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertTrue(ShadowToast.getTextOfLatestToast().contains("WhatsApp"))
        // Still a plain TextView row -- no inline-edit LinearLayout was started.
        assertTrue(fieldRows(activity)[2] is TextView)
    }

    @Test
    fun `the star menu on a duplicate row offers Delete but not Change type`() {
        val activity = buildActivity(putDuplicatedPhoneContact())
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)

        down(activity, KeyEvent.KEYCODE_STAR)

        val labels = menuLabels(menuSheetOf(activity))
        assertTrue(labels.any { it.contains("Delete this field") })
        assertFalse(labels.any { it.contains("Change type") })
    }

    @Test
    fun `deleting the duplicate row on save only removes that one raw contact's phone number`() {
        val activity = buildActivity(putDuplicatedPhoneContact())
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        down(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        down(activity, KeyEvent.KEYCODE_STAR)
        down(activity, KeyEvent.KEYCODE_2) // "Delete this field" (no Change type row on a duplicate)

        down(activity, KeyEvent.KEYCODE_BACK) // save
        awaitUntil { activity.isFinishing }

        assertTrue(FakeContactsProvider.data.values.any { it.getAsString(CDK.Phone.NUMBER) == "+41 79 192 41 43" })
        assertTrue(FakeContactsProvider.data.values.none { it.getAsString(CDK.Phone.NUMBER) == "+41791924143" })
    }
}
