package life.furrer.c9.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ContactStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `permission is denied by default in a fresh test environment`() {
        val freshContext = RuntimeEnvironment.getApplication()
        shadowOf(freshContext).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        assertFalse(ContactStore.hasPermission(freshContext))
    }

    @Test
    fun `queryContacts returns nothing without contacts permission`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        assertTrue(ContactStore.queryContacts(context).isEmpty())
    }

    @Test
    fun `hasPermission reflects a granted READ_CONTACTS permission`() {
        assertTrue(ContactStore.hasPermission(context))
    }

    @Test
    fun `queryContacts returns the flat contact list`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", starred = true)
        FakeContactsProvider.putContact(2L, "Bea Lombardi")

        val result = ContactStore.queryContacts(context)

        assertEquals(2, result.size)
        assertEquals("Andreas Furrer", result[0].displayName)
        assertTrue(result[0].starred)
        assertEquals("Bea Lombardi", result[1].displayName)
        assertFalse(result[1].starred)
    }

    @Test
    fun `queryContacts bulk-loads phone numbers per contact for number search`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41 79 123 45 67")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
                put(CDK.Phone.IS_SUPER_PRIMARY, 1)
            },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41 44 987 65 43")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_WORK)
            },
        )

        val result = ContactStore.queryContacts(context)

        val numbers = result.single().phoneNumbers
        assertEquals(listOf("+41 79 123 45 67", "+41 44 987 65 43"), numbers.map { it.number })
        assertEquals("MOBILE", numbers[0].label)
        assertTrue(numbers[0].isSuperPrimary)
        assertEquals("WORK", numbers[1].label)
        assertFalse(numbers[1].isSuperPrimary)
    }

    @Test
    fun `queryContacts gives a contact with no phone numbers an empty list, not a crash`() {
        FakeContactsProvider.putContact(1L, "Bea Lombardi")
        assertTrue(ContactStore.queryContacts(context).single().phoneNumbers.isEmpty())
    }

    @Test
    fun `queryContactDetail returns null without permission`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        assertNull(ContactStore.queryContactDetail(context, 1L))
    }

    @Test
    fun `queryContactDetail returns null for an unknown contact id`() {
        assertNull(ContactStore.queryContactDetail(context, 999L))
    }

    @Test
    fun `queryContactDetail reads every Data row for the contact`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", lookupKey = "abc123")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(CDK.Phone.NUMBER, "+41 79 123 45 67")
                put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
            },
        )
        FakeContactsProvider.putData(
            id = 11L, contactId = 1L, rawContactId = 100L, mimetype = CDK.Email.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.Email.ADDRESS, "andreas@acme.ch") },
        )

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertEquals("Andreas Furrer", detail.displayName)
        assertEquals("abc123", detail.lookupKey)
        assertEquals(2, detail.rows.size)
        val phoneRow = detail.rows.first { it.mimetype == CDK.Phone.CONTENT_ITEM_TYPE }
        assertEquals("+41 79 123 45 67", phoneRow.values.getAsString(CDK.Phone.NUMBER))
        assertEquals(CDK.Phone.TYPE_MOBILE, phoneRow.values.getAsInteger(CDK.Phone.TYPE))
    }

    @Test
    fun `queryContactDetail preserves rows of an unknown mimetype rather than dropping them`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 100L, mimetype = "vnd.android.cursor.item/vendor.custom.field",
            values = ContentValues().apply { put("data1", "some vendor value") },
        )

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertEquals(1, detail.rows.size)
        assertNull(DataKind.fromMimetype(detail.rows.single().mimetype))
        assertEquals("some vendor value", detail.rows.single().values.getAsString("data1"))
    }

    @Test
    fun `queryContactDetail with a single raw contact is not linked`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertFalse(detail.isLinked)
        assertEquals(1, detail.rawContacts.size)
    }

    @Test
    fun `queryContactDetail with multiple raw contacts is linked`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "work", accountType = "com.work")

        val detail = ContactStore.queryContactDetail(context, 1L)!!

        assertTrue(detail.isLinked)
        assertEquals(setOf("fastmail", "work"), detail.rawContacts.map { it.accountName }.toSet())
    }

    @Test
    fun `setStarred updates the STARRED column`() {
        FakeContactsProvider.putContact(1L, "Andreas Furrer", starred = false)

        val updated = ContactStore.setStarred(context, 1L, true)

        assertTrue(updated)
        assertTrue(ContactStore.queryContacts(context).single().starred)
    }

    // --- Groups filter (§7.4 `*` -> Groups) -------------------------------------------------

    @Test
    fun `queryGroups excludes deleted groups and groups with no title`() {
        FakeContactsProvider.putGroup(1L, "Family", "fastmail", "com.fastmail")
        FakeContactsProvider.putGroup(2L, "Old group", "fastmail", "com.fastmail", deleted = true)
        FakeContactsProvider.putGroup(3L, null, "fastmail", "com.fastmail")

        val groups = ContactStore.queryGroups(context)

        assertEquals(listOf("Family"), groups.map { it.title })
    }

    @Test
    fun `groupsByAccount groups by account name`() {
        FakeContactsProvider.putGroup(1L, "Family", "fastmail", "com.fastmail")
        FakeContactsProvider.putGroup(2L, "Work", "work", "com.work")
        FakeContactsProvider.putGroup(3L, "Friends", "fastmail", "com.fastmail")

        val grouped = ContactStore.groupsByAccount(ContactStore.queryGroups(context))

        assertEquals(setOf("fastmail", "work"), grouped.map { it.first }.toSet())
        assertEquals(2, grouped.single { it.first == "fastmail" }.second.size)
    }

    @Test
    fun `selected group ids round trip through prefs -- null, empty, and non-empty`() {
        assertNull(ContactStore.getSelectedGroupIds(context))

        ContactStore.setSelectedGroupIds(context, emptySet())
        assertEquals(emptySet<Long>(), ContactStore.getSelectedGroupIds(context))

        ContactStore.setSelectedGroupIds(context, setOf(1L, 2L))
        assertEquals(setOf(1L, 2L), ContactStore.getSelectedGroupIds(context))
    }

    @Test
    fun `queryContactIdsInGroups matches only contacts with a GroupMembership row in the given groups`() {
        FakeContactsProvider.putContact(1L, "In group")
        FakeContactsProvider.putContact(2L, "Not in group")
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putData(
            1L, contactId = 1L, rawContactId = 100L, mimetype = CDK.GroupMembership.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(CDK.GroupMembership.GROUP_ROW_ID, 5L) },
        )

        assertEquals(setOf(1L), ContactStore.queryContactIdsInGroups(context, setOf(5L)))
        assertEquals(emptySet<Long>(), ContactStore.queryContactIdsInGroups(context, setOf(6L)))
        assertEquals(emptySet<Long>(), ContactStore.queryContactIdsInGroups(context, emptySet()))
    }

    // --- Accounts filter (§7.4 `*` -> Accounts) ----------------------------------------------

    @Test
    fun `queryAccounts returns every distinct account with a raw contact`() {
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 2L, accountName = "whatsapp", accountType = "com.whatsapp")
        FakeContactsProvider.putRawContact(102L, contactId = 3L, accountName = "fastmail", accountType = "com.fastmail")

        val accounts = ContactStore.queryAccounts(context)

        assertEquals(setOf("fastmail", "whatsapp"), accounts.map { it.name }.toSet())
    }

    @Test
    fun `queryAccounts requires only READ_CONTACTS, unlike queryWritableAccounts`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")

        assertTrue(ContactStore.queryWritableAccounts(context).isEmpty())
        assertEquals(1, ContactStore.queryAccounts(context).size)
    }

    @Test
    fun `selected accounts round trip through prefs -- null, empty, and non-empty`() {
        assertNull(ContactStore.getSelectedAccounts(context))

        ContactStore.setSelectedAccounts(context, emptySet())
        assertEquals(emptySet<ContactStore.WritableAccount>(), ContactStore.getSelectedAccounts(context))

        val account = ContactStore.WritableAccount("fastmail", "com.fastmail")
        ContactStore.setSelectedAccounts(context, setOf(account))
        assertEquals(setOf(account), ContactStore.getSelectedAccounts(context))
    }

    @Test
    fun `queryContactIdsInAccounts matches only contacts with a raw contact in the given accounts`() {
        FakeContactsProvider.putRawContact(100L, contactId = 1L, accountName = "fastmail", accountType = "com.fastmail")
        FakeContactsProvider.putRawContact(101L, contactId = 2L, accountName = "whatsapp", accountType = "com.whatsapp")

        val fastmail = ContactStore.WritableAccount("fastmail", "com.fastmail")
        assertEquals(setOf(1L), ContactStore.queryContactIdsInAccounts(context, setOf(fastmail)))
        assertEquals(emptySet<Long>(), ContactStore.queryContactIdsInAccounts(context, emptySet()))
    }
}
