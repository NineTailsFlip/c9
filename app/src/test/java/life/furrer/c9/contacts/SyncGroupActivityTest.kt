package life.furrer.c9.contacts

import android.content.SyncAdapterType
import android.os.Looper
import android.provider.ContactsContract
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.core.Prefs

import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.contacts.VoicemailSync
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
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class SyncGroupActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putRawContact(900L, contactId = 900L, accountName = "me@dav", accountType = "bitfire.at.davdroid")
        FakeContactsProvider.putRawContact(901L, contactId = 901L, accountName = "gmail", accountType = "com.google")
        uploadingAccountTypes("bitfire.at.davdroid" to true, "com.google" to true)
        Prefs.setDefaultAccount(context, null, null)
    }

    private fun uploadingAccountTypes(vararg types: Pair<String, Boolean>) {
        ShadowContentResolver.setSyncAdapterTypes(
            types.map { (type, uploads) -> SyncAdapterType(ContactsContract.AUTHORITY, type, true, uploads) }.toTypedArray(),
        )
    }

    private fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun open() = Robolectric.buildActivity(SyncGroupActivity::class.java).setup().get()

    private fun labels(activity: SyncGroupActivity): List<String> {
        val frame = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val column = frame.getChildAt(0) as LinearLayout
        val list = (column.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        return (0 until list.childCount).mapNotNull { i ->
            val child = list.getChildAt(i)
            if (child is LinearLayout) (child.getChildAt(0) as TextView).text.toString() else null
        }
    }

    private fun press(activity: SyncGroupActivity, keyCode: Int) {
        activity.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    private fun moveTo(activity: SyncGroupActivity, label: String) {
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

    // On the phone, WhatsApp's and Signal's mirror accounts both counted as "writable".
    @Test
    fun `no create entry in an account whose sync adapter never uploads`() {
        FakeContactsProvider.putRawContact(902L, contactId = 902L, accountName = "WhatsApp", accountType = "com.whatsapp")
        uploadingAccountTypes("bitfire.at.davdroid" to true, "com.google" to true, "com.whatsapp" to false)

        assertTrue("Create “Favorites” in WhatsApp" !in labels(open()))
    }

    @Test
    fun `with a default address book set, create is only offered there`() {
        Prefs.setDefaultAccount(context, "me@dav", "bitfire.at.davdroid")
        val labels = labels(open())
        assertTrue("Create “Favorites” in me@dav" in labels)
        assertTrue("Create “Favorites” in gmail" !in labels)
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

        assertEquals(ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"), FavoritesSync.group(context))
        assertTrue(activity.isFinishing)
        assertTrue(shadowOf(context.getSystemService(android.app.job.JobScheduler::class.java)).getPendingJob(GroupSyncScheduler.JOB_ID) != null)
    }

    @Test
    fun `none clears the group and keeps the job armed for the voicemail repair`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FavoritesSync.setGroup(context, ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"))
        GroupSyncScheduler.update(context)

        val activity = open() // starts on the current group, Family, two rows below None
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertNull(FavoritesSync.group(context))
        assertNotNull(shadowOf(context.getSystemService(android.app.job.JobScheduler::class.java)).getPendingJob(GroupSyncScheduler.JOB_ID))
    }

    @Test
    fun `create makes a Favorites group in that account and selects it`() {
        val activity = open()
        moveTo(activity, "Create “Favorites” in gmail")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { FavoritesSync.group(context) != null }

        val group = FavoritesSync.group(context)!!
        assertEquals("Favorites", group.title)
        assertEquals("gmail", group.accountName)
        assertEquals("com.google", group.accountType)
        assertEquals(group, life.furrer.nine.contacts.ContactStore.queryGroups(context).single())
    }

    @Test
    fun `create reuses an existing Favorites group instead of duplicating`() {
        FakeContactsProvider.putGroup(5L, "favorites", "gmail", "com.google")
        val account = life.furrer.nine.contacts.ContactStore.WritableAccount("gmail", "com.google")
        val group = SyncGroupActivity.createOrReuse(context, account, SyncedProperty.FAVORITES.defaultTitle)
        assertEquals(5L, group?.id)
        assertEquals(1, life.furrer.nine.contacts.ContactStore.queryGroups(context).size)
    }

    @Test
    fun `back leaves the setting alone`() {
        val activity = open()
        press(activity, KeyEvent.KEYCODE_BACK)
        assertTrue(activity.isFinishing)
        assertNull(FavoritesSync.group(context))
    }

    @Test
    fun `settings label covers none, present and missing`() {
        assertEquals("none (starred only)", SyncedProperty.FAVORITES.label(FavoritesSync.groupState(context)))

        val group = ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FavoritesSync.setGroup(context, group)
        assertEquals("Family · me@dav", SyncedProperty.FAVORITES.label(FavoritesSync.groupState(context)))

        FakeContactsProvider.reset()
        assertEquals("Family: not found", SyncedProperty.FAVORITES.label(FavoritesSync.groupState(context)))
    }

    private fun openFor(property: SyncedProperty) = Robolectric.buildActivity(SyncGroupActivity::class.java, SyncGroupActivity.intent(context, property)).setup().get()

    @Test
    fun `the voicemail picker stores into the voicemail sync, not favorites`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        val activity = openFor(SyncedProperty.VOICEMAIL)
        assertEquals("None — this phone only", labels(activity).first())
        moveTo(activity, "Family")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"), VoicemailSync.group(context))
        assertNull(FavoritesSync.group(context))
        assertTrue(activity.isFinishing)
    }

    // Choosing a group with members gives all of them the property on the first line-up; for
    // voicemail that turns their calls away, so it asks first.
    @Test
    fun `the voicemail picker confirms a group with members before choosing it`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putMembership(9000L, contactId = 900L, rawContactId = 900L, groupId = 5L)
        val activity = openFor(SyncedProperty.VOICEMAIL)
        moveTo(activity, "Family")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertNull(VoicemailSync.group(context))
        assertFalse(activity.isFinishing)
        press(activity, KeyEvent.KEYCODE_1) // "Yes, send calls from 1 contact to voicemail"

        assertEquals(5L, VoicemailSync.group(context)?.id)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `keeping the current voicemail group leaves the setting alone`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putMembership(9000L, contactId = 900L, rawContactId = 900L, groupId = 5L)
        val activity = openFor(SyncedProperty.VOICEMAIL)
        moveTo(activity, "Family")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        press(activity, KeyEvent.KEYCODE_2) // "Keep current"

        assertNull(VoicemailSync.group(context))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `the favorites picker chooses a group with members straight away`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putMembership(9000L, contactId = 900L, rawContactId = 900L, groupId = 5L)
        val activity = openFor(SyncedProperty.FAVORITES)
        moveTo(activity, "Family")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(5L, FavoritesSync.group(context)?.id)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `voicemail confirm wording counts the members`() {
        val confirm = SyncedProperty.VOICEMAIL.confirmMembers!!
        assertEquals("Yes, send calls from 1 contact to voicemail", confirm(1))
        assertEquals("Yes, send calls from 12 contacts to voicemail", confirm(12))
        assertNull(SyncedProperty.FAVORITES.confirmMembers)
    }

    @Test
    fun `the voicemail picker offers its own default title and hides the favorites group`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(6L, "Favorites", "me@dav", "bitfire.at.davdroid")
        FavoritesSync.setGroup(context, ContactGroup(6L, "Favorites", "me@dav", "bitfire.at.davdroid"))

        val labels = labels(openFor(SyncedProperty.VOICEMAIL))

        assertTrue(labels.toString(), "Create “Send to voicemail” in me@dav" in labels)
        assertTrue("Family" in labels)
        assertTrue("Favorites" !in labels)
    }

    @Test
    fun `the favorites picker hides the voicemail group`() {
        FakeContactsProvider.putGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        VoicemailSync.setGroup(context, ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid"))

        assertTrue("Family" !in labels(openFor(SyncedProperty.FAVORITES)))
    }

    @Test
    fun `create in the voicemail picker makes a Send to voicemail group for voicemail only`() {
        val activity = openFor(SyncedProperty.VOICEMAIL)
        moveTo(activity, "Create “Send to voicemail” in gmail")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { VoicemailSync.group(context) != null }

        assertEquals("Send to voicemail", VoicemailSync.group(context)!!.title)
        assertNull(FavoritesSync.group(context))
    }

    @Test
    fun `groupChoices leaves out taken groups and uses the given default title`() {
        val dav = life.furrer.nine.contacts.ContactStore.WritableAccount("me@dav", "bitfire.at.davdroid")
        val family = ContactGroup(5L, "Family", "me@dav", "bitfire.at.davdroid")
        val work = ContactGroup(6L, "Work", "me@dav", "bitfire.at.davdroid")

        val choices = groupChoices(listOf(family, work), listOf(dav), "Muted", taken = { it.id == 6L }).map { it.second }

        assertEquals(listOf(GroupChoice.None, GroupChoice.Existing(family), GroupChoice.Create(dav)), choices)

        // A group titled like the default suppresses Create, taken or not only matters for listing.
        val muted = ContactGroup(7L, "muted", "me@dav", "bitfire.at.davdroid")
        assertEquals(
            listOf(GroupChoice.None, GroupChoice.Existing(muted)),
            groupChoices(listOf(muted), listOf(dav), "Muted").map { it.second },
        )
    }

    @Test
    fun `voicemail settings label covers none, present and missing`() {
        assertEquals("none (this phone only)", SyncedProperty.VOICEMAIL.label(VoicemailSync.groupState(context)))

        val group = ContactGroup(5L, "Muted", "me@dav", "bitfire.at.davdroid")
        FakeContactsProvider.putGroup(5L, "Muted", "me@dav", "bitfire.at.davdroid")
        VoicemailSync.setGroup(context, group)
        assertEquals("Muted · me@dav", SyncedProperty.VOICEMAIL.label(VoicemailSync.groupState(context)))
        assertEquals("Voicemail group", SyncedProperty.VOICEMAIL.settingsLabel)

        FakeContactsProvider.reset()
        assertEquals("Muted: not found", SyncedProperty.VOICEMAIL.label(VoicemailSync.groupState(context)))
    }
}
