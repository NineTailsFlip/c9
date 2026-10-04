package life.furrer.c9.contacts

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.util.Async
import life.furrer.nine.contacts.ContactDetail
import life.furrer.nine.contacts.ContactDetailActivity
import life.furrer.nine.contacts.ContactEntry
import life.furrer.nine.contacts.ContactGroups
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactsScreen
import life.furrer.nine.contacts.MembershipResult
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactsActions
import life.furrer.nine.contacts.ContactsActivity
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.contacts.GroupSync
import life.furrer.nine.contacts.VoicemailSync

/** C9's write actions on top of :contacts' read-only screens — everything New/Edit/Delete/
 *  Accounts/Sync/favorite (the "Groups" item and its screen are :contacts' own, shared with P9), built from C9's own activities so :contacts never has to name
 *  them. Registered once from [life.furrer.c9.C9Application]. A delete here writes straight to
 *  [ContactStore], a favorite or send-to-voicemail toggle through its [GroupSync] (the contact option plus its group), and relies on the screens' own ContentObserver to notice the
 *  change and reload — this object never calls back into ContactsActivity/ContactDetailActivity
 *  beyond [showConfirm]'s nested menu. */
object C9ContactsActions : ContactsActions {

    override fun listMenuItems(activity: Activity, selected: ContactEntry?): List<MenuItem> {
        val items = mutableListOf(MenuItem("New contact") { activity.startActivity(Intent(activity, ContactEditActivity::class.java)) })
        if (selected != null) {
            items.add(
                MenuItem("Edit \"${selected.displayName}\"") {
                    activity.startActivity(
                        Intent(activity, ContactEditActivity::class.java)
                            .putExtra(ContactEditActivity.EXTRA_CONTACT_ID, selected.id),
                    )
                },
            )
            items.add(
                MenuItem("Delete \"${selected.displayName}\"") {
                    showConfirm(activity as ContactsActivity, selected.displayName) { deleteContact(activity, selected.id) }
                },
            )
        }
        return items
    }

    override fun listMenuTrailingItems(activity: Activity): List<MenuItem> = listOf(
        MenuItem("Accounts") { activity.startActivity(Intent(activity, ContactAccountsActivity::class.java)) },
        MenuItem("Sync now") {
            Async.run(
                background = { ContactStore.requestSync(activity) },
                onResult = { Toast.makeText(activity, "Syncing…", Toast.LENGTH_SHORT).show() },
            )
        },
    )

    override fun detailMenuItems(activity: Activity, contact: ContactDetail): List<MenuItem> {
        val favoriteLabel = if (contact.starred) "Remove from favorites" else "Add to favorites"
        val voicemailLabel = if (contact.sendToVoicemail) "Stop sending calls to voicemail" else "Send calls to voicemail"
        return listOf(
            MenuItem("Edit") {
                activity.startActivity(
                    Intent(activity, ContactEditActivity::class.java)
                        .putExtra(ContactEditActivity.EXTRA_CONTACT_ID, contact.id),
                )
            },
            MenuItem(favoriteLabel) { setProperty(activity, FavoritesSync, contact.id, !contact.starred) },
            MenuItem(voicemailLabel) { setProperty(activity, VoicemailSync, contact.id, !contact.sendToVoicemail) },
            MenuItem("Groups…") {
                activity.startActivity(GroupChecklistActivity.intent(activity, contact.id))
            },
            MenuItem("Delete") {
                showConfirm(activity as ContactDetailActivity, contact.displayName) { deleteContact(activity, contact.id) }
            },
        )
    }

    private fun setProperty(activity: Activity, sync: GroupSync, contactId: Long, on: Boolean) {
        Async.run(
            background = { sync.setProperty(activity, contactId, on) },
            onResult = { success -> if (!success) Toast.makeText(activity, "Couldn't update", Toast.LENGTH_SHORT).show() },
        )
    }

    // --- Groups (the screens themselves are :contacts', shared with P9/M9) -------------------

    override fun groupsListMenuItems(activity: Activity, selected: ContactGroup?): List<MenuItem> {
        val items = mutableListOf(MenuItem("New group") { newGroup(activity) })
        if (selected != null) {
            items.add(MenuItem("Rename “${selected.title}”") { activity.startActivity(GroupNameActivity.renameIntent(activity, selected)) })
            items.add(MenuItem("Delete “${selected.title}”") { confirmDeleteGroup(activity, selected) })
        }
        return items
    }

