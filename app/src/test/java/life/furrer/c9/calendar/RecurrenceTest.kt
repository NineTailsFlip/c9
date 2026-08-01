package life.furrer.c9.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure string logic — no Android dependency, plain JUnit. */
class RecurrenceTest {

    @Test
    fun `NONE builds no rule`() {
        assertNull(Recurrence.buildRule(RecurrenceFrequency.NONE, 1))
    }

    @Test
    fun `an interval of 1 omits INTERVAL entirely`() {
        assertEquals("FREQ=WEEKLY", Recurrence.buildRule(RecurrenceFrequency.WEEKLY, 1))
    }

    @Test
    fun `an interval greater than 1 includes INTERVAL`() {
        assertEquals("FREQ=MONTHLY;INTERVAL=6", Recurrence.buildRule(RecurrenceFrequency.MONTHLY, 6))
    }

    @Test
    fun `describe renders singular and plural units`() {
        assertEquals("Every day", Recurrence.describe("FREQ=DAILY"))
        assertEquals("Every week", Recurrence.describe("FREQ=WEEKLY;INTERVAL=1"))
        assertEquals("Every 6 months", Recurrence.describe("FREQ=MONTHLY;INTERVAL=6"))
        assertEquals("Every 2 years", Recurrence.describe("FREQ=YEARLY;INTERVAL=2"))
    }

    @Test
    fun `describe falls back for an unrecognized or malformed rule`() {
        assertEquals("Repeats", Recurrence.describe("FREQ=SECONDLY"))
        assertEquals("Repeats", Recurrence.describe("BYDAY=MO"))
    }

    @Test
    fun `parseFrequency and parseInterval round trip with buildRule`() {
        val rule = Recurrence.buildRule(RecurrenceFrequency.YEARLY, 3)!!
        assertEquals(RecurrenceFrequency.YEARLY, Recurrence.parseFrequency(rule))
        assertEquals(3, Recurrence.parseInterval(rule))
    }

    @Test
    fun `a null rrule parses as NONE with an implicit interval of 1`() {
        assertEquals(RecurrenceFrequency.NONE, Recurrence.parseFrequency(null))
        assertEquals(1, Recurrence.parseInterval(null))
    }

    @Test
    fun `a rule with no explicit INTERVAL parses as 1`() {
        assertEquals(1, Recurrence.parseInterval("FREQ=DAILY"))
    }

    // --- withUntil (§6.1.3 "this and following") --------------------------------------------

    @Test
    fun `withUntil sets a UTC date-time one second before the split instance for a timed event`() {
        // 2026-08-06T07:00:00Z in epoch millis.
        val splitMillis = 1785999600000L
        val result = Recurrence.withUntil("FREQ=DAILY", splitMillis, allDay = false)
        assertEquals("FREQ=DAILY;UNTIL=20260806T065959Z", result)
    }

    @Test
    fun `withUntil sets a bare date for an all-day event`() {
        // 2026-08-06T00:00:00Z (UTC midnight, as all-day values are stored per §6.1.1) — the
        // split instance is the start of Aug 6, so the truncated series must end on Aug 5.
        val splitMillis = 1785974400000L
        val result = Recurrence.withUntil("FREQ=WEEKLY;INTERVAL=2", splitMillis, allDay = true)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;UNTIL=20260805", result)
    }

    @Test
    fun `withUntil drops COUNT since it is mutually exclusive with UNTIL`() {
        val result = Recurrence.withUntil("FREQ=DAILY;COUNT=10", 1785999600000L, allDay = false)
        assertEquals("FREQ=DAILY;UNTIL=20260806T065959Z", result)
    }

    @Test
    fun `withUntil preserves INTERVAL alongside the new UNTIL`() {
        val result = Recurrence.withUntil("FREQ=MONTHLY;INTERVAL=3", 1785999600000L, allDay = false)
        assertEquals("FREQ=MONTHLY;INTERVAL=3;UNTIL=20260806T065959Z", result)
    }

    @Test
    fun `withUntil returns the original rule verbatim if it has no FREQ`() {
        assertEquals("BYDAY=MO", Recurrence.withUntil("BYDAY=MO", 1785999600000L, allDay = false))
    }
}
