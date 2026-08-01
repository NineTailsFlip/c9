package life.furrer.c9.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds as CDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ContactDetailRowsTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    private fun dataRow(id: Long, mimetype: String, values: ContentValues) =
        DataRow(id = id, rawContactId = 100L, mimetype = mimetype, values = values)

    private fun detailOf(vararg rows: DataRow) = ContactDetail(
        id = 1L, lookupKey = "lookup1", displayName = "Andreas Furrer", starred = false,
        photoThumbnailUri = null, rows = rows.toList(), rawContacts = emptyList(),
    )

    private fun List<ContactDetailRow>.items() = filterIsInstance<ContactDetailRow.Item>()
    private fun List<ContactDetailRow>.headers() = filterIsInstance<ContactDetailRow.SectionHeader>()

    @Test
    fun `name and organization rows are excluded, shown in the header instead`() {
        val detail = detailOf(
            dataRow(1, CDK.StructuredName.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.StructuredName.DISPLAY_NAME, "Andreas Furrer") }),
            dataRow(2, CDK.Organization.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Organization.COMPANY, "Acme AG") }),
        )
        assertTrue(buildDetailRows(resources, detail).isEmpty())
    }

    @Test
    fun `photo and group membership rows produce no text row`() {
        val detail = detailOf(
            dataRow(1, CDK.Photo.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Photo.PHOTO, ByteArray(4)) }),
            dataRow(2, CDK.GroupMembership.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.GroupMembership.GROUP_ROW_ID, 5L) }),
        )
        assertTrue(buildDetailRows(resources, detail).isEmpty())
    }

    @Test
    fun `same-kind rows are grouped under one shared section header`() {
        val detail = detailOf(
            dataRow(1, CDK.Phone.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Phone.NUMBER, "111"); put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE) }),
            dataRow(2, CDK.Phone.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Phone.NUMBER, "222"); put(CDK.Phone.TYPE, CDK.Phone.TYPE_WORK) }),
        )
        val result = buildDetailRows(resources, detail)
        assertEquals(listOf(ContactDetailRow.SectionHeader("PHONE")), result.headers())
        assertEquals(2, result.items().size)
    }

    @Test
    fun `different kinds each get their own section header, grouped by DataKind order`() {
        // Deliberately inserted out of DataKind's own declared order (EMAIL before PHONE) —
        // buildDetailRows must still emit PHONE first since it groups/sorts by DataKind.ordinal,
        // not raw Data-table order.
        val detail = detailOf(
            dataRow(1, CDK.Email.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Email.ADDRESS, "a@b.ch") }),
            dataRow(2, CDK.Phone.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Phone.NUMBER, "123") }),
        )
        val result = buildDetailRows(resources, detail)
        assertEquals(listOf(ContactDetailRow.SectionHeader("PHONE"), ContactDetailRow.SectionHeader("EMAIL")), result.headers())
        assertEquals(DataKind.PHONE, result.items()[0].kind)
        assertEquals(DataKind.EMAIL, result.items()[1].kind)
    }

    @Test
    fun `numbers rows from 1, capped at 9, extras still included with a null index`() {
        val rows = (1..11).map { i ->
            dataRow(i.toLong(), CDK.Phone.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Phone.NUMBER, "+41 79 000 00 $i") })
        }
        val detail = detailOf(*rows.toTypedArray())

        val items = buildDetailRows(resources, detail).items()

        assertEquals(11, items.size)
        assertEquals(1, items[0].displayIndex)
        assertEquals(9, items[8].displayIndex)
        assertNull(items[9].displayIndex)
        assertNull(items[10].displayIndex)
    }

    @Test
    fun `a phone row carries its type label and formatted number`() {
        val detail = detailOf(
            dataRow(
                1, CDK.Phone.CONTENT_ITEM_TYPE,
                ContentValues().apply {
                    put(CDK.Phone.NUMBER, "+41 79 123 45 67")
                    put(CDK.Phone.TYPE, CDK.Phone.TYPE_MOBILE)
                },
            ),
        )
        val row = buildDetailRows(resources, detail).items().single()
        assertEquals(DataKind.PHONE, row.kind)
        assertEquals("MOBILE", row.typeLabel)
        assertEquals("+41 79 123 45 67", row.value)
    }

    @Test
    fun `an IM row's type label is its protocol, not home-work-other`() {
        val detail = detailOf(
            dataRow(
                1, CDK.Im.CONTENT_ITEM_TYPE,
                ContentValues().apply {
                    put(CDK.Im.DATA, "andreas.furrer")
                    put(CDK.Im.PROTOCOL, CDK.Im.PROTOCOL_SKYPE)
                },
            ),
        )
        val row = buildDetailRows(resources, detail).items().single()
        assertEquals("SKYPE", row.typeLabel)
        assertEquals("andreas.furrer", row.value)
    }

    @Test
    fun `a kind with no meaningful type label falls back to its section label`() {
        val detail = detailOf(dataRow(1, CDK.Note.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Note.NOTE, "likes tea") }))
        val row = buildDetailRows(resources, detail).items().single()
        assertEquals("NOTE", row.typeLabel)
    }

    @Test
    fun `a row with a blank formatted value is skipped`() {
        val detail = detailOf(dataRow(1, CDK.Phone.CONTENT_ITEM_TYPE, ContentValues()))
        assertTrue(buildDetailRows(resources, detail).isEmpty())
    }

    @Test
    fun `an unknown mimetype row is preserved under Other rather than dropped`() {
        val detail = detailOf(
            dataRow(1, "vnd.android.cursor.item/vendor.custom.field", ContentValues().apply { put("data1", "vendor value") }),
        )
        val result = buildDetailRows(resources, detail)
        assertEquals(listOf(ContactDetailRow.SectionHeader("OTHER")), result.headers())
        val row = result.items().single()
        assertNull(row.kind)
        assertEquals("OTHER", row.typeLabel)
        assertEquals("vendor value", row.value)
        assertEquals(1, row.displayIndex)
    }

    @Test
    fun `multiple unknown mimetype rows share a single OTHER header, sorted after every known kind`() {
        val detail = detailOf(
            dataRow(1, "vnd.android.cursor.item/vendor.a", ContentValues().apply { put("data1", "a") }),
            dataRow(2, CDK.Phone.CONTENT_ITEM_TYPE, ContentValues().apply { put(CDK.Phone.NUMBER, "123") }),
            dataRow(3, "vnd.android.cursor.item/vendor.b", ContentValues().apply { put("data1", "b") }),
        )
        val result = buildDetailRows(resources, detail)
        assertEquals(listOf(ContactDetailRow.SectionHeader("PHONE"), ContactDetailRow.SectionHeader("OTHER")), result.headers())
        assertEquals(3, result.items().size)
    }

    @Test
    fun `an unknown mimetype row with no data1 value is skipped rather than shown blank`() {
        val detail = detailOf(dataRow(1, "vnd.android.cursor.item/vendor.custom.field", ContentValues()))
        assertTrue(buildDetailRows(resources, detail).isEmpty())
    }
}
