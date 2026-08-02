package life.furrer.c9.settings

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.calendar.FakeCalendarProvider
import life.furrer.c9.contacts.FakeContactsProvider
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.manual.ManualActivity
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

/** Covers the "Default calendar" / "Default duration" / "Default address book" rows added
 *  alongside the existing Theme settings — in particular the Left/Right direction handling
 *  and the "ask each time" pseudo-option the address-book row cycles through ahead of the real
 *  accounts (see [SettingsActivity.resolvedAccountIndex]'s -1 convention). */
@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.putCalendar(1L, "timo@fastmail.com", "bitfire.at.davdroid", displayName = "Home")
        FakeCalendarProvider.putCalendar(2L, "timo@fastmail.com", "bitfire.at.davdroid", displayName = "Work")

        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putRawContact(900L, contactId = 900L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(901L, contactId = 901L, accountName = "gmail", accountType = "com.google")
    }

    private fun buildActivity(app: SettingsApp = SettingsApp.CALENDAR): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java, SettingsActivity.intentFor(context, app)).setup().get()

    private fun rowContainer(activity: SettingsActivity): LinearLayout {
        val scrollView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
        return scrollView.getChildAt(0) as LinearLayout
    }

    /** Section headers have no colon; every actual setting row does — same filter
     *  ContactEditActivityTest uses to tell rows apart from other TextViews structurally. */
    private fun rows(activity: SettingsActivity): List<TextView> =
        (0 until rowContainer(activity).childCount)
            .map { rowContainer(activity).getChildAt(it) as TextView }
            .filter { it.text.contains(":") }

    private val manualActionLabels = setOf("Intro", "Key mapping", "About", "Open website")

    /** Every row, colon or not — includes the Manual section's action rows (Intro, Key mapping,
     *  About, Open website), which [rows] deliberately excludes. */
    private fun allRows(activity: SettingsActivity): List<TextView> =
        (0 until rowContainer(activity).childCount)
            .map { rowContainer(activity).getChildAt(it) as TextView }
            .filter { it.text.contains(":") || it.text.toString() in manualActionLabels }

    private fun moveTo(activity: SettingsActivity, index: Int) {
        repeat(index) { activity.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0)) }
    }

    private fun press(activity: SettingsActivity, keyCode: Int) {
        activity.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    @Test
    fun `default calendar row cycles forward and back, wrapping in both directions`() {
        val activity = buildActivity()
        moveTo(activity, 2) // Default calendar is the 3rd row (after Font, Hint bar)

        assertTrue(rows(activity)[2].text.contains("Home"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(rows(activity)[2].text.contains("Work"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(rows(activity)[2].text.contains("Home")) // wraps past the last calendar
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(rows(activity)[2].text.contains("Work")) // wraps back the other way
    }

    @Test
    fun `default calendar setting persists across activity instances`() {
        val activity = buildActivity()
        moveTo(activity, 2)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(2L, Prefs.defaultCalendarId(context))

        val recreated = buildActivity()
        assertTrue(rows(recreated)[2].text.contains("Work"))
    }

    @Test
    fun `default duration row steps by 15 minutes and floors at 15`() {
        val activity = buildActivity()
        moveTo(activity, 3) // Default duration follows Default calendar

        assertTrue(rows(activity)[3].text.contains("1h"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(rows(activity)[3].text.contains("1h 15m"))

        repeat(5) { press(activity, KeyEvent.KEYCODE_DPAD_LEFT) } // 75m -> 15m in 4 steps, 1 spare
        assertEquals(15, Prefs.defaultEventDurationMinutes(context))
        assertTrue(rows(activity)[3].text.contains("15m"))
    }

    @Test
    fun `default duration row ceilings at 480 minutes`() {
        Prefs.setDefaultEventDurationMinutes(context, 480)
        val activity = buildActivity()
        moveTo(activity, 3)

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(480, Prefs.defaultEventDurationMinutes(context))
        assertTrue(rows(activity)[3].text.contains("8h"))
    }

    @Test
    fun `default address book row cycles through ask-each-time ahead of the real accounts`() {
        val activity = buildActivity(SettingsApp.CONTACTS)
        moveTo(activity, 2) // Default address book is the last row (Font, Hint bar, then this)

        assertTrue(rows(activity)[2].text.contains("ask each time"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        val afterFirstRight = rows(activity)[2].text.toString()
        assertTrue(afterFirstRight.contains("fastmail") || afterFirstRight.contains("gmail"))
        // Cycling left from "ask each time" should wrap to the last account, not the first —
        // reset back to that starting state explicitly, since the RIGHT press above already
        // persisted a default into the same SharedPreferences this fresh instance would reload.
        Prefs.setDefaultAccount(context, null, null)
        val fresh = buildActivity(SettingsApp.CONTACTS)
        moveTo(fresh, 2)
        press(fresh, KeyEvent.KEYCODE_DPAD_LEFT)
        val afterLeft = rows(fresh)[2].text.toString()
        assertTrue(afterLeft.contains("fastmail") || afterLeft.contains("gmail"))
        assertTrue(Prefs.defaultAccountName(context) != null)
    }

    @Test
    fun `the Contacts settings screen has no Calendar section`() {
        val activity = buildActivity(SettingsApp.CONTACTS)
        assertEquals(3, rows(activity).size) // Font, Hint bar, Default address book — nothing else
        rows(activity).forEach { row -> assertTrue(!row.text.contains("Default calendar") && !row.text.contains("Default duration")) }
    }

    @Test
    fun `the Calendar settings screen has no Contacts section`() {
        val activity = buildActivity(SettingsApp.CALENDAR)
        assertEquals(4, rows(activity).size) // Font, Hint bar, Default calendar, Default duration
        rows(activity).forEach { row -> assertTrue(!row.text.contains("Default address book")) }
    }

    @Test
    fun `every app gets the same Manual section, supplied by core`() {
        val calendar = allRows(buildActivity(SettingsApp.CALENDAR)).map { it.text.toString() }
        val contacts = allRows(buildActivity(SettingsApp.CONTACTS)).map { it.text.toString() }
        assertTrue(calendar.containsAll(manualActionLabels))
        assertTrue(contacts.containsAll(manualActionLabels))
    }

    @Test
    fun `selecting Intro opens core's ManualActivity for this app's surface`() {
        val activity = buildActivity(SettingsApp.CALENDAR)
        val introIndex = allRows(activity).indexOfFirst { it.text == "Intro" }
        moveTo(activity, introIndex)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ManualActivity::class.java.name, started.component?.className)
        assertEquals(ManualActivity.PAGE_INTRO, started.getStringExtra(ManualActivity.EXTRA_PAGE))
        assertEquals("calendar", started.getStringExtra(ManualActivity.EXTRA_SURFACE))
    }

    @Test
    fun `Left and Right do nothing on a Manual row`() {
        val activity = buildActivity(SettingsApp.CALENDAR)
        val introIndex = allRows(activity).indexOfFirst { it.text == "Intro" }
        moveTo(activity, introIndex)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)

        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
