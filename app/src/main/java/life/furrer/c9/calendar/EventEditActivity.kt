package life.furrer.c9.calendar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.LeadingMarginSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import life.furrer.nine.core.ui.ColorPickerActivity
import life.furrer.nine.core.ui.DatePickerActivity
import life.furrer.nine.core.ui.DatePickerMarkers
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.c9.core.Prefs
import life.furrer.c9.ui.InlineRowEditor
import life.furrer.c9.ui.columnHighlight
import life.furrer.nine.contacts.ContactValuePickerActivity
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.applyRowSelectionStyle
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar

private const val REQUEST_PICK_DATE = 200
private const val REQUEST_PICK_ATTENDEE = 201
private const val REQUEST_PICK_COLOR = 202
private const val REQUEST_EDIT_REPEAT = 203
private const val REQUEST_CALENDAR_PERMISSION = 203
private const val REQUEST_EDIT_NOTES = 204

/** How much of the notes' first line the editor's Notes row shows. */
private const val NOTES_PREVIEW_CHARS = 40

/** Lead times, in minutes, the Reminder row's menu offers for an added or changed reminder. */
private val REMINDER_PRESETS: List<Int> = listOf(0, 5, 10, 15, 30, 60, 1440)

/** The Label:value editor from §9.4 — same row shape as L9's SettingsActivity, reused verbatim.
 *  Date/time entry is a deliberate simplification from the doc's "typed digits with ← → moving
 *  between components": it reuses [DatePickerActivity] (already built, tested) for the date and
 *  a small numeric dialog for the time, rather than a new component-switching digit editor. Both
 *  are still keypad-only digit entry, just packaged as two steps instead of one continuous field —
 *  a reasonable trade given the milestone's real bar is a correct write path, not this screen's
 *  interaction polish.
 *
 *  Also the answer to other apps' `ACTION_INSERT` ("Add to calendar": a new event prefilled from
 *  [EventPrefill]) and `ACTION_EDIT` on an event Uri, read by [CalendarIntents]. Back saves and
 *  finishes as usual, which lands back in the calling app. */
class EventEditActivity : Activity() {

    // Show as/Privacy/Status come after Calendar: rarely changed, so they stay out of the way.
    private enum class FieldSlot { TITLE, STARTS, ENDS, ALL_DAY, REPEATS, WHERE, ATTENDEES, NOTES, REMINDER, COLOR, CALENDAR, SHOW_AS, PRIVACY, STATUS }

    /** An attendee added this session but not yet written — there's no real event id to attach
     *  it to until [save] actually inserts/updates the event, so it's held here exactly like
     *  every other unsaved field edit until then. */
    private data class PendingAttendee(val email: String, val name: String?)

    private var eventId: Long? = null
    /** A new event prefilled as a copy of this one ("Duplicate" on the detail screen). */
    private var duplicateOf: Long? = null
    private var instanceStartMillis: Long = 0L
    private var editScope: EditScope = EditScope.ALL_INSTANCES

    /** An external insert's extras — applied over a new event's defaults once on load. */
    private var prefill: EventPrefill? = null

    private var calendars: List<CalendarInfo> = emptyList()
    private var calendarIndex = 0
    private var title = ""
    private var location = ""
    // DESCRIPTION, edited on its own screen ([EventNotesActivity]). [loadedNotes] is what an
    // existing event came with, so a save leaves an untouched description exactly as synced.
    private var notes = ""
    private var loadedNotes = ""
    // Notes handed back before the event has (re)loaded - after the process died behind the notes
    // screen - applied once it has, so the load doesn't overwrite what was typed.
    private var pendingNotes: String? = null
    private var startMillis = 0L
    private var endMillis = 0L
    private var allDay = false
    private var frequency = RecurrenceFrequency.NONE

    // The existing event's own RRULE, kept verbatim: the editor only offers a frequency, so
    // rebuilding a rule from it would drop INTERVAL/BYDAY/COUNT/UNTIL (ticket #2). [currentRule]
    // hands this back unchanged while Repeats still shows its frequency.
    private var originalRrule: String? = null
    // The calendar an existing event was loaded from: its own reminders may already exceed that
    // calendar's MAX_REMINDERS (synced from elsewhere), so [save] only caps on a calendar switch.
    private var originalCalendarId: Long? = null

