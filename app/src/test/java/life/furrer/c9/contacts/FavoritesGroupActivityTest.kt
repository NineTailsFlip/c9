package life.furrer.c9.contacts

import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.settings.favoritesGroupLabel
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.contacts.FavoritesSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FavoritesGroupActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putRawContact(900L, contactId = 900L, accountName = "me@dav", accountType = "bitfire.at.davdroid")
        FakeContactsProvider.putRawContact(901L, contactId = 901L, accountName = "gmail", accountType = "com.google")
    }

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun open() = Robolectric.buildActivity(FavoritesGroupActivity::class.java).setup().get()

    private fun labels(activity: FavoritesGroupActivity): List<String> {
        val column = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
        val list = (column.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        return (0 until list.childCount).mapNotNull { i ->
            val child = list.getChildAt(i)
            if (child is LinearLayout) (child.getChildAt(0) as TextView).text.toString() else null
        }
    }

    private fun press(activity: FavoritesGroupActivity, keyCode: Int) {
        activity.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    private fun moveTo(activity: FavoritesGroupActivity, label: String) {
        val target = labels(activity).indexOf(label)
        assertTrue("no row $label in ${labels(activity)}", target >= 0)
        repeat(target) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
    }

    @Test
    fun `lists none, writable groups and a create entry per writable account`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(6L, "Hidden", "readonly", "com.example")
        assertEquals(
            listOf(
                "None — use starred only",
                "Create “Favorites” in gmail",
                "Family", "Create “Favorites” in me@dav",
            ),
            labels(open()),
        )
    }

    @Test
    fun `an existing Favorites group replaces the create entry`() {
        FakeContactsProvider.putGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid")
        val labels = labels(open())
        assertTrue("Favorites" in labels)
        assertTrue("Create “Favorites” in me@dav" !in labels)
        assertTrue("Create “Favorites” in gmail" in labels)
    }

    @Test
    fun `choosing a group stores it, arms the job and finishes`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        val activity = open()
        moveTo(activity, "Family")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"), FavoritesSync.favoritesGroup(context))
        assertTrue(activity.isFinishing)
        assertTrue(shadowOf(context.getSystemService(android.app.job.JobScheduler::class.java)).getPendingJob(FavoritesScheduler.JOB_ID) != null)
    }

    @Test
    fun `none clears the group and cancels the job`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FavoritesSync.setFavoritesGroup(context, ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"))
        FavoritesScheduler.update(context)

        val activity = open() // starts on the current group, Family, two rows below None
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertNull(FavoritesSync.favoritesGroup(context))
        assertNull(shadowOf(context.getSystemService(android.app.job.JobScheduler::class.java)).getPendingJob(FavoritesScheduler.JOB_ID))
    }

    @Test
    fun `create makes a Favorites group in that account and selects it`() {
        val activity = open()
        moveTo(activity, "Create “Favorites” in gmail")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { FavoritesSync.favoritesGroup(context) != null }

        val group = FavoritesSync.favoritesGroup(context)!!
        assertEquals("Favorites", group.title)
        assertEquals("gmail", group.accountName)
        assertEquals("com.google", group.accountType)
        assertEquals(group, life.furrer.nine.contacts.ContactStore.queryGroups(context).single())
    }

    @Test
    fun `create reuses an existing Favorites group instead of duplicating`() {
        FakeContactsProvider.putGroup(5L, "favorites", "gmail", "com.google")
        val account = life.furrer.nine.contacts.ContactStore.WritableAccount("gmail", "com.google")
        val group = FavoritesGroupActivity.createOrReuse(context, account)
        assertEquals(5L, group?.id)
        assertEquals(1, life.furrer.nine.contacts.ContactStore.queryGroups(context).size)
    }

    @Test
    fun `back leaves the setting alone`() {
        val activity = open()
        press(activity, KeyEvent.KEYCODE_BACK)
        assertTrue(activity.isFinishing)
        assertNull(FavoritesSync.favoritesGroup(context))
    }

    @Test
    fun `settings label covers none, present and missing`() {
        assertEquals("none (starred only)", favoritesGroupLabel(FavoritesSync.favoritesGroupState(context)))

        val group = ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FavoritesSync.setFavoritesGroup(context, group)
        assertEquals("Family · me@dav", favoritesGroupLabel(FavoritesSync.favoritesGroupState(context)))

        FakeContactsProvider.reset()
        assertEquals("Family: not found", favoritesGroupLabel(FavoritesSync.favoritesGroupState(context)))
    }
}
