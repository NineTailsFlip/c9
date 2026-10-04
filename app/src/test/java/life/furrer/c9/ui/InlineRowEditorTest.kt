package life.furrer.c9.ui

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import life.furrer.nine.core.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** [InlineRowEditor] on a bare Activity laid out the way both editors are: rows in a
 *  focus-blocking ScrollView, the editor's focus anchor added to a FrameLayout root. */
@RunWith(RobolectricTestRunner::class)
class InlineRowEditorTest {

    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var editor: InlineRowEditor
    private val log = mutableListOf<String>()
    private var creations = 0

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) { creations++ }
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            },
        )
        rowContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        repeat(3) { i -> rowContainer.addView(TextView(activity).apply { text = "row $i" }) }
        scrollView = ScrollView(activity).apply {
            addView(rowContainer)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root = FrameLayout(activity).apply { addView(scrollView) }
        editor = InlineRowEditor(activity, rowContainer, scrollView, onMove = { log += "move $it" }, onCommitted = { log += "committed" })
        editor.attachTo(root)
        activity.setContentView(root)
    }

    private fun down(keyCode: Int) = editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    private fun beginOnRow1(restored: View = TextView(activity)): EditText {
        val editText = EditText(activity).apply { setText("typed ") }
        editor.begin(rowContainer.getChildAt(1), "Label:  ", editText, onCommit = { log += "commit '$it'" }, restoreRow = { restored })
        return editText
    }

    @Test
    fun `attach adds the focus anchor last and keeps the screen out of Autofill`() {
        assertEquals(2, root.childCount)
        assertTrue(root.getChildAt(1).isFocusable)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, root.importantForAutofill)
    }

    @Test
    fun `begin swaps the row for its label and a focused edit field`() {
        val editText = beginOnRow1()
        assertTrue(editor.isEditing)
        val row = rowContainer.getChildAt(1) as LinearLayout
        assertEquals("Label:  ", (row.getChildAt(0) as TextView).text.toString())
        assertSame(editText, row.getChildAt(1))
        assertEquals(3, rowContainer.childCount)
        assertEquals(ViewGroup.FOCUS_BEFORE_DESCENDANTS, scrollView.descendantFocusability)
        assertTrue(editText.hasFocus())
        assertEquals(Color.WHITE, editText.currentTextColor)
        assertNull(editText.background)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, editText.importantForAutofill)
    }

    @Test
    fun `begin on a row that isn't in the container changes nothing`() {
        val stray = TextView(activity)
        assertNull(editor.begin(stray, "x", EditText(activity), onCommit = {}, restoreRow = { stray }))
        assertFalse(editor.isEditing)
        assertFalse(down(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `ok commits the typed text, puts the restored row back and recreates`() {
        val restored = TextView(activity)
        beginOnRow1(restored)
        val before = creations
        assertTrue(down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("commit 'typed '", "committed"), log)
        assertFalse(editor.isEditing)
        assertSame(restored, rowContainer.getChildAt(1))
        assertEquals(3, rowContainer.childCount)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, scrollView.descendantFocusability)
        assertTrue(root.getChildAt(1).isFocused)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before + 1, creations)
    }

    @Test
    fun `enter and back commit too`() {
        beginOnRow1()
        assertTrue(down(KeyEvent.KEYCODE_ENTER))
        beginOnRow1()
        assertTrue(down(KeyEvent.KEYCODE_BACK))
        assertEquals(listOf("commit 'typed '", "committed", "commit 'typed '", "committed"), log)
    }

    @Test
    fun `up and down commit first, then move`() {
        beginOnRow1()
        assertTrue(down(KeyEvent.KEYCODE_DPAD_UP))
        beginOnRow1()
        assertTrue(down(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(listOf("commit 'typed '", "committed", "move -1", "commit 'typed '", "committed", "move 1"), log)
    }

    @Test
    fun `other keys, key ups and keys while not editing are left alone`() {
        assertFalse(down(KeyEvent.KEYCODE_DPAD_CENTER))
        beginOnRow1()
        assertFalse(down(KeyEvent.KEYCODE_2))
        assertFalse(down(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertTrue(editor.isEditing)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `columnHighlight styles each segment and leaves a null one alone`() {
        val text = columnHighlight("Starts:  " to false, "Mon 3 Aug" to true, ", " to null, "14:30" to false)
        assertEquals("Starts:  Mon 3 Aug, 14:30", text.toString())
        fun colorAt(index: Int) = text.getSpans(index, index + 1, ForegroundColorSpan::class.java).map { it.foregroundColor }
        fun underlined(start: Int, end: Int) = text.getSpans(start, end, UnderlineSpan::class.java).map { text.getSpanStart(it) to text.getSpanEnd(it) }
        assertEquals(listOf(Palette.GRAY_DIM), colorAt(0))
        assertEquals(listOf(Color.WHITE), colorAt(9))
        assertEquals(emptyList<Int>(), colorAt(18))
        assertEquals(listOf(Palette.GRAY_DIM), colorAt(20))
        assertEquals(listOf(9 to 18), underlined(0, text.length))
        text.getSpans(0, text.length, Any::class.java).forEach { assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, text.getSpanFlags(it)) }
    }
}
