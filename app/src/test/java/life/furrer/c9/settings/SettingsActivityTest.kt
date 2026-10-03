package life.furrer.c9.settings

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.c9.calendar.FakeCalendarProvider
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.manual.ManualActivity
import life.furrer.nine.core.settings.NineSettingsActivity
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

/** Covers C9's own sections (Default calendar / Default duration / Default address book) on top
 *  of core's shared Settings framework — in particular the Left/Right direction handling and the
 *  "ask each time" pseudo-option the address-book row cycles through ahead of the real accounts. */
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

    private fun buildActivity(surfaceId: String = "calendar"): SettingsActivity =
        Robolectric.buildActivity(
            SettingsActivity::class.java,
            NineSettingsActivity.intentFor(context, SettingsActivity::class.java, surfaceId),
        ).setup().get()

    private fun rowContainer(activity: SettingsActivity): LinearLayout {
        val scrollView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
        return scrollView.getChildAt(0) as LinearLayout
    }

    private fun isSectionHeader(text: CharSequence): Boolean =
        text.isNotEmpty() && text.toString() == text.toString().uppercase()

    /** Every navigable row, colon or not — includes the About section's bare action labels
     *  (Intro, Key mapping, About, Open website), which section headers never match. */
    private fun rows(activity: SettingsActivity): List<TextView> =
        (0 until rowContainer(activity).childCount)
            .map { rowContainer(activity).getChildAt(it) as TextView }
            .filterNot { isSectionHeader(it.text) }

    private fun moveTo(activity: SettingsActivity, index: Int) {
        repeat(index) { activity.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0)) }
    }

    private fun press(activity: SettingsActivity, keyCode: Int) {
        activity.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    @Test
    fun `default calendar row cycles forward and back, wrapping in both directions`() {
        val activity = buildActivity()
        // App sections render before core's Theme/About, so Default calendar is the first row.
        moveTo(activity, 0)

        assertTrue(rows(activity)[0].text.contains("Home"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(rows(activity)[0].text.contains("Work"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(rows(activity)[0].text.contains("Home")) // wraps past the last calendar
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(rows(activity)[0].text.contains("Work")) // wraps back the other way
    }

    @Test
    fun `default calendar row never offers a read-only calendar`() {
        FakeCalendarProvider.putCalendar(3L, "Local", "LOCAL", displayName = "Birthdays", accessLevel = android.provider.CalendarContract.Calendars.CAL_ACCESS_READ)
        Prefs.setDefaultCalendarId(context, 3L) // stored default has since become read-only
        val activity = buildActivity()
        moveTo(activity, 0)

        repeat(4) {
            assertFalse(rows(activity)[0].text.contains("Birthdays"))
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }

    @Test
    fun `default calendar setting persists across activity instances`() {
        val activity = buildActivity()
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(2L, Prefs.defaultCalendarId(context))

        val recreated = buildActivity()
        assertTrue(rows(recreated)[0].text.contains("Work"))
    }

    @Test
    fun `default duration row steps by 15 minutes and floors at 15`() {
        val activity = buildActivity()
        moveTo(activity, 1) // Default duration follows Default calendar

        assertTrue(rows(activity)[1].text.contains("1h"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(rows(activity)[1].text.contains("1h 15m"))

        repeat(5) { press(activity, KeyEvent.KEYCODE_DPAD_LEFT) } // 75m -> 15m in 4 steps, 1 spare
        assertEquals(15, Prefs.defaultEventDurationMinutes(context))
        assertTrue(rows(activity)[1].text.contains("15m"))
    }

    @Test
    fun `default duration row ceilings at 480 minutes`() {
        Prefs.setDefaultEventDurationMinutes(context, 480)
        val activity = buildActivity()
        moveTo(activity, 1)

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(480, Prefs.defaultEventDurationMinutes(context))
        assertTrue(rows(activity)[1].text.contains("8h"))
    }

    @Test
    fun `reminder notifications row toggles the setting and asks for notification permission when turned on`() {
        val activity = buildActivity()
        moveTo(activity, 2) // after Default calendar and Default duration
        assertEquals("Reminder notifications: yes", rows(activity)[2].text.toString())

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(false, Prefs.reminderNotifications(context))
        assertEquals("Reminder notifications: no", rows(activity)[2].text.toString())

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(true, Prefs.reminderNotifications(context))
        assertTrue(
            shadowOf(activity).lastRequestedPermission.requestedPermissions
                .contains(android.Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    @Test
    fun `turning reminders on opens notification settings once the dialog can't be shown any more`() {
        // Asked before, and Android no longer offers a rationale: the dialog won't come up.
        Prefs.setAskedNotificationPermission(context)
        Prefs.setReminderNotifications(context, false)
        val activity = buildActivity()
        moveTo(activity, 2)

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)

        assertNull(shadowOf(activity).lastRequestedPermission)
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(context.packageName, opened.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `turning reminders on with the reminder channel blocked opens that channel's settings`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("event_reminders", "Event reminders", android.app.NotificationManager.IMPORTANCE_NONE),
        )
        Prefs.setReminderNotifications(context, false)
        val activity = buildActivity()
        moveTo(activity, 2)

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)

        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, opened.action)
        assertEquals("event_reminders", opened.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `turning reminders back on shows what's due right away`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        FakeCalendarProvider.putAlert(
            eventId = 7,
            alarmTime = now - 60_000L,
            begin = now,
            end = now + 3_600_000L,
            state = android.provider.CalendarContract.CalendarAlerts.STATE_FIRED,
        )
        Prefs.setReminderNotifications(context, false)
        val activity = buildActivity()
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(notifications.activeNotifications.isEmpty())
        moveTo(activity, 2)

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)

        val deadline = System.currentTimeMillis() + 3000
        while (notifications.activeNotifications.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "no reminder notification within 3s" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `snooze row steps by 5 minutes between 5 and 60`() {
        val activity = buildActivity()
        moveTo(activity, 3)
        assertEquals("Snooze reminders for: 5 min", rows(activity)[3].text.toString())

        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(10, Prefs.reminderSnoozeMinutes(context))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(5, Prefs.reminderSnoozeMinutes(context))
    }

    @Test
    fun `default address book row cycles through ask-each-time ahead of the real accounts`() {
        val activity = buildActivity("contacts")
        // App sections render before core's Theme/About, so Default address book is the first row.
        assertTrue(rows(activity)[0].text.contains("ask each time"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        val afterFirstRight = rows(activity)[0].text.toString()
        assertTrue(afterFirstRight.contains("fastmail") || afterFirstRight.contains("gmail"))
        // Cycling left from "ask each time" should wrap to the last account, not the first —
        // reset back to that starting state explicitly, since the RIGHT press above already
        // persisted a default into the same SharedPreferences this fresh instance would reload.
        Prefs.setDefaultAccount(context, null, null)
        val fresh = buildActivity("contacts")
        press(fresh, KeyEvent.KEYCODE_DPAD_LEFT)
        val afterLeft = rows(fresh)[0].text.toString()
        assertTrue(afterLeft.contains("fastmail") || afterLeft.contains("gmail"))
        assertTrue(Prefs.defaultAccountName(context) != null)
    }

    @Test
    fun `the Contacts surface has no Calendar section`() {
        val activity = buildActivity("contacts")
        assertTrue(rows(activity).none { it.text.contains("Default calendar") || it.text.contains("Default duration") })
    }

    @Test
    fun `the Calendar surface has no Contacts section`() {
        val activity = buildActivity("calendar")
        assertTrue(rows(activity).none { it.text.contains("Default address book") })
    }

    @Test
    fun `every surface gets the same core sections too`() {
        val calendar = rows(buildActivity("calendar")).map { it.text.toString() }
        val contacts = rows(buildActivity("contacts")).map { it.text.toString() }
        listOf(calendar, contacts).forEach { labels ->
            assertTrue(labels.any { it.contains("Font") })
            assertTrue(labels.any { it.contains("Hint bar") })
            assertTrue(labels.contains("Intro"))
            assertTrue(labels.contains("Key mapping"))
            assertTrue(labels.contains("About"))
            assertTrue(labels.contains("Open website"))
        }
    }

    @Test
    fun `selecting Intro opens core's ManualActivity for this surface`() {
        val activity = buildActivity("calendar")
        val introIndex = rows(activity).indexOfFirst { it.text == "Intro" }
        moveTo(activity, introIndex)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ManualActivity::class.java.name, started.component?.className)
        assertEquals(ManualActivity.PAGE_INTRO, started.getStringExtra(ManualActivity.EXTRA_PAGE))
        assertEquals("calendar", started.getStringExtra(ManualActivity.EXTRA_SURFACE))
    }

    @Test
    fun `Left and Right do nothing on the Intro row`() {
        val activity = buildActivity("calendar")
        val introIndex = rows(activity).indexOfFirst { it.text == "Intro" }
        moveTo(activity, introIndex)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)

        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
