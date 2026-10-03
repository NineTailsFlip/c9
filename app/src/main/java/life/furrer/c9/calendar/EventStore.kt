package life.furrer.c9.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.util.TimeZone
import life.furrer.nine.core.ui.Palette

data class EventEntry(
    val eventId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
    val calendarId: Long,
    val displayColor: Int,
    val rrule: String?,
)

data class EventDetail(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    /** `Events.DESCRIPTION` — RFC 5545's DESCRIPTION, free text that's often long and multi-line
     *  (meeting links, agendas). Shown read-only: the editor never writes it, so an edit leaves
     *  whatever is there untouched. */
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val rrule: String?,
    val reminderMinutes: Int?,
    val eventColorKey: String?,
    val displayColor: Int,
    /** One of `Attendees.ATTENDEE_STATUS_*` — the current account's own participation status per
     *  RFC 5545's PARTSTAT, kept in sync by the provider onto the Events row itself so reading it
     *  doesn't require cross-referencing the Attendees table. STATUS_NONE (the default) means
     *  either there's no attendee list at all or this account isn't on it — either way, nothing
     *  to respond to. */
    val selfAttendeeStatus: Int,
    /** `Events.DIRTY` — true when this row has local edits DAVx5 hasn't pushed upstream yet
     *  (§ per-item sync status shown on the CALENDAR line). Never true for a syncadapter write,
     *  only for edits made through this app or the platform calendar UI. */
    val dirty: Boolean,
)

/** An RFC 5545 ATTENDEE on an event, one row per invitee including the organizer (relationship
 *  distinguishes them — see [Attendees.ATTENDEE_RELATIONSHIP_ORGANIZER]). [status] is the
 *  attendee's own PARTSTAT (`Attendees.ATTENDEE_STATUS_*`). [isSelf] is true for the row matching
 *  the calendar's own owner account — the one [EventStore.respondToInvite] answers for. */
data class EventAttendee(
    val id: Long,
    val name: String?,
    val email: String,
    val status: Int,
    val relationship: Int,
    val isSelf: Boolean,
) {
    val isOrganizer: Boolean get() = relationship == Attendees.RELATIONSHIP_ORGANIZER
}

/** What the editor writes back — shared by insert and both update scopes. */
data class EventDraft(
    val calendarId: Long,
    val title: String,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val rrule: String?,
    val reminderMinutes: Int?,
    val eventColorKey: String?,
    /** The editor has no Notes field, so this is only ever set for an insert handed over by
     *  another app ("Add to calendar"); null leaves whatever DESCRIPTION the row has alone. */
    val description: String? = null,
)

data class EventColorOption(val key: String, val argb: Int)

/** Which instances an edit/delete applies to (§6.1.3). */
enum class EditScope { ALL_INSTANCES, THIS_INSTANCE, THIS_AND_FOLLOWING }

object EventStore {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Instances in [startMillis, endMillis), earliest first. Reads through the expansion URI —
     *  the provider does recurrence expansion, so this never parses an RRULE itself (§6.1).
     *  [selectedCalendarIds] null means "all calendars" (matches [CalendarStore]'s own
     *  null-means-default-all convention); filtering happens in memory, same style as L9's
     *  CalendarRepository — the row counts here (a few days at a time) are small enough that a
     *  SQL selection clause isn't worth the complexity. */
    fun queryRange(
        context: Context,
        startMillis: Long,
        endMillis: Long,
        selectedCalendarIds: Set<Long>?,
    ): List<EventEntry> {
        if (!hasPermission(context)) return emptyList()

        val uri = ContentUris.appendId(
            ContentUris.appendId(Instances.CONTENT_URI.buildUpon(), startMillis),
            endMillis,
        ).build()

        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.EVENT_LOCATION,
            Instances.CALENDAR_ID,
            Instances.DISPLAY_COLOR,
            Instances.RRULE,
        )

