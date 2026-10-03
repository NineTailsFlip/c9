package life.furrer.c9.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [Recurrence.toSpec] / [Recurrence.build]: what the Repeat screen edits and writes. */
class RepeatSpecTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val tuesday = LocalDate.of(2026, 10, 6) // the 1st Tuesday of its month
    private val lastTuesday = LocalDate.of(2026, 10, 27)
    private val fifthTuesday = LocalDate.of(2026, 3, 31)

    private fun build(spec: RepeatSpec, start: LocalDate = tuesday, allDay: Boolean = false) =
        Recurrence.build(spec, start, allDay, zurich)

    @Test
    fun `plain frequencies build the plain rule`() {
        assertEquals("FREQ=DAILY", build(RepeatSpec(RecurrenceFrequency.DAILY)))
        assertEquals("FREQ=YEARLY", build(RepeatSpec(RecurrenceFrequency.YEARLY)))
    }

    @Test
    fun `an interval and weekdays`() {
        val spec = RepeatSpec(RecurrenceFrequency.WEEKLY, interval = 2, weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", build(spec))
        assertEquals(spec, Recurrence.toSpec("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", tuesday, zurich))
    }

    @Test
    fun `monthly modes are relative to the start date`() {
        assertEquals("FREQ=MONTHLY", build(RepeatSpec(RecurrenceFrequency.MONTHLY)))
        assertEquals("FREQ=MONTHLY;BYDAY=1TU", build(RepeatSpec(RecurrenceFrequency.MONTHLY, monthly = MonthlyMode.NTH_WEEKDAY)))
        assertEquals("FREQ=MONTHLY;BYDAY=-1TU", build(RepeatSpec(RecurrenceFrequency.MONTHLY, monthly = MonthlyMode.LAST_WEEKDAY), lastTuesday))
        assertEquals(MonthlyMode.NTH_WEEKDAY, Recurrence.toSpec("FREQ=MONTHLY;BYDAY=1TU", tuesday, zurich)!!.monthly)
        assertEquals(MonthlyMode.LAST_WEEKDAY, Recurrence.toSpec("FREQ=MONTHLY;BYDAY=-1TU", lastTuesday, zurich)!!.monthly)
    }

    @Test
    fun `a fifth weekday is not offered as an nth one`() {
        assertNull(Recurrence.toSpec("FREQ=MONTHLY;BYDAY=5TU", fifthTuesday, zurich))
    }

    @Test
    fun `a count ends the rule`() {
        val spec = RepeatSpec(RecurrenceFrequency.DAILY, end = RepeatEnd.AfterCount(10))
        assertEquals("FREQ=DAILY;COUNT=10", build(spec))
        assertEquals(spec, Recurrence.toSpec("FREQ=DAILY;COUNT=10", tuesday, zurich))
    }

    @Test
    fun `a timed event's until is the end of that day as UTC`() {
        val spec = RepeatSpec(RecurrenceFrequency.WEEKLY, end = RepeatEnd.OnDate(LocalDate.of(2026, 12, 1)))
        // 23:59:59 CET is 22:59:59Z.
        assertEquals("FREQ=WEEKLY;UNTIL=20261201T225959Z", build(spec))
        assertEquals(spec, Recurrence.toSpec("FREQ=WEEKLY;UNTIL=20261201T225959Z", tuesday, zurich))
    }

    @Test
    fun `an all-day event's until is a bare date`() {
        val spec = RepeatSpec(RecurrenceFrequency.WEEKLY, end = RepeatEnd.OnDate(LocalDate.of(2026, 12, 1)))
        assertEquals("FREQ=WEEKLY;UNTIL=20261201", build(spec, allDay = true))
        assertEquals(spec, Recurrence.toSpec("FREQ=WEEKLY;UNTIL=20261201", tuesday, zurich))
    }

    @Test
    fun `rules with more than the screen can show are not editable`() {
        assertNull(Recurrence.toSpec("FREQ=YEARLY;BYMONTH=3", tuesday, zurich))
        assertNull(Recurrence.toSpec("FREQ=MONTHLY;BYMONTHDAY=15", tuesday, zurich))
        assertNull(Recurrence.toSpec("FREQ=MONTHLY;BYDAY=2MO", tuesday, zurich))
        assertNull(Recurrence.toSpec("FREQ=DAILY;COUNT=3;UNTIL=20261201", tuesday, zurich))
        assertNull(Recurrence.toSpec("FREQ=HOURLY", tuesday, zurich))
    }

    @Test
    fun `what the screen builds reads back and is described`() {
        val rule = build(RepeatSpec(RecurrenceFrequency.WEEKLY, interval = 2, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), end = RepeatEnd.AfterCount(6)))
        assertEquals("Every 2 weeks on Mon, Wed, 6 times", Recurrence.describe(rule, zurich, java.util.Locale.ENGLISH))
    }
}
