package life.furrer.c9.calendar

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What another app asked for through the platform's CalendarContract intents ("Add to
 *  calendar" from a browser or mail app, a widget opening an event, ...). */
sealed interface CalendarIntentRequest {
    /** `ACTION_INSERT` (or `ACTION_EDIT` on the events directory): a new event, prefilled. */
    data class Insert(val prefill: EventPrefill) : CalendarIntentRequest

    /** `ACTION_VIEW` on `content://com.android.calendar/events/<id>` — [instanceStartMillis] is
     *  the occurrence asked for (`EXTRA_EVENT_BEGIN_TIME`), null if the caller named none. */
    data class ViewEvent(val eventId: Long, val instanceStartMillis: Long?) : CalendarIntentRequest

    /** `ACTION_EDIT` on `content://com.android.calendar/events/<id>`. */
    data class EditEvent(val eventId: Long, val instanceStartMillis: Long?) : CalendarIntentRequest

    /** `ACTION_VIEW` on `content://com.android.calendar/time/<millis>`. */
    data class ViewTime(val millis: Long) : CalendarIntentRequest
}

/** An `ACTION_INSERT`'s extras, each null/empty when the caller didn't give it — the editor's own
 *  defaults fill in whatever is missing. */
data class EventPrefill(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val beginMillis: Long? = null,
    val endMillis: Long? = null,
    val allDay: Boolean = false,
    val attendeeEmails: List<String> = emptyList(),
    val rrule: String? = null,
) {
    data class Times(val startMillis: Long, val endMillis: Long, val allDay: Boolean)

    /** Start/end in the editor's own representation: timed events as plain instants, all-day
     *  ones as UTC midnights with an exclusive end (§6.1.1). A missing or non-positive span falls
     *  back to [defaultDurationMillis] (timed) or a single day (all-day).
     *
     *  Callers disagree on how to express an all-day day: most pass local midnight (the docs'
     *  own example builds it from a local Calendar), some the provider's UTC midnight. Either
     *  midnight is read as that date; any other time of day as the local date it falls on, with
     *  an end mid-day counting that day as included. */
    fun resolveTimes(defaultStartMillis: Long, defaultDurationMillis: Long, zone: ZoneId): Times {
        if (!allDay) {
            val start = beginMillis ?: defaultStartMillis
            val end = endMillis?.takeIf { it > start } ?: (start + defaultDurationMillis)
            return Times(start, end, allDay = false)
        }
        val startDate = beginMillis?.let { midnightDate(it, zone) ?: localDate(it, zone) } ?: localDate(defaultStartMillis, zone)
        val endExclusive = endMillis
            ?.let { midnightDate(it, zone) ?: localDate(it, zone).plusDays(1) }
            ?.takeIf { it > startDate }
            ?: startDate.plusDays(1)
        return Times(utcMidnight(startDate), utcMidnight(endExclusive), allDay = true)
    }

    private fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun midnightDate(millis: Long, zone: ZoneId): LocalDate? {
        val utc = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        if (utc.toLocalTime() == LocalTime.MIDNIGHT) return utc.toLocalDate()
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        if (local.toLocalTime() == LocalTime.MIDNIGHT) return local.toLocalDate()
        return null
    }

    private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** Reads a [CalendarIntentRequest] out of an incoming intent, or null for anything else (a
 *  launcher start, an in-app start with this app's own extras). Kept apart from the Activities
 *  so it can be tested without driving one. */
object CalendarIntents {

    fun parse(intent: Intent): CalendarIntentRequest? {
        val data = intent.data?.takeIf { it.authority == CalendarContract.AUTHORITY }
        val eventId = data?.let { idAfter(it, "events") }
        return when (intent.action) {
            Intent.ACTION_INSERT -> CalendarIntentRequest.Insert(prefill(intent))
            // AOSP Calendar's editor takes EDIT on anything but one event (the events directory,
            // or just a type with no Uri) as an insert too.
            Intent.ACTION_EDIT ->
                if (eventId != null) CalendarIntentRequest.EditEvent(eventId, longExtra(intent, CalendarContract.EXTRA_EVENT_BEGIN_TIME))
                else CalendarIntentRequest.Insert(prefill(intent))
            Intent.ACTION_VIEW -> when {
                eventId != null -> CalendarIntentRequest.ViewEvent(eventId, longExtra(intent, CalendarContract.EXTRA_EVENT_BEGIN_TIME))
                data != null -> idAfter(data, "time")?.let { CalendarIntentRequest.ViewTime(it) }
                else -> null
            }
            else -> null
        }
    }

    private fun prefill(intent: Intent): EventPrefill = EventPrefill(
        title = intent.getStringExtra(Events.TITLE)?.trim()?.takeIf { it.isNotEmpty() },
        description = intent.getStringExtra(Events.DESCRIPTION)?.takeIf { it.isNotBlank() },
        location = intent.getStringExtra(Events.EVENT_LOCATION)?.trim()?.takeIf { it.isNotEmpty() },
        beginMillis = longExtra(intent, CalendarContract.EXTRA_EVENT_BEGIN_TIME),
        endMillis = longExtra(intent, CalendarContract.EXTRA_EVENT_END_TIME),
        allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
        attendeeEmails = attendeeEmails(intent),
        rrule = intent.getStringExtra(Events.RRULE)?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** `Intent.EXTRA_EMAIL` is documented as a comma-separated String here, but it's a String[]
     *  everywhere else in the platform, so callers send either. */
    private fun attendeeEmails(intent: Intent): List<String> {
        val raw = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList()
            ?: intent.getStringExtra(Intent.EXTRA_EMAIL)?.let { listOf(it) }
            ?: return emptyList()
        return raw.flatMap { it.split(',', ';') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Begin/end times arrive as a long by convention, but nothing stops a caller sending an int
     *  (or nothing); 0 and below are treated as absent. */
    private fun longExtra(intent: Intent, key: String): Long? {
        @Suppress("DEPRECATION")
        val value = intent.extras?.get(key) as? Number ?: return null
        return value.toLong().takeIf { it > 0L }
    }

    private fun idAfter(uri: Uri, segment: String): Long? {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != segment) return null
        return segments[1].toLongOrNull()
    }
}