        val results = mutableListOf<EventEntry>()
        context.contentResolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { cursor ->
            val eventIdIndex = cursor.getColumnIndexOrThrow(Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(Instances.ALL_DAY)
            val locationIndex = cursor.getColumnIndexOrThrow(Instances.EVENT_LOCATION)
            val calendarIdIndex = cursor.getColumnIndexOrThrow(Instances.CALENDAR_ID)
            val colorIndex = cursor.getColumnIndexOrThrow(Instances.DISPLAY_COLOR)
            val rruleIndex = cursor.getColumnIndexOrThrow(Instances.RRULE)
            while (cursor.moveToNext()) {
                val calendarId = cursor.getLong(calendarIdIndex)
                if (selectedCalendarIds != null && calendarId !in selectedCalendarIds) continue
                results.add(
                    EventEntry(
                        eventId = cursor.getLong(eventIdIndex),
                        title = cursor.getString(titleIndex) ?: "(untitled)",
                        startMillis = cursor.getLong(beginIndex),
                        endMillis = cursor.getLong(endIndex),
                        allDay = cursor.getInt(allDayIndex) != 0,
                        location = cursor.getString(locationIndex),
                        calendarId = calendarId,
                        displayColor = cursor.getInt(colorIndex),
                        rrule = cursor.getString(rruleIndex),
                    )
                )
            }
        }
        return results
    }

    /** A wide-but-bounded window for the calendar's own Search screen (§7.3): searches match by
     *  title/location across roughly a 2-year span, wide enough to cover almost any real search
     *  while still going through the same recurrence-expanding [queryRange] as everything else —
     *  so a match on a recurring series shows a real occurrence's own time, not just the series'
     *  original definition. */
    fun querySearchWindow(context: Context, selectedCalendarIds: Set<Long>?): List<EventEntry> {
        val now = System.currentTimeMillis()
        val oneYearMillis = 365L * 24 * 60 * 60 * 1000
        return queryRange(context, now - oneYearMillis, now + oneYearMillis, selectedCalendarIds)
    }