    /** A new group goes in the Default address book if set, else the only real one, else the
     *  user is asked which — the same "never guess among accounts" rule as a new contact, with
     *  WhatsApp/Signal mirror accounts never offered (see [realAddressBooks]). */
    private fun newGroup(activity: Activity) {
        Async.run(
            background = {
                val accounts = ContactStore.queryWritableAccounts(activity)
                unambiguousAddressBook(activity, accounts) to realAddressBooks(activity, accounts)
            },
            onResult = { (target, real) ->
                when {
                    target != null -> activity.startActivity(GroupNameActivity.createIntent(activity, target))
                    real.isEmpty() -> Toast.makeText(activity, "No address book to put a group in", Toast.LENGTH_LONG).show()
                    else -> (activity as? ContactsScreen)?.showMenu(
                        real.map { account ->
                            MenuItem("${account.name} (${account.type.substringAfterLast('.')})") {
                                activity.startActivity(GroupNameActivity.createIntent(activity, account))
                            }
                        },
                    )
                }
            },
        )
    }

    /** The confirmation says what is *not* lost (the contacts stay) and, for a group chosen for
     *  a [SyncedProperty], what changes: the property stays, on this phone only. */
    private fun confirmDeleteGroup(activity: Activity, group: ContactGroup) {
        val property = SyncedProperty.ofGroup(activity, group)
        val label = when (property) {
            SyncedProperty.FAVORITES -> "Yes, delete “${group.title}” — favorites become starred only"
            SyncedProperty.VOICEMAIL -> "Yes, delete “${group.title}” — voicemail stays on this phone only"
            null -> "Yes, delete “${group.title}” (contacts stay)"
        }
        (activity as? ContactsScreen)?.showMenu(
            listOf(MenuItem(label) { deleteGroup(activity, group, property) }, MenuItem("Keep group") {}),
        )
    }

    private fun deleteGroup(activity: Activity, group: ContactGroup, property: SyncedProperty?) {
        val app = activity.applicationContext
        Async.run(
            background = { ContactGroups.deleteGroup(app, group) },
            onResult = { success ->
                if (!success) {
                    Toast.makeText(activity, "Couldn't delete", Toast.LENGTH_SHORT).show()
                } else if (property != null) {
                    // The stored group is gone; the property keeps working on its own rather
                    // than the settings screen reporting a missing group.
                    property.sync.setGroup(app, null)
                    GroupSyncScheduler.update(app)
                }
            },
        )
    }

    override fun groupMembersMenuItems(activity: Activity, group: ContactGroup, selected: ContactEntry?): List<MenuItem> {
        val items = mutableListOf(MenuItem("Add contacts…") { activity.startActivity(GroupAddContactsActivity.intent(activity, group)) })
        if (selected != null) {
            items.add(MenuItem("Remove “${selected.displayName}” from group") { removeFromGroup(activity, group, selected) })
        }
        return items
    }

    private fun removeFromGroup(activity: Activity, group: ContactGroup, contact: ContactEntry) {
        val app = activity.applicationContext
        Async.run(
            background = { setGroupMember(app, contact.id, group, member = false) },
            onResult = { result ->
                if (result == MembershipResult.FAILED) Toast.makeText(activity, "Couldn't remove", Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun deleteContact(activity: Activity, id: Long) {
        Async.run(
            background = { ContactStore.deleteContact(activity, id) },
            onResult = { success -> if (!success) Toast.makeText(activity, "Couldn't delete", Toast.LENGTH_SHORT).show() },
        )
    }

    private fun showConfirm(activity: ContactsActivity, displayName: String, onConfirm: () -> Unit) {
        activity.showMenu(
            listOf(
                MenuItem("Yes, delete \"$displayName\"") { onConfirm() },
                MenuItem("Keep contact") {},
            ),
        )
    }

    private fun showConfirm(activity: ContactDetailActivity, displayName: String, onConfirm: () -> Unit) {
        activity.showMenu(
            listOf(
                MenuItem("Yes, delete \"$displayName\"") { onConfirm() },
                MenuItem("Keep contact") {},
            ),
        )
    }
}
