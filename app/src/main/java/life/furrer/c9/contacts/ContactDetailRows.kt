package life.furrer.c9.contacts

import android.content.res.Resources

/** One row of the detail screen (§9.10, mockups.html's own reference render) — either a section
 *  header naming the kind ("PHONE", "EMAIL", ...) or a numbered action row: every row is a verb,
 *  `3` mails, `4` opens the map. [Item.displayIndex] is 1-9 for the first nine *item* rows
 *  (headers never consume a number, the same convention [CalendarRow] and [ContactRow] both use
 *  for their own header rows), then null past the ninth — still reachable by Up/Down, just not
 *  by a direct digit press. */
sealed interface ContactDetailRow {
    data class SectionHeader(val label: String) : ContactDetailRow
    data class Item(
        val dataRow: DataRow,
        val kind: DataKind?,
        val displayIndex: Int?,
        val typeLabel: String,
        val value: String,
    ) : ContactDetailRow
}

fun ContactDetailRow.isSelectable(): Boolean = this is ContactDetailRow.Item

/** Builds the numbered rows from a [ContactDetail]'s raw [DataRow]s, grouped under a section
 *  header per kind — mobile/work under "PHONE", home/work under "EMAIL", and so on, matching
 *  mockups.html's contact editor render (§9.11) applied to this read-only screen too. Rows are
 *  grouped by [DataKind.ordinal] via a *stable* sort, not raw `Data`-table order, so multiple
 *  rows of the same kind (two phone numbers) stay grouped even when the provider doesn't already
 *  return them adjacent to each other, and their own relative order is preserved. Every unknown
 *  mimetype sorts after every known kind, under a single "OTHER" header, rather than one header
 *  per row (§6.2: unknown mimetypes are never dropped).
 *
 *  Name and Organization are shown in the header instead (title/subtitle), Photo has no text
 *  row, and GroupMembership needs a Groups-table lookup this pass doesn't do (deferred — no
 *  visible row rather than a wrong one). A row with a known [DataKind] but a blank formatted
 *  value (e.g. an Organization row that turned out to be name-only, already covered by the
 *  header) is skipped rather than shown empty. */
fun buildDetailRows(resources: Resources, detail: ContactDetail): List<ContactDetailRow> {
    data class Parsed(val dataRow: DataRow, val kind: DataKind?, val typeLabel: String, val value: String)

    val parsed = detail.rows.mapNotNull { dataRow ->
        val kind = DataKind.fromMimetype(dataRow.mimetype)
        if (kind == DataKind.NAME || kind == DataKind.ORGANIZATION || kind == DataKind.PHOTO || kind == DataKind.GROUP_MEMBERSHIP) return@mapNotNull null

        val value = if (kind != null) DataFormat.valueText(kind, dataRow.values) else unknownMimetypeValue(dataRow)
        if (value.isBlank()) return@mapNotNull null

        val specificLabel = when {
            kind == null -> null
            kind == DataKind.IM -> DataFormat.imProtocolLabel(resources, dataRow.values) ?: DataFormat.typeLabel(resources, kind, dataRow.values)
            else -> DataFormat.typeLabel(resources, kind, dataRow.values)
        }
        val typeLabel = specificLabel ?: kind?.sectionLabel?.uppercase() ?: "OTHER"

        Parsed(dataRow, kind, typeLabel, value)
    }.sortedBy { it.kind?.ordinal ?: Int.MAX_VALUE }

    val rows = mutableListOf<ContactDetailRow>()
    var index = 0
    var lastGroup: DataKind? = null
    var unknownGroupShown = false
    parsed.forEach { p ->
        val newGroup = if (p.kind == null) !unknownGroupShown else p.kind != lastGroup
        if (newGroup) {
            rows.add(ContactDetailRow.SectionHeader(p.kind?.sectionLabel?.uppercase() ?: "OTHER"))
            lastGroup = p.kind
            if (p.kind == null) unknownGroupShown = true
        }
        index++
        rows.add(
            ContactDetailRow.Item(
                dataRow = p.dataRow,
                kind = p.kind,
                displayIndex = if (index <= 9) index else null,
                typeLabel = p.typeLabel,
                value = p.value,
            ),
        )
    }
    return rows
}

/** Best-effort text for a `Data` row whose mimetype isn't one this app understands — "data1" is
 *  the conventional primary-value column for arbitrary vendor mimetypes, so it's a reasonable
 *  first guess; if it's absent there's nothing sensible to show and the row is skipped here
 *  (the row itself is still preserved, just not rendered as text on this screen). */
private fun unknownMimetypeValue(dataRow: DataRow): String = dataRow.values.getAsString("data1").orEmpty()