    // Every reminder the event will have once saved, shortest lead first. Existing ones come
    // in with their method, so a synced email reminder stays an email one (ticket #3); the menu
    // only ever adds plain alerts.
    private var reminders: List<EventReminder> = emptyList()
    private var eventColorKey: String? = null
    private var availability = Events.AVAILABILITY_BUSY
    private var accessLevel = Events.ACCESS_DEFAULT
    private var status: Int? = null
    // What the event came with: the base of each row's options (see EventFlags), and for an
    // existing event what a save compares against to write only what changed.
    private var loadedAvailability = Events.AVAILABILITY_BUSY
    private var loadedAccessLevel = Events.ACCESS_DEFAULT
    private var loadedStatus: Int? = null
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
    // Title/Where/Starts-time/Ends-time are edited in place, no dialog.
    private lateinit var inlineEditor: InlineRowEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        // An external request never reads the in-app extras below (this Activity is exported).
        val external = CalendarIntents.parse(intent)
        when (external) {
            is CalendarIntentRequest.Insert -> prefill = external.prefill
            is CalendarIntentRequest.EditEvent -> {
                eventId = external.eventId
                instanceStartMillis = external.instanceStartMillis ?: 0L
            }
            else -> {
                eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L).takeIf { it != -1L }
                instanceStartMillis = intent.getLongExtra(EXTRA_INSTANCE_START_MILLIS, 0L)
                editScope = intent.getStringExtra(EXTRA_EDIT_SCOPE)?.let { EditScope.valueOf(it) } ?: EditScope.ALL_INSTANCES
                duplicateOf = intent.getLongExtra(EXTRA_DUPLICATE_OF, -1L).takeIf { it != -1L }
            }
        }

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
        inlineEditor = InlineRowEditor(this, rowContainer, scrollView, onMove = ::moveSelection, onCommitted = ::render)

        hintBar = HintBar(this).apply { setText("* menu · ok edit field · ‹ done") }
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
        inlineEditor.attachTo(root)
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
            notes = retained.notes
            loadedNotes = retained.loadedNotes
            startMillis = retained.startMillis
            endMillis = retained.endMillis
            allDay = retained.allDay
            frequency = retained.frequency
            originalRrule = retained.originalRrule
            originalCalendarId = retained.originalCalendarId
            reminders = retained.reminders
            eventColorKey = retained.eventColorKey
            availability = retained.availability
            accessLevel = retained.accessLevel
            status = retained.status
            loadedAvailability = retained.loadedAvailability
            loadedAccessLevel = retained.loadedAccessLevel
            loadedStatus = retained.loadedStatus
            duplicateOf = retained.duplicateOf
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
        } else if (external != null && !(EventStore.hasPermission(this) && EventStore.hasWritePermission(this))) {
            // Reached from another app, so CalendarActivity's permission screen may never have run.
            requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQUEST_CALENDAR_PERMISSION,
            )
        } else {
            val initialDate = intent.getLongExtra(EXTRA_INITIAL_DATE, -1L).takeIf { it != -1L }?.let { LocalDate.ofEpochDay(it) }
            loadInitialData(initialDate)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CALENDAR_PERMISSION) return
        if (EventStore.hasPermission(this) && EventStore.hasWritePermission(this)) {
            loadInitialData(null)
        } else {
            Toast.makeText(this, "C9 needs access to your Calendar to save this", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** [prefill] over a new event's defaults (already set from Settings) — whatever the caller
     *  left out keeps its default. */
    private fun applyPrefill(prefill: EventPrefill) {
        prefill.title?.let { title = it }
        prefill.location?.let { location = it }
        prefill.description?.let { notes = it }
        val times = prefill.resolveTimes(startMillis, endMillis - startMillis, ZoneId.systemDefault())
        startMillis = times.startMillis
        endMillis = times.endMillis
        allDay = times.allDay
        // Kept whole like an existing event's rule, so a caller's BYDAY/COUNT/UNTIL survive.
        prefill.rrule?.let { originalRrule = it; frequency = Recurrence.parseFrequency(it) }
        pendingNewAttendees = prefill.attendeeEmails.map { PendingAttendee(it, null) }
    }

    /** All in-memory edits not yet written to the calendar provider (this whole event isn't saved
     *  until Back on the top-level screen) — carried across the [recreate] every inline edit's
     *  commit does (see [InlineRowEditor]) via [onRetainNonConfigurationInstance], so that
     *  self-heal doesn't discard unsaved work. Doesn't need to include the edit in progress:
     *  recreate() is only ever called once a commit has already finished and cleared it. */
    private data class RetainedState(
        val eventId: Long?,
        val instanceStartMillis: Long,
        val editScope: EditScope,
        val calendars: List<CalendarInfo>,
        val calendarIndex: Int,
        val title: String,
        val location: String,
        val notes: String,
        val loadedNotes: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val frequency: RecurrenceFrequency,
        val originalRrule: String?,
        val originalCalendarId: Long?,
        val reminders: List<EventReminder>,
        val eventColorKey: String?,
        val availability: Int,
        val accessLevel: Int,
        val status: Int?,
        val loadedAvailability: Int,
        val loadedAccessLevel: Int,
        val loadedStatus: Int?,
        val duplicateOf: Long?,
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
        eventId, instanceStartMillis, editScope, calendars, calendarIndex, title, location, notes, loadedNotes,
        startMillis, endMillis, allDay, frequency, originalRrule, originalCalendarId, reminders, eventColorKey,
        availability, accessLevel, status, loadedAvailability, loadedAccessLevel, loadedStatus, duplicateOf,
        availableColors,
        pendingDateTimeTarget, visibleFields, selectedField, dateTimeColumn,
        originalAttendees, removedAttendeeIds, pendingNewAttendees,
    )

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        inlineEditor.cancelAutofill()
    }

    private fun makeRow(): TextView = TextView(this).apply {
        textSize = 16f
        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(6), dp(Dimens.SIDE_PADDING_DP), dp(6))
    }

    private data class LoadResult(
        val calendars: List<CalendarInfo>,
        /** The event being edited, or for a duplicate the one being copied. */
        val existing: EventDetail?,
        val colors: List<EventColorOption>,
        val attendees: List<EventAttendee>,
        val calendarId: Long?,
    )

    private fun loadFlags(event: EventDetail) {
        availability = event.availability
        accessLevel = event.accessLevel
        status = event.status
        loadedAvailability = event.availability
        loadedAccessLevel = event.accessLevel
        loadedStatus = event.status
    }

    /** A new event that starts as a copy of [source] at the occurrence it was opened on, in the
     *  calendar at [index]. Attendees are left out: copying them would invite everyone again,
     *  from this account, to what may be someone else's meeting. */
    private fun applyCopy(source: EventDetail, index: Int, colors: List<EventColorOption>) {
        calendarIndex = index
        title = source.title
        location = source.location ?: ""
        val start = instanceStartMillis.takeIf { it != 0L } ?: source.startMillis
        startMillis = start
        endMillis = start + (source.endMillis - source.startMillis)
        allDay = source.allDay
        originalRrule = source.rrule
        frequency = Recurrence.parseFrequency(source.rrule)
        reminders = source.reminders
        eventColorKey = source.eventColorKey?.takeIf { key -> colors.any { it.key == key } }
        notes = source.description.orEmpty()
        loadFlags(source)
    }

    private fun loadInitialData(initialDate: LocalDate?) {
        val id = eventId
        val sourceId = duplicateOf
        Async.run(
            background = {
                val existing = id?.let { EventStore.queryEventDetail(this, it) }
                val source = sourceId?.let { EventStore.queryEventDetail(this, it) }
                val cals = CalendarStore.editorCalendars(CalendarStore.queryCalendars(this), existing?.calendarId)
                // A copy stays in its source's calendar when that one can take new events.
                val colorCalendarId = existing?.calendarId
                    ?: source?.calendarId?.takeIf { sourceCalendar -> cals.any { it.id == sourceCalendar } }
                    ?: cals.getOrNull(cals.indexOfFirst { it.id == Prefs.defaultCalendarId(this) }.coerceAtLeast(0))?.id
                val colors = colorCalendarId?.let { EventStore.queryEventColors(this, it) } ?: emptyList()
                val attendees = if (id != null && existing != null) EventStore.queryAttendees(this, id, existing.calendarId) else emptyList()
                LoadResult(cals, existing ?: source, colors, attendees, colorCalendarId)
            },
            onResult = { (cals, existing, colors, attendees, chosenCalendarId) ->
                calendars = cals
                availableColors = colors
                originalAttendees = attendees
                if (existing != null && duplicateOf != null) {
                    applyCopy(existing, cals.indexOfFirst { it.id == chosenCalendarId }.coerceAtLeast(0), colors)
                } else if (existing != null) {
                    calendarIndex = cals.indexOfFirst { it.id == existing.calendarId }.coerceAtLeast(0)
                    title = existing.title
                    location = existing.location ?: ""
                    notes = existing.description.orEmpty()
                    loadedNotes = notes
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
                    originalRrule = existing.rrule
                    originalCalendarId = existing.calendarId
                    frequency = Recurrence.parseFrequency(existing.rrule)
                    reminders = existing.reminders
                    eventColorKey = existing.eventColorKey
                    loadFlags(existing)
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
                    prefill?.let { applyPrefill(it) }
                }
                loaded = true
                pendingNotes?.let { applyNotes(it) }
                pendingNotes = null
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
        fieldRows.getValue(FieldSlot.TITLE).let { row -> row.text = hangingValue(row, labelFor(FieldSlot.TITLE), title.ifBlank { "(untitled)" }) }
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
        fieldRows.getValue(FieldSlot.WHERE).let { row -> row.text = hangingValue(row, labelFor(FieldSlot.WHERE), location.ifBlank { "none" }) }
        fieldRows.getValue(FieldSlot.ATTENDEES).text = attendeesFieldText()
        fieldRows.getValue(FieldSlot.NOTES).let { row -> row.text = hangingValue(row, labelFor(FieldSlot.NOTES), notesPreview()) }
        fieldRows.getValue(FieldSlot.SHOW_AS).text = "Show as:   ${EventFlags.availabilityLabel(availability)}"
        fieldRows.getValue(FieldSlot.PRIVACY).text = "Privacy:   ${EventFlags.accessLabel(accessLevel)}"
        fieldRows.getValue(FieldSlot.STATUS).text = "Status:    ${EventFlags.statusLabel(status)}"

        (visibleFields - FieldSlot.STARTS - FieldSlot.ENDS).forEach { slot -> fieldRows.getValue(slot).applyRowSelectionStyle(slot == selectedField) }
        fieldRows[selectedField]?.let { row -> scrollView.post { scrollView.smoothScrollTo(0, row.top) } }
    }

    /** The first line with text, cut short, and "…" when there's more - the row stays one line
     *  however long the notes are; the detail screen and [EventNotesActivity] show them whole. */
    private fun notesPreview(): String {
        val lines = notes.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return "none"
        val shown = first.take(NOTES_PREVIEW_CHARS)
        return if (shown.length < first.length || lines.size > 1) "${shown.trimEnd()} …" else shown
    }

    /** "Label:  value" where a value too long for one line wraps under the value, not back under
     *  the label - the indent is the label's own measured width, so it holds in either font. */
    private fun hangingValue(row: TextView, label: String, value: String): CharSequence =
        SpannableString(label + value).apply {
            setSpan(LeadingMarginSpan.Standard(0, row.paint.measureText(label).toInt()), 0, length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
        }

    private fun labelFor(slot: FieldSlot): String = when (slot) {
        FieldSlot.TITLE -> "Title:     "
        FieldSlot.STARTS -> "Starts:    "
        FieldSlot.ENDS -> "Ends:      "
        FieldSlot.WHERE -> "Where:     "
        FieldSlot.NOTES -> "Notes:     "
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
        val selected = slot == selectedField
        val activeColumn = if (allDay) DateTimeColumn.DATE else dateTimeColumn
        val dateSegment = dateText to (selected && activeColumn == DateTimeColumn.DATE)
        row.text = if (timeText != null) {
            columnHighlight(label to false, dateSegment, ", " to null, timeText to (selected && activeColumn == DateTimeColumn.TIME))
        } else {
            columnHighlight(label to false, dateSegment)
        }
    }

    /** The rule [save] writes and the Repeats row describes: [originalRrule] verbatim while
     *  [frequency] still matches it — untouched, or cycled away and back — otherwise a plain rule
     *  for the newly picked frequency (this editor has no controls for the detail parts). A rule
     *  whose FREQ this editor doesn't offer (e.g. HOURLY) parses as NONE, so NONE keeps it rather
     *  than silently dropping it. */
    private fun currentRule(): String? {
        val original = originalRrule
        return if (original != null && frequency == Recurrence.parseFrequency(original)) original else Recurrence.buildRule(frequency, 1)
    }

    private fun repeatsText(): String = currentRule()?.let { Recurrence.describe(it) } ?: "no"

    /** "none", or the lead times in one line with a single trailing "before" ("10 min, 1 day
     *  before"); a reminder that isn't a plain alert names its method ("10 min (email)"). */
    private fun reminderText(): String {
        if (reminders.isEmpty()) return "none"
        val labels = reminders.map { it.durationLabel() + it.methodTag.let { tag -> if (tag.isEmpty()) "" else " ($tag)" } }
        return labels.joinToString(", ") + if (reminders.any { it.minutes > 0 }) " before" else ""
    }

    private fun EventReminder.durationLabel(): String = when {
        minutes < 0 -> "default"
        minutes == 0 -> "at time of event"
        minutes % 1440 == 0 -> "${minutes / 1440} day${if (minutes / 1440 > 1) "s" else ""}"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "$minutes min"
    }

    /** The Reminder row's OK menu, the Attendees menu's shape: "Add reminder" (hidden once the
     *  calendar's `MAX_REMINDERS` is reached) and one "Change ..." row per reminder, which
     *  opens the presets plus "Remove". Nothing is written until the event is saved. */
    private fun openReminderMenu() {
        val items = mutableListOf<MenuItem>()
        val max = calendars.getOrNull(calendarIndex)?.maxReminders
        if (max == null || reminders.size < max) items += MenuItem("Add reminder") { openReminderPresets(null) }
        reminders.forEach { reminder -> items += MenuItem("Change ${reminder.durationLabel()}${reminder.methodTag.let { if (it.isEmpty()) "" else " ($it)" }}") { openReminderPresets(reminder) } }
        if (items.isEmpty()) return
        menuSheet.show(items)
    }

    /** Preset lead times for a new reminder, or for [existing] (which also gets a "Remove" row);
     *  a changed reminder keeps its method. Every preset keeps its own number, so the keys stay
     *  the same whatever the event already has; a lead time it already has with the method the
     *  result would get (a new reminder's alert, or [existing]'s own) is shown as "(set)" and
     *  can't be picked, so it can't leave two identical rows, and [existing]'s own lead time is marked "(current)". */
    private fun openReminderPresets(existing: EventReminder?) {
        val method = existing?.method ?: Reminders.METHOD_ALERT
        val items = REMINDER_PRESETS.map { minutes ->
            val taken = reminders.any { it.minutes == minutes && it.method == method && it != existing }
            val label = EventReminder(minutes).durationLabel().replaceFirstChar { it.uppercase() }.let { if (minutes > 0) "$it before" else it }
            val mark = when {
                taken -> " (set)"
                existing?.minutes == minutes -> " (current)"
                else -> ""
            }
            MenuItem(label + mark, enabled = !taken) {
                val updated = EventReminder(minutes, existing?.method ?: Reminders.METHOD_ALERT)
                // One occurrence only: synced data can hold two identical rows.
                reminders = reminders.toMutableList().apply { existing?.let { remove(it) }; add(updated) }.sortedBy { it.minutes }
                render()
            }
        }.toMutableList()
        if (existing != null) {
            items += MenuItem("Remove") {
                reminders = reminders.toMutableList().apply { remove(existing) }
                render()
            }
        }
        menuSheet.show(items)
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

    /** The full attendee list this event should end up with once saved, as plain (email, name)
     *  pairs rather than [stagedAttendees]'s UI-facing (label, un-stage action) shape — used by
     *  [save] to write the *final* set onto whichever row the write actually lands on, since a
     *  scoped edit (THIS_INSTANCE/THIS_AND_FOLLOWING) writes to a brand-new exception/
     *  continuation row that starts with zero attendees of its own (see [save]'s own comment). */
    private fun finalAttendees(): List<PendingAttendee> =
        originalAttendees.filter { it.id !in removedAttendeeIds }.map { PendingAttendee(it.email, it.name) } + pendingNewAttendees

    /** The Attendees row's whole rendered text — unlike every other field here, this one isn't a
     *  single "Label:  value" line, since the value is itself a list. Each name gets its own
     *  indented line under the label instead of just a count, so the field row grows to fit
     *  ([makeRow]'s TextView is added to [rowContainer] with no explicit height, which is
     *  LinearLayout's own WRAP_CONTENT default). */
    private fun attendeesFieldText(): String {
        val staged = stagedAttendees()
        if (staged.isEmpty()) return "Attendees: none"
        val names = staged.joinToString("\n") { (label, _) -> "    $label" }
        return "Attendees: ${staged.size}\n$names"
    }

    private fun openAttendeeMenu() {
        val items = mutableListOf(MenuItem("Add attendee") { openAttendeePicker() })
        stagedAttendees().forEach { (label, remove) -> items += MenuItem("Remove $label") { remove() } }
        menuSheet.show(items)
    }

    private fun openAttendeePicker() {
        val intent = Intent(this, ContactValuePickerActivity::class.java).apply {
            putExtra(ContactValuePickerActivity.EXTRA_KIND, DataKind.EMAIL.name)
        }
        startActivityForResult(intent, REQUEST_PICK_ATTENDEE)
    }

    private fun addPendingAttendee(email: String, name: String?) {
        pendingNewAttendees = pendingNewAttendees + PendingAttendee(email, name)
        render()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inlineEditor.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

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
            FieldSlot.SHOW_AS -> availability = EventFlags.cycle(EventFlags.availabilityOptions(loadedAvailability), availability, direction)
            FieldSlot.PRIVACY -> accessLevel = EventFlags.cycle(EventFlags.accessOptions(loadedAccessLevel), accessLevel, direction)
            FieldSlot.STATUS -> status = EventFlags.cycle(EventFlags.statusOptions(loadedStatus), status, direction)
            FieldSlot.REPEATS -> {
                val values = RecurrenceFrequency.entries
                val index = (values.indexOf(frequency) + direction + values.size) % values.size
                frequency = values[index]
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
            FieldSlot.NOTES -> startActivityForResult(EventNotesActivity.intent(this, notes), REQUEST_EDIT_NOTES)
            FieldSlot.REMINDER -> openReminderMenu()
            FieldSlot.COLOR -> editColor()
            FieldSlot.REPEATS -> editRepeat()
            else -> adjust(1)
        }
    }

    /** OK on Repeats opens the [RepeatActivity] (interval, weekdays, end) for a rule it can show
     *  completely; Left/Right still cycle the plain frequencies. A synced rule with more in it
     *  (another BY* part, "every 2nd Tuesday of March") is left alone with a word about why,
     *  rather than being flattened by a screen that can't represent it. */
    private fun editRepeat() {
        val rule = currentRule()
        if (rule != null && Recurrence.toSpec(rule, repeatStartDate()) == null) {
            Toast.makeText(this, "This repeat rule can't be edited here", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, RepeatActivity::class.java).apply {
            putExtra(RepeatActivity.EXTRA_RRULE, rule)
            putExtra(RepeatActivity.EXTRA_START_MILLIS, startMillis)
            putExtra(RepeatActivity.EXTRA_ALL_DAY, allDay)
        }
        startActivityForResult(intent, REQUEST_EDIT_REPEAT)
    }

    private fun repeatStartDate(): LocalDate =
        if (allDay) DateFmt.allDayToLocalDate(startMillis) else Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Same shared [ColorPickerActivity] as M9's unread-marker color, but with
     *  [ColorPickerActivity.EXTRA_ALLOW_CUSTOM] left off: unlike a free ARGB preference,
     *  `EVENT_COLOR_KEY` must be one of [availableColors] — the CalDAV account's own `Colors`
     *  table, queried per calendar in [reloadColorsForCalendar] — so an arbitrary typed RGB isn't
     *  a legal value to write back. Replaces the old Left/Right one-at-a-time cycle through the
     *  same list. */
    private fun editColor() {
        if (availableColors.isEmpty()) return
        val presetIndex = availableColors.indexOfFirst { it.key == eventColorKey }
        val intent = Intent(this, ColorPickerActivity::class.java).apply {
            putExtra(ColorPickerActivity.EXTRA_COLORS, availableColors.map { it.argb }.toIntArray())
            putExtra(ColorPickerActivity.EXTRA_LABELS, availableColors.map { it.key }.toTypedArray())
            putExtra(ColorPickerActivity.EXTRA_ALLOW_NONE, true)
            putExtra(ColorPickerActivity.EXTRA_NONE_LABEL, "calendar default")
            if (presetIndex >= 0) putExtra(ColorPickerActivity.EXTRA_INITIAL_INDEX, presetIndex)
        }
        startActivityForResult(intent, REQUEST_PICK_COLOR)
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
        }
        beginInlineEdit(slot, editText, prefix = labelFor(slot)) { typed ->
            val value = typed.trim()
            when (slot) {
                FieldSlot.TITLE -> title = value
                FieldSlot.WHERE -> location = value
                else -> {}
            }
        }
    }

    /** The TIME sub-field: four digits, 24h HHmm, on the row's own date. */
    private fun beginInlineTimeEdit(slot: FieldSlot) {
        val date = currentLocalDate(slot)
        val currentMillis = if (slot == FieldSlot.STARTS) startMillis else endMillis
        val currentTime = Instant.ofEpochMilli(currentMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "HHmm"
            setText("%02d%02d".format(currentTime.hour, currentTime.minute))
            setSelection(text.length)
        }
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        beginInlineEdit(slot, editText, prefix = "${labelFor(slot)}$dow ${date.dayOfMonth} $month, ") { typed ->
            val digits = typed.filter { it.isDigit() }.padStart(4, '0').takeLast(4)
            val hour = (digits.substring(0, 2).toIntOrNull() ?: 0).coerceIn(0, 23)
            val minute = (digits.substring(2, 4).toIntOrNull() ?: 0).coerceIn(0, 59)
            val millis = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            applyDateTime(slot, millis)
        }
    }

    private fun currentLocalDate(slot: FieldSlot): LocalDate {
        val millis = if (slot == FieldSlot.STARTS) startMillis else endMillis
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    /** The slot's own row goes back in once the edit is committed, re-rendered with the new value. */
    private fun beginInlineEdit(slot: FieldSlot, editText: EditText, prefix: String, onCommit: (String) -> Unit) {
        val row = fieldRows.getValue(slot)
        inlineEditor.begin(row, prefix, editText, onCommit, restoreRow = { row })
    }

    private fun editDate(target: FieldSlot) {
        pendingDateTimeTarget = target
        DatePickerMarkers.loader = { context, start, endExclusive ->
            if (!EventStore.hasPermission(context)) {
                emptySet()
            } else {
                val zone = ZoneId.systemDefault()
                val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMillis = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
                val selectedIds = CalendarStore.getSelectedCalendarIds(context)
                EventStore.queryRange(context, startMillis, endMillis, selectedIds)
                    .flatMap { CalendarRows.datesOf(it, zone) }
                    .filter { it >= start && it < endExclusive }
                    .toSet()
            }
        }
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
                val name = data.getStringExtra(ContactValuePickerActivity.EXTRA_NAME)
                val email = data.getStringExtra(ContactValuePickerActivity.EXTRA_VALUE)
                if (email != null) {
                    addPendingAttendee(email, name)
                } else {
                    // More than one email on the picked contact — asked here rather than in the
                    // picker itself, since this screen has no search field of its own for the
                    // hardware T9 IME to keep composing into behind the menu (see
                    // ContactValuePickerActivity's own doc comment).
                    val emails = data.getStringArrayExtra(ContactValuePickerActivity.EXTRA_VALUES) ?: return
                    val labels = data.getStringArrayExtra(ContactValuePickerActivity.EXTRA_LABELS)
                    menuSheet.show(
                        emails.mapIndexed { index, candidate ->
                            val label = labels?.getOrNull(index)?.takeIf { it.isNotEmpty() }
                            val text = if (label != null) "$label: $candidate" else candidate
                            MenuItem(text) { addPendingAttendee(candidate, name) }
                        },
                    )
                }
            }
            REQUEST_EDIT_NOTES -> data.getStringExtra(EventNotesActivity.EXTRA_TEXT)?.let { text ->
                if (loaded) applyNotes(text) else pendingNotes = text
            }
            REQUEST_EDIT_REPEAT -> data.getStringExtra(RepeatActivity.EXTRA_RRULE)?.let { rule ->
                originalRrule = rule
                frequency = Recurrence.parseFrequency(rule)
                render()
            }
            REQUEST_PICK_COLOR -> {
                eventColorKey = if (data.getBooleanExtra(ColorPickerActivity.EXTRA_RESULT_IS_NONE, false)) {
                    null
                } else {
                    availableColors.getOrNull(data.getIntExtra(ColorPickerActivity.EXTRA_RESULT_INDEX, -1))?.key
                }
                render()
            }
        }
    }

    /** All-whitespace counts as no notes, so it clears rather than saves blanks - unless the event
     *  came with blank notes, which stay as they were rather than count as a change. */
    private fun applyNotes(text: String) {
        notes = if (text.isBlank() && loadedNotes.isBlank()) loadedNotes else text.ifBlank { "" }
        render()
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
            rrule = currentRule(),
            // A calendar switch can leave more staged than the new calendar allows: keep the
            // first N (shortest lead first) rather than write too many. Staying in the event's own
            // calendar never trims what it already has.
            reminders = calendars.getOrNull(calendarIndex)
                ?.takeIf { it.id != originalCalendarId }
                ?.maxReminders?.let { reminders.take(it) } ?: reminders,
            eventColorKey = eventColorKey,
            // An edit writes the notes only if they changed ("" clears them); a new event only if it has some.
            description = if (eventId != null) notes.takeIf { it != loadedNotes } else notes.ifEmpty { null },
            // An edit writes only what changed; a new event (a copy too) only what isn't the default.
            availability = availability.takeIf { it != if (eventId != null) loadedAvailability else Events.AVAILABILITY_BUSY },
            accessLevel = accessLevel.takeIf { it != if (eventId != null) loadedAccessLevel else Events.ACCESS_DEFAULT },
            status = status.takeIf { it != if (eventId != null) loadedStatus else null },
        )
        val id = eventId
        val scope = editScope
        val instanceStart = instanceStartMillis
        val toRemove = removedAttendeeIds
        val toAdd = pendingNewAttendees
        val finalAttendeeSet = finalAttendees()
        Async.run(
            background = {
                // Attendees are written as a follow-up once a real event id exists — for a new
                // event that's only true after insertEvent succeeds, the same reason reminders
                // are written that way inside EventStore itself; these instead live at this
                // Activity's own level since the "staged" list is UI session state, not part of
                // EventDraft's data model.
                val resultId = if (id == null) EventStore.insertEvent(this, draft) else EventStore.updateEvent(this, id, instanceStart, scope, draft)
                if (resultId != null) {
                    if (resultId == id) {
                        // Same row that [originalAttendees] was loaded from (ALL_INSTANCES on an
                        // existing event) — apply the diff the user actually made.
                        toRemove.forEach { EventStore.removeAttendee(this, it) }
                        toAdd.forEach { EventStore.addAttendee(this, resultId, it.email, it.name) }
                    } else {
                        // A brand-new row: a brand-new event (nothing on it yet), a
                        // THIS_AND_FOLLOWING continuation (starts empty), or a THIS_INSTANCE
                        // exception, which the provider creates with a copy of the original's
                        // attendees. So diff the staged set against the rows the new event
                        // actually has (by email, case-insensitively) instead of adding blindly,
                        // or the copied attendees would be listed twice.
                        val existing = EventStore.queryAttendees(this, resultId, calendarId)
                        val wanted = finalAttendeeSet.map { it.email.lowercase() }.toSet()
                        existing.filter { it.email.lowercase() !in wanted }
                            .forEach { EventStore.removeAttendee(this, it.id) }
                        val have = existing.map { it.email.lowercase() }.toSet()
                        finalAttendeeSet.distinctBy { it.email.lowercase() }
                            .filter { it.email.lowercase() !in have }
                            .forEach { EventStore.addAttendee(this, resultId, it.email, it.name) }
                    }
                }
                resultId
            },
            onResult = { resultId ->
                if (resultId == null) {
                    Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show()
                } else {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_EVENT_ID, resultId))
                }
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

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_INSTANCE_START_MILLIS = "instance_start_millis"
        const val EXTRA_EDIT_SCOPE = "edit_scope"
        const val EXTRA_INITIAL_DATE = "initial_date"
        /** Opens a new event prefilled from this event id (with [EXTRA_INSTANCE_START_MILLIS] naming the occurrence). */
        const val EXTRA_DUPLICATE_OF = "duplicate_of"
        /** In the saved result: the row the event was written to - for a "this event" or "this
         *  and following" edit a new one (see [EventStore.updateEvent]), not [EXTRA_EVENT_ID]. */
        const val EXTRA_RESULT_EVENT_ID = "result_event_id"
    }
}
