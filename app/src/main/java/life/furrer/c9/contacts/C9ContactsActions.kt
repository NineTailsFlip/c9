package life.furrer.c9.contacts

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.util.Async
import life.furrer.nine.contacts.ContactDetail
import life.furrer.nine.contacts.ContactDetailActivity
import life.furrer.nine.contacts.ContactEntry
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.ContactsActions
import life.furrer.nine.contacts.ContactsActivity
import life.furrer.nine.contacts.FavoritesSync

/** C9's write actions on top of :contacts' read-only screens — everything New/Edit/Delete/
 *  Groups/Accounts/Sync/favorite, built from C9's own activities so :contacts never has to name
 *  them. Registered once from [life.furrer.c9.C9Application]. A delete here writes straight to
 *  [ContactStore], a favorite toggle through [FavoritesSync] (STARRED plus the favorites group), and relies on the screens' own ContentObserver to notice the
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
        items.add(MenuItem("Groups") { activity.startActivity(Intent(activity, ContactGroupsActivity::class.java)) })
        items.add(MenuItem("Accounts") { activity.startActivity(Intent(activity, ContactAccountsActivity::class.java)) })
        items.add(
            MenuItem("Sync now") {
                Async.run(
                    background = { ContactStore.requestSync(activity) },
                    onResult = { Toast.makeText(activity, "Syncing…", Toast.LENGTH_SHORT).show() },
                )
            },
        )
        return items
    }

    override fun detailMenuItems(activity: Activity, contact: ContactDetail): List<MenuItem> {
        val favoriteLabel = if (contact.starred) "Remove from favorites" else "Add to favorites"
        return listOf(
            MenuItem("Edit") {
                activity.startActivity(
                    Intent(activity, ContactEditActivity::class.java)
                        .putExtra(ContactEditActivity.EXTRA_CONTACT_ID, contact.id),
                )
            },
            MenuItem(favoriteLabel) {
                Async.run(
                    background = { FavoritesSync.setFavorite(activity, contact.id, !contact.starred) },
                    onResult = { success -> if (!success) Toast.makeText(activity, "Couldn't update", Toast.LENGTH_SHORT).show() },
                )
            },
            MenuItem("Delete") {
                showConfirm(activity as ContactDetailActivity, contact.displayName) { deleteContact(activity, contact.id) }
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
