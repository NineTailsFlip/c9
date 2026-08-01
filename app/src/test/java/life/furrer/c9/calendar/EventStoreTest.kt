package life.furrer.c9.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EventStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `permission is denied by default in a fresh test environment`() {
        assertFalse(EventStore.hasPermission(context))
    }

    @Test
    fun `queryRange returns nothing without calendar permission`() {
        assertTrue(EventStore.queryRange(context, 0L, Long.MAX_VALUE, null).isEmpty())
    }

    @Test
    fun `hasPermission reflects a granted READ_CALENDAR permission`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        assertTrue(EventStore.hasPermission(context))
    }

    @Test
    fun `queryRange does not crash when permission is granted but no calendar provider is configured`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        // No calendar content provider is registered in this test environment, so this exercises
        // the "permission granted, but nothing to query" path without a real calendar app.
        assertTrue(EventStore.queryRange(context, 0L, Long.MAX_VALUE, null).isEmpty())
    }
}
