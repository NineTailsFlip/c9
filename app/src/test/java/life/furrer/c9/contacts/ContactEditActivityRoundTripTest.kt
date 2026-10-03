package life.furrer.c9.contacts

import android.content.ContentValues
import android.content.Intent
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.core.ui.MenuSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The editor must only write what the user changed (todo.sr.ht ~timofurrer/nine#1): saving a
 *  contact used to rewrite every row from its one-line text, flattening names and addresses,
 *  retyping IM rows, turning a lone job title into a company and uppercasing custom labels. */
@RunWith(RobolectricTestRunner::class)
class ContactEditActivityRoundTripTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putRawContact(900L, contactId = 900L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putContact(900L, "Dr. Hans Peter Muster")
        uploadingAccountTypes("com.fastmail" to true)
    }


    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun open(): ContactEditActivity {
        val intent = Intent(context, ContactEditActivity::class.java).putExtra(ContactEditActivity.EXTRA_CONTACT_ID, 900L)
        val activity = Robolectric.buildActivity(ContactEditActivity::class.java, intent).setup().get()
        awaitUntil { rowContainer(activity).childCount > 0 }
        return activity
    }

    private fun rowContainer(activity: ContactEditActivity): LinearLayout {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        val scrollView = (root.getChildAt(0) as LinearLayout).getChildAt(0) as ScrollView
        return scrollView.getChildAt(0) as LinearLayout
    }

    private fun menuSheetOf(activity: ContactEditActivity): MenuSheet =
        (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout).getChildAt(1) as MenuSheet

    private fun menuLabels(activity: ContactEditActivity): List<String> {
        val list = (menuSheetOf(activity).getChildAt(0) as LinearLayout).getChildAt(1) as LinearLayout
        return (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() }
    }

    private fun fieldRows(activity: ContactEditActivity) =
        (0 until rowContainer(activity).childCount).map { rowContainer(activity).getChildAt(it) }
            .filter { it is LinearLayout || (it is TextView && it.text.contains(":")) }

    private fun down(activity: ContactEditActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    /** Moves the selection to the first field row whose text contains [needle]. */
    private fun select(activity: ContactEditActivity, needle: String) {
        val target = fieldRows(activity).indexOfFirst { it is TextView && it.text.contains(needle) }
        check(target >= 0) { "no row containing $needle" }
        repeat(target) { down(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
    }

    private fun typeInto(activity: ContactEditActivity, index: Int, text: String) {
        val row = fieldRows(activity)[index] as LinearLayout
        (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<EditText>().first().setText(text)
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun saveAndWait(activity: ContactEditActivity) {
        down(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { activity.isFinishing }
    }

    private fun putName() = FakeContactsProvider.putData(
        10L, 900L, 900L, CDK.StructuredName.CONTENT_ITEM_TYPE,
        ContentValues().apply {
            put(CDK.StructuredName.DISPLAY_NAME, "Dr. Hans Peter Muster")
            put(CDK.StructuredName.PREFIX, "Dr.")
            put(CDK.StructuredName.GIVEN_NAME, "Hans")
            put(CDK.StructuredName.MIDDLE_NAME, "Peter")
            put(CDK.StructuredName.FAMILY_NAME, "Muster")
        },
    )

    private fun putAddress() = FakeContactsProvider.putData(
        11L, 900L, 900L, CDK.StructuredPostal.CONTENT_ITEM_TYPE,
        ContentValues().apply {
            put(CDK.StructuredPostal.FORMATTED_ADDRESS, "Bahnhofstrasse 1\n8001 Zürich\nSwitzerland")
            put(CDK.StructuredPostal.STREET, "Bahnhofstrasse 1")
            put(CDK.StructuredPostal.CITY, "Zürich")
            put(CDK.StructuredPostal.POSTCODE, "8001")
            put(CDK.StructuredPostal.COUNTRY, "Switzerland")
            put(CDK.StructuredPostal.TYPE, CDK.StructuredPostal.TYPE_WORK)
        },
    )

    private fun putIm() = FakeContactsProvider.putData(
        12L, 900L, 900L, CDK.Im.CONTENT_ITEM_TYPE,
        ContentValues().apply {
            put(CDK.Im.DATA, "hans@jabber.example")
            put(CDK.Im.TYPE, CDK.Im.TYPE_HOME)
            put(CDK.Im.PROTOCOL, CDK.Im.PROTOCOL_JABBER)
        },
    )

    private fun putTitleOnly() = FakeContactsProvider.putData(
        13L, 900L, 900L, CDK.Organization.CONTENT_ITEM_TYPE,
        ContentValues().apply { put(CDK.Organization.TITLE, "Engineer") },
    )

    private fun putEvent() = FakeContactsProvider.putData(
        14L, 900L, 900L, CDK.Event.CONTENT_ITEM_TYPE,
        ContentValues().apply {
            put(CDK.Event.START_DATE, "2020-06-01")
            put(CDK.Event.TYPE, CDK.Event.TYPE_CUSTOM)
            put(CDK.Event.LABEL, "Graduation Day")
        },
    )

    private fun putRelation() = FakeContactsProvider.putData(
        15L, 900L, 900L, CDK.Relation.CONTENT_ITEM_TYPE,
        ContentValues().apply {
            put(CDK.Relation.NAME, "Anna")
            put(CDK.Relation.TYPE, CDK.Relation.TYPE_CUSTOM)
            put(CDK.Relation.LABEL, "Godmother")
        },
    )

    private fun putEverything() {
        putName(); putAddress(); putIm(); putTitleOnly(); putEvent(); putRelation()
    }

    @Test
    fun `saving without changes rewrites no data row at all`() {
        putEverything()
        val activity = open()
        saveAndWait(activity)
        assertEquals(emptyList<Long>(), FakeContactsProvider.dataUpdates)
        assertTrue(FakeContactsProvider.data.getValue(11L).getAsString(CDK.StructuredPostal.STREET) == "Bahnhofstrasse 1")
    }

    @Test
    fun `an IM row keeps its protocol and type when only the handle changes`() {
        putEverything()
        val activity = open()
        select(activity, "hans@jabber")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        typeInto(activity, fieldRows(activity).indexOfFirst { it is LinearLayout }, "hans2@jabber.example")
        saveAndWait(activity)

        val im = FakeContactsProvider.data.getValue(12L)
        assertEquals("hans2@jabber.example", im.getAsString(CDK.Im.DATA))
        assertEquals(CDK.Im.PROTOCOL_JABBER, im.getAsInteger(CDK.Im.PROTOCOL))
        assertEquals(CDK.Im.TYPE_HOME, im.getAsInteger(CDK.Im.TYPE))
        assertNull(im.getAsString(CDK.Im.CUSTOM_PROTOCOL))
    }

    @Test
    fun `a job title without a company stays the title when edited`() {
        putEverything()
        val activity = open()
        select(activity, "Engineer")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        typeInto(activity, fieldRows(activity).indexOfFirst { it is LinearLayout }, "Senior Engineer")
        saveAndWait(activity)

        val org = FakeContactsProvider.data.getValue(13L)
        assertEquals("Senior Engineer", org.getAsString(CDK.Organization.TITLE))
        assertTrue(org.getAsString(CDK.Organization.COMPANY).isNullOrEmpty())
    }

    @Test
    fun `organisation text with a separator still splits into company and title`() {
        putEverything()
        val activity = open()
        select(activity, "Engineer")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        typeInto(activity, fieldRows(activity).indexOfFirst { it is LinearLayout }, "ACME · Engineer")
        saveAndWait(activity)

        val org = FakeContactsProvider.data.getValue(13L)
        assertEquals("ACME", org.getAsString(CDK.Organization.COMPANY))
        assertEquals("Engineer", org.getAsString(CDK.Organization.TITLE))
    }

    @Test
    fun `editing an event's date or a relation's name keeps the custom label's case`() {
        putEverything()
        val activity = open()
        select(activity, "Anna")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        typeInto(activity, fieldRows(activity).indexOfFirst { it is LinearLayout }, "Anna Maria")
        saveAndWait(activity)

        assertEquals("Anna Maria", FakeContactsProvider.data.getValue(15L).getAsString(CDK.Relation.NAME))
        assertEquals("Godmother", FakeContactsProvider.data.getValue(15L).getAsString(CDK.Relation.LABEL))
        assertEquals("Graduation Day", FakeContactsProvider.data.getValue(14L).getAsString(CDK.Event.LABEL))
        assertFalse(FakeContactsProvider.dataUpdates.contains(14L))
    }

    @Test
    fun `editing the name text keeps prefix and middle name and does not make the prefix the given name`() {
        putEverything()
        val activity = open()
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // Name row
        typeInto(activity, 0, "Dr. Hans Peter Meier")
        saveAndWait(activity)

        val name = FakeContactsProvider.data.getValue(10L)
        assertEquals("Dr.", name.getAsString(CDK.StructuredName.PREFIX))
        assertEquals("Hans", name.getAsString(CDK.StructuredName.GIVEN_NAME))
        assertEquals("Peter", name.getAsString(CDK.StructuredName.MIDDLE_NAME))
        assertEquals("Meier", name.getAsString(CDK.StructuredName.FAMILY_NAME))
        assertEquals("Dr. Hans Peter Meier", name.getAsString(CDK.StructuredName.DISPLAY_NAME))
        assertEquals(listOf(10L), FakeContactsProvider.dataUpdates)
    }

    @Test
    fun `removing the prefix from the name text clears it`() {
        putEverything()
        val activity = open()
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        typeInto(activity, 0, "Hans Peter Muster")
        saveAndWait(activity)

        val name = FakeContactsProvider.data.getValue(10L)
        assertNull(name.getAsString(CDK.StructuredName.PREFIX))
        assertEquals("Hans", name.getAsString(CDK.StructuredName.GIVEN_NAME))
        assertEquals("Peter", name.getAsString(CDK.StructuredName.MIDDLE_NAME))
        assertEquals("Muster", name.getAsString(CDK.StructuredName.FAMILY_NAME))
    }

    @Test
    fun `the name parts menu edits one part and leaves the others`() {
        putEverything()
        val activity = open()
        down(activity, KeyEvent.KEYCODE_STAR)
        val labels = menuLabels(activity)
        val partsKey = labels.first { it.contains("parts", ignoreCase = true) }.substringBefore(' ').toInt()
        down(activity, KeyEvent.KEYCODE_0 + partsKey)
        assertEquals(listOf("1 Prefix: Dr.", "2 First: Hans", "3 Middle: Peter", "4 Last: Muster", "5 Suffix:"), menuLabels(activity).map { it.trimEnd() })
        down(activity, KeyEvent.KEYCODE_5) // suffix
        typeInto(activity, 0, "med.")
        saveAndWait(activity)

        val name = FakeContactsProvider.data.getValue(10L)
        assertEquals("med.", name.getAsString(CDK.StructuredName.SUFFIX))
        assertEquals("Dr.", name.getAsString(CDK.StructuredName.PREFIX))
        assertEquals("Hans", name.getAsString(CDK.StructuredName.GIVEN_NAME))
        assertEquals("Dr. Hans Peter Muster med.", name.getAsString(CDK.StructuredName.DISPLAY_NAME))
    }

    @Test
    fun `editing one address part keeps the other parts and the type`() {
        putEverything()
        val activity = open()
        select(activity, "Bahnhofstrasse")
        down(activity, KeyEvent.KEYCODE_DPAD_CENTER) // opens the address parts menu
        val labels = menuLabels(activity)
        assertTrue(labels.toString(), labels.any { it.contains("Street: Bahnhofstrasse 1") })
        down(activity, KeyEvent.KEYCODE_4) // City
        typeInto(activity, fieldRows(activity).indexOfFirst { it is LinearLayout }, "Bern")
        saveAndWait(activity)

        val address = FakeContactsProvider.data.getValue(11L)
        assertEquals("Bern", address.getAsString(CDK.StructuredPostal.CITY))
        assertEquals("Bahnhofstrasse 1", address.getAsString(CDK.StructuredPostal.STREET))
        assertEquals("8001", address.getAsString(CDK.StructuredPostal.POSTCODE))
        assertEquals("Switzerland", address.getAsString(CDK.StructuredPostal.COUNTRY))
        assertEquals(CDK.StructuredPostal.TYPE_WORK, address.getAsInteger(CDK.StructuredPostal.TYPE))
        assertEquals(listOf(11L), FakeContactsProvider.dataUpdates)
    }
}
