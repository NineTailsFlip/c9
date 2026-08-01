package life.furrer.c9.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSearchTest {

    private fun entry(
        eventId: Long,
        title: String,
        startMillis: Long,
        location: String? = null,
    ) = EventEntry(eventId, title, startMillis, startMillis + 3_600_000L, false, location, 1L, 0xFF0000FF.toInt(), null)

    @Test
    fun `empty query matches nothing`() {
        assertFalse(EventSearch.matches(entry(1, "Standup", 0L), ""))
    }

    @Test
    fun `matches by title word-prefix`() {
        assertTrue(EventSearch.matches(entry(1, "Team Standup", 0L), "stand"))
    }

    @Test
    fun `matches by location word-prefix when title does not match`() {
        assertTrue(EventSearch.matches(entry(1, "Meeting", 0L, location = "Bahnhofstrasse 12"), "bahnhof"))
    }

    @Test
    fun `does not match unrelated text`() {
        assertFalse(EventSearch.matches(entry(1, "Meeting", 0L, location = "Zug"), "xyz"))
    }

    @Test
    fun `buildResults dedups a recurring event down to its next upcoming occurrence`() {
        val now = 1_000_000L
        val events = listOf(
            entry(1, "Daily Standup", now - 200_000L), // past
            entry(1, "Daily Standup", now + 100_000L), // next upcoming
            entry(1, "Daily Standup", now + 200_000L), // later upcoming
        )
        val results = EventSearch.buildResults(events, "standup", now)
        assertEquals(1, results.size)
        assertEquals(now + 100_000L, results.single().startMillis)
    }

    @Test
    fun `buildResults falls back to the most recent past occurrence when nothing is upcoming`() {
        val now = 1_000_000L
        val events = listOf(
            entry(1, "Old Meetup", now - 300_000L),
            entry(1, "Old Meetup", now - 100_000L), // most recent past
        )
        val results = EventSearch.buildResults(events, "meetup", now)
        assertEquals(1, results.size)
        assertEquals(now - 100_000L, results.single().startMillis)
    }

    @Test
    fun `buildResults keeps distinct events separate and sorts them earliest first`() {
        val now = 1_000_000L
        val events = listOf(
            entry(2, "Zebra Sync", now + 500_000L),
            entry(1, "Alpha Sync", now + 100_000L),
        )
        val results = EventSearch.buildResults(events, "sync", now)
        assertEquals(listOf(1L, 2L), results.map { it.eventId })
    }

    @Test
    fun `buildResults excludes non-matching events entirely`() {
        val now = 1_000_000L
        val events = listOf(entry(1, "Standup", now), entry(2, "Retro", now))
        val results = EventSearch.buildResults(events, "standup", now)
        assertEquals(1, results.size)
        assertEquals(1L, results.single().eventId)
    }
}
