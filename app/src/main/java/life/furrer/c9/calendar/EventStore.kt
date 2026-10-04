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
import life.furrer.nine.core.util.bool
import life.furrer.nine.core.util.firstRow
import life.furrer.nine.core.util.int
import life.furrer.nine.core.util.intOr
import life.furrer.nine.core.util.intOrNull
import life.furrer.nine.core.util.long
import life.furrer.nine.core.util.longOrNull
import life.furrer.nine.core.util.mapRows
import life.furrer.nine.core.util.mapRowsNotNull
import life.furrer.nine.core.util.string
import life.furrer.nine.core.util.stringOr

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

/** One `Reminders` row: [minutes] before the start and the `Reminders.METHOD_*` it fires by.
 *  Kept whole rather than as bare minutes so an edit writes a synced email/SMS/alarm reminder
 *  back as it came (ticket #3); only reminders added in the editor are [Reminders.METHOD_ALERT]. */
data class EventReminder(val minutes: Int, val method: Int = Reminders.METHOD_ALERT) {
    /** Empty for a plain alert - the only kind the editor creates - so only the odd ones are
     *  called out wherever a reminder is listed. */
    val methodTag: String get() = when (method) {
        Reminders.METHOD_EMAIL -> "email"
        Reminders.METHOD_SMS -> "SMS"
        Reminders.METHOD_ALARM -> "alarm"
        else -> ""
    }
}

