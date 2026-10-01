package life.furrer.c9.contacts

import android.content.Intent
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.contacts.ContactStore.WritableAccount
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.contacts.ContactStore.ContactGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class GroupChecklistActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        setUpGroupsProvider(context)
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(6L, "Work", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(7L, "WhatsApp group", "WhatsApp", "com.whatsapp")
    }

    private fun open(intent: Intent): GroupChecklistActivity {
        val activity = Robolectric.buildActivity(GroupChecklistActivity::class.java, intent).setup().get()
        awaitUntil { rows(activity).isNotEmpty() }
        return activity
    }

    private fun rowViews(activity: GroupChecklistActivity): List<LinearLayout> {
        val column = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
        val list = (column.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        return (0 until list.childCount).map { list.getChildAt(it) }.filterIsInstance<LinearLayout>()
    }

    private fun rows(activity: GroupChecklistActivity): List<String> =
        rowViews(activity).map { row -> (row.getChildAt(0) as TextView).text.toString() + (row.getChildAt(1) as TextView).text.let { if (it.isEmpty()) "" else " $it" } }

    private fun press(activity: GroupChecklistActivity, keyCode: Int) {
        activity.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    @Test
    fun `lists the real address books' groups with the contact's memberships checked`() {
        FakeContactsProvider.putMembership(50L, 1L, 101L, 6L)
        val activity = open(GroupChecklistActivity.intent(context, 1L))
        // WhatsApp's own group is a mirror account's, never offered.
        assertEquals(listOf("[ ] Family", "[x] Work"), rows(activity))
    }

    @Test
    fun `ok toggles and saves at once`() {
        val activity = open(GroupChecklistActivity.intent(context, 1L))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { rows(activity)[0] == "[x] Family" }
        assertEquals(setOf(101L), FakeContactsProvider.memberRawContactIds(5L))

        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { rows(activity)[0] == "[ ] Family" }
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
    }

    @Test
    fun `a group the contact can't join is dimmed and refuses to toggle`() {
        val activity = open(GroupChecklistActivity.intent(context, 2L)) // Bob is only in WhatsApp
        assertEquals(
            listOf("[ ] Family (not in this address book)", "[ ] Work (not in this address book)"),
            rows(activity),
        )
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Not in me@dav, can't join", ShadowToast.getTextOfLatestToast())
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
    }

    @Test
    fun `the favorites group is starred in the list and toggling it keeps STARRED in step`() {
        FavoritesSync.setFavoritesGroup(context, ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"))
        val activity = open(GroupChecklistActivity.intent(context, 1L))
        assertEquals("[ ] ★ Family", rows(activity)[0])

        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { rows(activity)[0] == "[x] ★ Family" }
        assertTrue(FakeContactsProvider.isStarred(1L))

        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { rows(activity)[0] == "[ ] ★ Family" }
        assertTrue(!FakeContactsProvider.isStarred(1L))
    }

    @Test
    fun `deferred mode writes nothing and returns the checked ids on back`() {
        val activity = open(
            GroupChecklistActivity.deferredIntent(context, 1L, null, listOf(6L)),
        )
        assertEquals(listOf("[ ] Family", "[x] Work"), rows(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER) // check Family
        assertEquals("[x] Family", rows(activity)[0])
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER) // uncheck Work
        press(activity, KeyEvent.KEYCODE_BACK)

        assertTrue(activity.isFinishing)
        val result = shadowOf(activity).resultIntent
        assertArrayEquals(longArrayOf(5L), result.getLongArrayExtra(GroupChecklistActivity.EXTRA_CHECKED_IDS))
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
        assertTrue(FakeContactsProvider.memberRawContactIds(6L).isEmpty())
    }

    @Test
    fun `deferred mode for a new contact offers only the chosen account's groups`() {
        FakeContactsProvider.putRawContact(103L, contactId = 3L, accountName = "gmail", accountType = "com.google")
        uploadingAccountTypes("bitfire.at.davdroid" to true, "com.google" to true)
        FakeContactsProvider.putGroup(8L, "Gmail only", "gmail", "com.google")
        val activity = open(
            GroupChecklistActivity.deferredIntent(context, null, WritableAccount("gmail", "com.google"), emptyList()),
        )
        assertEquals(listOf("[ ] Gmail only"), rows(activity))
    }
}
