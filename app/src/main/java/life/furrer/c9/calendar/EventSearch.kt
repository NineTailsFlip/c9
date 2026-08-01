package life.furrer.c9.calendar

import life.furrer.c9.core.StringMatch

/** Pure matching/dedup logic for the calendar's Search screen (§7.3), split out from
 *  [EventStore.querySearchWindow] the same way CalendarRows.kt separates row-building from the
 *  provider query. */
object EventSearch {

    fun matches(event: EventEntry, query: String): Boolean {
        if (query.isEmpty()) return false
        if (StringMatch.matchesName(event.title, query)) return true
        return event.location?.let { StringMatch.matchesName(it, query) } ?: false
    }

    /** [events] comes from [EventStore.querySearchWindow], which expands recurrence — a daily
     *  event over a 2-year window is ~730 rows all sharing one [EventEntry.eventId]. A search
     *  result list should show that series once, not 730 times: this keeps only the single most
     *  relevant occurrence per event id — the next upcoming one relative to [nowMillis] if any
     *  exist, otherwise the most recent past one — sorted earliest-first.
     *
     *  Known limitation, confirmed on-device: a "this event" exception (§6.1.3) is a genuinely
     *  separate Events row with its own event id, distinct from the recurring series it excepts
     *  one occurrence of — Instances reports that one occurrence under the exception's own id,
     *  every other occurrence under the master's id. This function has no way to know the two
     *  ids are related without an extra ORIGINAL_ID lookup per result, so a series with an
     *  exception can surface as two separate search results (one for the excepted occurrence,
     *  one for the rest of the series) instead of being merged into one. Neither result is wrong
     *  on its own — opening either still shows that occurrence's real, correct details — this
     *  only affects how many rows the same logical series takes up in this list. */
    fun buildResults(events: List<EventEntry>, query: String, nowMillis: Long): List<EventEntry> {
        val matching = events.filter { matches(it, query) }
        return matching.groupBy { it.eventId }
            .values
            .map { occurrences ->
                occurrences.filter { it.startMillis >= nowMillis }.minByOrNull { it.startMillis }
                    ?: occurrences.maxBy { it.startMillis }
            }
            .sortedBy { it.startMillis }
    }
}
