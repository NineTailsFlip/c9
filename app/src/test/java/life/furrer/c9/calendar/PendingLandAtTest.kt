package life.furrer.c9.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingLandAtTest {

    private val pending = PendingLandAt()

    @Test
    fun `a plain refresh preserves the selection`() {
        pending.request(null)
        assertNull(pending.take(stale = false))
    }

    @Test
    fun `a jump is applied once`() {
        pending.request(true)
        assertEquals(true, pending.take(stale = false))
        assertNull(pending.take(stale = false))
    }

    @Test
    fun `a refresh arriving after a jump doesn't erase it`() {
        pending.request(false)
        pending.request(null)
        assertEquals(false, pending.take(stale = false))
    }

    @Test
    fun `the newest jump wins`() {
        pending.request(true)
        pending.request(false)
        assertEquals(false, pending.take(stale = false))
    }

    @Test
    fun `a stale result leaves the jump for the fresh load behind it`() {
        pending.request(true)
        assertNull(pending.take(stale = true))
        assertEquals(true, pending.take(stale = false))
    }
}
