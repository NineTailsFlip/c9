package life.furrer.c9.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure java.time/java.text — no Android dependency, plain JUnit. */
class DateFmtTest {

    // The all-day conversion is IMPLEMENTATION.md's "single most common calendar-client bug" —
    // written first, per §10.

    @Test
    fun `all-day event on a US spring-forward DST date reads correctly regardless of local zone`() {
        // 2026-03-08 is when America_New_York springs forward. Midnight UTC on that date is
        // still 2026-03-07 evening in New York (still on standard time, since the switch
        // itself happens later that day) — a local-zone conversion would misread this as the
        // previous day.
        val provider = LocalDate.of(2026, 3, 8)
        val beginMillis = provider.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals(provider, DateFmt.allDayToLocalDate(beginMillis))

        val naiveLocalZoneResult = Instant.ofEpochMilli(beginMillis)
            .atZone(ZoneId.of("America/New_York"))
            .toLocalDate()
        assertNotEquals(
            "this test only demonstrates the bug if the naive approach actually gets it wrong",
            provider,
            naiveLocalZoneResult,
        )
    }

    @Test
    fun `all-day event on a European DST date reads correctly regardless of local zone`() {
        // 2026-03-29 is when Europe_Zurich springs forward (last Sunday of March).
        val provider = LocalDate.of(2026, 3, 29)
        val beginMillis = provider.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals(provider, DateFmt.allDayToLocalDate(beginMillis))
    }

    @Test
    fun `all-day date holds for a negative-offset zone too, not just DST transitions`() {
        // Any negative-offset zone reads midnight UTC as the previous evening, DST aside —
        // this just confirms allDayToLocalDate never delegates to the local zone at all.
        val provider = LocalDate.of(2026, 7, 30)
        val beginMillis = provider.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val naiveResult = Instant.ofEpochMilli(beginMillis).atZone(ZoneId.of("America/Los_Angeles")).toLocalDate()
        assertNotEquals(provider, naiveResult)
        assertEquals(provider, DateFmt.allDayToLocalDate(beginMillis))
    }

    @Test
    fun `formatDuration renders minutes, whole hours, and hours plus minutes`() {
        assertEquals("45 min", DateFmt.formatDuration(45 * 60_000L))
        assertEquals("3h", DateFmt.formatDuration(3 * 60 * 60_000L))
        assertEquals("3h 30m", DateFmt.formatDuration(3 * 60 * 60_000L + 30 * 60_000L))
    }

    @Test
    fun `formatGapLabel wraps the duration with em dashes`() {
        assertEquals("— 2h 15m —", DateFmt.formatGapLabel(2 * 60 * 60_000L + 15 * 60_000L))
    }

    @Test
    fun `formatTime renders 24-hour and 12-hour formats`() {
        // Anchored to the JVM's own default zone (not UTC) since formatTime formats wall-clock
        // local time, whatever zone that happens to be in this test environment.
        val millis = LocalDate.of(2026, 7, 30).atTime(14, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("14:05", DateFmt.formatTime(millis, is24Hour = true))
        assertEquals("2:05 PM", DateFmt.formatTime(millis, is24Hour = false))
    }
}
