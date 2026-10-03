package life.furrer.c9.calendar.reminders

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The manifest's filters, resolved the way the system resolves the provider's broadcasts: an
 *  EVENT_REMINDER without the content-scheme data in the filter would simply never arrive. */
@RunWith(RobolectricTestRunner::class)
class EventReminderReceiverManifestTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun receiversFor(intent: Intent): List<String> =
        context.packageManager.queryBroadcastReceivers(intent.setPackage(context.packageName), 0)
            .map { it.activityInfo.name }

    @Test
    fun `the provider's EVENT_REMINDER reaches EventReminderReceiver`() {
        val intent = Intent(CalendarContract.ACTION_EVENT_REMINDER, Uri.parse("content://com.android.calendar/123"))
        assertEquals(listOf(EventReminderReceiver::class.java.name), receiversFor(intent))
    }

    @Test
    fun `boot and app updates reach it too`() {
        assertTrue(EventReminderReceiver::class.java.name in receiversFor(Intent(Intent.ACTION_BOOT_COMPLETED)))
        assertTrue(EventReminderReceiver::class.java.name in receiversFor(Intent(Intent.ACTION_MY_PACKAGE_REPLACED)))
    }

    @Test
    fun `clock and timezone changes reach it`() {
        listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED).forEach { action ->
            assertTrue(action, EventReminderReceiver::class.java.name in receiversFor(Intent(action)))
        }
    }

    @Test
    fun `an EVENT_REMINDER for another authority doesn't`() {
        val intent = Intent(CalendarContract.ACTION_EVENT_REMINDER, Uri.parse("content://elsewhere/123"))
        assertTrue(receiversFor(intent).isEmpty())
    }
}
