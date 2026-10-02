package life.furrer.c9.calendar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.CalendarContract.Attendees
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.util.DateFmt
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar

private const val SIDE_PADDING_DP = 16
private const val SCROLL_STEP_DP = 60

/** Read-only event detail per §9.3 — sections appear only when the field has a value. Edit/
 *  Delete live behind the `*` menu; a recurring event's edit/delete first asks which instances
 *  it applies to (§6.1.3), since "this and following" isn't built yet (M6), only these two. */
class EventDetailActivity : Activity() {

    private var eventId: Long = -1L
    private var instanceStartMillis: Long = 0L
    private var detail: EventDetail? = null
    private var attendees: List<EventAttendee> = emptyList()

    private lateinit var titleBarColor: View
    private lateinit var titleText: TextView
    private lateinit var subtitleView: TextView
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        instanceStartMillis = intent.getLongExtra(EXTRA_INSTANCE_START_MILLIS, 0L)
        if (eventId == -1L) {
            finish()
            return
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(4))
        }
        header.addView(
            TextView(this).apply {
                text = "‹ event"
                textSize = 13f
                setTextColor(Palette.GRAY_FAINT)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextClock(this).apply {
                textSize = 13f
                setTextColor(Palette.GRAY_MUTED)
                format24Hour = "HH:mm"
                format12Hour = null
            },
        )

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE_PADDING_DP), dp(4), dp(SIDE_PADDING_DP), 0)
        }
        titleBarColor = View(this)
        titleRow.addView(titleBarColor, LinearLayout.LayoutParams(dp(3), dp(24)).apply { marginEnd = dp(8) })
        titleText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        titleRow.addView(titleText)

        subtitleView = TextView(this).apply {
            textSize = 15f
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(SIDE_PADDING_DP), dp(2), dp(SIDE_PADDING_DP), dp(8))
        }
        val rule = View(this).apply { setBackgroundColor(Palette.DIVIDER) }
        sectionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(titleRow)
            addView(subtitleView)
            addView(
                rule,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                },
            )
            addView(sectionsContainer)
        }
        scrollView = ScrollView(this).apply {
            addView(content)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        hintBar = HintBar(this).apply { setText("* menu · ‹ back") }
        menuSheet = MenuSheet(this)
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        hideStatusBar()
    }

    override fun onResume() {
        super.onResume()
        // Reloads every resume, not just once in onCreate, so coming back from the editor (or a
        // background sync) shows the current state rather than what was loaded before it.
        if (eventId != -1L) load(eventId)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun load(id: Long) {
        Async.run(
            background = {
                val d = EventStore.queryEventDetail(this, id)
                val calendar = d?.let { detail -> CalendarStore.queryCalendars(this).find { it.id == detail.calendarId } }
                val loadedAttendees = d?.let { detail -> EventStore.queryAttendees(this, id, detail.calendarId) } ?: emptyList()
                Triple(d, calendar, loadedAttendees)
            },
            onResult = { (loadedDetail, calendar, loadedAttendees) ->
                if (loadedDetail == null) {
                    finish()
                } else {
                    detail = loadedDetail
                    attendees = loadedAttendees
                    render(loadedDetail, calendar)
                }
            },
        )
    }

    private fun render(detail: EventDetail, calendar: CalendarInfo?) {
        titleBarColor.setBackgroundColor(Palette.pastelize(detail.displayColor))
        titleText.text = detail.title
        subtitleView.text = formatSubtitle(detail)

        sectionsContainer.removeAllViews()
        detail.location?.takeIf { it.isNotBlank() }?.let { addSection("WHERE", it) }
        detail.description?.trim()?.takeIf { it.isNotEmpty() }?.let { addSection("NOTES", it) }
        calendar?.let {
            val value = "${it.displayName} · ${it.accountName}" + if (detail.dirty) " · not yet synced" else ""
            addSection("CALENDAR", value, valueColor = if (detail.dirty) Palette.RED else Palette.GRAY_DIM)
        }
        detail.rrule?.let { addSection("REPEATS", Recurrence.describe(it)) }
        detail.reminderMinutes?.let { addSection("REMINDER", formatReminder(it)) }
        if (attendees.isNotEmpty()) addAttendeesSection(attendees)
    }

    /** RFC 5545 calls these ATTENDEE (with a PARTSTAT for the participation status shown here) —
     *  the organizer is just another attendee with RELATIONSHIP_ORGANIZER, so it isn't special-
     *  cased out of the list, just labelled. */
    private fun addAttendeesSection(attendees: List<EventAttendee>) {
        sectionsContainer.addView(
            TextView(this).apply {
                text = "ATTENDEES"
                textSize = 11f
                letterSpacing = 0.11f
                setTextColor(Palette.GRAY_FAINT)
                setPadding(dp(SIDE_PADDING_DP), dp(14), dp(SIDE_PADDING_DP), dp(2))
            },
        )
        attendees.forEach { attendee ->
            sectionsContainer.addView(
                TextView(this).apply {
                    text = formatAttendeeLine(attendee)
                    textSize = 15f
                    setTextColor(Palette.GRAY_DIM)
                    setPadding(dp(SIDE_PADDING_DP), dp(2), dp(SIDE_PADDING_DP), dp(2))
                },
            )
        }
    }

    private fun formatAttendeeLine(attendee: EventAttendee): String {
        val name = attendee.name?.takeIf { it.isNotBlank() } ?: attendee.email
        val badges = buildList {
            if (attendee.isOrganizer) add("organizer")
            if (attendee.isSelf) add("you")
        }
        val label = if (badges.isEmpty()) name else "$name (${badges.joinToString(", ")})"
        return "$label — ${attendeeStatusLabel(attendee.status)}"
    }

    private fun attendeeStatusLabel(status: Int): String = when (status) {
        Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
        Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
        Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
        Attendees.ATTENDEE_STATUS_INVITED -> "needs action"
        else -> "no response"
    }

    /** [EventDetail] always carries the *master* Events row's own DTSTART/DTEND — for a
     *  recurring event that's just its first occurrence, regardless of which occurrence the
     *  user actually opened. [instanceStartMillis] (from the Instances row that got us here) is
     *  the one that matters for display; the master's duration is preserved onto it. */
    private fun formatSubtitle(detail: EventDetail): String {
        val duration = detail.endMillis - detail.startMillis
        val displayStart = instanceStartMillis
        val displayEnd = displayStart + duration
        val date = if (detail.allDay) {
            DateFmt.allDayToLocalDate(displayStart)
        } else {
            Instant.ofEpochMilli(displayStart).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val dateText = "$dow ${date.dayOfMonth} $month"
        return if (detail.allDay) {
            "$dateText · All day"
        } else {
            val start = DateFmt.formatTime(displayStart)
            val end = DateFmt.formatTime(displayEnd)
            "$dateText · $start – $end"
        }
    }

    private fun addSection(header: String, value: String, valueColor: Int = Palette.GRAY_DIM) {
        sectionsContainer.addView(
            TextView(this).apply {
                text = header
                textSize = 11f
                letterSpacing = 0.11f
                setTextColor(Palette.GRAY_FAINT)
                setPadding(dp(SIDE_PADDING_DP), dp(14), dp(SIDE_PADDING_DP), dp(2))
            },
        )
        sectionsContainer.addView(
            TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(valueColor)
                setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), 0)
            },
        )
    }

    private fun formatReminder(minutes: Int): String = when {
        minutes <= 0 -> "At time of event"
        minutes % 60 == 0 && minutes >= 60 -> {
            val hours = minutes / 60
            "$hours hour${if (hours > 1) "s" else ""} before"
        }
        minutes % 1440 == 0 -> "${minutes / 1440} day${if (minutes / 1440 > 1) "s" else ""} before"
        else -> "$minutes minutes before"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            openMenu()
            return true
        }
        // Nothing on this screen takes focus, so the d-pad scrolls the page itself — a long
        // NOTES section (meeting links, agendas) easily runs past the bottom.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            scrollView.smoothScrollBy(0, -dp(SCROLL_STEP_DP))
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            scrollView.smoothScrollBy(0, dp(SCROLL_STEP_DP))
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Adding/removing attendees lives only in the editor (EventEditActivity), not here — a
    // recurring event's Edit already asks This event/This and following/All events before
    // touching anything, and attendee changes need that same scope decision (an exception or
    // continuation row is a *new* Events row starting with zero attendees of its own, so a
    // change made without picking a scope has no well-defined target). Routing through Edit
    // means that question is never skipped. Responding to an invite is unaffected by any of
    // this — it answers for whichever specific occurrence's own Attendees row is already
    // loaded here, nothing about it is scope-ambiguous — so it stays as a direct action.
    private fun openMenu() {
        val current = detail ?: return
        val items = mutableListOf(
            MenuItem("Edit \"${current.title}\"") { startEdit(current) },
            MenuItem("Delete \"${current.title}\"") { confirmDelete(current) },
        )
        if (current.selfAttendeeStatus != Attendees.ATTENDEE_STATUS_NONE) {
            items += MenuItem("Respond to invite") { openResponseMenu(current.calendarId) }
        }
        menuSheet.show(items)
    }

    private fun openResponseMenu(calendarId: Long) {
        menuSheet.show(
            listOf(
                MenuItem("Accept") { respondToInvite(calendarId, Attendees.ATTENDEE_STATUS_ACCEPTED) },
                MenuItem("Decline") { respondToInvite(calendarId, Attendees.ATTENDEE_STATUS_DECLINED) },
                MenuItem("Tentative") { respondToInvite(calendarId, Attendees.ATTENDEE_STATUS_TENTATIVE) },
            ),
        )
    }

    private fun respondToInvite(calendarId: Long, status: Int) {
        val id = eventId
        Async.run(
            background = { EventStore.respondToInvite(this, id, calendarId, status) },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't respond", Toast.LENGTH_SHORT).show()
                load(id)
            },
        )
    }

    private fun startEdit(detail: EventDetail) {
        if (detail.rrule != null) {
            promptScope { scope -> launchEditor(detail, scope) }
        } else {
            launchEditor(detail, EditScope.ALL_INSTANCES)
        }
    }

    private fun launchEditor(detail: EventDetail, scope: EditScope) {
        startActivity(
            Intent(this, EventEditActivity::class.java).apply {
                putExtra(EventEditActivity.EXTRA_EVENT_ID, detail.eventId)
                putExtra(EventEditActivity.EXTRA_INSTANCE_START_MILLIS, instanceStartMillis)
                putExtra(EventEditActivity.EXTRA_EDIT_SCOPE, scope.name)
            },
        )
    }

    private fun confirmDelete(detail: EventDetail) {
        if (detail.rrule != null) {
            promptScope { scope -> performDelete(detail.eventId, scope) }
        } else {
            performDelete(detail.eventId, EditScope.ALL_INSTANCES)
        }
    }

    private fun promptScope(onChosen: (EditScope) -> Unit) {
        menuSheet.show(
            listOf(
                MenuItem("This event") { onChosen(EditScope.THIS_INSTANCE) },
                MenuItem("This and following") { onChosen(EditScope.THIS_AND_FOLLOWING) },
                MenuItem("All events") { onChosen(EditScope.ALL_INSTANCES) },
            ),
        )
    }

    private fun performDelete(id: Long, scope: EditScope) {
        Async.run(
            background = { EventStore.deleteEvent(this, id, instanceStartMillis, scope) },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show()
                finish()
            },
        )
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_INSTANCE_START_MILLIS = "instance_start_millis"
    }
}
