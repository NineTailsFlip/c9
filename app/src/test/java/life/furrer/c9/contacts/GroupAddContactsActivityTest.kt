package life.furrer.c9.contacts

import android.app.Activity
import android.content.Intent
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactValuePickerActivity
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.contacts.FavoritesSync
import org.junit.Assert.assertEquals
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
class GroupAddContactsActivityTest {

    private val context = RuntimeEnvironment.getApplication()
    private val family = ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")

    @Before
    fun setUp() {
        setUpGroupsProvider(context)
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
    }

    private fun open() = Robolectric.buildActivity(GroupAddContactsActivity::class.java, GroupAddContactsActivity.intent(context, family)).setup().get()

    /** The picker the trampoline opened, answered as if [id] were picked. */
    private fun pick(activity: Activity, id: Long, name: String) {
        val shadow = shadowOf(activity)
        val request = shadow.nextStartedActivityForResult
        assertEquals(ContactValuePickerActivity::class.java.name, request.intent.component?.className)
        assertTrue(request.intent.getBooleanExtra(ContactValuePickerActivity.EXTRA_PICK_CONTACT, false))
        shadow.receiveResult(
            request.intent, Activity.RESULT_OK,
            Intent().putExtra(ContactValuePickerActivity.EXTRA_CONTACT_ID, id).putExtra(ContactValuePickerActivity.EXTRA_NAME, name),
        )
    }

    @Test
    fun `a picked contact joins the group and the picker opens again`() {
        val activity = open()
        pick(activity, 1L, "Ann")
        awaitUntil { FakeContactsProvider.memberRawContactIds(5L) == setOf(101L) }
        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertEquals("Added Ann", ShadowToast.getTextOfLatestToast())
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `picking someone already in the group says so`() {
        FakeContactsProvider.putMembership(50L, 1L, 101L, 5L)
        val activity = open()
        pick(activity, 1L, "Ann")
        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertEquals("Ann is already in Family", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a contact outside the group's address book can't join`() {
        val activity = open()
        pick(activity, 2L, "Bob")
        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertEquals("Bob isn't in me@dav, can't join", ShadowToast.getTextOfLatestToast())
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
    }

    @Test
    fun `adding to the favorites group also stars`() {
        FavoritesSync.setFavoritesGroup(context, family)
        val activity = open()
        pick(activity, 1L, "Ann")
        awaitUntil { FakeContactsProvider.isStarred(1L) && FakeContactsProvider.memberRawContactIds(5L).isNotEmpty() }
        assertEquals(setOf(101L), FakeContactsProvider.memberRawContactIds(5L))
    }

    @Test
    fun `a non-joinable contact is not starred through the favorites group`() {
        FavoritesSync.setFavoritesGroup(context, family)
        val activity = open()
        pick(activity, 2L, "Bob")
        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertTrue(!FakeContactsProvider.isStarred(2L))
    }

    @Test
    fun `cancelling the picker ends it`() {
        val activity = open()
        val shadow = shadowOf(activity)
        shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
        assertTrue(activity.isFinishing)
    }
}
