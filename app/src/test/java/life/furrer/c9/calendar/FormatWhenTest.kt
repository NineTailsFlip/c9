package life.furrer.c9.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatWhenTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private fun local(y: Int, m: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, m, d, h, mi).atZone(zurich).toInstant().toEpochMilli()
    private fun utcDay(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hhmm = { millis: Long -> java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(millis).atZone(zurich)) }

    private fun when_(allDay: Boolean, start: Long, end: Long) = formatWhen(allDay, start, end, zurich, Locale.ENGLISH, hhmm)

    @Test
    fun `a same-day event says the date once`() {
        assertEquals("Fri 9 Oct · 10:00 – 11:00", when_(false, local(2026, 10, 9, 10), local(2026, 10, 9, 11)))
    }

    @Test
    fun `an overnight event names both days`() {
        assertEquals("Fri 9 Oct 23:00 – Sat 10 Oct 01:00", when_(false, local(2026, 10, 9, 23), local(2026, 10, 10, 1)))
    }

    @Test
    fun `an event ending at midnight stays on its day`() {
        assertEquals("Fri 9 Oct · 22:00 – 00:00", when_(false, local(2026, 10, 9, 22), local(2026, 10, 10, 0)))
    }

    @Test
    fun `an all-day event is one day or a range with its exclusive end`() {
        assertEquals("Fri 16 Oct · All day", when_(true, utcDay(2026, 10, 16), utcDay(2026, 10, 17)))
        assertEquals("Fri 16 Oct – Sun 18 Oct · All day", when_(true, utcDay(2026, 10, 16), utcDay(2026, 10, 19)))
    }
}
