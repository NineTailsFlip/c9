package life.furrer.c9.calendar

/** RFC 5545 §3.3.6 DURATION values as found in `CalendarContract.Events.DURATION`. Sync
 *  adapters and other calendar apps write all of the grammar: `P1W` (DAVx5), `P3600S` (AOSP
 *  Calendar/Etar for recurring events), `P1DT2H30M`, with an optional leading sign. `P3600S` is not strictly valid (seconds need a `T`) but is
 *  what AOSP writes, so a bare seconds part is accepted. */
object IcalDuration {
    private val PATTERN = Regex("""([+-])?P(?:(\d+)W|(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?|(\d+)S)?)""")

    /** The duration in milliseconds (negative for a `-` sign), or null when [value] isn't a
     *  valid duration - callers decide what a broken value means rather than it silently
     *  reading as zero. */
    fun parseMillis(value: String): Long? {
        val text = value.trim()
        val match = PATTERN.matchEntire(text) ?: return null
        // The pattern also matches a bare "P" and "PT", and "P1DT": a designator-less value
        // or a dangling T has no meaning.
        if (text.last() == 'P' || text.last() == 'T') return null
        val g = match.groupValues
        fun n(i: Int) = g[i].toLongOrNull() ?: 0L
        val total = n(2) * 604_800_000L + n(3) * 86_400_000L + n(4) * 3_600_000L +
            n(5) * 60_000L + (n(6) + n(7)) * 1_000L
        return if (g[1] == "-") -total else total
    }
}
