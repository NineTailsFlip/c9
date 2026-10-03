package life.furrer.c9.contacts

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.core.Prefs
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.contacts.FavoritesSync
import org.junit.Assert.assertArrayEquals
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

/** The editor's Groups row: shown only when there are groups to choose from, chosen through the
 *  checklist in deferred mode, and written when the editor saves. */
@RunWith(RobolectricTestRunner::class)
class ContactEditActivityGroupsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        setUpGroupsProvider(context)
        FakeContactsProvider.putData(
            10L, 1L, 101L, CDK.StructuredName.CONTENT_ITEM_TYPE,
            ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Ann") },
        )
    }

    private fun group(id: Long, title: String) = FakeContactsProvider.putGroup(id, title, "me@dav", "bitfire.at.davdroid")

    private fun controller(contactId: Long?) = Robolectric.buildActivity(
        ContactEditActivity::class.java,
        Intent(context, ContactEditActivity::class.java).apply { contactId?.let { putExtra(ContactEditActivity.EXTRA_CONTACT_ID, it) } },
    )

    private fun open(contactId: Long?): ContactEditActivity {
        val activity = controller(contactId).setup().get()
        awaitUntil { rowContainer(activity).childCount > 0 }
        return activity
    }

    private fun rowContainer(activity: ContactEditActivity): LinearLayout {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        return ((root.getChildAt(0) as LinearLayout).getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
    }

    private fun rows(activity: ContactEditActivity): List<String> =
        (0 until rowContainer(activity).childCount).map { rowContainer(activity).getChildAt(it) }
            .filter { it is TextView && it.text.contains(":") }
            .map { (it as TextView).text.toString().replace(Regex("\\s+"), " ") }

    private fun press(activity: ContactEditActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    /** Selects the (last) Groups row, opens its checklist and answers it with [ids]. */
    private fun chooseGroups(activity: ContactEditActivity, ids: LongArray): Intent {
        press(activity, KeyEvent.KEYCODE_DPAD_UP) // wraps from the first row to the last
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(GroupChecklistActivity::class.java.name, request.intent.component?.className)
        shadowOf(activity).receiveResult(
            request.intent, Activity.RESULT_OK, Intent().putExtra(GroupChecklistActivity.EXTRA_CHECKED_IDS, ids),
        )
        return request.intent
    }

    @Test
    fun `without any groups there is no Groups row`() {
        assertEquals(listOf("Name : Ann"), rows(open(1L)))
    }

    @Test
    fun `the Groups row sits under its own section header`() {
        group(6L, "Work")
        FakeContactsProvider.putMembership(50L, 1L, 101L, 6L)
        val container = rowContainer(open(1L))

        val texts = (0 until container.childCount).map { (container.getChildAt(it) as TextView).text.toString() }
        val header = texts.indexOf("GROUPS")
        assertTrue(header >= 0)
        assertTrue(texts[header + 1].startsWith("Groups"))
    }

    @Test
    fun `an existing contact's row lists its groups and the checklist opens deferred`() {
        group(5L, "Family")
        group(6L, "Work")
        FakeContactsProvider.putMembership(50L, 1L, 101L, 6L)
        val activity = open(1L)
        assertEquals(listOf("Name : Ann", "Groups : Work"), rows(activity))

        val intent = chooseGroups(activity, longArrayOf(5L, 6L))
        assertTrue(intent.getBooleanExtra(GroupChecklistActivity.EXTRA_DEFERRED, false))
        assertEquals(1L, intent.getLongExtra(GroupChecklistActivity.EXTRA_CONTACT_ID, -1L))
        assertArrayEquals(longArrayOf(6L), intent.getLongArrayExtra(GroupChecklistActivity.EXTRA_CHECKED_IDS))
        assertEquals("Groups : Family, Work", rows(activity)[1])
        // Nothing is written until the editor saves.
        assertEquals(setOf(101L), FakeContactsProvider.memberRawContactIds(6L))
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
    }

    @Test
    fun `saving applies the membership changes after the field changes`() {
        group(5L, "Family")
        group(6L, "Work")
        FakeContactsProvider.putMembership(50L, 1L, 101L, 6L)
        val activity = open(1L)
        chooseGroups(activity, longArrayOf(5L)) // join Family, leave Work
        press(activity, KeyEvent.KEYCODE_BACK)

        awaitUntil { activity.isFinishing }
        assertEquals(setOf(101L), FakeContactsProvider.memberRawContactIds(5L))
        assertTrue(FakeContactsProvider.memberRawContactIds(6L).isEmpty())
    }

    @Test
    fun `saving without touching the row leaves memberships alone`() {
        group(6L, "Work")
        FakeContactsProvider.putMembership(50L, 1L, 101L, 6L)
        val activity = open(1L)
        press(activity, KeyEvent.KEYCODE_BACK)
        awaitUntil { activity.isFinishing }
        assertEquals(setOf(101L), FakeContactsProvider.memberRawContactIds(6L))
    }

    @Test
    fun `the favorites group goes through the favorite toggle so the star follows`() {
        group(5L, "Family")
        FavoritesSync.setFavoritesGroup(context, ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"))
        val activity = open(1L)
        chooseGroups(activity, longArrayOf(5L))
        press(activity, KeyEvent.KEYCODE_BACK)

        awaitUntil { activity.isFinishing }
        awaitUntil { FakeContactsProvider.isStarred(1L) && FakeContactsProvider.memberRawContactIds(5L).isNotEmpty() }
    }

    @Test
    fun `a new contact is offered only its account's groups and joins the chosen ones after insert`() {
        Prefs.setDefaultAccount(context, "me@dav", "bitfire.at.davdroid")
        group(5L, "Family")
        FakeContactsProvider.putGroup(9L, "Other book", "gmail", "com.google")
        FakeContactsProvider.putRawContact(103L, contactId = 3L, accountName = "gmail", accountType = "com.google")
        uploadingAccountTypes("bitfire.at.davdroid" to true, "com.google" to true)

        val activity = open(null)
        assertEquals(listOf("Name : (empty)", "Groups : none"), rows(activity))

        // Name first (row 0), then the groups.
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        val edit = (rowContainer(activity).getChildAt(1) as LinearLayout).let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<EditText>().first()
        }
        edit.setText("New Person")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER) // commit
        val intent = chooseGroups(activity, longArrayOf(5L))
        assertEquals("me@dav", intent.getStringExtra(GroupChecklistActivity.EXTRA_ACCOUNT_NAME))
        assertFalse(intent.hasExtra(GroupChecklistActivity.EXTRA_CONTACT_ID))
        press(activity, KeyEvent.KEYCODE_BACK)

        awaitUntil { activity.isFinishing }
        awaitUntil { FakeContactsProvider.memberRawContactIds(5L).isNotEmpty() }
        val newRaw = FakeContactsProvider.memberRawContactIds(5L).single()
        assertEquals("me@dav", FakeContactsProvider.rawContacts[newRaw]?.getAsString(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME))
    }

    @Test
    fun `the choice survives the editor recreating itself`() {
        group(5L, "Family")
        val controller = controller(1L).setup()
        var activity = controller.get()
        awaitUntil { rowContainer(activity).childCount > 0 }
        chooseGroups(activity, longArrayOf(5L))

        controller.recreate()
        activity = controller.get()
        assertEquals("Groups : Family", rows(activity)[1])
    }
}
