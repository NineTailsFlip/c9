package life.furrer.c9.calendar.reminders

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules [ReminderPlanner] applies to the provider's due alerts — pure logic, no provider. */
class ReminderPlannerTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val minute = 60_000L
    private val now = LocalDate.of(2026, 10, 3).atTime(9, 50).atZone(zone).toInstant().toEpochMilli()

    private fun alert(
        id: Long,
        eventId: Long = id,
        state: Int = CalendarAlerts.STATE_SCHEDULED,
        alarmTime: Long = now,
        begin: Long = now + 10 * minute,
        end: Long = begin + 60 * minute,
        allDay: Boolean = false,
        selfAttendeeStatus: Int = Attendees.ATTENDEE_STATUS_NONE,
        visible: Boolean = true,
        minutes: Int = 10,
    ) = AlertRow(
        id = id,
        eventId = eventId,
        state = state,
        title = "Event $eventId",
        location = null,
        allDay = allDay,
        alarmTime = alarmTime,
        begin = begin,
        end = end,
        selfAttendeeStatus = selfAttendeeStatus,
        visible = visible,
        minutes = minutes,
    )

    @Test
    fun `a due scheduled alert fires and is notified with sound`() {
        val plan = ReminderPlanner.plan(listOf(alert(1)), now, zone)
        assertEquals(mapOf(1L to CalendarAlerts.STATE_FIRED), plan.stateChanges)
        assertEquals(1, plan.notices.size)
        assertTrue(plan.notices.single().loud)
        assertTrue(plan.cancels.isEmpty())
    }

    @Test
    fun `an already fired alert is re-posted quietly and its state left alone`() {
        val plan = ReminderPlanner.plan(listOf(alert(1, state = CalendarAlerts.STATE_FIRED)), now, zone)
        assertTrue(plan.stateChanges.isEmpty())
        assertFalse(plan.notices.single().loud)
    }

    @Test
    fun `an alert not yet due, or already dismissed, is left alone`() {
        val plan = ReminderPlanner.plan(
            listOf(alert(1, alarmTime = now + minute), alert(2, state = CalendarAlerts.STATE_DISMISSED)),
            now,
            zone,
        )
        assertTrue(plan.stateChanges.isEmpty())
        assertTrue(plan.notices.isEmpty())
    }

    @Test
    fun `two reminders of one instance make one notification, loud when the second just fired`() {
        val begin = now + 5 * minute
        val plan = ReminderPlanner.plan(
            listOf(
                alert(1, eventId = 7, state = CalendarAlerts.STATE_FIRED, alarmTime = now - 10 * minute, begin = begin),
                alert(2, eventId = 7, alarmTime = now, begin = begin),
            ),
            now,
            zone,
        )
        assertEquals(mapOf(2L to CalendarAlerts.STATE_FIRED), plan.stateChanges)
        val notice = plan.notices.single()
        assertEquals(InstanceKey(7, begin), notice.key)
        assertTrue(notice.loud)
    }

    @Test
    fun `two instances of one recurring event are two notifications`() {
        val plan = ReminderPlanner.plan(
            listOf(
                alert(1, eventId = 7, state = CalendarAlerts.STATE_FIRED, begin = now - 24 * 60 * minute + 10 * minute),
                alert(2, eventId = 7, begin = now + 10 * minute),
            ),
            now,
            zone,
        )
        assertEquals(2, plan.notices.size)
    }

    @Test
    fun `a backlog that fires at once rings only for the most recently due reminder`() {
        val plan = ReminderPlanner.plan(
            listOf(
                alert(1, alarmTime = now - 30 * minute),
                alert(2, alarmTime = now - 2 * minute),
                alert(3, alarmTime = now - 20 * minute),
            ),
            now,
            zone,
        )
        assertEquals(3, plan.stateChanges.count { it.value == CalendarAlerts.STATE_FIRED })
        assertEquals(listOf(2L), plan.notices.filter { it.loud }.map { it.key.eventId })
    }

    @Test
    fun `settle only rings for an alert that really fired`() {
        val plan = ReminderPlanner.plan(
            listOf(alert(1, alarmTime = now - 2 * minute), alert(2, alarmTime = now - minute)),
            now,
            zone,
        )
        assertEquals(listOf(2L), plan.notices.filter { it.loud }.map { it.key.eventId })

        // Alert 2's guarded update hit no row (fired or deleted by someone else meanwhile).
        val settled = ReminderPlanner.settle(plan.notices, fired = setOf(1L))
        assertEquals(listOf(1L), settled.filter { it.loud }.map { it.key.eventId })
        assertTrue(ReminderPlanner.settle(plan.notices, fired = emptySet()).none { it.loud })
    }

    @Test
    fun `a scheduled alert for an instance that already ended is dismissed without a notification`() {
        val plan = ReminderPlanner.plan(
            listOf(alert(1, alarmTime = now - 3 * 60 * minute, begin = now - 2 * 60 * minute, end = now - 60 * minute)),
            now,
            zone,
        )
        assertEquals(mapOf(1L to CalendarAlerts.STATE_DISMISSED), plan.stateChanges)
        assertTrue(plan.notices.isEmpty())
    }

    @Test
    fun `a snooze still fires after the event it's for has ended`() {
        // Meeting 10:00-10:30, snoozed for 30 minutes at 10:20: due at 10:50.
        val begin = now - 50 * minute
        val plan = ReminderPlanner.plan(
            listOf(alert(1, minutes = 0, alarmTime = now, begin = begin, end = begin + 30 * minute)),
            now,
            zone,
        )
        assertEquals(mapOf(1L to CalendarAlerts.STATE_FIRED), plan.stateChanges)
        assertTrue(plan.notices.single().loud)
    }

    @Test
    fun `a snooze more than a day late is still dropped`() {
        val plan = ReminderPlanner.plan(
            listOf(alert(1, minutes = 0, alarmTime = now - 25 * 60 * minute, begin = now - 26 * 60 * minute, end = now - 25 * 60 * minute)),
            now,
            zone,
        )
        assertEquals(mapOf(1L to CalendarAlerts.STATE_DISMISSED), plan.stateChanges)
    }

    @Test
    fun `an at-time-of-event alert for a zero-length event still shows just after it`() {
        val begin = now - minute
        val plan = ReminderPlanner.plan(listOf(alert(1, alarmTime = begin, begin = begin, end = begin)), now, zone)
        assertEquals(mapOf(1L to CalendarAlerts.STATE_FIRED), plan.stateChanges)
        assertEquals(1, plan.notices.size)
    }

    @Test
    fun `a scheduled alert more than a day late is dismissed even if the event is still running`() {
        val plan = ReminderPlanner.plan(
            listOf(alert(1, alarmTime = now - 25 * 60 * minute, begin = now - 24 * 60 * minute, end = now + 60 * minute)),
            now,
            zone,
        )
        assertEquals(mapOf(1L to CalendarAlerts.STATE_DISMISSED), plan.stateChanges)
        assertTrue(plan.notices.isEmpty())
    }

    @Test
    fun `a fired alert is retired a day after its instance ended, and its notification taken down`() {
        val old = alert(
            1,
            state = CalendarAlerts.STATE_FIRED,
            alarmTime = now - 27 * 60 * minute,
            begin = now - 26 * 60 * minute,
            end = now - 25 * 60 * minute,
        )
        val plan = ReminderPlanner.plan(listOf(old), now, zone)
        assertEquals(mapOf(1L to CalendarAlerts.STATE_DISMISSED), plan.stateChanges)
        assertEquals(setOf(InstanceKey(1, old.begin)), plan.cancels)
        assertTrue(plan.notices.isEmpty())
    }

    @Test
    fun `a fired alert for a meeting that ended an hour ago keeps showing`() {
        val plan = ReminderPlanner.plan(
            listOf(alert(1, state = CalendarAlerts.STATE_FIRED, alarmTime = now - 3 * 60 * minute, begin = now - 2 * 60 * minute, end = now - 60 * minute)),
            now,
            zone,
        )
        assertTrue(plan.stateChanges.isEmpty())
        assertEquals(1, plan.notices.size)
    }

    @Test
    fun `declined invitations and invisible calendars are dismissed`() {
        val plan = ReminderPlanner.plan(
            listOf(
                alert(1, selfAttendeeStatus = Attendees.ATTENDEE_STATUS_DECLINED),
                alert(2, state = CalendarAlerts.STATE_FIRED, visible = false),
            ),
            now,
            zone,
        )
        assertEquals(mapOf(1L to CalendarAlerts.STATE_DISMISSED, 2L to CalendarAlerts.STATE_DISMISSED), plan.stateChanges)
        assertTrue(plan.notices.isEmpty())
        assertEquals(2, plan.cancels.size)
    }

    @Test
    fun `an all-day event runs until local midnight of its end date`() {
        // Today, all day: UTC-midnight bounds 3 Oct -> 4 Oct; the reminder fired the evening before.
        val begin = LocalDate.of(2026, 10, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 10, 4).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val row = alert(1, alarmTime = now - 12 * 60 * minute, begin = begin, end = end, allDay = true)
        assertEquals(
            LocalDate.of(2026, 10, 4).atStartOfDay(zone).toInstant().toEpochMilli(),
            ReminderPlanner.endMillis(row, zone),
        )
        val plan = ReminderPlanner.plan(listOf(row), now, zone)
        assertEquals(mapOf(1L to CalendarAlerts.STATE_FIRED), plan.stateChanges)
    }

    @Test
    fun `an all-day reminder after the start (negative minutes) fires during the day`() {
        val today = LocalDate.of(2026, 10, 3)
        val row = alert(
            1,
            alarmTime = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            begin = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            end = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            allDay = true,
            minutes = -9 * 60,
        )
        assertEquals(mapOf(1L to CalendarAlerts.STATE_FIRED), ReminderPlanner.plan(listOf(row), now, zone).stateChanges)
    }

    // Tomorrow (4 Oct), all day, reminded 15 hours ahead: 09:00 today in Zurich (+2), 07:00 UTC.
    private val tomorrow = LocalDate.of(2026, 10, 4)
    private val allDayBegin = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val allDayEnd = tomorrow.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val zurichAlarm = allDayBegin - 2 * 60 * minute - 15 * 60 * minute

    private fun allDayAlert(id: Long, state: Int, alarmTime: Long, minutes: Int = 15 * 60, begin: Long = allDayBegin, end: Long = allDayEnd) =
        alert(id, eventId = 7, state = state, alarmTime = alarmTime, begin = begin, end = end, allDay = true, minutes = minutes)

    @Test
    fun `the provider's repeat of an all-day reminder that already rang, from after a zone change, is put away quietly`() {
        // Rang at 09:00 Zurich; then the phone went to Helsinki (+3), and the provider wrote its
        // own row for the same reminder at the new offset, an hour earlier — and so due too.
        val plan = ReminderPlanner.plan(
            listOf(
                allDayAlert(1, CalendarAlerts.STATE_FIRED, zurichAlarm),
                allDayAlert(2, CalendarAlerts.STATE_SCHEDULED, zurichAlarm - 60 * minute),
            ),
            now,
            zone,
        )
        assertEquals(mapOf(2L to CalendarAlerts.STATE_DISMISSED), plan.stateChanges)
        assertFalse(plan.notices.single().loud) // the fired one, re-posted as ever
        assertTrue(plan.cancels.isEmpty())
    }

    @Test
    fun `the same goes for a repeat of one that was dismissed already`() {
        val plan = ReminderPlanner.plan(
            listOf(
                allDayAlert(1, CalendarAlerts.STATE_DISMISSED, zurichAlarm),
                allDayAlert(2, CalendarAlerts.STATE_SCHEDULED, zurichAlarm - 60 * minute),
            ),
            now,
            zone,
        )
        assertEquals(mapOf(2L to CalendarAlerts.STATE_DISMISSED), plan.stateChanges)
        assertTrue(plan.notices.isEmpty())
    }

    @Test
    fun `a snooze of an all-day reminder at the start of the day is no repeat of it`() {
        // Today, "at the start" (minutes 0): rang at local midnight; snoozed, the snooze is due now.
        val today = LocalDate.of(2026, 10, 3)
        val begin = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val plan = ReminderPlanner.plan(
            listOf(
                allDayAlert(1, CalendarAlerts.STATE_DISMISSED, today.atStartOfDay(zone).toInstant().toEpochMilli(), minutes = 0, begin = begin, end = end),
                allDayAlert(2, CalendarAlerts.STATE_SCHEDULED, now - 1234, minutes = 0, begin = begin, end = end),
            ),
            now,
            zone,
        )
        assertEquals(mapOf(2L to CalendarAlerts.STATE_FIRED), plan.stateChanges)
        assertTrue(plan.notices.single().loud)
    }

    @Test
    fun `another lead time, or a timed event, is no repeat`() {
        val otherLead = ReminderPlanner.plan(
            listOf(
                allDayAlert(1, CalendarAlerts.STATE_FIRED, zurichAlarm),
                allDayAlert(2, CalendarAlerts.STATE_SCHEDULED, zurichAlarm - 60 * minute, minutes = 16 * 60),
            ),
            now,
            zone,
        )
        assertEquals(mapOf(2L to CalendarAlerts.STATE_FIRED), otherLead.stateChanges)

        val timed = ReminderPlanner.plan(
            listOf(
                alert(1, eventId = 7, state = CalendarAlerts.STATE_FIRED, alarmTime = now - 10 * minute),
                alert(2, eventId = 7, alarmTime = now - 70 * minute),
            ),
            now,
            zone,
        )
        assertEquals(mapOf(2L to CalendarAlerts.STATE_FIRED), timed.stateChanges)
        assertTrue(timed.notices.single().loud)
    }
}
