package life.furrer.c9.contacts

import android.app.Activity
import android.os.Looper
import life.furrer.nine.contacts.ContactDetail
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.contacts.FavoritesSync
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

@RunWith(RobolectricTestRunner::class)
class C9ContactsActionsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putContact(1L, "Ann")
        FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "me@dav", accountType = "bitfire.at.davdroid", sourceId = "v1")
    }

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun toggle(starred: Boolean) {
        val detail = ContactDetail(1L, "lookup1", "Ann", starred, null, emptyList(), emptyList())
        val label = if (starred) "Remove from favorites" else "Add to favorites"
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        C9ContactsActions.detailMenuItems(activity, detail).single { it.label == label }.action()
    }

    @Test
    fun `with a favorites group the toggle stars and joins the group, then reverses both`() {
        FavoritesSync.setFavoritesGroup(context, ContactGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid"))

        // Both halves of the batch, not just the first: the fake provider applies the starred
        // update and the membership change one after the other on Async's thread, so checking
        // membership as soon as STARRED flips raced it.
        toggle(starred = false)
        awaitUntil { FakeContactsProvider.isStarred(1L) && FakeContactsProvider.memberRawContactIds(5L) == setOf(101L) }

        toggle(starred = true)
        awaitUntil { !FakeContactsProvider.isStarred(1L) && FakeContactsProvider.memberRawContactIds(5L).isEmpty() }
    }

    @Test
    fun `without a group the toggle only sets starred`() {
        toggle(starred = false)
        awaitUntil { FakeContactsProvider.isStarred(1L) }
        assertFalse(FakeContactsProvider.memberRawContactIds(5L).isNotEmpty())
    }
}
