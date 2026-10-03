package life.furrer.c9.calendar.reminders

import android.Manifest
import android.app.Activity
import life.furrer.c9.core.Prefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** POST_NOTIFICATIONS is asked for once from the calendar, and never while reminders are off. */
@RunWith(RobolectricTestRunner::class)
class ReminderPermissionTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `asks once, and not again on the next launch`() {
        val first = activity()
        ReminderPermission.maybeRequest(first)
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            shadowOf(first).lastRequestedPermission.requestedPermissions,
        )

        val second = activity()
        ReminderPermission.maybeRequest(second)
        assertNull(shadowOf(second).lastRequestedPermission)
    }

    @Test
    fun `doesn't ask when already granted`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = activity()
        ReminderPermission.maybeRequest(activity)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun `doesn't ask while reminder notifications are switched off`() {
        Prefs.setReminderNotifications(context, false)
        val activity = activity()
        ReminderPermission.maybeRequest(activity)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }
}
