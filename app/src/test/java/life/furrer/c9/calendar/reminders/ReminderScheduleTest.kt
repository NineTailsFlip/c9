package life.furrer.c9.calendar.reminders

import android.provider.CalendarContract.Attendees
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
        existing: Set<AlertKey> = emptySet(),
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
        val times = plan(listOf(instance(1, begin)), mapOf(1L to listOf(10)), existing = setOf(AlertKey(1, begin, now)))
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
    fun `declined invitations and default-minute reminders are left out`() {
        val times = plan(
            listOf(instance(1, now + 10 * minute, status = Attendees.ATTENDEE_STATUS_DECLINED), instance(2, now + 10 * minute)),
            mapOf(1L to listOf(10, 5), 2L to listOf(-1)),
        )
        assertTrue(times.due.isEmpty())
        assertEquals(now + ReminderSchedule.RECHECK_MS, times.nextAlarm)
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
}
