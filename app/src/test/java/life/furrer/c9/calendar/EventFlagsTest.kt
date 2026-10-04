package life.furrer.c9.calendar

import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventFlagsTest {

    private fun detail(availability: Int = Events.AVAILABILITY_BUSY, accessLevel: Int = Events.ACCESS_DEFAULT, status: Int? = null) =
        EventDetail(
            eventId = 1L, calendarId = 1L, title = "x", location = null, description = null,
            startMillis = 0L, endMillis = 0L, allDay = false, rrule = null, reminders = emptyList(),
            eventColorKey = null, displayColor = 0, selfAttendeeStatus = 0, dirty = false,
            availability = availability, accessLevel = accessLevel, status = status,
        )

    @Test
    fun `an ordinary event has no summary`() {
        assertNull(EventFlags.summary(detail()))
        assertNull(EventFlags.summary(detail(status = Events.STATUS_CONFIRMED)))
    }

    @Test
    fun `the summary names only what differs from an ordinary event`() {
        assertEquals("Free", EventFlags.summary(detail(availability = Events.AVAILABILITY_FREE)))
        assertEquals(
            "Free · private · tentative",
            EventFlags.summary(detail(Events.AVAILABILITY_FREE, Events.ACCESS_PRIVATE, Events.STATUS_TENTATIVE)),
        )
    }

    @Test
    fun `a synced-only value is offered only when the event came with it`() {
        assertEquals(listOf(Events.AVAILABILITY_BUSY, Events.AVAILABILITY_FREE), EventFlags.availabilityOptions(Events.AVAILABILITY_BUSY))
        assertEquals(
            listOf(Events.ACCESS_CONFIDENTIAL, Events.ACCESS_DEFAULT, Events.ACCESS_PRIVATE, Events.ACCESS_PUBLIC),
            EventFlags.accessOptions(Events.ACCESS_CONFIDENTIAL),
        )
        assertEquals(listOf(null, Events.STATUS_CONFIRMED, Events.STATUS_TENTATIVE), EventFlags.statusOptions(null))
    }

    @Test
    fun `cycle wraps both ways`() {
        val options = listOf(1, 2, 3)
        assertEquals(1, EventFlags.cycle(options, 3, 1))
        assertEquals(3, EventFlags.cycle(options, 1, -1))
    }
}
