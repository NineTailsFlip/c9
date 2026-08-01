package life.furrer.c9.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CalendarStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `permission is denied by default in a fresh test environment`() {
        assertFalse(CalendarStore.hasPermission(context))
    }

    @Test
    fun `queryCalendars returns nothing without calendar permission`() {
        assertTrue(CalendarStore.queryCalendars(context).isEmpty())
    }

    @Test
    fun `selected calendar ids are null until the user customizes them`() {
        // Null vs. empty-set is a meaningful distinction: null means "default to all calendars";
        // an explicitly empty set means the user deselected everything.
        assertNull(CalendarStore.getSelectedCalendarIds(context))
    }

    @Test
    fun `an explicitly empty calendar selection round trips as empty, not null`() {
        CalendarStore.setSelectedCalendarIds(context, emptySet())
        assertEquals(emptySet<Long>(), CalendarStore.getSelectedCalendarIds(context))
    }

    @Test
    fun `a non-empty calendar selection round trips`() {
        CalendarStore.setSelectedCalendarIds(context, setOf(1L, 2L, 42L))
        assertEquals(setOf(1L, 2L, 42L), CalendarStore.getSelectedCalendarIds(context))
    }

    @Test
    fun `groupByAccount groups calendars under their own account name, preserving order`() {
        val calendars = listOf(
            CalendarInfo(1, "Personal", "timo@fastmail.com", "fastmail", 0xFF0000FF.toInt()),
            CalendarInfo(2, "Work", "timo@fastmail.com", "fastmail", 0xFF00FF00.toInt()),
            CalendarInfo(3, "Birthdays", "Local", "LOCAL", 0xFFFF0000.toInt()),
        )
        val grouped = CalendarStore.groupByAccount(calendars)

        assertEquals(listOf("timo@fastmail.com", "Local"), grouped.map { it.first })
        assertEquals(listOf("Personal", "Work"), grouped[0].second.map { it.displayName })
        assertEquals(listOf("Birthdays"), grouped[1].second.map { it.displayName })
    }

    @Test
    fun `hasPermission reflects a granted READ_CALENDAR permission`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        assertTrue(CalendarStore.hasPermission(context))
    }
}
