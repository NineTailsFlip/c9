package life.furrer.c9.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcalDurationTest {
    private val h = 3_600_000L
    private val d = 24 * h

    @Test fun `seconds as AOSP Calendar writes them`() = assertEquals(h, IcalDuration.parseMillis("P3600S"))

    @Test fun `weeks as DAVx5 writes them`() = assertEquals(7 * d, IcalDuration.parseMillis("P1W"))

    @Test fun `several weeks`() = assertEquals(14 * d, IcalDuration.parseMillis("P2W"))

    @Test fun `days and time combined`() = assertEquals(d + 2 * h + 30 * 60_000L, IcalDuration.parseMillis("P1DT2H30M"))

    @Test fun `hours minutes and seconds`() = assertEquals(h + 90_000L, IcalDuration.parseMillis("PT1H1M30S"))

    @Test fun `days only`() = assertEquals(3 * d, IcalDuration.parseMillis("P3D"))

    @Test fun `zero`() {
        assertEquals(0L, IcalDuration.parseMillis("PT0S"))
        assertEquals(0L, IcalDuration.parseMillis("P0D"))
    }

    @Test fun `signs`() {
        assertEquals(h, IcalDuration.parseMillis("+PT1H"))
        assertEquals(-h, IcalDuration.parseMillis("-PT1H"))
    }

    @Test fun `malformed input is null`() {
        listOf("", "P", "PT", "P1DT", "1H", "PT1X", "P1W2D", "PT1.5H", "P-1D", "garbage", "pt1h").forEach {
            assertNull("'$it'", IcalDuration.parseMillis(it))
        }
    }
}
