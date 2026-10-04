package life.furrer.c9.calendar

import android.app.Activity
import android.view.KeyEvent
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EventNotesActivityTest {

    private fun open(text: String): EventNotesActivity {
        val intent = EventNotesActivity.intent(RuntimeEnvironment.getApplication(), text)
        return Robolectric.buildActivity(EventNotesActivity::class.java, intent).setup().get()
    }

    private fun field(activity: EventNotesActivity): EditText {
        fun find(view: android.view.View): EditText? = when (view) {
            is EditText -> view
            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return find(activity.window.decorView)!!
    }

    private fun key(activity: EventNotesActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
    }

    private fun result(activity: EventNotesActivity): String {
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        return shadowOf(activity).resultIntent.getStringExtra(EventNotesActivity.EXTRA_TEXT)!!
    }

    @Test
    fun `opens on the notes, line breaks and all`() {
        assertEquals("Agenda:\n1. Budget", field(open("Agenda:\n1. Budget")).text.toString())
    }

    @Test
    fun `OK hands back the edited text instead of adding a line break`() {
        val activity = open("Agenda:\n1. Budget")
        field(activity).append("\n2. Hiring")
        key(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Agenda:\n1. Budget\n2. Hiring", result(activity))
    }

    @Test
    fun `Enter is done too, which is what OK arrives as through TT9`() {
        val activity = open("a")
        key(activity, KeyEvent.KEYCODE_ENTER)
        assertEquals("a", result(activity))
    }

    @Test
    fun `a held Back's repeats don't close the screen`() {
        val activity = open("")
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 3))
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `Back keeps what's there too`() {
        val activity = open("")
        key(activity, KeyEvent.KEYCODE_BACK)
        assertEquals("", result(activity))
    }
}
