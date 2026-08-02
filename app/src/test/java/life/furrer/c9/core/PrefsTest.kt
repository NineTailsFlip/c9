package life.furrer.c9.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PrefsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `is24Hour defaults to true`() {
        assertTrue(Prefs.is24Hour(context))
    }

    @Test
    fun `is24Hour round-trips`() {
        Prefs.setIs24Hour(context, false)
        assertFalse(Prefs.is24Hour(context))
        Prefs.setIs24Hour(context, true)
        assertTrue(Prefs.is24Hour(context))
    }

    @Test
    fun `defaultCalendarId defaults to null and round-trips, including back to null`() {
        assertNull(Prefs.defaultCalendarId(context))
        Prefs.setDefaultCalendarId(context, 42L)
        assertEquals(42L, Prefs.defaultCalendarId(context))
        Prefs.setDefaultCalendarId(context, null)
        assertNull(Prefs.defaultCalendarId(context))
    }

    @Test
    fun `defaultEventDurationMinutes defaults to 60 and round-trips`() {
        assertEquals(60, Prefs.defaultEventDurationMinutes(context))
        Prefs.setDefaultEventDurationMinutes(context, 90)
        assertEquals(90, Prefs.defaultEventDurationMinutes(context))
    }

    @Test
    fun `defaultAccount defaults to null and round-trips, including back to null`() {
        assertNull(Prefs.defaultAccountName(context))
        assertNull(Prefs.defaultAccountType(context))
        Prefs.setDefaultAccount(context, "fastmail", "com.fastmail")
        assertEquals("fastmail", Prefs.defaultAccountName(context))
        assertEquals("com.fastmail", Prefs.defaultAccountType(context))
        Prefs.setDefaultAccount(context, null, null)
        assertNull(Prefs.defaultAccountName(context))
        assertNull(Prefs.defaultAccountType(context))
    }
}
