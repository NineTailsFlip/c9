package life.furrer.c9.calendar.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.provider.CalendarContract.CalendarAlerts
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import life.furrer.c9.calendar.CalendarActivity
import life.furrer.c9.calendar.EventDetailActivity
import life.furrer.c9.calendar.FakeCalendarProvider
import life.furrer.c9.core.Prefs
import org.junit.Assert.assertEquals
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

/** [Reminders] end to end against [FakeCalendarProvider]'s CalendarAlerts table: what a pass
 *  writes back to the provider, which notifications it posts, and what Snooze/Dismiss/open do. */
@RunWith(RobolectricTestRunner::class)
class RemindersTest {

    private val context = RuntimeEnvironment.getApplication()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val minute = 60_000L
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun state(alertId: Long): Int = FakeCalendarProvider.alerts.getValue(alertId).getAsInteger(CalendarAlerts.STATE)

    private fun posted(): List<Notification> = shadowOf(notifications).allNotifications

    private fun postedFor(key: InstanceKey): Notification? =
        notifications.activeNotifications.firstOrNull { it.tag == ReminderNotifications.tag(key) }?.notification

    @Test
    fun `a due reminder is marked fired and notified with the event, Snooze and Dismiss`() {
        val begin = now + 10 * minute
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + 60 * minute, location = "Room 4")

        Reminders.refresh(context, now)

