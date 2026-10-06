package life.furrer.c9.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderLeadTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    private fun millis(at: LocalDateTime) = at.atZone(zurich).toInstant().toEpochMilli()

    @Test
    fun `short labels name every non-zero part, largest first`() {
        assertEquals("default", ReminderLead.label(-1))
        assertEquals("at time of event", ReminderLead.label(0))
        assertEquals("10 min", ReminderLead.label(10))
        assertEquals("1h", ReminderLead.label(60))
        assertEquals("1h 30 min", ReminderLead.label(90))
        assertEquals("1 day", ReminderLead.label(1440))
        assertEquals("2 days 2h 30 min", ReminderLead.label(2 * 1440 + 150))
        assertEquals("1 week", ReminderLead.label(10_080))
        assertEquals("4 weeks", ReminderLead.label(40_320))
        assertEquals("1 week 1 day 5 min", ReminderLead.label(10_080 + 1440 + 5))
    }

    @Test
    fun `long labels spell the units out`() {
        assertEquals("1 minute", ReminderLead.label(1, long = true))
        assertEquals("2 hours", ReminderLead.label(120, long = true))
        assertEquals("1 day 1 hour 30 minutes", ReminderLead.label(1440 + 90, long = true))
        assertEquals("2 weeks", ReminderLead.label(20_160, long = true))
    }

    @Test
    fun `the presets end with a week and 4 weeks, Google's maximum`() {
        assertEquals(listOf(0, 5, 10, 15, 30, 60, 1440, 10_080, 40_320), ReminderLead.PRESETS)
    }

    @Test
    fun `a timed event's lead time is the gap to its start`() {
        val start = millis(LocalDateTime.of(2026, 10, 8, 14, 30))
        assertEquals(0, ReminderLead.leadMinutes(start, false, LocalDateTime.of(2026, 10, 8, 14, 30), zurich))
        assertEquals(20 * 60 + 30, ReminderLead.leadMinutes(start, false, LocalDateTime.of(2026, 10, 7, 18, 0), zurich))
        assertNull(ReminderLead.leadMinutes(start, false, LocalDateTime.of(2026, 10, 8, 14, 31), zurich))
    }

    @Test
    fun `an all-day event's lead time counts from local midnight of its day`() {
        // All-day bounds are stored at UTC midnight.
        val start = LocalDate.of(2026, 10, 8).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(6 * 60, ReminderLead.leadMinutes(start, true, LocalDateTime.of(2026, 10, 7, 18, 0), zurich))
        assertNull(ReminderLead.leadMinutes(start, true, LocalDateTime.of(2026, 10, 8, 9, 0), zurich))
        assertEquals(LocalDateTime.of(2026, 10, 7, 18, 0), ReminderLead.ringsAt(start, true, 6 * 60, zurich))
    }

    @Test
    fun `lead time and ring time are each other's inverse, across a DST change`() {
        // Zurich leaves summer time in the night of 25 Oct 2026: 2 days ahead is 49 hours here.
        val start = millis(LocalDateTime.of(2026, 10, 26, 9, 0))
        val rings = LocalDateTime.of(2026, 10, 24, 9, 0)
        val lead = ReminderLead.leadMinutes(start, false, rings, zurich)!!
        assertEquals(49 * 60, lead)
        assertEquals(rings, ReminderLead.ringsAt(start, false, lead, zurich))
    }
}
