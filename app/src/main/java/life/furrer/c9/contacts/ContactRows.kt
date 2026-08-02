package life.furrer.c9.contacts

import life.furrer.nine.core.util.StringMatch

/** One row of the contacts list per §9.8/§9.9 — a pure data shape, built by [buildContactRows]
 *  from a flat [ContactEntry] list and the current search/favorites state, then handed to a
 *  [life.furrer.c9.core.RowBinder] for rendering. Mirrors calendar/CalendarRows.kt's split
 *  between pure row-building and the Activity's own rendering/key-handling. */
sealed interface ContactRow {
    data class SectionHeader(val label: String) : ContactRow
    data class Entry(val contact: ContactEntry) : ContactRow

    /** A contact that matched by phone number rather than by name, shown under its own
     *  "NUMBERS" section with the matching number as the reason (§9.9: "show *why* a
     *  non-obvious match matched"). */
    data class NumberMatch(val contact: ContactEntry, val matchedNumber: String) : ContactRow

    /** The typed digits themselves, echoed when nothing in the list matches at all but the
     *  query is still dial-able — "that row *is* the dial target in the no-match case, not a
     *  decoration" (§7.4). Not selectable; the call key's fallback dials [digits] directly
     *  regardless of list selection. */
    data class TypedNumber(val digits: String) : ContactRow
}

/** [contacts] must already be sorted the way the caller wants non-favorite, non-search rows to
 *  appear (SORT_KEY_PRIMARY collation from the provider query) — this function only groups and
 *  filters, it never re-sorts.
 *
 *  - Empty query: favorites (if any and [favoritesOnly] is false) shown flat under a
 *    FAVORITES header, then every contact (or, if [favoritesOnly], just the starred ones)
 *    grouped under a letter header per §9.8.
 *  - Non-empty query: name matches first (§6.3 word-prefix), then, only when the query is all
 *    digits, contacts that matched by phone number under a NUMBERS header (§9.9) — a contact
 *    that already matched by name is not repeated there even if one of its numbers also
 *    matches. If nothing matched at all and the query still looks dial-able, the typed digits
 *    are echoed as the call-key's fallback target (§7.4).
 */
fun buildContactRows(contacts: List<ContactEntry>, query: String, favoritesOnly: Boolean): List<ContactRow> {
    if (query.isEmpty()) return buildBrowseRows(contacts, favoritesOnly)
    return buildSearchRows(contacts, query)
}

private fun buildBrowseRows(contacts: List<ContactEntry>, favoritesOnly: Boolean): List<ContactRow> {
    val rows = mutableListOf<ContactRow>()
    if (!favoritesOnly) {
        val favorites = contacts.filter { it.starred }
        if (favorites.isNotEmpty()) {
            rows.add(ContactRow.SectionHeader("FAVORITES"))
            favorites.forEach { rows.add(ContactRow.Entry(it)) }
        }
    }
    val browsable = if (favoritesOnly) contacts.filter { it.starred } else contacts
    var currentLetter: String? = null
    browsable.forEach { contact ->
        val letter = letterGroupFor(contact.displayName)
        if (letter != currentLetter) {
            rows.add(ContactRow.SectionHeader(letter))
            currentLetter = letter
        }
        rows.add(ContactRow.Entry(contact))
    }
    return rows
}

private fun buildSearchRows(contacts: List<ContactEntry>, query: String): List<ContactRow> {
    val nameMatches = contacts.filter { StringMatch.matchesName(it.displayName, query) }
    val rows = mutableListOf<ContactRow>()
    rows.addAll(nameMatches.map { ContactRow.Entry(it) })

    if (StringMatch.isAllDigits(query)) {
        val nameMatchIds = nameMatches.map { it.id }.toSet()
        val numberMatches = contacts
            .filter { it.id !in nameMatchIds }
            .mapNotNull { contact ->
                val matched = contact.phoneNumbers.firstOrNull { StringMatch.matchesPhoneNumber(it.number, query) }
                matched?.let { contact to it.number }
            }
        if (numberMatches.isNotEmpty()) {
            rows.add(ContactRow.SectionHeader("NUMBERS"))
            numberMatches.forEach { (contact, number) -> rows.add(ContactRow.NumberMatch(contact, number)) }
        }
    }

    if (rows.isEmpty() && StringMatch.looksDialable(query)) {
        rows.add(ContactRow.SectionHeader("NUMBERS"))
        rows.add(ContactRow.TypedNumber(query))
    }
    return rows
}

/** First letter of the display name, uppercased — non-letters (digits, symbols, an empty name)
 *  all group under a single "#" header rather than one header per odd character. */
private fun letterGroupFor(displayName: String): String {
    val first = displayName.trim().firstOrNull()?.uppercaseChar()
    return if (first != null && first in 'A'..'Z') first.toString() else "#"
}

fun ContactRow.isSelectable(): Boolean = this is ContactRow.Entry || this is ContactRow.NumberMatch

fun ContactRow.contactOrNull(): ContactEntry? = when (this) {
    is ContactRow.Entry -> contact
    is ContactRow.NumberMatch -> contact
    else -> null
}
