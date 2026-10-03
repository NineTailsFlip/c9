package life.furrer.c9.calendar

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric only for [Intent]/[Uri] — [CalendarIntents.parse] itself touches no provider. */
@RunWith(RobolectricTestRunner::class)
class CalendarIntentsTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `INSERT on the events directory carries every CalendarContract extra`() {
        val intent = Intent(Intent.ACTION_INSERT, Events.CONTENT_URI)
            .putExtra(Events.TITLE, "  Concert ")
            .putExtra(Events.DESCRIPTION, "Doors at 19:00\nBring tickets")
            .putExtra(Events.EVENT_LOCATION, "Hallenstadion")
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1_800_000_000_000L)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, 1_800_007_200_000L)
            .putExtra(Intent.EXTRA_EMAIL, "a@example.com, b@example.com,,a@example.com")
            .putExtra(Events.RRULE, "FREQ=WEEKLY")

        val request = CalendarIntents.parse(intent) as CalendarIntentRequest.Insert

        assertEquals(
            EventPrefill(
                title = "Concert",
                description = "Doors at 19:00\nBring tickets",
                location = "Hallenstadion",
                beginMillis = 1_800_000_000_000L,
                endMillis = 1_800_007_200_000L,
                allDay = false,
                attendeeEmails = listOf("a@example.com", "b@example.com"),
                rrule = "FREQ=WEEKLY",
            ),
            request.prefill,
        )
    }

    @Test
    fun `INSERT with only a type and no extras is an empty prefill`() {
        val intent = Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/event")
        assertEquals(CalendarIntentRequest.Insert(EventPrefill()), CalendarIntents.parse(intent))
    }

    @Test
    fun `attendees also arrive as a String array, and int begin times are accepted`() {
        val intent = Intent(Intent.ACTION_INSERT)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("a@example.com", " b@example.com "))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1_000)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0L)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)

        val prefill = (CalendarIntents.parse(intent) as CalendarIntentRequest.Insert).prefill

        assertEquals(listOf("a@example.com", "b@example.com"), prefill.attendeeEmails)
        assertEquals(1_000L, prefill.beginMillis)
        assertNull("0 is no time at all", prefill.endMillis)
        assertEquals(true, prefill.allDay)
    }

    @Test
    fun `EDIT on one event opens it, EDIT on the directory or a bare type inserts`() {
        val eventUri = ContentUris.withAppendedId(Events.CONTENT_URI, 42L)
        assertEquals(
            CalendarIntentRequest.EditEvent(42L, 1_800_000_000_000L),
            CalendarIntents.parse(Intent(Intent.ACTION_EDIT, eventUri).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1_800_000_000_000L)),
        )
        assertEquals(CalendarIntentRequest.EditEvent(42L, null), CalendarIntents.parse(Intent(Intent.ACTION_EDIT, eventUri)))
        assertEquals(
            CalendarIntentRequest.Insert(EventPrefill(title = "Lunch")),
            CalendarIntents.parse(Intent(Intent.ACTION_EDIT, Events.CONTENT_URI).putExtra(Events.TITLE, "Lunch")),
        )
        assertEquals(
            CalendarIntentRequest.Insert(EventPrefill(title = "Lunch")),
            CalendarIntents.parse(Intent(Intent.ACTION_EDIT).setType("vnd.android.cursor.item/event").putExtra(Events.TITLE, "Lunch")),
        )
    }

    @Test
    fun `VIEW on an event, with and without the occurrence`() {
        val eventUri = ContentUris.withAppendedId(Events.CONTENT_URI, 7L)
        assertEquals(CalendarIntentRequest.ViewEvent(7L, null), CalendarIntents.parse(Intent(Intent.ACTION_VIEW, eventUri)))
        assertEquals(
            CalendarIntentRequest.ViewEvent(7L, 1_800_000_000_000L),
            CalendarIntents.parse(
                Intent(Intent.ACTION_VIEW, eventUri)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1_800_000_000_000L)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, 1_800_003_600_000L),
            ),
        )
    }

    @Test
    fun `VIEW on a time opens that date`() {
        val timeUri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath("1800000000000").build()
        assertEquals(CalendarIntentRequest.ViewTime(1_800_000_000_000L), CalendarIntents.parse(Intent(Intent.ACTION_VIEW, timeUri)))
    }

    @Test
    fun `anything else is not a request`() {
        assertNull(CalendarIntents.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(CalendarIntents.parse(Intent()))
        assertNull(CalendarIntents.parse(Intent(Intent.ACTION_VIEW)))
        assertNull(CalendarIntents.parse(Intent(Intent.ACTION_VIEW, Events.CONTENT_URI)))
        assertNull(CalendarIntents.parse(Intent(Intent.ACTION_VIEW, Uri.parse("content://elsewhere/events/7"))))
        assertNull(CalendarIntents.parse(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/events/x"))))
    }

    @Test
    fun `timed prefill falls back to the defaults for whatever is missing`() {
        val defaultStart = 1_800_000_000_000L
        val hour = 3_600_000L

        assertEquals(
            EventPrefill.Times(defaultStart, defaultStart + hour, false),
            EventPrefill().resolveTimes(defaultStart, hour, zurich),
        )
        assertEquals(
            EventPrefill.Times(5_000_000L, 5_000_000L + hour, false),
            EventPrefill(beginMillis = 5_000_000L).resolveTimes(defaultStart, hour, zurich),
        )
        assertEquals(
            EventPrefill.Times(5_000_000L, 9_000_000L, false),
            EventPrefill(beginMillis = 5_000_000L, endMillis = 9_000_000L).resolveTimes(defaultStart, hour, zurich),
        )
        assertEquals(
            "an end before the start is ignored",
            EventPrefill.Times(5_000_000L, 5_000_000L + hour, false),
            EventPrefill(beginMillis = 5_000_000L, endMillis = 4_000_000L).resolveTimes(defaultStart, hour, zurich),
        )
    }

    @Test
    fun `all-day prefill lands on UTC midnights, whichever midnight the caller used`() {
        val day = LocalDate.of(2026, 10, 3)
        val localMidnight = day.atStartOfDay(zurich).toInstant().toEpochMilli()
        val nextLocalMidnight = day.plusDays(1).atStartOfDay(zurich).toInstant().toEpochMilli()
        val oneDay = EventPrefill.Times(utcMidnight(day), utcMidnight(day.plusDays(1)), true)

        assertEquals(oneDay, EventPrefill(beginMillis = localMidnight, endMillis = nextLocalMidnight, allDay = true).resolveTimes(0L, 0L, zurich))
        assertEquals(oneDay, EventPrefill(beginMillis = utcMidnight(day), endMillis = utcMidnight(day.plusDays(1)), allDay = true).resolveTimes(0L, 0L, zurich))
        assertEquals("no end is one day", oneDay, EventPrefill(beginMillis = localMidnight, allDay = true).resolveTimes(0L, 0L, zurich))
        assertEquals("end == begin is one day", oneDay, EventPrefill(beginMillis = localMidnight, endMillis = localMidnight, allDay = true).resolveTimes(0L, 0L, zurich))

        val noon = day.atTime(12, 0).atZone(zurich).toInstant().toEpochMilli()
        val twoDaysLaterEvening = day.plusDays(2).atTime(20, 0).atZone(zurich).toInstant().toEpochMilli()
        assertEquals(
            "a mid-day end counts that day in",
            EventPrefill.Times(utcMidnight(day), utcMidnight(day.plusDays(3)), true),
            EventPrefill(beginMillis = noon, endMillis = twoDaysLaterEvening, allDay = true).resolveTimes(0L, 0L, zurich),
        )
        assertEquals(
            "no begin is the default start's own date",
            oneDay,
            EventPrefill(allDay = true).resolveTimes(noon, 3_600_000L, zurich),
        )
    }
}
