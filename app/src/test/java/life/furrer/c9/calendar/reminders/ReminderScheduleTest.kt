package life.furrer.c9.calendar.reminders

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ReminderSchedule]'s timing rules — pure logic, no provider. */
class ReminderScheduleTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val minute = 60_000L
    private val now = LocalDate.of(2026, 10, 3).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun instance(eventId: Long, begin: Long, end: Long = begin + 60 * minute, allDay: Boolean = false, status: Int = Attendees.ATTENDEE_STATUS_NONE) =
        UpcomingInstance(eventId, begin, end, allDay, status)

    private fun plan(
        instances: List<UpcomingInstance>,
        minutes: Map<Long, List<Int>>,
        existing: List<ExistingAlert> = emptyList(),
        pending: List<Long> = emptyList(),
    ) = ReminderSchedule.plan(instances, minutes, existing, pending, now, zone)

    @Test
    fun `the next alarm is the earliest reminder still ahead`() {
        val times = plan(
            listOf(instance(1, now + 30 * minute), instance(2, now + 20 * minute)),
            mapOf(1L to listOf(10, 25), 2L to listOf(10)),
        )
        assertTrue(times.due.isEmpty())
        assertEquals(now + 5 * minute, times.nextAlarm) // event 1's 25-minute reminder
    }

    @Test
    fun `with nothing coming up it looks again a day later`() {
        assertEquals(now + ReminderSchedule.RECHECK_MS, plan(emptyList(), emptyMap()).nextAlarm)
    }

    @Test
    fun `a reminder that came due without a row is due, once per instance and lead time`() {
        val begin = now + 10 * minute
        val times = plan(listOf(instance(1, begin)), mapOf(1L to listOf(10, 10)))
        assertEquals(listOf(DueReminder(1, begin, begin + 60 * minute, now, 10)), times.due)
    }

    @Test
    fun `a reminder that already has a row, in any state, isn't due again`() {
        val begin = now + 10 * minute
        val times = plan(listOf(instance(1, begin)), mapOf(1L to listOf(10)), existing = listOf(ExistingAlert(1, begin, now, 10)))
        assertTrue(times.due.isEmpty())
    }

    @Test
    fun `a reminder whose time had passed when it was created fires while the event hasn't ended`() {
        // Created at 10:00 for a 10:05 event with a 35-minute reminder: due since 9:30.
        val late = instance(1, now + 5 * minute)
        assertEquals(1, plan(listOf(late), mapOf(1L to listOf(35))).due.size)
        // ...but not from further back than the provider's own two-hour slack...
        val tooLate = instance(2, now + 5 * minute)
        assertTrue(plan(listOf(tooLate), mapOf(2L to listOf(3 * 60))).due.isEmpty())
        // ...nor for an instance that's already over.
        val over = instance(3, now - 50 * minute, end = now - minute)
        assertTrue(plan(listOf(over), mapOf(3L to listOf(10))).due.isEmpty())
    }

    @Test
    fun `declined invitations are left out`() {
        val times = plan(
            listOf(instance(1, now + 10 * minute, status = Attendees.ATTENDEE_STATUS_DECLINED)),
            mapOf(1L to listOf(10, 5)),
        )
        assertTrue(times.due.isEmpty())
        assertEquals(now + ReminderSchedule.RECHECK_MS, times.nextAlarm)
    }

    @Test
    fun `negative lead times count literally, like the provider takes them`() {
        // -1 (Reminders.MINUTES_DEFAULT): begin + 1 minute, as the provider computes it.
        assertEquals(now + 11 * minute, plan(listOf(instance(2, now + 10 * minute)), mapOf(2L to listOf(-1))).nextAlarm)
        // A started meeting's "5 minutes in" reminder is due now.
        val started = instance(3, now - 5 * minute)
        assertEquals(listOf(DueReminder(3, started.begin, started.end, now, -5)), plan(listOf(started), mapOf(3L to listOf(-5))).due)
    }

    @Test
    fun `an all-day 9 o'clock on the day reminder rings at 9 local`() {
        val day = LocalDate.of(2026, 10, 4)
        fun utc(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDay = instance(1, utc(day), end = utc(day.plusDays(1)), allDay = true)

        val times = plan(listOf(allDay), mapOf(1L to listOf(-9 * 60)))

        assertEquals(day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), times.nextAlarm)
    }

    @Test
    fun `every instance of a recurring event counts`() {
        val first = now + 10 * minute
        val second = first + 24 * 60 * minute
        val times = plan(listOf(instance(1, first), instance(1, second)), mapOf(1L to listOf(10, 30)))
        // Today's instance: both reminders due (the 30-minute one 20 minutes late, inside the
        // slack); tomorrow's: the 30-minute one is the next alarm.
        assertEquals(listOf(now - 20 * minute, now), times.due.map { it.alarmTime })
        assertTrue(times.due.all { it.begin == first })
        assertEquals(second - 30 * minute, times.nextAlarm)
    }

    @Test
    fun `an all-day reminder counts from local midnight, as the provider computes it`() {
        val day = LocalDate.of(2026, 10, 4)
        val utcMidnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDay = instance(1, utcMidnight, end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)

        val times = plan(listOf(allDay), mapOf(1L to listOf(15)))

        assertEquals(day.atStartOfDay(zone).toInstant().toEpochMilli() - 15 * minute, times.nextAlarm)
    }

    @Test
    fun `an all-day reminder comes due at its local time, and today's midnight one is long past`() {
        val today = LocalDate.of(2026, 10, 3)
        fun utc(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Tomorrow, reminded 14 hours ahead: today 10:00 local, i.e. now.
        val tomorrow = instance(1, utc(today.plusDays(1)), end = utc(today.plusDays(2)), allDay = true)
        assertEquals(listOf(now), plan(listOf(tomorrow), mapOf(1L to listOf(14 * 60))).due.map { it.alarmTime })
        // Today, "at the start": local midnight, ten hours ago — beyond the slack.
        val todays = instance(2, utc(today), end = utc(today.plusDays(1)), allDay = true)
        assertTrue(plan(listOf(todays), mapOf(2L to listOf(0))).due.isEmpty())
    }

    @Test
    fun `a pending snooze earlier than any reminder sets the next alarm, a past one doesn't`() {
        val reminders = mapOf(1L to listOf(10))
        val instances = listOf(instance(1, now + 30 * minute))
        assertEquals(now + 5 * minute, plan(instances, reminders, pending = listOf(now + 5 * minute)).nextAlarm)
        assertEquals(now + 20 * minute, plan(instances, reminders, pending = listOf(now - minute, now + 25 * minute)).nextAlarm)
    }

    @Test
    fun `an all-day reminder that has a row from before an offset change isn't due again`() {
        val day = LocalDate.of(2026, 10, 4)
        fun utc(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDay = instance(1, utc(day), end = utc(day.plusDays(1)), allDay = true)
        val reminded = mapOf(1L to listOf(14 * 60)) // due right now (10:00 the day before)
        // Its row was written an hour earlier in local terms, before the clocks went back (or
        // the phone crossed a zone) — the same reminder.
        val before = listOf(ExistingAlert(1, allDay.begin, now - 60 * minute, 14 * 60))
        assertTrue(plan(listOf(allDay), reminded, existing = before).due.isEmpty())
        // A row at any old millisecond (as a snooze lands) or with another lead time doesn't count.
        val snooze = listOf(ExistingAlert(1, allDay.begin, now - 7 * minute - 1234, 14 * 60), ExistingAlert(1, allDay.begin, now - 60 * minute, 30))
        assertEquals(1, plan(listOf(allDay), reminded, existing = snooze).due.size)
    }

    // Tomorrow (4 Oct), all day, reminded 11h38 ahead: 12:22 today in Zurich (+2), i.e. 10:22
    // UTC — and in London (+1) 12:22 local too, which is 11:22 UTC, an hour later.
    private val holiday = LocalDate.of(2026, 10, 4).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val lead = 11 * 60 + 38
    private val zurichTime = holiday - 2 * 60 * minute - lead * minute
    private val london = ZoneId.of("Europe/London")

    private fun scheduled(id: Long, alarmTime: Long, minutes: Int? = lead, allDay: Boolean = true, state: Int = CalendarAlerts.STATE_SCHEDULED) =
        ExistingAlert(1, holiday, alarmTime, minutes, id = id, state = state, allDay = allDay)

    @Test
    fun `an all-day row written at another offset moves to today's alarm time`() {
        val moves = ReminderSchedule.realign(listOf(scheduled(5, zurichTime)), now, london)
        assertEquals(listOf(Realignment(5, zurichTime, zurichTime + 60 * minute)), moves)
        // Where it belongs already, nothing to do.
        assertTrue(ReminderSchedule.realign(listOf(scheduled(5, zurichTime)), now, zone).isEmpty())
    }

    @Test
    fun `fired, dismissed, timed and snoozed rows stay where they are`() {
        val rows = listOf(
            scheduled(1, zurichTime, state = CalendarAlerts.STATE_FIRED),
            scheduled(2, zurichTime - minute, state = CalendarAlerts.STATE_DISMISSED),
            scheduled(3, zurichTime, allDay = false),
            scheduled(4, now + 5 * minute + 1234, minutes = 0), // a snooze: at no offset's time
            scheduled(6, zurichTime, minutes = null),
        )
        assertTrue(ReminderSchedule.realign(rows, now, london).isEmpty())
    }

    @Test
    fun `a stale row whose reminder already has a row at today's time is deleted`() {
        // The provider wrote its own row after the change (or the reminder rang there already).
        val rows = listOf(scheduled(5, zurichTime), scheduled(6, zurichTime + 60 * minute, state = CalendarAlerts.STATE_FIRED))
        assertEquals(listOf(Realignment(5, zurichTime, null)), ReminderSchedule.realign(rows, now, london))
        // Two stale rows of one reminder (two zones ago, and one): one moves, the other goes.
        val twice = listOf(scheduled(5, zurichTime), scheduled(7, zurichTime - 60 * minute))
        assertEquals(
            listOf(Realignment(5, zurichTime, zurichTime + 60 * minute), Realignment(7, zurichTime - 60 * minute, null)),
            ReminderSchedule.realign(twice, now, london),
        )
        // Another reminder's row at that time doesn't count: the move is tried (and the table
        // decides).
        val other = listOf(scheduled(5, zurichTime), ExistingAlert(1, holiday, zurichTime + 60 * minute, lead - 60, id = 8, state = CalendarAlerts.STATE_FIRED, allDay = true))
        assertEquals(listOf(Realignment(5, zurichTime, zurichTime + 60 * minute)), ReminderSchedule.realign(other, now, london))
    }

    @Test
    fun `across the switch to winter time a row from before moves an hour later`() {
        // Mon 26 Oct 2026, all day, reminded 15 hours ahead; the row was written on Saturday at
        // +2: Sunday 07:00 UTC (09:00 summer time). Clocks go back Sunday 01:00 UTC; at +1 the
        // reminder is 08:00 UTC, still 09:00 local.
        val monday = LocalDate.of(2026, 10, 26).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val saturday = LocalDate.of(2026, 10, 24).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val written = ReminderSchedule.alarmTime(UpcomingInstance(1, monday, monday + 24 * 60 * minute, true), 15 * 60, saturday, zone)
        val sundayDue = LocalDate.of(2026, 10, 25).atTime(7, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(sundayDue, written)
        val row = ExistingAlert(1, monday, written, 15 * 60, id = 3, allDay = true)

        assertTrue(ReminderSchedule.realign(listOf(row), saturday, zone).isEmpty())
        assertEquals(listOf(Realignment(3, written, sundayDue + 60 * minute)), ReminderSchedule.realign(listOf(row), sundayDue, zone))
    }

    @Test
    fun `it looks again right after a DST switch that comes before the next reminder`() {
        // Mon 29 Mar 2027, all day, reminded 15 hours ahead: on Saturday (+1) that's Sunday
        // 08:00 UTC — but at 01:00 UTC the clocks go forward, and 09:00 becomes 07:00 UTC.
        val monday = LocalDate.of(2027, 3, 29).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val saturday = LocalDate.of(2027, 3, 27).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val switch = LocalDate.of(2027, 3, 28).atTime(1, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val times = ReminderSchedule.plan(
            listOf(UpcomingInstance(1, monday, monday + 24 * 60 * minute, true)), mapOf(1L to listOf(15 * 60)), emptyList(), emptyList(), saturday, zone,
        )
        assertEquals(switch + 1, times.nextAlarm)
        // Just after it, today's offset gives the right time.
        val after = ReminderSchedule.plan(
            listOf(UpcomingInstance(1, monday, monday + 24 * 60 * minute, true)), mapOf(1L to listOf(15 * 60)), emptyList(), emptyList(), switch + 1, zone,
        )
        assertEquals(switch + 6 * 60 * minute, after.nextAlarm)
        // A reminder before the switch still comes first; a zone without DST never has one.
        assertEquals(saturday + 5 * minute, ReminderSchedule.plan(emptyList(), emptyMap(), emptyList(), listOf(saturday + 5 * minute), saturday, zone).nextAlarm)
        assertEquals(saturday + ReminderSchedule.RECHECK_MS, ReminderSchedule.plan(emptyList(), emptyMap(), emptyList(), emptyList(), saturday, ZoneId.of("Asia/Kolkata")).nextAlarm)
    }

    @Test
    fun `a row more than a day overdue is left to the planner`() {
        // Written in Zurich for 27 hours before today's 12:22 — yesterday 09:22 UTC.
        val old = scheduled(5, zurichTime - 27 * 60 * minute, minutes = lead + 27 * 60)
        assertTrue(ReminderSchedule.realign(listOf(old), now - 2 * 60 * minute, london).isNotEmpty())
        assertTrue(ReminderSchedule.realign(listOf(old), now, london).isEmpty())
    }

    @Test
    fun `a timed reminder is only matched exactly`() {
        val begin = now + 10 * minute
        val shifted = listOf(ExistingAlert(1, begin, now - 60 * minute, 10))
        assertEquals(1, plan(listOf(instance(1, begin)), mapOf(1L to listOf(10)), existing = shifted).due.size)
    }
}
