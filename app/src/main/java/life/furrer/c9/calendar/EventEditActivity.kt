package life.furrer.c9.calendar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.util.DateFmt
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.hideStatusBar

private const val SIDE_PADDING_DP = 16
private const val REQUEST_PICK_DATE = 200
private const val REQUEST_PICK_ATTENDEE = 201

/** Time-of-day-only reminder presets, cycled via Left/Right on the Reminder row; null is "none". */
private val REMINDER_PRESETS: List<Int?> = listOf(null, 0, 5, 10, 15, 30, 60, 1440)

/** The Label:value editor from §9.4 — same row shape as L9's SettingsActivity, reused verbatim.
 *  Date/time entry is a deliberate simplification from the doc's "typed digits with ← → moving
 *  between components": it reuses [DatePickerActivity] (already built, tested) for the date and
 *  a small numeric dialog for the time, rather than a new component-switching digit editor. Both
 *  are still keypad-only digit entry, just packaged as two steps instead of one continuous field —
 *  a reasonable trade given the milestone's real bar is a correct write path, not this screen's
 *  interaction polish. */
class EventEditActivity : Activity() {

    // ATTENDEES is deliberately last (after CALENDAR, not grouped with e.g. WHERE) so every
    // existing row-index assumption elsewhere (including EventEditActivityTest's) stays valid —
    // it only ever adds a new row past the end, never shifts one already there.
    private enum class FieldSlot { TITLE, STARTS, ENDS, ALL_DAY, REPEATS, WHERE, REMINDER, COLOR, CALENDAR, ATTENDEES }

    /** An attendee added this session but not yet written — there's no real event id to attach
     *  it to until [save] actually inserts/updates the event, so it's held here exactly like
     *  every other unsaved field edit until then. */
    private data class PendingAttendee(val email: String, val name: String?)

    private var eventId: Long? = null
    private var instanceStartMillis: Long = 0L
    private var editScope: EditScope = EditScope.ALL_INSTANCES

    private var calendars: List<CalendarInfo> = emptyList()
    private var calendarIndex = 0
    private var title = ""
    private var location = ""
    private var startMillis = 0L
    private var endMillis = 0L
    private var allDay = false
    private var frequency = RecurrenceFrequency.NONE
    private var reminderMinutes: Int? = null
    private var eventColorKey: String? = null
    private var availableColors: List<EventColorOption> = emptyList()
    private var loaded = false
    private var pendingDateTimeTarget: FieldSlot? = null

    // Attendees, like every other field here, aren't written until [save] — an existing event's
    // attendees load once into [originalAttendees] and stay untouched there; [removedAttendeeIds]
    // marks which of those to delete on save, and [pendingNewAttendees] holds ones added this
    // session that don't exist in the provider yet.
    private var originalAttendees: List<EventAttendee> = emptyList()
    private var removedAttendeeIds: Set<Long> = emptySet()
    private var pendingNewAttendees: List<PendingAttendee> = emptyList()

    private val fieldRows = mutableMapOf<FieldSlot, TextView>()
    private var visibleFields: List<FieldSlot> = FieldSlot.entries.filter { it != FieldSlot.COLOR }
    private var selectedField: FieldSlot = FieldSlot.TITLE

    // Inline editing state (Title/Where/Starts-time/Ends-time) — the row's own TextView is
    // swapped for a real EditText in place, no dialog: OK/Back/Up/Down all commit (there's no
    // cancel — an accidental edit is no worse than any other field change, and the whole event
    // itself still isn't written until Back on the top-level screen saves it).
    private enum class InlineEditKind { TEXT, TIME }
    private var inlineEditField: FieldSlot? = null
    private var inlineEditKind: InlineEditKind? = null
    private var inlineEditText: EditText? = null
    private var inlineEditDate: LocalDate? = null

    // Starts/Ends each hold two independent sub-fields — date (Left) and time (Right) — per the
    // user's own suggestion, rather than one field that always opens the date picker and then
    // auto-chains into a time prompt. Reset to DATE whenever selection moves to a different row,
    // so arriving at a row always starts on its primary (date) sub-field.
    private enum class DateTimeColumn { DATE, TIME }
    private var dateTimeColumn: DateTimeColumn = DateTimeColumn.DATE

