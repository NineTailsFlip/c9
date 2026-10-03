package life.furrer.c9.calendar.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders as ReminderRows
import life.furrer.c9.calendar.FakeCalendarProvider
import life.furrer.c9.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** C9's own scheduling end to end against [FakeCalendarProvider]: reminders that fire with no
 *  provider alarm behind them, the alarm armed for the next one, and the content-trigger job. */
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulingTest {

    private val context = RuntimeEnvironment.getApplication()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val jobs = shadowOf(context.getSystemService(JobScheduler::class.java))
    private val minute = 60_000L
    private val now = System.currentTimeMillis()

    private val zoneBefore = java.util.TimeZone.getDefault()

    @After
    fun tearDown() {
        java.util.TimeZone.setDefault(zoneBefore)
    }

    @Before
    fun setUp() {
        // C9Application's startup pass, so it can't land in the middle of the test.
        Reminders.awaitBackground()
        // No DST: the next alarm is also capped at the zone's next offset transition, which in
        // the build machine's zone could fall within a test's day on the wrong date.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    /** An event (title only, for the alert join) with one instance and METHOD_ALERT reminders. */
    private fun event(id: Long, begin: Long, vararg minutes: Int, title: String = "Event $id", visible: Boolean = true, status: Int = Attendees.ATTENDEE_STATUS_NONE) {
        FakeCalendarProvider.events[id] = ContentValues().apply { put(Events.TITLE, title) }
        FakeCalendarProvider.putInstance(id, begin, begin + 30 * minute, visible = visible, selfAttendeeStatus = status)
        minutes.forEach { FakeCalendarProvider.putReminder(id, it) }
    }

    /** When C9's reminder alarm is set for, or null if it isn't. */
    private fun alarmAt(): Long? = alarms.scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == ReminderReceiver.ACTION_REMINDER_DUE }
        .also { assertTrue("one reminder alarm at most", it.size <= 1) }
        .singleOrNull()?.triggerAtTime

    private fun shown(eventId: Long, begin: Long): Boolean =
        notifications.activeNotifications.any { it.tag == ReminderNotifications.tag(InstanceKey(eventId, begin)) }

    private fun rowsFor(eventId: Long) = FakeCalendarProvider.alerts.values.filter { it.getAsLong(CalendarAlerts.EVENT_ID) == eventId }

    @Test
    fun `two reminders minutes apart both fire on time without any provider alarm`() {
        val first = now + 12 * minute // reminded 10 minutes ahead: due at now + 2
        val second = now + 14 * minute + 20_000 // due at now + 4:20
        event(1, first, 10)
        event(2, second, 10)

        Reminders.refresh(context, now)
        assertEquals(now + 2 * minute, alarmAt())
        assertTrue(FakeCalendarProvider.alerts.isEmpty())

        Reminders.refresh(context, now + 2 * minute)
        val row = rowsFor(1).single()
        assertEquals(CalendarAlerts.STATE_FIRED, row.getAsInteger(CalendarAlerts.STATE))
        assertEquals(first, row.getAsLong(CalendarAlerts.BEGIN))
        assertEquals(first + 30 * minute, row.getAsLong(CalendarAlerts.END))
        assertEquals(10, row.getAsInteger(CalendarAlerts.MINUTES))
        assertEquals(now + 2 * minute, row.getAsLong(CalendarAlerts.CREATION_TIME))
        assertTrue(shown(1, first))
        assertEquals(second - 10 * minute, alarmAt())

        Reminders.refresh(context, second - 10 * minute)
        assertTrue(shown(2, second))
        assertEquals(CalendarAlerts.STATE_FIRED, rowsFor(2).single().getAsInteger(CalendarAlerts.STATE))
        // The first one stays up, once.
        assertTrue(shown(1, first))
        assertEquals(1, rowsFor(1).size)
    }

    @Test
    fun `a reminder the provider already wrote a row for gets no second row and fires once`() {
        val begin = now + 10 * minute
        event(1, begin, 10)
        val providerRow = FakeCalendarProvider.putAlert(eventId = 1, alarmTime = now, begin = begin, end = begin + 30 * minute)

        Reminders.refresh(context, now)

        assertEquals(listOf(providerRow), FakeCalendarProvider.alerts.keys.toList())
        assertEquals(CalendarAlerts.STATE_FIRED, FakeCalendarProvider.alerts.getValue(providerRow).getAsInteger(CalendarAlerts.STATE))
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test
    fun `a dismissed reminder doesn't come back`() {
        val begin = now + 10 * minute
        event(1, begin, 11) // due a minute ago, and dismissed since
        FakeCalendarProvider.putAlert(eventId = 1, alarmTime = now - minute, begin = begin, end = begin + 30 * minute, state = CalendarAlerts.STATE_DISMISSED)

        Reminders.refresh(context, now)

        assertEquals(1, FakeCalendarProvider.alerts.size)
        assertFalse(shown(1, begin))
    }

    @Test
    fun `a reminder that was already due when the event was created fires right away`() {
        val begin = now + 5 * minute
        event(1, begin, 35) // due 30 minutes ago

        Reminders.refresh(context, now)

        assertTrue(shown(1, begin))
        assertEquals(now - 30 * minute, rowsFor(1).single().getAsLong(CalendarAlerts.ALARM_TIME))
    }

    @Test
    fun `a pending snooze doesn't hold back another reminder, nor the other way round`() {
        val snoozedBegin = now - 20 * minute
        FakeCalendarProvider.putAlert(eventId = 9, alarmTime = now + 10 * minute, begin = snoozedBegin, end = now + 40 * minute, minutes = 0)
        val begin = now + 15 * minute
        event(1, begin, 10) // due at now + 5

        Reminders.refresh(context, now)
        assertEquals(now + 5 * minute, alarmAt())

        Reminders.refresh(context, now + 5 * minute)
        assertTrue(shown(1, begin))
        assertEquals(now + 10 * minute, alarmAt())

        Reminders.refresh(context, now + 10 * minute)
        assertTrue(shown(9, snoozedBegin))
    }

    @Test
    fun `snooze arms the alarm for the snoozed time when that's what comes next`() {
        val begin = now + 10 * minute
        event(1, begin, 10)
        Reminders.refresh(context, now)

        Reminders.snooze(context, InstanceKey(1, begin), begin + 30 * minute, now, minutes = 5)

        assertEquals(now + 5 * minute, alarmAt())
    }

    @Test
    fun `a snooze exactly 15 minutes after an all-day start-of-day reminder still rings`() {
        // Today, all day (UTC here), "at the start": rang at midnight; snoozed at 00:10 for 5
        // minutes, which would land exactly on an alarm time an all-day row could have.
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val begin = allDayEvent(1, today, 0)
        val end = utcMillis(today.plusDays(1))
        FakeCalendarProvider.putAlert(eventId = 1, alarmTime = begin, begin = begin, end = end, state = CalendarAlerts.STATE_FIRED, allDay = true, minutes = 0)

        Reminders.snooze(context, InstanceKey(1, begin), end, begin + 10 * minute, minutes = 5)

        val snooze = rowsFor(1).single { it.getAsInteger(CalendarAlerts.STATE) == CalendarAlerts.STATE_SCHEDULED }
        assertEquals(begin + 15 * minute + 1, snooze.getAsLong(CalendarAlerts.ALARM_TIME))
        Reminders.refresh(context, begin + 15 * minute + 1)
        assertEquals(CalendarAlerts.STATE_FIRED, snooze.getAsInteger(CalendarAlerts.STATE))
        assertTrue(shown(1, begin))
    }

    @Test
    fun `hidden calendars and declined invitations get no rows and no alarm`() {
        event(1, now + 10 * minute, 10, visible = false)
        event(2, now + 10 * minute, 10, status = Attendees.ATTENDEE_STATUS_DECLINED)
        event(3, now + 20 * minute, 10, visible = false)

        Reminders.refresh(context, now)

        assertTrue(FakeCalendarProvider.alerts.isEmpty())
        assertEquals(now + ReminderSchedule.RECHECK_MS, alarmAt())
    }

    @Test
    fun `only notification reminders count`() {
        FakeCalendarProvider.events[1] = ContentValues().apply { put(Events.TITLE, "Mail me") }
        FakeCalendarProvider.putInstance(1, now + 10 * minute, now + 40 * minute)
        FakeCalendarProvider.putReminder(1, 10, ReminderRows.METHOD_EMAIL)
        FakeCalendarProvider.putReminder(1, 5, ReminderRows.METHOD_DEFAULT)

        Reminders.refresh(context, now)

        assertTrue(FakeCalendarProvider.alerts.isEmpty())
        assertEquals(now + ReminderSchedule.RECHECK_MS, alarmAt())
    }

    @Test
    fun `a reminder weeks ahead of its event is found beyond the regular lookahead`() {
        val begin = now + 14 * 24 * 60 * minute + 5 * minute
        event(1, begin, 14 * 24 * 60) // due in five minutes

        Reminders.refresh(context, now)

        assertEquals(now + 5 * minute, alarmAt())
    }

    @Test
    fun `a pass arms the content-trigger job on the calendar provider`() {
        Reminders.refresh(context, now)

        val job = jobs.getPendingJob(ReminderScheduler.JOB_ID)
        assertNotNull(job)
        assertEquals(ReminderJobService::class.java.name, job.service.className)
        assertEquals(CalendarContract.CONTENT_URI, job.triggerContentUris!!.single().uri)
        assertEquals(ReminderScheduler.UPDATE_DELAY_MS, job.triggerContentUpdateDelay)
        assertEquals(ReminderScheduler.MAX_DELAY_MS, job.triggerContentMaxDelay)
    }

    @Test
    fun `a sync adapter deleting the event takes its notification down on the job's pass`() {
        val begin = now + 10 * minute
        event(1, begin, 10)
        Reminders.refresh(context, now)
        assertTrue(shown(1, begin))

        // DAVx5 deletes the event: the provider drops instance, reminder and alert rows, and
        // sends no EVENT_REMINDER — only the content change the job is waiting for.
        FakeCalendarProvider.instances.clear()
        FakeCalendarProvider.reminders.clear()
        FakeCalendarProvider.alerts.clear()
        Reminders.refresh(context, now + minute, armJob = false)

        assertFalse(shown(1, begin))
    }

    @Test
    fun `switched off means no rows, and neither the alarm nor the job stays armed`() {
        event(1, now + 20 * minute, 10)
        Reminders.refresh(context, now)
        assertNotNull(alarmAt())
        assertNotNull(jobs.getPendingJob(ReminderScheduler.JOB_ID))

        Prefs.setReminderNotifications(context, false)
        Reminders.refresh(context, now + 10 * minute)

        assertTrue(FakeCalendarProvider.alerts.isEmpty())
        assertNull(alarmAt())
        assertNull(jobs.getPendingJob(ReminderScheduler.JOB_ID))
    }

    @Test
    fun `blocked notifications leave the table alone too`() {
        event(1, now + 10 * minute, 10)
        shadowOf(notifications).setNotificationsEnabled(false)

        Reminders.refresh(context, now)

        assertTrue(FakeCalendarProvider.alerts.isEmpty())
        assertNull(alarmAt())
    }

    @Test
    fun `ensureRunning runs a pass only when the job isn't armed`() {
        Reminders.refresh(context, now)
        assertTrue(ReminderScheduler.isJobArmed(context))
        context.getSystemService(JobScheduler::class.java).cancel(ReminderScheduler.JOB_ID)
        assertFalse(ReminderScheduler.isJobArmed(context))

        Reminders.ensureRunning(context)
        val deadline = System.currentTimeMillis() + 3000
        while (!ReminderScheduler.isJobArmed(context)) {
            check(System.currentTimeMillis() < deadline) { "no pass within 3s" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a pass that throws still leaves an alarm armed, never later than the one it had`() {
        event(1, now + 15 * minute, 10) // due at now + 5
        Reminders.refresh(context, now)
        assertEquals(now + 5 * minute, alarmAt())

        FakeCalendarProvider.afterAlertsQuery = { throw IllegalStateException("provider hiccup") }
        val thrown = runCatching { Reminders.refresh(context, now + minute) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(now + 5 * minute, alarmAt())
    }

    @Test
    fun `a pass that throws with nothing armed retries soon`() {
        FakeCalendarProvider.afterAlertsQuery = { throw IllegalStateException("provider hiccup") }
        runCatching { Reminders.refresh(context, now) }

        assertEquals(now + ReminderScheduler.RETRY_MS, alarmAt())
        assertTrue(ReminderScheduler.isJobArmed(context))
    }

    @Test
    fun `a provider that answers nothing means retry soon, not tomorrow`() {
        event(1, now + 15 * minute, 10)
        FakeCalendarProvider.unavailable = true

        Reminders.refresh(context, now)

        assertEquals(now + ReminderScheduler.RETRY_MS, alarmAt())
    }

    @Test
    fun `a failed look keeps a sooner armed alarm`() {
        ReminderScheduler.arm(context, now + 2 * minute)
        FakeCalendarProvider.unavailable = true

        Reminders.refresh(context, now)

        assertEquals(now + 2 * minute, alarmAt())
    }

    @Test
    fun `a background pass that throws is logged, and what follows it still runs`() {
        val after = java.util.concurrent.CountDownLatch(1)
        reminderThread({ throw IllegalStateException("boom") }, after = { after.countDown() })
        assertTrue(after.await(3, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `isAlarmArmed follows arm and disarm`() {
        assertFalse(ReminderScheduler.isAlarmArmed(context))
        Reminders.refresh(context, now)
        assertTrue(ReminderScheduler.isAlarmArmed(context))
        ReminderScheduler.disarm(context)
        assertFalse(ReminderScheduler.isAlarmArmed(context))
    }

    @Test
    fun `an all-day reminder that rang before the clocks changed doesn't ring again`() {
        val zone = java.time.ZoneId.systemDefault()
        val tomorrow = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        fun utc(d: java.time.LocalDate) = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val begin = utc(tomorrow)
        FakeCalendarProvider.events[1] = ContentValues().apply { put(Events.TITLE, "Holiday") }
        FakeCalendarProvider.putInstance(1, begin, utc(tomorrow.plusDays(1)), allDay = true)
        val minutes = 60
        FakeCalendarProvider.putReminder(1, minutes)
        val dueNow = ReminderSchedule.alarmTime(UpcomingInstance(1, begin, utc(tomorrow.plusDays(1)), true), minutes, now)
        // Rang (and was dismissed) when the offset was an hour different: its row is an hour off.
        FakeCalendarProvider.putAlert(
            eventId = 1, alarmTime = dueNow - 60 * minute, begin = begin, end = utc(tomorrow.plusDays(1)),
            state = CalendarAlerts.STATE_DISMISSED, allDay = true, minutes = minutes,
        )

        Reminders.refresh(context, dueNow)

        assertEquals(1, FakeCalendarProvider.alerts.size)
        assertFalse(shown(1, begin))
    }

    @Test
    fun `the provider's own repeat of an all-day reminder that rang before a zone change doesn't ring`() = inZone("Europe/Helsinki") {
        val begin = allDayEvent(1, oct4, lead)
        val end = utcMillis(oct4.plusDays(1))
        // Rang at 12:22 in Zurich (10:22 UTC), then the phone went to Helsinki (+3): the
        // provider's scheduler doesn't see the fired row (another alarm time) and writes its own
        // for 12:22 Helsinki time, 09:22 UTC — an hour earlier, so already due.
        val fired = FakeCalendarProvider.putAlert(
            eventId = 1, alarmTime = utcMillis(oct3, 10, 22), begin = begin, end = end, state = CalendarAlerts.STATE_FIRED, allDay = true, minutes = lead,
        )
        val repeat = FakeCalendarProvider.putAlert(
            eventId = 1, alarmTime = utcMillis(oct3, 9, 22), begin = begin, end = end, allDay = true, minutes = lead,
        )

        Reminders.refresh(context, utcMillis(oct3, 10, 23))

        assertEquals(CalendarAlerts.STATE_FIRED, FakeCalendarProvider.alerts.getValue(fired).getAsInteger(CalendarAlerts.STATE))
        assertEquals(CalendarAlerts.STATE_DISMISSED, FakeCalendarProvider.alerts.getValue(repeat).getAsInteger(CalendarAlerts.STATE))
        assertEquals(2, FakeCalendarProvider.alerts.size)
        // Only the fired one's quiet re-post: no notification rang for the repeat.
        assertTrue(shadowOf(notifications).allNotifications.all { it.group != null })
    }

    private fun inZone(id: String, block: () -> Unit) {
        val before = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
        try {
            block()
        } finally {
            java.util.TimeZone.setDefault(before)
        }
    }

    private fun utcMillis(date: java.time.LocalDate, hour: Int = 0, minuteOfHour: Int = 0): Long =
        date.atTime(hour, minuteOfHour).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    /** An all-day event on [date] with one METHOD_ALERT reminder; returns its begin. */
    private fun allDayEvent(id: Long, date: java.time.LocalDate, minutes: Int): Long {
        FakeCalendarProvider.events[id] = ContentValues().apply { put(Events.TITLE, "Holiday") }
        FakeCalendarProvider.putInstance(id, utcMillis(date), utcMillis(date.plusDays(1)), allDay = true)
        FakeCalendarProvider.putReminder(id, minutes)
        return utcMillis(date)
    }

    // 4 Oct 2026, all day, reminded 11h38 ahead: 12:22 on 3 Oct wherever the phone is — 10:22 UTC
    // in Zurich (+2), 11:22 UTC in London (+1).
    private val oct3 = java.time.LocalDate.of(2026, 10, 3)
    private val oct4 = oct3.plusDays(1)
    private val lead = 11 * 60 + 38

    @Test
    fun `an all-day reminder the provider wrote in Zurich rings at 12-22 London time after the move`() = inZone("Europe/London") {
        val begin = allDayEvent(1, oct4, lead)
        val stale = FakeCalendarProvider.putAlert(
            eventId = 1, alarmTime = utcMillis(oct3, 10, 22), begin = begin, end = utcMillis(oct4.plusDays(1)), allDay = true, minutes = lead,
        )
        // A timed reminder due right now isn't touched by any of it.
        FakeCalendarProvider.events[2] = ContentValues().apply { put(Events.TITLE, "Call") }
        FakeCalendarProvider.putInstance(2, utcMillis(oct3, 10, 25), utcMillis(oct3, 11, 0))
        FakeCalendarProvider.putReminder(2, 10)
        val timed = FakeCalendarProvider.putAlert(eventId = 2, alarmTime = utcMillis(oct3, 10, 15), begin = utcMillis(oct3, 10, 25), end = utcMillis(oct3, 11, 0))

        // 12:15 in Zurich: the phone lands in London (11:15 there).
        Reminders.refresh(context, utcMillis(oct3, 10, 15))
        assertEquals(utcMillis(oct3, 11, 22), FakeCalendarProvider.alerts.getValue(stale).getAsLong(CalendarAlerts.ALARM_TIME))
        assertEquals(CalendarAlerts.STATE_SCHEDULED, FakeCalendarProvider.alerts.getValue(stale).getAsInteger(CalendarAlerts.STATE))
        assertEquals(utcMillis(oct3, 11, 22), alarmAt())
        assertFalse(shown(1, begin))
        assertEquals(CalendarAlerts.STATE_FIRED, FakeCalendarProvider.alerts.getValue(timed).getAsInteger(CalendarAlerts.STATE))
        assertEquals(utcMillis(oct3, 10, 15), FakeCalendarProvider.alerts.getValue(timed).getAsLong(CalendarAlerts.ALARM_TIME))

        // The provider's old alarm still goes off at what was 12:22 in Zurich: nothing to ring.
        Reminders.refresh(context, utcMillis(oct3, 10, 22))
        assertFalse(shown(1, begin))

        Reminders.refresh(context, utcMillis(oct3, 11, 22))
        assertTrue(shown(1, begin))
        assertEquals(CalendarAlerts.STATE_FIRED, rowsFor(1).single().getAsInteger(CalendarAlerts.STATE))
    }

    @Test
    fun `when the provider re-wrote the reminder for London itself, the Zurich row goes and London's rings once`() = inZone("Europe/London") {
        val begin = allDayEvent(1, oct4, lead)
        val end = utcMillis(oct4.plusDays(1))
        val stale = FakeCalendarProvider.putAlert(eventId = 1, alarmTime = utcMillis(oct3, 10, 22), begin = begin, end = end, allDay = true, minutes = lead)
        val london = FakeCalendarProvider.putAlert(eventId = 1, alarmTime = utcMillis(oct3, 11, 22), begin = begin, end = end, allDay = true, minutes = lead)

        Reminders.refresh(context, utcMillis(oct3, 10, 15))
        assertFalse(stale in FakeCalendarProvider.alerts)
        assertEquals(listOf(utcMillis(oct3, 11, 22)), rowsFor(1).map { it.getAsLong(CalendarAlerts.ALARM_TIME) })
        assertEquals(utcMillis(oct3, 11, 22), alarmAt())

        Reminders.refresh(context, utcMillis(oct3, 11, 22))
        assertTrue(shown(1, begin))
        assertEquals(CalendarAlerts.STATE_FIRED, FakeCalendarProvider.alerts.getValue(london).getAsInteger(CalendarAlerts.STATE))
        assertEquals(1, rowsFor(1).size)
    }

    @Test
    fun `two all-day reminders an hour apart both move, though one's new time is the other's old`() = inZone("Europe/London") {
        // Leads of 11h38 and 10h38, written in Zurich: 10:22 and 11:22 UTC. In London they
        // belong at 11:22 and 12:22 — the first one's new time still held by the second's row
        // until that one has moved. The first row comes first, so its move is the blocked one.
        val begin = allDayEvent(1, oct4, lead)
        FakeCalendarProvider.putReminder(1, lead - 60)
        val end = utcMillis(oct4.plusDays(1))
        val first = FakeCalendarProvider.putAlert(eventId = 1, alarmTime = utcMillis(oct3, 10, 22), begin = begin, end = end, allDay = true, minutes = lead)
        val second = FakeCalendarProvider.putAlert(eventId = 1, alarmTime = utcMillis(oct3, 11, 22), begin = begin, end = end, allDay = true, minutes = lead - 60)

        Reminders.refresh(context, utcMillis(oct3, 10, 15))

        assertEquals(utcMillis(oct3, 11, 22), FakeCalendarProvider.alerts.getValue(first).getAsLong(CalendarAlerts.ALARM_TIME))
        assertEquals(utcMillis(oct3, 12, 22), FakeCalendarProvider.alerts.getValue(second).getAsLong(CalendarAlerts.ALARM_TIME))
        assertEquals(utcMillis(oct3, 11, 22), alarmAt())
    }

    @Test
    fun `an all-day reminder written before the clocks went back rings at its local time after`() = inZone("Europe/Zurich") {
        // Mon 26 Oct 2026, reminded 15 hours ahead: Sunday 09:00 local. Written on Saturday at
        // +2, the row says 07:00 UTC; the clocks go back at 01:00 UTC on Sunday, and at +1
        // 09:00 is 08:00 UTC.
        val monday = java.time.LocalDate.of(2026, 10, 26)
        val sunday = monday.minusDays(1)
        val begin = allDayEvent(1, monday, 15 * 60)
        val saturday = sunday.minusDays(1).atTime(12, 0).atZone(java.time.ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli()
        val row = FakeCalendarProvider.putAlert(
            eventId = 1, alarmTime = utcMillis(sunday, 7), begin = begin, end = utcMillis(monday.plusDays(1)), allDay = true, minutes = 15 * 60,
        )

        // C9 looks again right after the switch, which sends no broadcast...
        Reminders.refresh(context, saturday)
        val switched = utcMillis(sunday, 1) + 1
        assertEquals(switched, alarmAt())

        // ...and moves the row then.
        Reminders.refresh(context, switched)
        assertEquals(utcMillis(sunday, 8), FakeCalendarProvider.alerts.getValue(row).getAsLong(CalendarAlerts.ALARM_TIME))
        assertEquals(utcMillis(sunday, 8), alarmAt())

        // The provider's own alarm for the old time: nothing rings.
        Reminders.refresh(context, utcMillis(sunday, 7))
        assertFalse(shown(1, begin))

        Reminders.refresh(context, utcMillis(sunday, 8))
        assertTrue(shown(1, begin))
        assertEquals(CalendarAlerts.STATE_FIRED, rowsFor(1).single().getAsInteger(CalendarAlerts.STATE))
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test
    fun `an all-day reminder written before the clocks went forward rings at its local time, not an hour late`() = inZone("Europe/Zurich") {
        // Mon 29 Mar 2027, reminded 15 hours ahead: Sunday 09:00 local. Written on Saturday at
        // +1, the row says 08:00 UTC; the clocks go forward at 01:00 UTC on Sunday, and at +2
        // 09:00 is 07:00 UTC — before the old time, so only a pass right after the switch can
        // still ring it on time.
        val monday = java.time.LocalDate.of(2027, 3, 29)
        val sunday = monday.minusDays(1)
        val begin = allDayEvent(1, monday, 15 * 60)
        val saturday = sunday.minusDays(1).atTime(12, 0).atZone(java.time.ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli()
        val row = FakeCalendarProvider.putAlert(
            eventId = 1, alarmTime = utcMillis(sunday, 8), begin = begin, end = utcMillis(monday.plusDays(1)), allDay = true, minutes = 15 * 60,
        )

        Reminders.refresh(context, saturday)
        val switched = utcMillis(sunday, 1) + 1
        assertEquals(switched, alarmAt())

        Reminders.refresh(context, switched)
        assertEquals(utcMillis(sunday, 7), FakeCalendarProvider.alerts.getValue(row).getAsLong(CalendarAlerts.ALARM_TIME))
        assertEquals(utcMillis(sunday, 7), alarmAt())
        assertFalse(shown(1, begin))

        Reminders.refresh(context, utcMillis(sunday, 7))
        assertTrue(shown(1, begin))
        assertEquals(CalendarAlerts.STATE_FIRED, rowsFor(1).single().getAsInteger(CalendarAlerts.STATE))
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }
}
