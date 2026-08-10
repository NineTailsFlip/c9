package life.furrer.c9.calendar

import android.text.TextUtils
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Covers [bindCalendarRow]'s actual View properties — [CalendarRowsTest] only covers the pure
 *  row-building logic, not what gets set on the Views themselves. */
@RunWith(RobolectricTestRunner::class)
class CalendarRowRendererTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun eventWithLocation(location: String) = EventEntry(
        eventId = 1L,
        title = "Standup",
        startMillis = 0L,
        endMillis = 3_600_000L,
        allDay = false,
        location = location,
        calendarId = 1L,
        displayColor = 0xFFFF0000.toInt(),
        rrule = null,
    )

    /** Regression test: a selected row's metadata line (duration + location) had no maxLines/
     *  ellipsize, so a long location value (e.g. a full video-call URL, a common thing to paste
     *  there) wrapped to two lines. The expanded selected row has a *fixed* height budget split
     *  between the title line (weighted) and this metadata line (wrap-content) — a two-line
     *  metadata line overflows that budget and squeezes the title line down to almost nothing,
     *  confirmed on-device: the event's own time/title rendered as a barely-visible sliver above
     *  a fully-rendered two-line metadata block. */
    @Test
    fun `the selected row's metadata line stays single-line and ellipsizes a long location`() {
        val longUrl = "https://meet.google.com/ubb-aqve-pcu-plus-quite-a-bit-more-text-to-force-wrapping-if-unbounded"
        val row = CalendarRow.EventRow(eventWithLocation(longUrl), isPast = false)
        val container = createCalendarRowContainer(context)

        bindCalendarRow(container, row, selected = true)

        val metaLine = container.getChildAt(1) as TextView
        assertEquals(1, metaLine.maxLines)
        assertEquals(TextUtils.TruncateAt.END, metaLine.ellipsize)
    }

    @Test
    fun `an unselected row has no metadata line at all`() {
        val row = CalendarRow.EventRow(eventWithLocation("Some office"), isPast = false)
        val container = createCalendarRowContainer(context)

        bindCalendarRow(container, row, selected = false)

        assertEquals(1, container.childCount)
    }

    /** Regression test: [MultiDayView]'s compact (Week) mode selects a row (for title styling)
     *  without expanding its height (§7.5/§9.2 — no room for a metadata line in a 7-day view), so
     *  `selected=true, expanded=false` is a real combination, not a redundant one. Passing plain
     *  `selected` straight into the metadata-line decision — as opposed to the [expanded] this
     *  binds against — added the metadata line into a row RowListView never gave the extra height
     *  budget for, squeezing the whole row into an overlapping mess on-device. */
    @Test
    fun `a selected but not expanded row has no metadata line either`() {
        val row = CalendarRow.EventRow(eventWithLocation("Some office"), isPast = false)
        val container = createCalendarRowContainer(context)

        bindCalendarRow(container, row, selected = true, expanded = false)

        assertEquals(1, container.childCount)
    }
}