    private lateinit var rowContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var keyFocusAnchor: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L).takeIf { it != -1L }
        instanceStartMillis = intent.getLongExtra(EXTRA_INSTANCE_START_MILLIS, 0L)
        editScope = intent.getStringExtra(EXTRA_EDIT_SCOPE)?.let { EditScope.valueOf(it) } ?: EditScope.ALL_INSTANCES

        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        FieldSlot.entries.forEach { slot ->
            val row = makeRow()
            fieldRows[slot] = row
            rowContainer.addView(row)
        }
        scrollView = ScrollView(this).apply {
            addView(rowContainer)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        hintBar = HintBar(this).apply { setText("* menu · ok edit field · ‹ done") }
        menuSheet = MenuSheet(this)
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        // No row TextView is ever focusable (see scrollView above), so once an inline-edit
        // EditText is removed there is nothing else in the layout for Android's focus system to
        // hand focus to — confirmed on-device that View.clearFocus() then just re-grants the same
        // (now-detached) EditText focus, silently swallowing every later DPAD_CENTER/STAR press
        // (they reach the dead EditText's own key handling instead of this Activity's onKeyDown)
        // while arrow keys kept working, since plain Views don't specially consume those. This
        // always-present, never-removed anchor is the reclaim target instead. Added last (as a
        // root sibling, not inside mainColumn) so it doesn't shift any existing child index —
        // EventEditActivityTest's rowContainer() helper walks fixed getChildAt(0) indices.
        keyFocusAnchor = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(keyFocusAnchor, FrameLayout.LayoutParams(0, 0))
            // None of this screen's EditTexts (title/location/time digits) are ever credentials,
            // but Android's Autofill framework doesn't know that. This hint alone does NOT stop a
            // service from offering a suggestion here (confirmed on-device: a real "Autofill UI"
            // popup still appeared despite this, and despite the same flag set directly on the
            // EditText too — importantForAutofill is advisory, not enforced, and neither is
            // FLAG_SECURE on this device). Kept as a best-effort default anyway; the actual fix
            // for the popup's fallout is commitInlineEdit's recreate() call — see its comment.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()

        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedState
        if (retained != null) {
            eventId = retained.eventId
            instanceStartMillis = retained.instanceStartMillis
            editScope = retained.editScope
            calendars = retained.calendars
            calendarIndex = retained.calendarIndex
            title = retained.title
            location = retained.location
            startMillis = retained.startMillis
            endMillis = retained.endMillis
            allDay = retained.allDay
            frequency = retained.frequency
            reminderMinutes = retained.reminderMinutes
            eventColorKey = retained.eventColorKey
            availableColors = retained.availableColors
            pendingDateTimeTarget = retained.pendingDateTimeTarget
            visibleFields = retained.visibleFields
            selectedField = retained.selectedField
            dateTimeColumn = retained.dateTimeColumn
            originalAttendees = retained.originalAttendees
            removedAttendeeIds = retained.removedAttendeeIds
            pendingNewAttendees = retained.pendingNewAttendees
            loaded = true
            render()
        } else {
            val initialDate = intent.getLongExtra(EXTRA_INITIAL_DATE, -1L).takeIf { it != -1L }?.let { LocalDate.ofEpochDay(it) }
            loadInitialData(initialDate)
        }
    }

    /** All in-memory edits not yet written to the calendar provider (this whole event isn't saved
     *  until Back on the top-level screen) — carried across [recreate] by [commitInlineEdit] via
     *  [onRetainNonConfigurationInstance], so that self-heal doesn't discard unsaved work. Doesn't
     *  need to include inline-edit-in-progress fields (inlineEditField etc.): recreate() is only
     *  ever called once a commit has already finished and cleared them. */
    private data class RetainedState(
        val eventId: Long?,
        val instanceStartMillis: Long,
        val editScope: EditScope,
        val calendars: List<CalendarInfo>,
        val calendarIndex: Int,
        val title: String,
        val location: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val frequency: RecurrenceFrequency,
        val reminderMinutes: Int?,
        val eventColorKey: String?,
        val availableColors: List<EventColorOption>,
        val pendingDateTimeTarget: FieldSlot?,
        val visibleFields: List<FieldSlot>,
        val selectedField: FieldSlot,
        val dateTimeColumn: DateTimeColumn,
        val originalAttendees: List<EventAttendee>,
        val removedAttendeeIds: Set<Long>,
        val pendingNewAttendees: List<PendingAttendee>,
    )

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = RetainedState(
        eventId, instanceStartMillis, editScope, calendars, calendarIndex, title, location,
        startMillis, endMillis, allDay, frequency, reminderMinutes, eventColorKey, availableColors,
        pendingDateTimeTarget, visibleFields, selectedField, dateTimeColumn,
        originalAttendees, removedAttendeeIds, pendingNewAttendees,
    )

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    // Distinct from the fill-suggestion focus-steal bug fixed via recreate() in commitInlineEdit
    // (KNOWN_ISSUE_T9_IME.md) — this is Android's separate Save flow, which pops "Save to
    // Bitwarden?" after this screen's fields are committed and the window goes away, since none
    // of this screen's EditTexts are ever credentials but the platform's heuristic doesn't know
    // that. cancel() (unlike commit()) explicitly discards the pending autofill context instead of
    // letting it evaluate for a save prompt — called unconditionally on every destroy (including
    // the mid-session recreate()) since we never want that prompt regardless of how this Activity
    // instance ends.
    override fun onDestroy() {
        super.onDestroy()
        getSystemService(AutofillManager::class.java)?.cancel()
    }

    private fun makeRow(): TextView = TextView(this).apply {
        textSize = 16f
        setPadding(dp(SIDE_PADDING_DP), dp(6), dp(SIDE_PADDING_DP), dp(6))
    }

    private data class LoadResult(
        val calendars: List<CalendarInfo>,
        val existing: EventDetail?,
        val colors: List<EventColorOption>,
        val attendees: List<EventAttendee>,
    )

    private fun loadInitialData(initialDate: LocalDate?) {
        val id = eventId
        Async.run(
            background = {
                val cals = CalendarStore.queryCalendars(this)
                val existing = id?.let { EventStore.queryEventDetail(this, it) }
                val colorCalendarId = existing?.calendarId ?: cals.firstOrNull()?.id
                val colors = colorCalendarId?.let { EventStore.queryEventColors(this, it) } ?: emptyList()
                val attendees = if (id != null && existing != null) EventStore.queryAttendees(this, id, existing.calendarId) else emptyList()
                LoadResult(cals, existing, colors, attendees)
            },
            onResult = { (cals, existing, colors, attendees) ->
                calendars = cals
                availableColors = colors
                originalAttendees = attendees
                if (existing != null) {
                    calendarIndex = cals.indexOfFirst { it.id == existing.calendarId }.coerceAtLeast(0)
                    title = existing.title
                    location = existing.location ?: ""
                    if (editScope != EditScope.ALL_INSTANCES && existing.rrule != null) {
                        // [existing] carries the *master* row's own DTSTART/DTEND — editing a
                        // single occurrence must start from the occurrence actually being
                        // turned into an exception, not the series' original date, or saving
                        // without touching Starts/Ends would write an exception whose own
                        // DTSTART doesn't match its ORIGINAL_INSTANCE_TIME (confirmed on-device:
                        // this produced a Jul-31 exception row for an Aug-1 occurrence). The same
                        // is true of THIS_AND_FOLLOWING's continuation event: it must start from
                        // the split instance, not the original series' first occurrence.
                        val duration = existing.endMillis - existing.startMillis
                        startMillis = instanceStartMillis
                        endMillis = instanceStartMillis + duration
                    } else {
                        startMillis = existing.startMillis
                        endMillis = existing.endMillis
                    }
                    allDay = existing.allDay
                    frequency = Recurrence.parseFrequency(existing.rrule)
                    reminderMinutes = existing.reminderMinutes
                    eventColorKey = existing.eventColorKey
                } else {
                    // The time-of-day is always "now" (rounded up to the next hour); only the
                    // date changes when [initialDate] says the user was looking at a day other
                    // than today (§7.x) — combining current wall-clock time with a different
                    // date via ZonedDateTime keeps this DST-safe.
                    val zone = ZoneId.systemDefault()
                    val roundedNow = Instant.ofEpochMilli(((System.currentTimeMillis() / 3_600_000L) + 1) * 3_600_000L).atZone(zone)
                    val roundedStart = roundedNow.toLocalTime().atDate(initialDate ?: roundedNow.toLocalDate()).atZone(zone).toInstant().toEpochMilli()
                    startMillis = roundedStart
                    endMillis = roundedStart + Prefs.defaultEventDurationMinutes(this) * 60_000L
                    calendarIndex = cals.indexOfFirst { it.id == Prefs.defaultCalendarId(this) }.coerceAtLeast(0)
                }
                loaded = true
                refreshVisibleFields()
                render()
            },
        )
    }

    private fun refreshVisibleFields() {
        visibleFields = FieldSlot.entries.filter { it != FieldSlot.COLOR || availableColors.isNotEmpty() }
        if (selectedField !in visibleFields) selectedField = visibleFields.first()
    }

    private fun render() {
        FieldSlot.entries.forEach { slot -> fieldRows.getValue(slot).visibility = if (slot in visibleFields) View.VISIBLE else View.GONE }
        fieldRows.getValue(FieldSlot.TITLE).text = "Title:     ${title.ifBlank { "(untitled)" }}"
        fieldRows.getValue(FieldSlot.CALENDAR).text = "Calendar:  ${calendars.getOrNull(calendarIndex)?.displayName ?: "none available"}"
        renderDateTimeRow(FieldSlot.STARTS, startMillis)
        // All-day DTEND is the exclusive boundary (the day *after* the last included day, per
        // §6.1.1) — shown/edited here as the last included day instead, since "Ends: 1 Aug" for
        // an event that's actually over by the end of 31 Jul would read as off-by-one to anyone
        // not steeped in the storage format.
        val endDisplayMillis = if (allDay) endMillis - 86_400_000L else endMillis
        renderDateTimeRow(FieldSlot.ENDS, endDisplayMillis)
        fieldRows.getValue(FieldSlot.ALL_DAY).text = "All day:   ${if (allDay) "yes" else "no"}"
        fieldRows.getValue(FieldSlot.REPEATS).text = "Repeats:   ${repeatsText()}"
        fieldRows.getValue(FieldSlot.REMINDER).text = "Reminder:  ${reminderText()}"
        if (FieldSlot.COLOR in visibleFields) {
            fieldRows.getValue(FieldSlot.COLOR).text = "Color:     ${eventColorKey ?: "calendar default"}"
        }
        fieldRows.getValue(FieldSlot.WHERE).text = "Where:     ${location.ifBlank { "none" }}"
        fieldRows.getValue(FieldSlot.ATTENDEES).text = "Attendees: ${attendeesSummary()}"

        (visibleFields - FieldSlot.STARTS - FieldSlot.ENDS).forEach { slot -> fieldRows.getValue(slot).applyRowSelectionStyle(slot == selectedField) }
        fieldRows[selectedField]?.let { row -> scrollView.post { scrollView.smoothScrollTo(0, row.top) } }
    }

    private fun labelFor(slot: FieldSlot): String = when (slot) {
        FieldSlot.TITLE -> "Title:     "
        FieldSlot.STARTS -> "Starts:    "
        FieldSlot.ENDS -> "Ends:      "
        FieldSlot.WHERE -> "Where:     "
        else -> ""
    }

    /** Starts/Ends render as a single line ("Starts:    Mon 3 Aug, 14:30") but the date and time
     *  are two independently selectable sub-fields (Left/Right, see [adjust]) — only the active
     *  one is highlighted (white + underline) while the row itself is selected, the label and the
     *  inactive sub-field stay dim, matching every other row's selected/unselected convention but
     *  at sub-string granularity instead of the whole row. */
    private fun renderDateTimeRow(slot: FieldSlot, displayMillis: Long) {
        val row = fieldRows.getValue(slot)
        val date = if (allDay) DateFmt.allDayToLocalDate(displayMillis) else Instant.ofEpochMilli(displayMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val dateText = "$dow ${date.dayOfMonth} $month"
        val timeText = if (allDay) null else DateFmt.formatTime(displayMillis)
        val label = labelFor(slot)
        val full = if (timeText != null) "$label$dateText, $timeText" else "$label$dateText"

        val selected = slot == selectedField
        val activeColumn = if (allDay) DateTimeColumn.DATE else dateTimeColumn
        val spannable = SpannableString(full)
        fun style(start: Int, end: Int, highlight: Boolean) {
            spannable.setSpan(ForegroundColorSpan(if (highlight) Color.WHITE else Palette.GRAY_DIM), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (highlight) spannable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        style(0, label.length, false)
        val dateStart = label.length
        val dateEnd = dateStart + dateText.length
        style(dateStart, dateEnd, selected && activeColumn == DateTimeColumn.DATE)
        if (timeText != null) {
            val timeStart = full.length - timeText.length
            style(timeStart, full.length, selected && activeColumn == DateTimeColumn.TIME)
        }
        row.text = spannable
    }

    private fun repeatsText(): String = Recurrence.buildRule(frequency, 1)?.let { Recurrence.describe(it) } ?: "no"

    private fun reminderText(): String = when (val minutes = reminderMinutes) {
        null -> "none"
        0 -> "at time of event"
        else -> if (minutes % 60 == 0) "${minutes / 60}h before" else "$minutes min before"
    }

    /** Every attendee currently staged to be on this event once saved — an existing event's own
     *  rows minus any marked for removal, plus whatever's been added this session — each paired
     *  with the action that un-stages it, for [openAttendeeMenu]'s "Remove ..." rows. */
    private fun stagedAttendees(): List<Pair<String, () -> Unit>> {
        val kept = originalAttendees.filter { it.id !in removedAttendeeIds }.map { attendee ->
            (attendee.name ?: attendee.email) to {
                removedAttendeeIds = removedAttendeeIds + attendee.id
                render()
            }
        }
        val pending = pendingNewAttendees.map { attendee ->
            (attendee.name ?: attendee.email) to {
                pendingNewAttendees = pendingNewAttendees - attendee
                render()
            }
        }
        return kept + pending
    }

    private fun attendeesSummary(): String = stagedAttendees().size.let { if (it == 0) "none" else "$it" }

    private fun openAttendeeMenu() {
        val items = mutableListOf(MenuItem("Add attendee") { openAttendeePicker() })
        stagedAttendees().forEach { (label, remove) -> items += MenuItem("Remove $label") { remove() } }
        menuSheet.show(items)
    }

    private fun openAttendeePicker() {
        startActivityForResult(Intent(this, AttendeePickerActivity::class.java), REQUEST_PICK_ATTENDEE)
    }

    private fun addPendingAttendee(email: String, name: String?) {
        pendingNewAttendees = pendingNewAttendees + PendingAttendee(email, name)
        render()
    }

    /** While inline-editing, the focused EditText would otherwise swallow Up/Down (cursor/focus
     *  search) and give OK/Back no defined meaning — intercepted here, ahead of normal dispatch,
     *  so all four always commit the field and (for Up/Down) move on, matching the rest of the
     *  app's "OK edits in place" rows. There's no cancel key: an accidental inline edit is no
     *  different from any other field change, since the event itself still isn't written until
     *  the top-level screen is saved. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inlineEditField != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    commitInlineEdit()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    commitInlineEdit()
                    moveSelection(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    commitInlineEdit()
                    moveSelection(1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        if (!loaded) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                adjust(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                adjust(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                onSelectField()
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                openMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                attemptSaveAndClose()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveSelection(direction: Int) {
        val index = visibleFields.indexOf(selectedField)
        selectedField = visibleFields[(index + direction + visibleFields.size) % visibleFields.size]
        dateTimeColumn = DateTimeColumn.DATE
        render()
    }

    private fun adjust(direction: Int) {
        when (selectedField) {
            FieldSlot.CALENDAR -> {
                if (calendars.isEmpty()) return
                calendarIndex = (calendarIndex + direction + calendars.size) % calendars.size
                reloadColorsForCalendar()
                return
            }
            FieldSlot.STARTS, FieldSlot.ENDS -> {
                if (!allDay) dateTimeColumn = if (direction < 0) DateTimeColumn.DATE else DateTimeColumn.TIME
            }
            FieldSlot.ALL_DAY -> toggleAllDay()
            FieldSlot.REPEATS -> {
                val values = RecurrenceFrequency.entries
                val index = (values.indexOf(frequency) + direction + values.size) % values.size
                frequency = values[index]
            }
            FieldSlot.REMINDER -> {
                val index = REMINDER_PRESETS.indexOf(reminderMinutes).let { if (it == -1) 0 else it }
                reminderMinutes = REMINDER_PRESETS[(index + direction + REMINDER_PRESETS.size) % REMINDER_PRESETS.size]
            }
            FieldSlot.COLOR -> {
                val options: List<String?> = listOf(null) + availableColors.map { it.key }
                val index = options.indexOf(eventColorKey).let { if (it == -1) 0 else it }
                eventColorKey = options[(index + direction + options.size) % options.size]
            }
            else -> {}
        }
        render()
    }

    /** Start/end are stored as either local-zone timed millis or UTC-midnight-boundary millis
     *  depending on [allDay] (§6.1.1) — flipping the toggle must reinterpret the existing values
     *  into the other representation, not just relabel them. Getting this wrong would silently
     *  write a timed event's local-zone millis straight into an ALL_DAY=1 row (wrong date near
     *  a UTC boundary) or read an all-day event's UTC-midnight millis as if they were local wall
     *  time (also wrong). */
    private fun toggleAllDay() {
        allDay = !allDay
        val zone = ZoneId.systemDefault()
        if (allDay) {
            val startDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate().coerceAtLeast(startDate)
            startMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            endMillis = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            val startDate = DateFmt.allDayToLocalDate(startMillis)
            startMillis = startDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            endMillis = startMillis + 3_600_000L
        }
    }

    private fun reloadColorsForCalendar() {
        val calendarId = calendars.getOrNull(calendarIndex)?.id ?: return
        Async.run(
            background = { EventStore.queryEventColors(this, calendarId) },
            onResult = { colors ->
                availableColors = colors
                if (eventColorKey !in colors.map { it.key }) eventColorKey = null
                refreshVisibleFields()
                render()
            },
        )
    }

    private fun onSelectField() {
        when (selectedField) {
            FieldSlot.TITLE -> beginInlineTextEdit(FieldSlot.TITLE, title)
            FieldSlot.WHERE -> beginInlineTextEdit(FieldSlot.WHERE, location)
            FieldSlot.STARTS -> onSelectDateTime(FieldSlot.STARTS)
            FieldSlot.ENDS -> onSelectDateTime(FieldSlot.ENDS)
            FieldSlot.ATTENDEES -> openAttendeeMenu()
            else -> adjust(1)
        }
    }

    /** The DATE sub-field always goes through [DatePickerActivity] (month grid, typed ddmm,
     *  quick rows — a numeric EditText would just reinvent a worse version of it); the TIME
     *  sub-field is a plain 4-digit inline edit, since 24h HHmm needs nothing more. All-day
     *  events have no time sub-field, so OK always means "pick the date" regardless of column. */
    private fun onSelectDateTime(slot: FieldSlot) {
        if (allDay || dateTimeColumn == DateTimeColumn.DATE) editDate(slot) else beginInlineTimeEdit(slot)
    }

    private fun beginInlineTextEdit(slot: FieldSlot, initialValue: String) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(initialValue)
            setSelection(text.length)
            // Kept as a best-effort hint even though it alone doesn't suppress the popup on this
            // device (confirmed live — see commitInlineEdit's recreate() call for the real fix).
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        inlineEditKind = InlineEditKind.TEXT
        swapRowForEdit(slot, editText, prefix = labelFor(slot))
    }

    private fun beginInlineTimeEdit(slot: FieldSlot) {
        val date = currentLocalDate(slot)
        val currentMillis = if (slot == FieldSlot.STARTS) startMillis else endMillis
        val currentTime = Instant.ofEpochMilli(currentMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "HHmm"
            setText("%02d%02d".format(currentTime.hour, currentTime.minute))
            setSelection(text.length)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        inlineEditKind = InlineEditKind.TIME
        inlineEditDate = date
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        swapRowForEdit(slot, editText, prefix = "${labelFor(slot)}$dow ${date.dayOfMonth} $month, ")
    }

    private fun currentLocalDate(slot: FieldSlot): LocalDate {
        val millis = if (slot == FieldSlot.STARTS) startMillis else endMillis
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    /** [prefix] (e.g. "Starts:    Mon 3 Aug, " or "Title:     ") is rendered as static text ahead
     *  of the editable value so the row keeps its label while being edited, instead of collapsing
     *  to a bare value (or bare "0900" for the time sub-field) with no indication of what it is. */
    private fun swapRowForEdit(slot: FieldSlot, editText: EditText, prefix: String) {
        editText.apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            background = null
            setPadding(0, dp(6), dp(SIDE_PADDING_DP), dp(6))
        }
        val rowView: View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE_PADDING_DP), 0, 0, 0)
            addView(
                TextView(this@EventEditActivity).apply {
                    text = prefix
                    textSize = 16f
                    setTextColor(Palette.GRAY_DIM)
                },
            )
            addView(editText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val index = FieldSlot.entries.indexOf(slot)
        rowContainer.removeViewAt(index)
        rowContainer.addView(rowView, index)
        inlineEditField = slot
        inlineEditText = editText
        // The ScrollView blocks descendant focus so the d-pad's own row navigation (selectedField)
        // never fights Android's focus search — but that also blocks this EditText from ever
        // taking focus. Lifted only while actually inline-editing, restored on commit.
        scrollView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        editText.requestFocus()
    }

    private fun commitInlineEdit() {
        val slot = inlineEditField ?: return
        val editText = inlineEditText ?: return
        when (inlineEditKind) {
            InlineEditKind.TEXT -> {
                val value = editText.text.toString().trim()
                when (slot) {
                    FieldSlot.TITLE -> title = value
                    FieldSlot.WHERE -> location = value
                    else -> {}
                }
            }
            InlineEditKind.TIME -> {
                val date = inlineEditDate ?: LocalDate.now()
                val digits = editText.text.toString().filter { it.isDigit() }.padStart(4, '0').takeLast(4)
                val hour = (digits.substring(0, 2).toIntOrNull() ?: 0).coerceIn(0, 23)
                val minute = (digits.substring(2, 4).toIntOrNull() ?: 0).coerceIn(0, 59)
                val millis = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                applyDateTime(slot, millis)
            }
            null -> {}
        }
        // Confirmed on-device: [keyFocusAnchor]'s requestFocus() must happen *before* the
        // EditText is removed from the hierarchy — moving focus while both are still attached is
        // the reliable transfer; clearFocus() alone (tried first) just re-granted the same,
        // now-detached EditText focus since nothing else in the row layout is focusable.
        keyFocusAnchor.requestFocus()
        val index = FieldSlot.entries.indexOf(slot)
        rowContainer.removeViewAt(index)
        rowContainer.addView(fieldRows.getValue(slot), index)
        scrollView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        inlineEditField = null
        inlineEditKind = null
        inlineEditText = null
        inlineEditDate = null
        render()
        // Formerly documented here as an unfixable T9-IME-level bug (DPAD_CENTER/STAR/BACK/#
        // permanently undelivered to dispatchKeyEvent after any EditText took real focus, cleared
        // only by a full force-stop + relaunch — arrow keys unaffected). Root-caused instead to
        // Android's own Autofill Framework: this device queries its active AutofillService
        // (confirmed: Bitwarden) the moment this EditText gets real focus, popping a real
        // "Autofill UI" system window that steals this Activity's window focus, even though the
        // field is excluded via importantForAutofill (confirmed on-device: that hint, and
        // FLAG_SECURE, are both advisory here, not enforced — neither stopped the popup). That
        // focus churn corrupts this window's key-dispatch state for the rest of the process.
        // No purely-preventive fix was found (tried: importantForAutofill on the view and on an
        // ancestor, FLAG_SECURE, deferring real focus to the first keystroke — none stopped the
        // trigger). What *does* work, confirmed live: recreating the Activity — its dispatch state
        // resets cleanly, exactly like the process-restart workaround but scoped to just this
        // screen. So every commit unconditionally self-heals by recreating right here, via
        // onRetainNonConfigurationInstance/RetainedState so no in-memory (not-yet-saved-to-
        // provider) edits are lost. See KNOWN_ISSUE_T9_IME.md for the full investigation history.
        recreate()
    }

    private fun editDate(target: FieldSlot) {
        pendingDateTimeTarget = target
        startActivityForResult(Intent(this, DatePickerActivity::class.java), REQUEST_PICK_DATE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_PICK_DATE -> {
                val epochDay = data.getLongExtra(DatePickerActivity.EXTRA_EPOCH_DAY, -1L)
                if (epochDay >= 0) onDatePicked(LocalDate.ofEpochDay(epochDay))
            }
            REQUEST_PICK_ATTENDEE -> {
                val name = data.getStringExtra(AttendeePickerActivity.EXTRA_NAME)
                val email = data.getStringExtra(AttendeePickerActivity.EXTRA_EMAIL)
                if (email != null) {
                    addPendingAttendee(email, name)
                } else {
                    // More than one email on the picked contact — asked here rather than in the
                    // picker itself, for the same reason EventDetailActivity does: no search
                    // field on this screen for the hardware T9 IME to keep composing into behind
                    // the menu (see AttendeePickerActivity's own doc comment).
                    val emails = data.getStringArrayExtra(AttendeePickerActivity.EXTRA_EMAILS) ?: return
                    menuSheet.show(emails.map { candidate -> MenuItem(candidate) { addPendingAttendee(candidate, name) } })
                }
            }
        }
    }

    private fun onDatePicked(date: LocalDate) {
        val target = pendingDateTimeTarget ?: return
        if (allDay) {
            // The picker shows/returns the last included day for Ends (see render()) — the
            // stored value is the exclusive boundary one day after it.
            val boundaryDate = if (target == FieldSlot.ENDS) date.plusDays(1) else date
            applyDateTime(target, boundaryDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        } else {
            // Date and time are independent sub-fields now — picking a new date keeps the
            // existing time-of-day rather than prompting for it, matching Right's own inline
            // time edit as a separate, explicit step.
            val currentMillis = if (target == FieldSlot.STARTS) startMillis else endMillis
            val currentTime = Instant.ofEpochMilli(currentMillis).atZone(ZoneId.systemDefault()).toLocalTime()
            applyDateTime(target, date.atTime(currentTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
    }

    private fun applyDateTime(target: FieldSlot, millis: Long) {
        if (target == FieldSlot.STARTS) {
            val duration = (endMillis - startMillis).coerceAtLeast(0L)
            startMillis = millis
            endMillis = millis + duration
        } else {
            endMillis = millis
            if (endMillis < startMillis) endMillis = startMillis
        }
        render()
    }

    private fun attemptSaveAndClose() {
        if (title.isBlank()) {
            // A plain AlertDialog's two buttons turned out not to be Left/Right-navigable on the
            // real device (confirmed on hardware: Right never moved focus off the default
            // button) — MenuSheet's numbered rows sidestep that entirely, consistent with every
            // other choice in the app.
            menuSheet.show(
                listOf(
                    MenuItem("Discard (no title)") { finish() },
                    MenuItem("Keep editing") {},
                ),
            )
        } else {
            save()
        }
    }

    private fun save() {
        val calendarId = calendars.getOrNull(calendarIndex)?.id
        if (calendarId == null) {
            Toast.makeText(this, "No calendar to save to", Toast.LENGTH_SHORT).show()
            return
        }
        val draft = EventDraft(
            calendarId = calendarId,
            title = title,
            location = location.ifBlank { null },
            startMillis = startMillis,
            endMillis = if (allDay) endMillis else endMillis.coerceAtLeast(startMillis + 60_000L),
            allDay = allDay,
            rrule = Recurrence.buildRule(frequency, 1),
            reminderMinutes = reminderMinutes,
            eventColorKey = eventColorKey,
        )
        val id = eventId
        val scope = editScope
        val instanceStart = instanceStartMillis
        val toRemove = removedAttendeeIds
        val toAdd = pendingNewAttendees
        Async.run(
            background = {
                // Attendees are written as a follow-up once a real event id exists — for a new
                // event that's only true after insertEvent succeeds, the same reason reminders
                // are written that way inside EventStore itself; these instead live at this
                // Activity's own level since the "staged" list is UI session state, not part of
                // EventDraft's data model.
                val resultId = if (id == null) {
                    EventStore.insertEvent(this, draft)
                } else if (EventStore.updateEvent(this, id, instanceStart, scope, draft)) {
                    id
                } else {
                    null
                }
                if (resultId != null) {
                    toRemove.forEach { EventStore.removeAttendee(this, it) }
                    toAdd.forEach { EventStore.addAttendee(this, resultId, it.email, it.name) }
                }
                resultId != null
            },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show()
                finish()
            },
        )
    }

    private fun openMenu() {
        val items = mutableListOf(MenuItem("Discard") { finish() })
        eventId?.let { id -> items.add(MenuItem("Delete") { confirmDelete(id) }) }
        menuSheet.show(items)
    }

    private fun confirmDelete(id: Long) {
        val scope = editScope
        val instanceStart = instanceStartMillis
        Async.run(
            background = { EventStore.deleteEvent(this, id, instanceStart, scope) },
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
        const val EXTRA_EDIT_SCOPE = "edit_scope"
        const val EXTRA_INITIAL_DATE = "initial_date"
    }
}
