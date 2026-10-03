package life.furrer.c9.calendar

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
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

    // --- describe's detail parts (ticket #2: the editor shows a synced rule as it really is) --

    private fun describeIn(rrule: String) = Recurrence.describe(rrule, ZoneOffset.UTC, Locale.ENGLISH)

    @Test
    fun `describe names plain weekdays, and Mon to Fri as weekdays`() {
        assertEquals("Every 2 weeks on Mon, Wed", describeIn("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"))
        assertEquals("Every week on weekdays", describeIn("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
    }

    @Test
    fun `describe names ordinal weekdays and month days`() {
        assertEquals("Every month on the 1st Mon", describeIn("FREQ=MONTHLY;BYDAY=1MO"))
        assertEquals("Every month on the last Fri", describeIn("FREQ=MONTHLY;BYDAY=-1FR"))
        assertEquals("Every month on day 15", describeIn("FREQ=MONTHLY;BYMONTHDAY=15"))
        assertEquals("Every month on days 1, 15", describeIn("FREQ=MONTHLY;BYMONTHDAY=1,15"))
        assertEquals("Every month on the last day", describeIn("FREQ=MONTHLY;BYMONTHDAY=-1"))
    }

    @Test
    fun `describe includes COUNT and UNTIL in every RFC 5545 form`() {
        assertEquals("Every day, 10 times", describeIn("FREQ=DAILY;COUNT=10"))
        assertEquals("Every week on Mon until 1 Dec 2026", describeIn("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261201T120000Z"))
        assertEquals("Every year until 1 Dec 2026", describeIn("FREQ=YEARLY;UNTIL=20261201"))
        assertEquals("Every day until 1 Dec 2026", describeIn("FREQ=DAILY;UNTIL=20261201T235959"))
    }

    @Test
    fun `describe reads a UTC UNTIL as a date in the given zone`() {
        val result = Recurrence.describe("FREQ=DAILY;UNTIL=20261201T030000Z", ZoneId.of("America/New_York"), Locale.ENGLISH)
        assertEquals("Every day until 30 Nov 2026", result)
    }

    @Test
    fun `describe marks a rule with parts it can't put into words as custom`() {
        assertEquals("Every year (custom)", describeIn("FREQ=YEARLY;BYMONTH=3;BYSETPOS=-1"))
        assertEquals("Every month (custom)", describeIn("FREQ=MONTHLY;BYDAY=-2MO"))
        assertEquals("Every week on Mon", describeIn("FREQ=WEEKLY;WKST=MO;BYDAY=MO"))
    }

    // --- countOf / withCount ("this and following" on a COUNT-limited series) ----------------

    @Test
    fun `countOf reads COUNT and is null without one`() {
        assertEquals(10, Recurrence.countOf("FREQ=DAILY;COUNT=10"))
        assertNull(Recurrence.countOf("FREQ=DAILY;UNTIL=20261201"))
    }

    @Test
    fun `withCount replaces only COUNT and keeps every other part in place`() {
        assertEquals(
            "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=MO,WE",
            Recurrence.withCount("FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=MO,WE", 4),
        )
    }
}
