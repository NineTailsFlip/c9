package life.furrer.c9.contacts

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK

private const val AUTHORITY = "com.android.contacts"
private const val CONTACTS = 1
private const val CONTACTS_ID = 2
private const val DATA = 3
private const val DATA_PHONES = 4
private const val RAW_CONTACTS = 5
private const val GROUPS = 6

/** A minimal in-memory contacts provider for tests — confirmed empirically (same technique as
 *  FakeCalendarProvider) that Robolectric has no working ContactsProvider2, and the real URI
 *  paths this matches ("contacts", "data", "data/phones", "raw_contacts") were themselves
 *  confirmed by printing the real constants under Robolectric rather than assumed. This covers
 *  only what ContactStore's read path needs: the flat contacts list, a bulk phone-number query,
 *  a per-contact Data row dump, and per-contact RawContacts/account rows. */
class FakeContactsProvider : ContentProvider() {

    companion object {
        /** Each entry's ContentValues also carries CONTACT_ID (an ordinary column here, exactly
         *  like FakeCalendarProvider treats DIRTY/DELETED) so the Data/RawContacts fakes below
         *  can filter by it the same way the real provider's join views do. */
        val contacts = mutableMapOf<Long, ContentValues>()
        val data = mutableMapOf<Long, ContentValues>()
        val rawContacts = mutableMapOf<Long, ContentValues>()
        val groups = mutableMapOf<Long, ContentValues>()
        private var nextRawContactId = 1000L
        private var nextDataId = 1000L

        fun reset() {
            contacts.clear()
            data.clear()
            rawContacts.clear()
            groups.clear()
            nextRawContactId = 1000L
            nextDataId = 1000L
        }

        fun putContact(
            id: Long,
            displayName: String,
            starred: Boolean = false,
            lookupKey: String = "lookup$id",
            photoThumbnailUri: String? = null,
        ) {
            contacts[id] = ContentValues().apply {
                put(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, displayName)
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
                put(ContactsContract.Contacts.LOOKUP_KEY, lookupKey)
                put(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, photoThumbnailUri)
            }
        }

        fun putData(id: Long, contactId: Long, rawContactId: Long, mimetype: String, values: ContentValues) {
            data[id] = ContentValues(values).apply {
                put(ContactsContract.Data.MIMETYPE, mimetype)
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(CDK.Phone.CONTACT_ID, contactId) // same underlying column as Data.CONTACT_ID
            }
        }

        fun putRawContact(id: Long, contactId: Long, accountName: String?, accountType: String?) {
            rawContacts[id] = ContentValues().apply {
                put(ContactsContract.RawContacts.CONTACT_ID, contactId)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
            }
        }

        fun putGroup(id: Long, title: String?, accountName: String, accountType: String, deleted: Boolean = false) {
            groups[id] = ContentValues().apply {
                put(ContactsContract.Groups.TITLE, title)
                put(ContactsContract.Groups.ACCOUNT_NAME, accountName)
                put(ContactsContract.Groups.ACCOUNT_TYPE, accountType)
                put(ContactsContract.Groups.DELETED, if (deleted) 1 else 0)
            }
        }
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "contacts", CONTACTS)
        addURI(AUTHORITY, "contacts/#", CONTACTS_ID)
        addURI(AUTHORITY, "data", DATA)
        addURI(AUTHORITY, "data/phones", DATA_PHONES)
        addURI(AUTHORITY, "raw_contacts", RAW_CONTACTS)
        addURI(AUTHORITY, "groups", GROUPS)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (matcher.match(uri)) {
        CONTACTS -> {
            val cols = projection ?: arrayOf(
                ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            )
            val cursor = MatrixCursor(cols)
            contacts.entries.sortedBy { it.key }.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == ContactsContract.Contacts._ID) id else values.get(col) })
            }
            cursor
        }
        CONTACTS_ID -> {
            val id = ContentUris.parseId(uri)
            val values = contacts[id]
            val cols = projection ?: arrayOf(
                ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            )
            val cursor = MatrixCursor(cols)
            if (values != null) cursor.addRow(cols.map { col -> if (col == ContactsContract.Contacts._ID) id else values.get(col) })
            cursor
        }
        DATA -> {
            // queryContactIdsInGroups' selection ("mimetype=? AND group_id IN (?,?,...)") shares
            // the DATA URI with queryContactDetail's own "contact_id=?" shape -- distinguish by
            // the selection string itself, same exact-match convention as RAW_CONTACTS below.
            val matching = if (selection?.startsWith(ContactsContract.Data.MIMETYPE) == true) {
                val mimetype = selectionArgs?.getOrNull(0)
                val groupIds = selectionArgs.orEmpty().drop(1).mapNotNull { it.toLongOrNull() }.toSet()
                data.entries.filter { (_, v) ->
                    v.getAsString(ContactsContract.Data.MIMETYPE) == mimetype &&
                        v.getAsLong(CDK.GroupMembership.GROUP_ROW_ID) in groupIds
                }
            } else {
                val contactId = selectionArgs?.firstOrNull()?.toLongOrNull()
                data.entries.filter { (_, v) -> contactId == null || v.getAsLong(CDK.Phone.CONTACT_ID) == contactId }
            }
            val cols = projection ?: (
                listOf(ContactsContract.Data._ID, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE) +
                    matching.flatMap { it.value.keySet() }.distinct()
                ).distinct().toTypedArray()
            val cursor = MatrixCursor(cols)
            matching.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == ContactsContract.Data._ID) id else values.get(col) })
            }
            cursor
        }
        DATA_PHONES -> {
            val matching = data.entries.filter { (_, v) -> v.getAsString(ContactsContract.Data.MIMETYPE) == CDK.Phone.CONTENT_ITEM_TYPE }
            val cols = projection ?: arrayOf(CDK.Phone.CONTACT_ID, CDK.Phone.NUMBER)
            val cursor = MatrixCursor(cols)
            matching.forEach { (_, values) -> cursor.addRow(cols.map { values.get(it) }) }
            cursor
        }
        RAW_CONTACTS -> {
            // Both real query shapes this fake needs to support share one filter value but
            // differ in *which* column it filters — exact-match the selection string (rather
            // than substring-check it) since "contact_id" itself contains "_id".
            val filterValue = selectionArgs?.firstOrNull()?.toLongOrNull()
            val matching = when {
                filterValue == null -> rawContacts.entries
                selection == "${ContactsContract.RawContacts._ID}=?" -> rawContacts.entries.filter { it.key == filterValue }
                else -> rawContacts.entries.filter { (_, v) -> v.getAsLong(ContactsContract.RawContacts.CONTACT_ID) == filterValue }
            }
            val cols = projection ?: arrayOf(
                ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.CONTACT_ID,
            )
            val cursor = MatrixCursor(cols)
            matching.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == ContactsContract.RawContacts._ID) id else values.get(col) })
            }
            cursor
        }
        GROUPS -> {
            // queryGroups' own hardcoded selection ("deleted=0 AND title IS NOT NULL") is the
            // only shape this fake ever needs to serve, so it's applied directly rather than
            // actually parsed.
            val matching = groups.entries.filter { (_, v) ->
                v.getAsInteger(ContactsContract.Groups.DELETED) == 0 && v.getAsString(ContactsContract.Groups.TITLE) != null
            }
            val cols = projection ?: arrayOf(
                ContactsContract.Groups._ID, ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_NAME, ContactsContract.Groups.ACCOUNT_TYPE,
            )
            val cursor = MatrixCursor(cols)
            matching.forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == ContactsContract.Groups._ID) id else values.get(col) })
            }
            cursor
        }
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = when (matcher.match(uri)) {
        RAW_CONTACTS -> {
            val rawId = nextRawContactId++
            // No real aggregation logic in this fake — each new raw contact becomes its own
            // aggregate contact 1:1, which is enough for testing the write path's plumbing
            // (back-references, batching) without reimplementing ContactsProvider2's matching.
            val contactId = rawId
            val row = ContentValues(values)
            row.put(ContactsContract.RawContacts.CONTACT_ID, contactId)
            rawContacts[rawId] = row
            contacts[contactId] = ContentValues().apply {
                put(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, "(no name)")
                put(ContactsContract.Contacts.STARRED, 0)
                put(ContactsContract.Contacts.LOOKUP_KEY, "lookup$contactId")
            }
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId)
        }
        DATA -> {
            val id = nextDataId++
            val row = ContentValues(values)
            val rawContactId = row.getAsLong(ContactsContract.Data.RAW_CONTACT_ID)
            val contactId = rawContacts[rawContactId]?.getAsLong(ContactsContract.RawContacts.CONTACT_ID)
            if (contactId != null) row.put(CDK.Phone.CONTACT_ID, contactId)
            data[id] = row
            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
        }
        else -> null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        when (matcher.match(uri)) {
            CONTACTS_ID -> {
                val id = ContentUris.parseId(uri)
                val row = contacts[id]
                if (row == null) {
                    0
                } else {
                    row.putAll(values ?: ContentValues())
                    1
                }
            }
            DATA -> {
                val id = selectionArgs?.firstOrNull()?.toLongOrNull()
                val row = data[id]
                if (row == null) {
                    0
                } else {
                    row.putAll(values ?: ContentValues())
                    1
                }
            }
            else -> 0
        }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        when (matcher.match(uri)) {
            DATA -> {
                val id = selectionArgs?.firstOrNull()?.toLongOrNull()
                if (id != null && data.remove(id) != null) 1 else 0
            }
            RAW_CONTACTS -> {
                val contactId = selectionArgs?.firstOrNull()?.toLongOrNull()
                val toRemove = rawContacts.entries.filter { (_, v) -> v.getAsLong(ContactsContract.RawContacts.CONTACT_ID) == contactId }.map { it.key }
                toRemove.forEach { rawContacts.remove(it) }
                data.entries.filter { (_, v) -> v.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) in toRemove }.map { it.key }.forEach { data.remove(it) }
                if (contactId != null) contacts.remove(contactId)
                toRemove.size
            }
            else -> 0
        }

    override fun getType(uri: Uri): String? = null
}