    /** The full Events-table row for the detail/edit screens — Instances only carries what a
     *  list row needs. A recurring event's Events row has DURATION instead of DTEND (the
     *  provider requires this), so [endMillis] is computed from whichever one is actually set. */
    fun queryEventDetail(context: Context, eventId: Long): EventDetail? {
        if (!hasPermission(context)) return null
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val projection = arrayOf(
            Events._ID,
            Events.CALENDAR_ID,
            Events.TITLE,
            Events.EVENT_LOCATION,
            Events.DESCRIPTION,
            Events.DTSTART,
            Events.DTEND,
            Events.DURATION,
            Events.ALL_DAY,
            Events.RRULE,
            Events.EVENT_COLOR_KEY,
            Events.DISPLAY_COLOR,
            Events.SELF_ATTENDEE_STATUS,
            Events.DIRTY,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val start = cursor.getLong(cursor.getColumnIndexOrThrow(Events.DTSTART))
            val dtEndIndex = cursor.getColumnIndexOrThrow(Events.DTEND)
            val end = if (!cursor.isNull(dtEndIndex)) {
                cursor.getLong(dtEndIndex)
            } else {
                val duration = cursor.getString(cursor.getColumnIndexOrThrow(Events.DURATION))
                start + (duration?.let(::parseIcalDurationMillis) ?: 0L)
            }
            return EventDetail(
                eventId = cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID)),
                calendarId = cursor.getLong(cursor.getColumnIndexOrThrow(Events.CALENDAR_ID)),
                title = cursor.getString(cursor.getColumnIndexOrThrow(Events.TITLE)) ?: "(untitled)",
                location = cursor.getString(cursor.getColumnIndexOrThrow(Events.EVENT_LOCATION)),
                description = cursor.getString(cursor.getColumnIndexOrThrow(Events.DESCRIPTION)),
                startMillis = start,
                endMillis = end,
                allDay = cursor.getInt(cursor.getColumnIndexOrThrow(Events.ALL_DAY)) != 0,
                rrule = cursor.getString(cursor.getColumnIndexOrThrow(Events.RRULE)),
                reminderMinutes = queryReminderMinutes(context, eventId),
                eventColorKey = cursor.getString(cursor.getColumnIndexOrThrow(Events.EVENT_COLOR_KEY)),
                displayColor = cursor.getInt(cursor.getColumnIndexOrThrow(Events.DISPLAY_COLOR)),
                selfAttendeeStatus = cursor.getInt(cursor.getColumnIndexOrThrow(Events.SELF_ATTENDEE_STATUS)),
                dirty = cursor.getInt(cursor.getColumnIndexOrThrow(Events.DIRTY)) != 0,
            )
        }
        return null
    }

    private fun queryReminderMinutes(context: Context, eventId: Long): Int? {
        val projection = arrayOf(Reminders.MINUTES)
        context.contentResolver.query(
            Reminders.CONTENT_URI, projection, "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(cursor.getColumnIndexOrThrow(Reminders.MINUTES))
        }
        return null
    }

    /** Every attendee on [eventId] (organizer included — see [EventAttendee.isOrganizer]),
     *  in the provider's own row order, which for CalDAV-synced events matches the ATTENDEE
     *  lines' order in the underlying iCalendar data. [isSelf] is worked out by comparing each
     *  attendee's email against the calendar's own [Calendars.OWNER_ACCOUNT] rather than trusting
     *  any single row to say so, since nothing in the Attendees table marks that directly. */
    fun queryAttendees(context: Context, eventId: Long, calendarId: Long): List<EventAttendee> {
        if (!hasPermission(context)) return emptyList()
        val ownerAccount = queryCalendarOwnerAccount(context, calendarId)
        val projection = arrayOf(
            Attendees._ID,
            Attendees.ATTENDEE_NAME,
            Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_STATUS,
            Attendees.ATTENDEE_RELATIONSHIP,
        )
        val results = mutableListOf<EventAttendee>()
        context.contentResolver.query(
            Attendees.CONTENT_URI, projection, "${Attendees.EVENT_ID}=?", arrayOf(eventId.toString()), null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Attendees._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(Attendees.ATTENDEE_NAME)
            val emailIndex = cursor.getColumnIndexOrThrow(Attendees.ATTENDEE_EMAIL)
            val statusIndex = cursor.getColumnIndexOrThrow(Attendees.ATTENDEE_STATUS)
            val relationshipIndex = cursor.getColumnIndexOrThrow(Attendees.ATTENDEE_RELATIONSHIP)
            while (cursor.moveToNext()) {
                val email = cursor.getString(emailIndex) ?: continue
                results.add(
                    EventAttendee(
                        id = cursor.getLong(idIndex),
                        name = cursor.getString(nameIndex),
                        email = email,
                        status = cursor.getInt(statusIndex),
                        relationship = cursor.getInt(relationshipIndex),
                        isSelf = ownerAccount != null && ownerAccount.equals(email, ignoreCase = true),
                    ),
                )
            }
        }
        return results
    }

    /** Adds [email] (with an optional display [name]) as a required attendee, per RFC 5545 with
     *  PARTSTAT starting at NEEDS-ACTION (`ATTENDEE_STATUS_INVITED`) — the organizer hasn't heard
     *  back yet. Whether this actually delivers an invitation is up to the calendar's own sync
     *  adapter/server (e.g. a CalDAV server with scheduling support); this only writes the
     *  attendee data the same way any calendar client would. */
    fun addAttendee(context: Context, eventId: Long, email: String, name: String?): Boolean {
        if (!hasWritePermission(context)) return false
        val values = ContentValues().apply {
            put(Attendees.EVENT_ID, eventId)
            put(Attendees.ATTENDEE_EMAIL, email)
            if (!name.isNullOrBlank()) put(Attendees.ATTENDEE_NAME, name)
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
            put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED)
        }
        return context.contentResolver.insert(Attendees.CONTENT_URI, values) != null
    }

    /** Confirmed on-device against the real CalendarProvider2: a bare `Attendees.CONTENT_URI`
     *  delete with a `"_id=?"` selection crashes the provider itself —
     *  `SQLiteException: ambiguous column name: _id`, because its delete path joins
     *  Attendees/Events/Calendars to check sync-adapter rules first, and every one of those
     *  tables has its own `_id` column. The ID-appended URI form (the same shape already used
     *  for Events elsewhere in this file) sidesteps it entirely — no selection string for the
     *  provider's join to trip over. */
    fun removeAttendee(context: Context, attendeeId: Long): Boolean {
        if (!hasWritePermission(context)) return false
        val uri = ContentUris.withAppendedId(Attendees.CONTENT_URI, attendeeId)
        return context.contentResolver.delete(uri, null, null) > 0
    }

    /** Answers an invitation by writing [status] (one of `Attendees.ATTENDEE_STATUS_*`) onto this
     *  account's own Attendees row. Confirmed on-device against the real CalendarProvider2:
     *  [Events.SELF_ATTENDEE_STATUS] is read-only from the outside —
     *  `IllegalArgumentException: Updating selfAttendeeStatus in Events table is not allowed`,
     *  from both a bulk and an ID-appended update — despite the column existing precisely to
     *  answer "am I invited, and how did I respond" without a join. The provider computes it
     *  itself from the Attendees table and mirrors it onto the Events row automatically the
     *  moment the matching Attendees row changes (confirmed live: updating the self row's own
     *  ATTENDEE_STATUS immediately updates SELF_ATTENDEE_STATUS too) — so this writes there
     *  instead, the same place a real CalDAV client would. */
    fun respondToInvite(context: Context, eventId: Long, calendarId: Long, status: Int): Boolean {
        if (!hasWritePermission(context)) return false
        val self = queryAttendees(context, eventId, calendarId).firstOrNull { it.isSelf } ?: return false
        val uri = ContentUris.withAppendedId(Attendees.CONTENT_URI, self.id)
        val values = ContentValues().apply { put(Attendees.ATTENDEE_STATUS, status) }
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    private fun queryCalendarOwnerAccount(context: Context, calendarId: Long): String? {
        val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)
        context.contentResolver.query(uri, arrayOf(Calendars.OWNER_ACCOUNT), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(cursor.getColumnIndexOrThrow(Calendars.OWNER_ACCOUNT))
        }
        return null
    }

    /** The event colors available for whichever account owns [calendarId] — empty when the
     *  account hasn't populated any (confirmed on-device: DAVx⁵ does for some accounts, not
     *  others, per §12). Callers should hide the color field entirely when this is empty.
     *  Returned in hue order (see [Palette.paletteSortKey]), not the alphabetical-by-key order the
     *  provider itself has no particular reason to return them in — `ColorPickerActivity`'s grid
     *  reads as a rainbow this way, its whole reason for existing over the old Left/Right
     *  one-key-at-a-time cycle (which never showed more than one color at once, so this ordering
     *  never mattered there). */
    fun queryEventColors(context: Context, calendarId: Long): List<EventColorOption> {
        if (!hasPermission(context)) return emptyList()
        val account = queryCalendarAccount(context, calendarId) ?: return emptyList()
        val projection = arrayOf(Colors.COLOR_KEY, Colors.COLOR)
        val selection = "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=? AND ${Colors.COLOR_TYPE}=?"
        val args = arrayOf(account.name, account.type, Colors.TYPE_EVENT.toString())
        val results = mutableListOf<EventColorOption>()
        context.contentResolver.query(Colors.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            val keyIndex = cursor.getColumnIndexOrThrow(Colors.COLOR_KEY)
            val colorIndex = cursor.getColumnIndexOrThrow(Colors.COLOR)
            while (cursor.moveToNext()) {
                results.add(EventColorOption(cursor.getString(keyIndex), cursor.getInt(colorIndex)))
            }
        }
        return results.sortedBy { Palette.paletteSortKey(it.argb) }
    }

    private data class CalendarAccount(val name: String, val type: String)

    private fun queryCalendarAccount(context: Context, calendarId: Long): CalendarAccount? {
        val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)
        val projection = arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return CalendarAccount(
                    cursor.getString(cursor.getColumnIndexOrThrow(Calendars.ACCOUNT_NAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(Calendars.ACCOUNT_TYPE)),
                )
            }
        }
        return null
    }

    /** @return the new event's id, or null if the write failed/wasn't permitted. Never appends
     *  CALLER_IS_SYNCADAPTER — that flag means "this came from the server", and setting it would
     *  silently create local-only data that never syncs (§6.1.2). */
    fun insertEvent(context: Context, draft: EventDraft): Long? {
        if (!hasWritePermission(context)) return null
        return insertEventRow(context, draft, eventValues(draft))
    }

    private fun insertEventRow(context: Context, draft: EventDraft, values: ContentValues): Long? {
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values) ?: return null
        val eventId = ContentUris.parseId(uri)
        writeReminder(context, eventId, draft.reminderMinutes)
        return eventId
    }

    /** [instanceStartMillis] (the instance's own BEGIN) is required for [EditScope.THIS_INSTANCE]
     *  and [EditScope.THIS_AND_FOLLOWING], and ignored for [EditScope.ALL_INSTANCES]. */
    /** @return the id of the Events row [draft] actually ended up written to, or null on
     *  failure — for [EditScope.ALL_INSTANCES] that's still [eventId], but
     *  [EditScope.THIS_INSTANCE] and [EditScope.THIS_AND_FOLLOWING] both write to a *new* row (an
     *  exception, or a continuation event) with its own id, distinct from [eventId]. Callers that
     *  need to attach anything else to the row this write actually produced (e.g. attendees) must
     *  use the returned id, not [eventId] — using [eventId] for those two scopes would silently
     *  attach to the wrong row. */
    fun updateEvent(
        context: Context,
        eventId: Long,
        instanceStartMillis: Long?,
        scope: EditScope,
        draft: EventDraft,
    ): Long? {
        if (!hasWritePermission(context)) return null
        return when (scope) {
            EditScope.ALL_INSTANCES -> {
                val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
                val updated = context.contentResolver.update(uri, eventValues(draft), null, null) > 0
                if (updated) writeReminder(context, eventId, draft.reminderMinutes)
                if (updated) eventId else null
            }
            EditScope.THIS_INSTANCE -> {
                val instanceTime = requireNotNull(instanceStartMillis) {
                    "THIS_INSTANCE requires the instance's own start time"
                }
                val values = eventValues(draft, forException = true).apply {
                    put(Events.ORIGINAL_INSTANCE_TIME, instanceTime)
                }
                val exceptionUri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId)
                val resultUri = context.contentResolver.insert(exceptionUri, values) ?: return null
                val exceptionId = ContentUris.parseId(resultUri)
                writeReminder(context, exceptionId, draft.reminderMinutes)
                exceptionId
            }
            EditScope.THIS_AND_FOLLOWING -> {
                // The continuation is a brand-new row built only from the editor's fields, and
                // the editor never touches DESCRIPTION - carry the original's over, or the rest
                // of the series silently loses it.
                val original = queryEventDetail(context, eventId)
                val continuation = original?.let { continuationDraft(context, eventId, it.startMillis, instanceStartMillis, draft) } ?: draft
                if (!truncateSeriesBefore(context, eventId, instanceStartMillis)) return null
                insertEventRow(context, continuation, eventValues(continuation).apply { put(Events.DESCRIPTION, original?.description) })
            }
        }
    }

    fun deleteEvent(context: Context, eventId: Long, instanceStartMillis: Long?, scope: EditScope): Boolean {
        if (!hasWritePermission(context)) return false
        return when (scope) {
            // A normal delete() marks the row deleted for sync rather than dropping it — that's
            // the provider's own doing, not something this code needs to arrange (§6.1.2).
            EditScope.ALL_INSTANCES -> {
                val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
                context.contentResolver.delete(uri, null, null) > 0
            }
            // Deleting one instance is the same exception insert as editing it, with
            // STATUS_CANCELED instead of new field values (§6.1.3).
            EditScope.THIS_INSTANCE -> {
                val instanceTime = requireNotNull(instanceStartMillis) {
                    "THIS_INSTANCE requires the instance's own start time"
                }
                val values = ContentValues().apply {
                    put(Events.ORIGINAL_INSTANCE_TIME, instanceTime)
                    put(Events.STATUS, Events.STATUS_CANCELED)
                }
                val exceptionUri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId)
                context.contentResolver.insert(exceptionUri, values) != null
            }
            // Deleting "this and following" is just the truncation half of the update case —
            // there's no continuation event to insert since nothing replaces the deleted tail.
            EditScope.THIS_AND_FOLLOWING -> truncateSeriesBefore(context, eventId, instanceStartMillis)
        }
    }

    /** A "this and following" continuation keeps the series' own rule — UNTIL needs nothing, the
     *  continuation ends where the original would have — but a COUNT counts from the series'
     *  first occurrence, so carrying it over as-is would add every occurrence already past the
     *  split on top. Lowered here by however many occurred before [splitMillis]. */
    private fun continuationDraft(context: Context, eventId: Long, seriesStartMillis: Long, splitMillis: Long?, draft: EventDraft): EventDraft {
        val rule = draft.rrule ?: return draft
        val count = Recurrence.countOf(rule) ?: return draft
        val before = countOccurrencesBefore(context, eventId, seriesStartMillis, splitMillis ?: return draft) ?: return draft
        return draft.copy(rrule = Recurrence.withCount(rule, (count - before).coerceAtLeast(1)))
    }

    /** How many of [eventId]'s occurrences start before [beforeMillis], as the provider itself
     *  expanded them (§6.1: never computed here). An occurrence turned into an exception — moved,
     *  or canceled ("delete this event only") — drops out of the master's own Instances but still
     *  used up one of its COUNT, so exception rows are counted by their ORIGINAL_INSTANCE_TIME
     *  instead. Null if either query fails. */
    private fun countOccurrencesBefore(context: Context, eventId: Long, seriesStartMillis: Long, beforeMillis: Long): Int? {
        // Instances are returned by overlap with the window, so the window just has to reach
        // from the series' start (a day early, for all-day events' UTC boundaries) to the split;
        // the BEGIN selection is what actually decides "before".
        val uri = ContentUris.appendId(
            ContentUris.appendId(Instances.CONTENT_URI.buildUpon(), seriesStartMillis - 86_400_000L),
            beforeMillis,
        ).build()
        val instances = context.contentResolver.query(
            uri,
            arrayOf(Instances.BEGIN),
            "${Instances.EVENT_ID}=? AND ${Instances.BEGIN}<?",
            arrayOf(eventId.toString(), beforeMillis.toString()),
            null,
        )?.use { it.count } ?: return null
        val exceptions = context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}<? AND ${Events.DELETED}=0",
            arrayOf(eventId.toString(), beforeMillis.toString()),
            null,
        )?.use { it.count } ?: return null
        return instances + exceptions
    }

    /** Shared by [updateEvent] and [deleteEvent]'s `THIS_AND_FOLLOWING` cases: rewrites
     *  [eventId]'s own RRULE so the series ends just before [instanceStartMillis] via
     *  [Recurrence.withUntil]. Requires the event to actually be recurring — the UI must never
     *  offer this scope for a non-recurring event, so a null RRULE here is a caller bug. */
    private fun truncateSeriesBefore(context: Context, eventId: Long, instanceStartMillis: Long?): Boolean {
        val instanceTime = requireNotNull(instanceStartMillis) {
            "THIS_AND_FOLLOWING requires the instance's own start time"
        }
        val original = queryEventDetail(context, eventId) ?: return false
        val originalRrule = requireNotNull(original.rrule) {
            "THIS_AND_FOLLOWING requires a recurring event"
        }
        val truncated = Recurrence.withUntil(originalRrule, instanceTime, original.allDay)
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val values = ContentValues().apply {
            put(Events.RRULE, truncated)
            // Confirmed on-device against the real CalendarProvider2: an update() that touches
            // only RRULE leaves its cached Instances rows stale indefinitely (verified with a
            // never-before-queried future window, and even across a provider process restart) —
            // the already-materialized instances beyond the new UNTIL keep showing up as if
            // nothing changed. Rewriting DTSTART to its own existing value is a harmless no-op
            // that's enough to make the provider actually invalidate and recompute Instances.
            put(Events.DTSTART, original.startMillis)
        }
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    /** [forException] is true for a this-instance exception row: CALENDAR_ID is inherited from
     *  the original event and must not be set, and it's never itself recurring so RRULE is never
     *  written — but (confirmed on-device against the real provider, which is stricter than the
     *  hand-rolled test double: "IllegalArgumentException: Exceptions can't overwrite dtend") an
     *  exception row must carry DURATION exactly like its recurring parent, never DTEND. */
    /** [forException]'s row (a "this event only" edit exception) rejects several fields a normal
     *  event row accepts — confirmed on-device against the real CalendarProvider2, which throws
     *  `IllegalArgumentException` naming the exact field: DTEND ("Exceptions can't overwrite
     *  dtend", already worked around below via DURATION instead) and, just as strictly,
     *  EVENT_COLOR_KEY ("Exceptions can't overwrite eventColor_key") — an exception always
     *  inherits its color from the series it excepts rather than carrying its own. */
    private fun eventValues(draft: EventDraft, forException: Boolean = false): ContentValues = ContentValues().apply {
        if (!forException) put(Events.CALENDAR_ID, draft.calendarId)
        put(Events.TITLE, draft.title)
        put(Events.EVENT_LOCATION, draft.location)
        put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else TimeZone.getDefault().id)
        put(Events.DTSTART, draft.startMillis)
        if (forException) {
            put(Events.DURATION, formatIcalDuration(draft.endMillis - draft.startMillis, draft.allDay))
        } else if (draft.rrule != null) {
            put(Events.RRULE, draft.rrule)
            put(Events.DURATION, formatIcalDuration(draft.endMillis - draft.startMillis, draft.allDay))
        } else {
            put(Events.DTEND, draft.endMillis)
        }
        if (!forException) put(Events.EVENT_COLOR_KEY, draft.eventColorKey)
        draft.description?.let { put(Events.DESCRIPTION, it) }
    }

    private fun writeReminder(context: Context, eventId: Long, minutes: Int?) {
        context.contentResolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
        if (minutes != null) {
            context.contentResolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, minutes)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            )
        }
        context.contentResolver.update(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            ContentValues().apply { put(Events.HAS_ALARM, if (minutes != null) 1 else 0) },
            null,
            null,
        )
    }

    /** e.g. "PT1H30M", "PT45M", "P1D" — the whole-days form only for all-day events, per
     *  IMPLEMENTATION.md's own all-day handling (§6.1.1); a timed event always gets an hours/
     *  minutes form even when the duration happens to be a whole number of days. */
    private fun formatIcalDuration(durationMillis: Long, allDay: Boolean): String {
        if (allDay) {
            val days = (durationMillis / 86_400_000L).coerceAtLeast(1)
            return "P${days}D"
        }
        val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(1)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return buildString {
            append("PT")
            if (hours > 0) append("${hours}H")
            if (minutes > 0 || hours == 0L) append("${minutes}M")
        }
    }

    private val DURATION_PATTERN = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

    /** Parses the subset of RFC 5545 durations Android's own calendar UI generates: P(n)D and/or
     *  T(n)H(n)M(n)S. Returns 0 for anything else rather than guessing. */
    private fun parseIcalDurationMillis(duration: String): Long {
        val match = DURATION_PATTERN.matchEntire(duration) ?: return 0L
        val days = match.groupValues[1].toLongOrNull() ?: 0L
        val hours = match.groupValues[2].toLongOrNull() ?: 0L
        val minutes = match.groupValues[3].toLongOrNull() ?: 0L
        val seconds = match.groupValues[4].toLongOrNull() ?: 0L
        return days * 86_400_000L + hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L
    }
}
