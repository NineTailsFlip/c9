package life.furrer.c9.calendar

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RepeatActivityTest {

    // Tue 6 Oct 2026, 10:00 in the default zone.
    private val startMillis = LocalDate.of(2026, 10, 6).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun open(rrule: String?): RepeatActivity {
        val intent = Intent(org.robolectric.RuntimeEnvironment.getApplication(), RepeatActivity::class.java)
            .putExtra(RepeatActivity.EXTRA_RRULE, rrule)
            .putExtra(RepeatActivity.EXTRA_START_MILLIS, startMillis)
        return Robolectric.buildActivity(RepeatActivity::class.java, intent).setup().get()
    }

    private fun key(activity: RepeatActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    private fun result(activity: RepeatActivity): String {
        key(activity, KeyEvent.KEYCODE_BACK)
        val shadow = shadowOf(activity)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        return shadow.resultIntent.getStringExtra(RepeatActivity.EXTRA_RRULE)!!
    }

    @Test
    fun `an empty start offers a plain weekly rule`() {
        assertEquals("FREQ=WEEKLY", result(open(null)))
    }

    @Test
    fun `every two weeks on Monday and Wednesday`() {
        val activity = open("FREQ=WEEKLY")
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN) // Every
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN) // On
        key(activity, KeyEvent.KEYCODE_1) // Mon
        key(activity, KeyEvent.KEYCODE_3) // Wed

        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", result(activity))
    }

    @Test
    fun `ending after a number of times`() {
        val activity = open("FREQ=DAILY")
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN) // Every
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN) // Ends (daily has no On row)
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT) // on a date
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT) // after a number
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN) // Times
        key(activity, KeyEvent.KEYCODE_5)

        assertEquals("FREQ=DAILY;COUNT=5", result(activity))
    }

    @Test
    fun `an existing rule opens as it is and comes back unchanged`() {
        assertEquals("FREQ=MONTHLY;INTERVAL=3;BYDAY=1TU;COUNT=4", result(open("FREQ=MONTHLY;INTERVAL=3;BYDAY=1TU;COUNT=4")))
    }

    @Test
    fun `changing the frequency drops the parts that belonged to the old one`() {
        val activity = open("FREQ=WEEKLY;BYDAY=MO,WE")
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT) // monthly

        assertEquals("FREQ=MONTHLY", result(activity))
    }
}
