package life.furrer.c9.contacts

import android.app.Activity
import android.content.Context
import android.content.SyncAdapterType
import android.os.Looper
import android.provider.ContactsContract
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.contacts.ContactsScreen
import life.furrer.nine.contacts.FakeContactsProvider
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

/** Shared by the C9 group-management tests: the fake provider with permissions, a "me@dav" CardDAV
 *  address book (the only real one, as its sync adapter uploads) plus a WhatsApp mirror account
 *  that doesn't, and one contact in each. */
internal fun setUpGroupsProvider(context: Context) {
    FakeContactsProvider.reset()
    Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
    shadowOf(context as android.app.Application).grantPermissions(
        android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS,
    )
    Prefs.setDefaultAccount(context, null, null)
    uploadingAccountTypes("bitfire.at.davdroid" to true, "com.whatsapp" to false)
    FakeContactsProvider.putContact(1L, "Ann")
    FakeContactsProvider.putRawContact(101L, contactId = 1L, accountName = "me@dav", accountType = "bitfire.at.davdroid", sourceId = "v1")
    FakeContactsProvider.putContact(2L, "Bob")
    FakeContactsProvider.putRawContact(102L, contactId = 2L, accountName = "WhatsApp", accountType = "com.whatsapp")
}

internal fun uploadingAccountTypes(vararg types: Pair<String, Boolean>) {
    ShadowContentResolver.setSyncAdapterTypes(
        types.map { (type, uploads) -> SyncAdapterType(ContactsContract.AUTHORITY, type, true, uploads) }.toTypedArray(),
    )
}

internal fun awaitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        shadowOf(Looper.getMainLooper()).idle()
        check(System.currentTimeMillis() <= deadline) { "condition not met within ${timeoutMs}ms" }
        Thread.sleep(5)
    }
}

/** A host screen for a menu item that follows up with [ContactsScreen.showMenu]. */
class FakeScreen : Activity(), ContactsScreen {
    var shown: List<MenuItem>? = null
    override fun showMenu(items: List<MenuItem>) { shown = items }
    override fun reload() {}
}