data class EventDetail(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    /** `Events.DESCRIPTION` — RFC 5545's DESCRIPTION, free text that's often long and multi-line
     *  (meeting links, agendas). The editor's Notes row edits it, and writes it back only when
     *  changed (see [EventDraft.description]). */
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val rrule: String?,
    /** Every reminder row, shortest lead first. */
    val reminders: List<EventReminder>,
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
    /** `Events.AVAILABILITY_*` (RFC 5545 TRANSP): whether the event blocks time. */
    val availability: Int = Events.AVAILABILITY_BUSY,
    /** `Events.ACCESS_*` (RFC 5545 CLASS). */
    val accessLevel: Int = Events.ACCESS_DEFAULT,
    /** `Events.STATUS_*` (RFC 5545 STATUS), null when the event doesn't say. */
    val status: Int? = null,
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
    /** The reminders the event should end up with: [EventStore] adds and removes only the
     *  difference from what the row has, so the ones the user left alone aren't rewritten. */
    val reminders: List<EventReminder>,
    val eventColorKey: String?,
    /** The Notes field. Null leaves whatever DESCRIPTION the row has alone (an edit that didn't
     *  change the notes), and an empty string clears it. */
    val description: String? = null,
    /** `Events.AVAILABILITY_*`, `Events.ACCESS_*` and `Events.STATUS_*`. Null leaves the row's
     *  own value alone, so an edit only writes the ones the user actually changed. */
    val availability: Int? = null,
    val accessLevel: Int? = null,
    val status: Int? = null,
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
        // A series without a _sync_id gets its exceptions from Events, not Instances (#122).
        val exceptions = UnsyncedSeries.queryExceptions(context).orEmpty()
        return UnsyncedSeries.repair(results, exceptions, startMillis, endMillis, { it.eventId }, { it.startMillis }) { e ->
            if (selectedCalendarIds != null && e.calendarId !in selectedCalendarIds) {
                null
            } else {
                EventEntry(
                    eventId = e.id,
                    title = e.title ?: "(untitled)",
                    startMillis = e.begin,
                    endMillis = e.end,
                    allDay = e.allDay,
                    location = e.location,
                    calendarId = e.calendarId,
                    displayColor = e.displayColor,
                    rrule = null,
                )
            }
        }.sortedBy { it.startMillis }
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
            Events.AVAILABILITY,
            Events.ACCESS_LEVEL,
            Events.STATUS,
        )
        return context.contentResolver.query(uri, projection, null, null, null).firstRow { cursor ->
            val start = cursor.long(Events.DTSTART)
            val end = cursor.longOrNull(Events.DTEND) ?: run {
                val duration = cursor.string(Events.DURATION)
                start + (duration?.let(IcalDuration::parseMillis)?.coerceAtLeast(0L) ?: 0L)
            }
            EventDetail(
                eventId = cursor.long(Events._ID),
                calendarId = cursor.long(Events.CALENDAR_ID),
                title = cursor.stringOr(Events.TITLE, "(untitled)"),
                location = cursor.string(Events.EVENT_LOCATION),
                description = cursor.string(Events.DESCRIPTION),
                startMillis = start,
                endMillis = end,
                allDay = cursor.bool(Events.ALL_DAY),
                rrule = cursor.string(Events.RRULE),
                reminders = queryReminders(context, eventId).map { it.second },
                eventColorKey = cursor.string(Events.EVENT_COLOR_KEY),
                displayColor = cursor.int(Events.DISPLAY_COLOR),
                selfAttendeeStatus = cursor.int(Events.SELF_ATTENDEE_STATUS),
                dirty = cursor.bool(Events.DIRTY),
                availability = cursor.int(Events.AVAILABILITY),
                accessLevel = cursor.int(Events.ACCESS_LEVEL),
                status = cursor.intOrNull(Events.STATUS),
            )
        }
    }

    /** [eventId]'s reminder rows as (row id, reminder), shortest lead first. */
    private fun queryReminders(context: Context, eventId: Long): List<Pair<Long, EventReminder>> {
        val projection = arrayOf(Reminders._ID, Reminders.MINUTES, Reminders.METHOD)
        val rows = context.contentResolver.query(
            Reminders.CONTENT_URI, projection, "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null
        ).mapRows { cursor ->
            cursor.long(Reminders._ID) to
                EventReminder(cursor.int(Reminders.MINUTES), cursor.intOr(Reminders.METHOD, Reminders.METHOD_DEFAULT))
        }
        return rows.sortedBy { it.second.minutes }
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
        return context.contentResolver.query(
            Attendees.CONTENT_URI, projection, "${Attendees.EVENT_ID}=?", arrayOf(eventId.toString()), null,
        ).mapRowsNotNull { cursor ->
            val email = cursor.string(Attendees.ATTENDEE_EMAIL) ?: return@mapRowsNotNull null
            EventAttendee(
                id = cursor.long(Attendees._ID),
                name = cursor.string(Attendees.ATTENDEE_NAME),
                email = email,
                status = cursor.int(Attendees.ATTENDEE_STATUS),
                relationship = cursor.int(Attendees.ATTENDEE_RELATIONSHIP),
                isSelf = ownerAccount != null && ownerAccount.equals(email, ignoreCase = true),
            )
        }
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

    /** Answers for one occurrence of the recurring [eventId] only (#11): writing to the series'
     *  own Attendees row would answer every meeting in it. Like a "this event" edit, the
     *  occurrence becomes an exception row, which the provider creates with a copy of the
     *  series' attendees, and the response goes onto that copy's self row - DAVx5 syncs it as
     *  the occurrence's own RECURRENCE-ID override. DTSTART is written so the exception starts
     *  where the occurrence did, not at the series' first one.
     *  @return the id of the exception row that now holds the occurrence, or null if
     *  the write failed or there's no self attendee to answer for. */
    fun respondToOccurrence(context: Context, eventId: Long, calendarId: Long, instanceStartMillis: Long, status: Int): Long? {
        if (!hasWritePermission(context)) return null
        if (queryAttendees(context, eventId, calendarId).none { it.isSelf }) return null
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, instanceStartMillis)
            put(Events.DTSTART, instanceStartMillis)
        }
        val exceptionUri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId)
        val exceptionId = context.contentResolver.insert(exceptionUri, values)?.let(ContentUris::parseId) ?: return null
        UnsyncedSeries.restoreSeries(context, eventId)
        return exceptionId.takeIf { respondToInvite(context, it, calendarId, status) }
    }

    private fun queryCalendarOwnerAccount(context: Context, calendarId: Long): String? {
        val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)
        return context.contentResolver.query(uri, arrayOf(Calendars.OWNER_ACCOUNT), null, null, null)
            .firstRow { it.string(Calendars.OWNER_ACCOUNT) }
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
        val results = context.contentResolver.query(Colors.CONTENT_URI, projection, selection, args, null)
            .mapRows { EventColorOption(it.string(Colors.COLOR_KEY)!!, it.int(Colors.COLOR)) }
        return results.sortedBy { Palette.paletteSortKey(it.argb) }
    }

    private data class CalendarAccount(val name: String, val type: String)

    private fun queryCalendarAccount(context: Context, calendarId: Long): CalendarAccount? {
        val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)
        val projection = arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)
        return context.contentResolver.query(uri, projection, null, null, null).firstRow {
            CalendarAccount(it.string(Calendars.ACCOUNT_NAME)!!, it.string(Calendars.ACCOUNT_TYPE)!!)
        }
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
        writeReminders(context, eventId, draft.reminders)
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
        // The editor shows and edits instants in the device zone, but a recurring event expands
        // in its own EVENT_TIMEZONE: overwriting that with the device zone would shift a New York
        // 09:00 series by an hour in the weeks where US and EU DST differ. DTSTART is an absolute
        // instant, so keeping the stored zone leaves every edited wall-clock time in it coherent.
        val zone = queryEventTimeZone(context, eventId)
        return when (splitScope(context, eventId, instanceStartMillis, scope)) {
            EditScope.ALL_INSTANCES -> {
                val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
                val updated = context.contentResolver.update(uri, eventValues(draft, zone = zone), null, null) > 0
                if (updated) writeReminders(context, eventId, draft.reminders)
                // An occurrence edited before is its own exception row, and rewriting one wipes
                // an unsynced series' Instances just like inserting it did (#122).
                if (updated) queryOriginalId(context, eventId)?.let { UnsyncedSeries.restoreSeries(context, it) }
                if (updated) eventId else null
            }
            EditScope.THIS_INSTANCE -> {
                val instanceTime = requireNotNull(instanceStartMillis) {
                    "THIS_INSTANCE requires the instance's own start time"
                }
                val values = eventValues(draft, forException = true, zone = zone).apply {
                    put(Events.ORIGINAL_INSTANCE_TIME, instanceTime)
                }
                val exceptionUri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId)
                val resultUri = context.contentResolver.insert(exceptionUri, values) ?: return null
                val exceptionId = ContentUris.parseId(resultUri)
                writeReminders(context, exceptionId, draft.reminders)
                UnsyncedSeries.restoreSeries(context, eventId)
                exceptionId
            }
            EditScope.THIS_AND_FOLLOWING -> {
                // The continuation is a brand-new row built only from the editor's fields, and
                // the editor leaves unchanged notes/availability/privacy/status unset - carry the
                // original's over, or the rest of the series loses them.
                val original = queryEventDetail(context, eventId)
                // Truncate first: its DTSTART+RRULE rewrite is what makes the provider re-expand
                // the master, which the COUNT below is read from (see countOccurrencesBefore).
                if (!truncateSeriesBefore(context, eventId, instanceStartMillis)) return null
                val continuation = original?.let {
                    continuationDraft(context, eventId, it.startMillis, instanceStartMillis, draft).copy(
                        availability = draft.availability ?: it.availability,
                        accessLevel = draft.accessLevel ?: it.accessLevel,
                        status = draft.status ?: it.status,
                        description = draft.description ?: it.description,
                    )
                } ?: draft
                insertEventRow(context, continuation, eventValues(continuation, zone = zone))
            }
        }
    }

    /** The series [eventId] is an exception of, or null if it isn't one. */
    private fun queryOriginalId(context: Context, eventId: Long): Long? {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        context.contentResolver.query(uri, arrayOf(Events.ORIGINAL_ID), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** [eventId]'s own EVENT_TIMEZONE, or null if it has none or is all-day (UTC), in which
     *  case the device zone applies. */
    private fun queryEventTimeZone(context: Context, eventId: Long): String? {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        context.contentResolver.query(uri, arrayOf(Events.EVENT_TIMEZONE, Events.ALL_DAY), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(1) != 0) return null
            return c.getString(0)?.takeIf { it.isNotBlank() && TimeZone.getAvailableIDs().contains(it) }
        }
        return null
    }

    fun deleteEvent(context: Context, eventId: Long, instanceStartMillis: Long?, scope: EditScope): Boolean {
        if (!hasWritePermission(context)) return false
        return when (splitScope(context, eventId, instanceStartMillis, scope)) {
            // A normal delete() marks the row deleted for sync rather than dropping it — that's
            // the provider's own doing, not something this code needs to arrange (§6.1.2).
            // An occurrence edited before is its own exception row. Deleting that row would
            // bring the occurrence back at its original time (an override removed, in RFC 5545
            // terms), so it's canceled instead, like "delete this event only" (#122).
            EditScope.ALL_INSTANCES -> {
                val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
                val seriesId = queryOriginalId(context, eventId)
                if (seriesId == null) {
                    context.contentResolver.delete(uri, null, null) > 0
                } else {
                    val values = ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }
                    (context.contentResolver.update(uri, values, null, null) > 0).also { canceled ->
                        if (canceled) UnsyncedSeries.restoreSeries(context, seriesId)
                    }
                }
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
                (context.contentResolver.insert(exceptionUri, values) != null).also { inserted ->
                    if (inserted) UnsyncedSeries.restoreSeries(context, eventId)
                }
            }
            // Deleting "this and following" is just the truncation half of the update case —
            // there's no continuation event to insert since nothing replaces the deleted tail.
            EditScope.THIS_AND_FOLLOWING -> truncateSeriesBefore(context, eventId, instanceStartMillis)
        }
    }

    /** "This and following" from the series' very first occurrence is the whole series:
     *  splitting there would end the old series before it starts, leaving a master row with no
     *  occurrences of its own that other clients may still show next to the new one (#10). */
    private fun splitScope(context: Context, eventId: Long, instanceStartMillis: Long?, scope: EditScope): EditScope {
        if (scope != EditScope.THIS_AND_FOLLOWING || instanceStartMillis == null) return scope
        val seriesStart = queryEventDetail(context, eventId)?.startMillis ?: return scope
        return if (instanceStartMillis <= seriesStart) EditScope.ALL_INSTANCES else scope
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
     *  or canceled ("delete this event only") — still used up one of the COUNT, so it's counted by
     *  its ORIGINAL_INSTANCE_TIME. Counted as distinct start times rather than added up, because
     *  whether the master's own Instances leave the excepted ones out depends on the series having
     *  a `_sync_id` (confirmed on-device, #120): CalendarInstancesHelper only matches an exception
     *  to its master by ORIGINAL_SYNC_ID, so for a local or not-yet-uploaded series it never
     *  cancels the master's occurrence - and inserting an exception even wipes the master's
     *  Instances until the master itself is rewritten, which is why [updateEvent] truncates the
     *  series (a DTSTART+RRULE rewrite, so a re-expansion) before calling this. Null if either
     *  query fails. */
    private fun countOccurrencesBefore(context: Context, eventId: Long, seriesStartMillis: Long, beforeMillis: Long): Int? {
        // Instances are returned by overlap with the window, so the window just has to reach
        // from the series' start (a day early, for all-day events' UTC boundaries) to the split;
        // the BEGIN selection is what actually decides "before".
        val uri = ContentUris.appendId(
            ContentUris.appendId(Instances.CONTENT_URI.buildUpon(), seriesStartMillis - 86_400_000L),
            beforeMillis,
        ).build()
        val starts = mutableSetOf<Long>()
        context.contentResolver.query(
            uri,
            arrayOf(Instances.BEGIN),
            "${Instances.EVENT_ID}=? AND ${Instances.BEGIN}<?",
            arrayOf(eventId.toString(), beforeMillis.toString()),
            null,
        )?.use { while (it.moveToNext()) starts += it.getLong(0) } ?: return null
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events.ORIGINAL_INSTANCE_TIME),
            "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}<? AND ${Events.DELETED}=0",
            arrayOf(eventId.toString(), beforeMillis.toString()),
            null,
        )?.use { while (it.moveToNext()) starts += it.getLong(0) } ?: return null
        return starts.size
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
    private fun eventValues(draft: EventDraft, forException: Boolean = false, zone: String? = null): ContentValues = ContentValues().apply {
        if (!forException) put(Events.CALENDAR_ID, draft.calendarId)
        put(Events.TITLE, draft.title)
        put(Events.EVENT_LOCATION, draft.location)
        put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else zone ?: TimeZone.getDefault().id)
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
        draft.description?.let { put(Events.DESCRIPTION, it.ifEmpty { null }) }
        draft.availability?.let { put(Events.AVAILABILITY, it) }
        draft.accessLevel?.let { put(Events.ACCESS_LEVEL, it) }
        draft.status?.let { put(Events.STATUS, it) }
    }

    /** Makes [eventId]'s reminder rows match [wanted] by deleting and inserting only the
     *  difference - a sync adapter sees every touched row as a change, and an untouched
     *  reminder (its method included) has nothing to say. Compared against what the row has
     *  *now*, since the provider copies the series' reminders onto a new exception row; a
     *  continuation event starts with none. HAS_ALARM isn't written: the provider keeps it in
     *  step with the rows itself and ignores it in an update. */
    private fun writeReminders(context: Context, eventId: Long, wanted: List<EventReminder>) {
        val missing = wanted.toMutableList()
        queryReminders(context, eventId).forEach { (rowId, reminder) ->
            if (!missing.remove(reminder)) {
                context.contentResolver.delete(ContentUris.withAppendedId(Reminders.CONTENT_URI, rowId), null, null)
            }
        }
        missing.forEach { reminder ->
            context.contentResolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, reminder.minutes)
                    put(Reminders.METHOD, reminder.method)
                },
            )
        }
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
}
