package life.furrer.c9.contacts

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import life.furrer.c9.core.Prefs
import life.furrer.nine.contacts.ContactStore.WritableAccount

/** Which of the phone's contact accounts are real address books — somewhere a group or contact
 *  written by C9 actually reaches a server — as opposed to messaging apps' mirror accounts.
 *
 *  "Writable" also covers WhatsApp's and Signal's mirror accounts (confirmed on-device, where
 *  both showed up), and a group there would never reach a real address book. The Default address
 *  book setting is the user's own answer to "which of these is my real address book", so when
 *  set (and still present) it is the only one. Without one, fall back to accounts whose sync
 *  adapter uploads contacts at all: that drops WhatsApp (declared read-only) but not Signal,
 *  which on-device claims supportsUploading=true; app names are deliberately never hardcoded.
 *
 *  Shared by the favorites-group picker and every group-management screen, so they can never
 *  disagree about where a group may live. */
internal fun realAddressBooks(context: Context, accounts: List<WritableAccount>): List<WritableAccount> {
    val default = defaultAddressBook(context, accounts)
    if (default != null) return listOf(default)
    val uploading = ContentResolver.getSyncAdapterTypes()
        .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
        .map { it.accountType }
        .toSet()
    return accounts.filter { it.type in uploading }
}

/** The configured Default address book, if it is set and still one of [accounts]. */
internal fun defaultAddressBook(context: Context, accounts: List<WritableAccount>): WritableAccount? {
    val name = Prefs.defaultAccountName(context)
    val type = Prefs.defaultAccountType(context)
    return accounts.firstOrNull { it.name == name && it.type == type }
}

/** Where a new group goes without asking: the Default address book, else the only real one;
 *  null when there is none or several and the user has to say which. */
internal fun unambiguousAddressBook(context: Context, accounts: List<WritableAccount>): WritableAccount? =
    defaultAddressBook(context, accounts) ?: realAddressBooks(context, accounts).singleOrNull()
