package life.furrer.c9.contacts

import android.content.Context
import life.furrer.nine.contacts.ContactGroups
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.contacts.MembershipResult

/** Whether [group] is the configured favorites group. Read from the stored setting alone (no
 *  provider query), so it is safe on the main thread when a menu is being built. */
internal fun isFavoritesGroup(context: Context, group: ContactGroup): Boolean {
    val favorites = FavoritesSync.favoritesGroup(context) ?: return false
    return favorites.id == group.id && favorites.accountName == group.accountName && favorites.accountType == group.accountType
}

/** The one way C9 puts a contact in or takes it out of a group. The favorites group is the
 *  exception to a plain membership row: favorites are Android's STARRED flag *and* that group's
 *  membership kept in step, so writing only the membership would leave the contact starred (or
 *  not) against the group — [FavoritesSync.setFavorite] sets both and updates its baseline so
 *  the next line-up doesn't read the change as someone else's. Blocking; call off the main
 *  thread. A contact that can't join (no raw contact in the group's account) is reported, never
 *  silently starred. */
internal fun setGroupMember(context: Context, contactId: Long, group: ContactGroup, member: Boolean): MembershipResult {
    if (!isFavoritesGroup(context, group)) {
        return if (member) ContactGroups.addToGroup(context, contactId, group) else ContactGroups.removeFromGroup(context, contactId, group)
    }
    if (!ContactGroups.isJoinable(context, contactId, group)) {
        // Leaving needs nothing when the contact could never have been in it; the star alone
        // is still the contact's to clear via the normal favorite toggle.
        return MembershipResult.NOT_JOINABLE
    }
    return if (FavoritesSync.setFavorite(context, contactId, member, group)) MembershipResult.CHANGED else MembershipResult.FAILED
}

/** Applies a set of membership changes for one contact (the editor's deferred Groups row).
 *  Returns false if any write failed; not-joinable groups are skipped, like the checklist
 *  already refuses them. */
internal fun applyGroupChanges(context: Context, contactId: Long, add: Collection<ContactGroup>, remove: Collection<ContactGroup>): Boolean {
    var ok = true
    remove.forEach { if (setGroupMember(context, contactId, it, false) == MembershipResult.FAILED) ok = false }
    add.forEach { if (setGroupMember(context, contactId, it, true) == MembershipResult.FAILED) ok = false }
    return ok
}
