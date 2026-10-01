package life.furrer.c9.contacts

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactStore.WritableAccount
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class GroupNameActivityTest {

    private val context = RuntimeEnvironment.getApplication()
    private val account = WritableAccount("me@dav", "bitfire.at.davdroid")

    @Before
    fun setUp() = setUpGroupsProvider(context)

    private fun editText(activity: GroupNameActivity): EditText =
        (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout).getChildAt(1) as EditText

    private fun press(activity: GroupNameActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    @Test
    fun `ok creates the group in the given account and closes`() {
        val activity = Robolectric.buildActivity(GroupNameActivity::class.java, GroupNameActivity.createIntent(context, account)).setup().get()
        assertEquals("", editText(activity).text.toString())
        editText(activity).setText("  Family ")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUntil { activity.isFinishing }

        assertEquals(listOf(ContactGroup(1000L, "Family", "me@dav", "bitfire.at.davdroid")), ContactStore.queryGroups(context))
    }

    @Test
    fun `rename is prefilled and changes only the title`() {
        FakeContactsProvider.putGroup(5L, "Work", "me@dav", "bitfire.at.davdroid")
        val group = ContactGroup(5L, "Work", "me@dav", "bitfire.at.davdroid")
        val activity = Robolectric.buildActivity(GroupNameActivity::class.java, GroupNameActivity.renameIntent(context, group)).setup().get()
        assertEquals("Work", editText(activity).text.toString())

        editText(activity).setText("Colleagues")
        press(activity, KeyEvent.KEYCODE_ENTER)
        awaitUntil { activity.isFinishing }

        assertEquals(listOf(ContactGroup(5L, "Colleagues", "me@dav", "bitfire.at.davdroid")), ContactStore.queryGroups(context))
    }

    @Test
    fun `a blank name is refused and the screen stays`() {
        val activity = Robolectric.buildActivity(GroupNameActivity::class.java, GroupNameActivity.createIntent(context, account)).setup().get()
        editText(activity).setText("   ")
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals("Needs a name", ShadowToast.getTextOfLatestToast())
        assertFalse(activity.isFinishing)
        assertTrue(ContactStore.queryGroups(context).isEmpty())
    }

    @Test
    fun `back cancels without writing`() {
        val activity = Robolectric.buildActivity(GroupNameActivity::class.java, GroupNameActivity.createIntent(context, account)).setup().get()
        editText(activity).setText("Family")
        press(activity, KeyEvent.KEYCODE_BACK)

        assertTrue(activity.isFinishing)
        assertTrue(ContactStore.queryGroups(context).isEmpty())
    }
}
