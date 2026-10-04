package life.furrer.c9.calendar

import android.provider.CalendarContract.Events

/** An event's availability (busy/free, RFC 5545 TRANSP), privacy (CLASS) and status (STATUS) as
 *  the editor offers and the detail screen shows them. Each option list is what the editor
 *  cycles through, built from the value the event was loaded with (not the current one, or
 *  cycling away from it would drop it from the list): a value only a synced event carries
 *  (tentative availability, confidential, no status, canceled) is offered only when the event
 *  came with it, so it can be cycled back to. */
object EventFlags {

    fun availabilityOptions(loaded: Int): List<Int> =
        withLoaded(listOf(Events.AVAILABILITY_BUSY, Events.AVAILABILITY_FREE), loaded)

    fun accessOptions(loaded: Int): List<Int> =
        withLoaded(listOf(Events.ACCESS_DEFAULT, Events.ACCESS_PRIVATE, Events.ACCESS_PUBLIC), loaded)

    fun statusOptions(loaded: Int?): List<Int?> =
        withLoaded(listOf(Events.STATUS_CONFIRMED, Events.STATUS_TENTATIVE), loaded)

    fun availabilityLabel(value: Int): String = when (value) {
        Events.AVAILABILITY_FREE -> "free"
        Events.AVAILABILITY_TENTATIVE -> "tentative"
        else -> "busy"
    }

    fun accessLabel(value: Int): String = when (value) {
        Events.ACCESS_PRIVATE -> "private"
        Events.ACCESS_PUBLIC -> "public"
        Events.ACCESS_CONFIDENTIAL -> "confidential"
        else -> "calendar default"
    }

    fun statusLabel(value: Int?): String = when (value) {
        Events.STATUS_CONFIRMED -> "confirmed"
        Events.STATUS_TENTATIVE -> "tentative"
        Events.STATUS_CANCELED -> "canceled"
        else -> "not set"
    }

    /** The detail screen's line, e.g. "Free · private · tentative": only what differs from an
     *  ordinary event (busy, calendar default, confirmed or not set), null when nothing does. */
    fun summary(detail: EventDetail): String? {
        val parts = buildList {
            if (detail.availability != Events.AVAILABILITY_BUSY) add(availabilityLabel(detail.availability))
            if (detail.accessLevel != Events.ACCESS_DEFAULT) add(accessLabel(detail.accessLevel))
            if (detail.status != null && detail.status != Events.STATUS_CONFIRMED) add(statusLabel(detail.status))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.replaceFirstChar { it.uppercase() }
    }

    /** The next option after [current] in [direction], wrapping. */
    fun <T> cycle(options: List<T>, current: T, direction: Int): T {
        val index = options.indexOf(current).coerceAtLeast(0)
        return options[(index + direction + options.size) % options.size]
    }

    private fun <T> withLoaded(options: List<T>, loaded: T): List<T> =
        if (loaded in options) options else listOf(loaded) + options
}
