package life.furrer.c9.contacts

import android.app.Activity
import life.furrer.c9.core.Prefs
import life.furrer.nine.contacts.ContactEntry
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.contacts.FavoritesSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class C9ContactsActionsGroupsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val family = ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")

    @Before
    fun setUp() {
        setUpGroupsProvider(context)
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
    }

    private fun screen() = Robolectric.buildActivity(FakeScreen::class.java).get()

    private fun entry(id: Long, name: String) = ContactEntry(id, "lookup$id", name, starred = false, photoThumbnailUri = null)

    private fun item(activity: Activity, selected: ContactGroup?, label: String) =
        C9ContactsActions.groupsListMenuItems(activity, selected).single { it.label == label }

    // --- Groups list menu ---------------------------------------------------------------------

    @Test
    fun `groups menu offers New group always and Rename and Delete for the selected group`() {
        val activity = screen()
        assertEquals(listOf("New group"), C9ContactsActions.groupsListMenuItems(activity, null).map { it.label })
        assertEquals(
            listOf("New group", "Rename “Family”", "Delete “Family”"),
            C9ContactsActions.groupsListMenuItems(activity, family).map { it.label },
        )
    }

    @Test
    fun `new group goes straight to the only real address book, never to a mirror account`() {
        val activity = screen()
        item(activity, null, "New group").action()
        awaitUntil { shadowOf(activity).peekNextStartedActivity() != null }

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GroupNameActivity::class.java.name, started.component?.className)
        assertEquals("me@dav", started.getStringExtra("account_name"))
        assertNull(activity.shown)
    }

    @Test
    fun `new group uses the default address book without asking`() {
        FakeContactsProvider.putRawContact(103L, contactId = 3L, accountName = "gmail", accountType = "com.google")
        uploadingAccountTypes("bitfire.at.davdroid" to true, "com.google" to true)
        Prefs.setDefaultAccount(context, "gmail", "com.google")
        val activity = screen()
        item(activity, null, "New group").action()
        awaitUntil { shadowOf(activity).peekNextStartedActivity() != null }

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GroupNameActivity::class.java.name, started.component?.className)
        assertEquals("gmail", started.getStringExtra("account_name"))
        assertNull(activity.shown)
    }

    @Test
    fun `new group asks which address book when several are real and none is the default`() {
        FakeContactsProvider.putRawContact(103L, contactId = 3L, accountName = "gmail", accountType = "com.google")
        uploadingAccountTypes("bitfire.at.davdroid" to true, "com.google" to true, "com.whatsapp" to false)
        val activity = screen()
        item(activity, null, "New group").action()
        awaitUntil { activity.shown != null }

        val menu = activity.shown!!
        assertEquals(listOf("gmail (google)", "me@dav (davdroid)"), menu.map { it.label }.sorted())
        menu.single { it.label.startsWith("me@dav") }.action()
        assertEquals("me@dav", shadowOf(activity).nextStartedActivity.getStringExtra("account_name"))
    }

    @Test
    fun `new group with no real address book says so`() {
        uploadingAccountTypes("bitfire.at.davdroid" to false, "com.whatsapp" to false)
        val activity = screen()
        item(activity, null, "New group").action()
        awaitUntil { ShadowToast.getLatestToast() != null }
        assertEquals("No address book to put a group in", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `rename opens the name screen prefilled`() {
        val activity = screen()
        item(activity, family, "Rename “Family”").action()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GroupNameActivity::class.java.name, started.component?.className)
        assertEquals("Family", started.getStringExtra("title"))
    }

    @Test
    fun `delete asks first and leaves the contacts alone`() {
        FakeContactsProvider.putMembership(50L, 1L, 101L, 5L)
        val activity = screen()
        item(activity, family, "Delete “Family”").action()

        val confirm = activity.shown!!
        assertEquals(listOf("Yes, delete “Family” (contacts stay)", "Keep group"), confirm.map { it.label })
        confirm[1].action()
        assertEquals(1, ContactStore.queryGroups(context).size)

        confirm[0].action()
        awaitUntil { ContactStore.queryGroups(context).isEmpty() }
        assertTrue(FakeContactsProvider.contacts.containsKey(1L))
    }

    @Test
    fun `deleting the favorites group warns and falls back to starred only`() {
        FavoritesSync.setFavoritesGroup(context, family)
        val activity = screen()
        item(activity, family, "Delete “Family”").action()

        val confirm = activity.shown!!
        assertTrue(confirm[0].label, confirm[0].label.contains("favorites become starred only"))
        confirm[0].action()
        awaitUntil { ContactStore.queryGroups(context).isEmpty() && FavoritesSync.favoritesGroup(context) == null }
        assertNull(FavoritesSync.favoritesGroup(context))
    }

    @Test
    fun `deleting another group keeps the favorites setting`() {
        FakeContactsProvider.putGroup(6L, "Favorites", "me@dav", "bitfire.at.davdroid")
        FavoritesSync.setFavoritesGroup(context, ContactGroup(6L, "Favorites", "me@dav", "bitfire.at.davdroid"))
        val activity = screen()
        item(activity, family, "Delete “Family”").action()
        activity.shown!![0].action()
        awaitUntil { ContactStore.queryGroups(context).size == 1 }
        assertNotNull(FavoritesSync.favoritesGroup(context))
    }

    // --- Member view menu ---------------------------------------------------------------------

    @Test
    fun `member menu offers Add contacts and Remove for the selected member`() {
        val activity = screen()
        assertEquals(listOf("Add contacts…"), C9ContactsActions.groupMembersMenuItems(activity, family, null).map { it.label })
        val items = C9ContactsActions.groupMembersMenuItems(activity, family, entry(1L, "Ann"))
        assertEquals(listOf("Add contacts…", "Remove “Ann” from group"), items.map { it.label })

        items[0].action()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GroupAddContactsActivity::class.java.name, started.component?.className)
        assertEquals(5L, started.getLongExtra("group_id", -1L))
    }

    @Test
    fun `remove takes the contact out of the group only`() {
        FakeContactsProvider.putMembership(50L, 1L, 101L, 5L)
        val activity = screen()
        C9ContactsActions.groupMembersMenuItems(activity, family, entry(1L, "Ann"))[1].action()
        awaitUntil { FakeContactsProvider.memberRawContactIds(5L).isEmpty() }
        assertTrue(FakeContactsProvider.contacts.containsKey(1L))
    }

    @Test
    fun `removing from the favorites group also clears the star`() {
        FavoritesSync.setFavoritesGroup(context, family)
        FavoritesSync.setFavorite(context, 1L, true)
        assertTrue(FakeContactsProvider.isStarred(1L))
        assertEquals(setOf(101L), FakeContactsProvider.memberRawContactIds(5L))

        val activity = screen()
        C9ContactsActions.groupMembersMenuItems(activity, family, entry(1L, "Ann"))[1].action()
        awaitUntil { !FakeContactsProvider.isStarred(1L) && FakeContactsProvider.memberRawContactIds(5L).isEmpty() }
        assertTrue(FakeContactsProvider.memberRawContactIds(5L).isEmpty())
    }

    // --- Detail menu --------------------------------------------------------------------------

    @Test
    fun `detail menu opens the groups checklist for the contact`() {
        val activity = screen()
        val detail = life.furrer.nine.contacts.ContactDetail(1L, "lookup1", "Ann", false, null, emptyList(), emptyList())
        C9ContactsActions.detailMenuItems(activity, detail).single { it.label == "Groups…" }.action()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GroupChecklistActivity::class.java.name, started.component?.className)
        assertEquals(1L, started.getLongExtra("contact_id", -1L))
        assertFalse(started.getBooleanExtra("deferred", false))
    }
}