        assertEquals(CalendarAlerts.STATE_FIRED, state(id))
        assertEquals(now, FakeCalendarProvider.alerts.getValue(id).getAsLong(CalendarAlerts.NOTIFY_TIME))
        val notification = postedFor(InstanceKey(7, begin))
        assertNotNull(notification)
        assertEquals("Dentist", notification!!.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().endsWith("· Room 4"))
        assertEquals(listOf("Snooze 5 min", "Dismiss"), notification.actions.map { it.title.toString() })
        assertEquals("event_reminders", notification.channelId)
        assertNotNull(notification.deleteIntent)
    }

    @Test
    fun `a second pass posts no second notification and leaves the alert fired`() {
        val begin = now + 10 * minute
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + 60 * minute)
        Reminders.refresh(context, now)
        Reminders.refresh(context, now + minute)

        assertEquals(CalendarAlerts.STATE_FIRED, state(id))
        assertEquals(1, posted().size)
    }

    @Test
    fun `only the pass that fires a reminder rings, a re-post is quiet`() {
        val begin = now + 10 * minute
        FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + 60 * minute)
        Reminders.refresh(context, now)
        val first = postedFor(InstanceKey(7, begin))!!
        assertNull(first.group)

        // e.g. after a reboot: the notification is gone, the alert is still FIRED.
        notifications.cancelAll()
        Reminders.refresh(context, now + minute)
        val again = postedFor(InstanceKey(7, begin))!!
        assertEquals(Notification.GROUP_ALERT_SUMMARY, again.groupAlertBehavior)
        assertNotNull(again.group)
    }

    @Test
    fun `a notification whose alert the provider deleted is taken down on the next pass`() {
        val begin = now + 10 * minute
        val gone = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + minute)
        FakeCalendarProvider.putAlert(eventId = 8, alarmTime = now, begin = begin, end = begin + minute)
        Reminders.refresh(context, now)
        assertNotNull(postedFor(InstanceKey(7, begin)))

        // Event 7 deleted: the provider drops its alert rows, FIRED or not, and pings "now".
        FakeCalendarProvider.alerts.remove(gone)
        Reminders.refresh(context, now + minute)

        assertNull(postedFor(InstanceKey(7, begin)))
        assertNotNull(postedFor(InstanceKey(8, begin)))
    }

    @Test
    fun `an alert someone else fired between the read and the write is shown but doesn't ring`() {
        val begin = now + 10 * minute
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + minute)
        FakeCalendarProvider.afterAlertsQuery = {
            FakeCalendarProvider.alerts.getValue(id).put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED)
        }

        Reminders.refresh(context, now)

        val notification = postedFor(InstanceKey(7, begin))!!
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertNotNull(notification.group)
    }

    @Test
    fun `nothing is posted or written with reminder notifications switched off`() {
        Prefs.setReminderNotifications(context, false)
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = now, end = now + 60 * minute)

        Reminders.refresh(context, now)

        assertEquals(CalendarAlerts.STATE_SCHEDULED, state(id))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `alerts are left for another app while C9's notifications are blocked`() {
        shadowOf(notifications).setNotificationsEnabled(false)
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = now, end = now + 60 * minute)

        Reminders.refresh(context, now)

        assertEquals(CalendarAlerts.STATE_SCHEDULED, state(id))
    }

    @Test
    fun `alerts are left alone while the reminder channel is switched off`() {
        notifications.createNotificationChannel(
            android.app.NotificationChannel("event_reminders", "Event reminders", NotificationManager.IMPORTANCE_NONE),
        )
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = now, end = now + 60 * minute)

        Reminders.refresh(context, now)

        assertEquals(CalendarAlerts.STATE_SCHEDULED, state(id))
    }

    @Test
    fun `nothing happens without calendar permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_CALENDAR)
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = now, end = now + 60 * minute)

        Reminders.refresh(context, now)

        assertEquals(CalendarAlerts.STATE_SCHEDULED, state(id))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `dismiss retires that instance's fired alerts and its notification only`() {
        val begin = now + 10 * minute
        val first = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now - minute, begin = begin, end = begin + minute)
        val other = FakeCalendarProvider.putAlert(eventId = 8, alarmTime = now - minute, begin = begin, end = begin + minute)
        val later = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now + 5 * minute, begin = begin, end = begin + minute)
        Reminders.refresh(context, now)

        Reminders.dismiss(context, InstanceKey(7, begin))

        assertEquals(CalendarAlerts.STATE_DISMISSED, state(first))
        assertEquals(CalendarAlerts.STATE_FIRED, state(other))
        assertEquals(CalendarAlerts.STATE_SCHEDULED, state(later))
        assertNull(postedFor(InstanceKey(7, begin)))
        assertNotNull(postedFor(InstanceKey(8, begin)))
    }

    @Test
    fun `snooze schedules a fresh alert and an alarm that brings the reminder back`() {
        val begin = now + 10 * minute
        val end = begin + 30 * minute
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = end)
        Reminders.refresh(context, now)

        Reminders.snooze(context, InstanceKey(7, begin), end, now)

        assertEquals(CalendarAlerts.STATE_DISMISSED, state(id))
        assertNull(postedFor(InstanceKey(7, begin)))
        val (snoozeId, snoozed) = FakeCalendarProvider.alerts.entries.single { it.key != id }
        assertEquals(CalendarAlerts.STATE_SCHEDULED, snoozed.getAsInteger(CalendarAlerts.STATE))
        assertEquals(now + 5 * minute, snoozed.getAsLong(CalendarAlerts.ALARM_TIME))
        assertEquals(begin, snoozed.getAsLong(CalendarAlerts.BEGIN))
        assertEquals(end, snoozed.getAsLong(CalendarAlerts.END))
        assertEquals(0, snoozed.getAsInteger(CalendarAlerts.MINUTES))
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()
        assertEquals(now + 5 * minute, alarm.triggerAtTime)

        // The snooze row has no joined event columns in the fake; give it the title the real
        // provider's join would.
        snoozed.put(CalendarAlerts.TITLE, "Dentist")
        Reminders.refresh(context, now + 5 * minute)
        assertEquals(CalendarAlerts.STATE_FIRED, FakeCalendarProvider.alerts.getValue(snoozeId).getAsInteger(CalendarAlerts.STATE))
        assertNotNull(postedFor(InstanceKey(7, begin)))
    }

    @Test
    fun `a snooze that comes due after the meeting ended still rings`() {
        val begin = now - 20 * minute
        val end = begin + 30 * minute
        FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now - 30 * minute, begin = begin, end = end)
        Reminders.refresh(context, now)
        Reminders.snooze(context, InstanceKey(7, begin), end, now)
        val snoozeId = FakeCalendarProvider.alerts.entries.single { it.value.getAsInteger(CalendarAlerts.MINUTES) == 0 }.key

        Reminders.refresh(context, now + 30 * minute) // 20 minutes after the meeting ended

        assertEquals(CalendarAlerts.STATE_FIRED, state(snoozeId))
        assertNotNull(postedFor(InstanceKey(7, begin)))
    }

    @Test
    fun `the snooze length follows the setting`() {
        Prefs.setReminderSnoozeMinutes(context, 15)
        val begin = now + 10 * minute
        val id = FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + minute)
        Reminders.refresh(context, now)
        assertEquals("Snooze 15 min", postedFor(InstanceKey(7, begin))!!.actions[0].title.toString())

        Reminders.snooze(context, InstanceKey(7, begin), begin + minute, now)
        val snoozed = FakeCalendarProvider.alerts.entries.single { it.key != id }.value
        assertEquals(now + 15 * minute, snoozed.getAsLong(CalendarAlerts.ALARM_TIME))
    }

    @Test
    fun `cancelAll takes every reminder notification down`() {
        FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = now, end = now + minute)
        FakeCalendarProvider.putAlert(eventId = 8, alarmTime = now, begin = now, end = now + minute)
        Reminders.refresh(context, now)
        assertEquals(2, notifications.activeNotifications.size)

        ReminderNotifications.cancelAll(context)

        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test
    fun `opening the notification starts the calendar with that instance of the event on top`() {
        val begin = now + 10 * minute
        FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + minute)
        Reminders.refresh(context, now)
        val open = shadowOf(postedFor(InstanceKey(7, begin))!!.contentIntent).savedIntent

        val activity = Robolectric.buildActivity(ReminderOpenActivity::class.java, open).create().get()

        // No calendar task running (Robolectric has no app tasks): a fresh one, event on top.
        // (Robolectric hands startActivities' intents back last one first.)
        val (started, calendar) = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        assertEquals(CalendarActivity::class.java.name, calendar.component?.className)
        assertTrue(calendar.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(calendar.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP == 0)
        assertEquals(EventDetailActivity::class.java.name, started.component?.className)
        assertEquals(7L, started.getLongExtra(EventDetailActivity.EXTRA_EVENT_ID, -1L))
        assertEquals(begin, started.getLongExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, -1L))
        assertNull(postedFor(InstanceKey(7, begin)))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `the notification's actions carry the instance to the receiver`() {
        val begin = now + 10 * minute
        FakeCalendarProvider.putAlert(eventId = 7, alarmTime = now, begin = begin, end = begin + 30 * minute)
        Reminders.refresh(context, now)
        val notification = postedFor(InstanceKey(7, begin))!!

        val snooze: Intent = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertEquals(ReminderReceiver.ACTION_SNOOZE, snooze.action)
        assertEquals(InstanceKey(7, begin), ReminderReceiver.instanceOf(snooze))
        assertEquals(begin + 30 * minute, snooze.getLongExtra(ReminderNotifications.EXTRA_END, 0L))
        val dismiss: Intent = shadowOf(notification.actions[1].actionIntent).savedIntent
        assertEquals(ReminderReceiver.ACTION_DISMISS, dismiss.action)
        assertEquals(ReminderReceiver.ACTION_DISMISS, shadowOf(notification.deleteIntent).savedIntent.action)
    }

    @Test
    fun `describe shows the time range, the day when it isn't today, and the location`() {
        val zone = ZoneId.of("Europe/Zurich")
        val today = LocalDate.of(2026, 10, 3)
        val clock = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        fun notice(begin: Long, end: Long, allDay: Boolean = false, location: String? = null) =
            ReminderNotice(InstanceKey(1, begin), "Dentist", location, begin, end, allDay, loud = true)

        assertEquals("10:00 – 11:00", ReminderNotifications.describe(notice(at(today, 10), at(today, 11)), clock, zone))
        assertEquals(
            "10:00 – 11:00 · Room 4",
            ReminderNotifications.describe(notice(at(today, 10), at(today, 11), location = " Room 4 "), clock, zone),
        )
        val tomorrow = today.plusDays(1)
        assertEquals("Sun 4 Oct, 08:00 – 09:00", ReminderNotifications.describe(notice(at(tomorrow, 8), at(tomorrow, 9)), clock, zone))
        val utc = { day: LocalDate -> day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        assertEquals("All day", ReminderNotifications.describe(notice(utc(today), utc(tomorrow), allDay = true), clock, zone))
        assertEquals(
            "Sun 4 Oct, all day",
            ReminderNotifications.describe(notice(utc(tomorrow), utc(tomorrow.plusDays(1)), allDay = true), clock, zone),
        )
    }
}
