package life.furrer.c9.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.ContactValuePickerActivity
import life.furrer.nine.contacts.MembershipResult
import life.furrer.nine.core.util.Async

/** The invisible half of a group's `*` → "Add contacts…": menu hooks only get a plain Activity
 *  and so can't receive the picker's result, so this trampoline starts
 *  [ContactValuePickerActivity] in whole-contact mode, adds whoever is picked, and opens the
 *  picker again — pick, pick, pick, then Back to stop. One contact per pick with an
 *  automatic re-open is the keypad-friendly shape: no "add another?" question to answer between
 *  picks, and ending is the Back key every screen already ends with. It has no UI of its own
 *  (translucent), and finishes straight back onto the group's member view, which reloads itself
 *  on the provider change each add causes. */
class GroupAddContactsActivity : Activity() {

    private lateinit var group: ContactGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        group = ContactGroup(
            intent.getLongExtra(EXTRA_GROUP_ID, -1L),
            intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(EXTRA_ACCOUNT_NAME).orEmpty(),
            intent.getStringExtra(EXTRA_ACCOUNT_TYPE).orEmpty(),
        )
        if (savedInstanceState == null) startPicker()
    }

    private fun startPicker() {
        startActivityForResult(
            Intent(this, ContactValuePickerActivity::class.java).putExtra(ContactValuePickerActivity.EXTRA_PICK_CONTACT, true),
            REQUEST_PICK,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val contactId = data?.getLongExtra(ContactValuePickerActivity.EXTRA_CONTACT_ID, -1L)?.takeIf { it != -1L }
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || contactId == null) {
            finish()
            return
        }
        val name = data.getStringExtra(ContactValuePickerActivity.EXTRA_NAME).orEmpty()
        val app = applicationContext
        val target = group
        Async.run(
            background = { setGroupMember(app, contactId, target, member = true) },
            onResult = { result ->
                Toast.makeText(this, addMessage(result, name, target), Toast.LENGTH_SHORT).show()
                startPicker()
            },
        )
    }

    private fun addMessage(result: MembershipResult, name: String, group: ContactGroup): String = when (result) {
        MembershipResult.CHANGED -> "Added $name"
        MembershipResult.UNCHANGED -> "$name is already in ${group.title}"
        MembershipResult.NOT_JOINABLE -> "$name isn't in ${group.accountName}, can't join"
        MembershipResult.FAILED -> "Couldn't add $name"
    }

    companion object {
        private const val REQUEST_PICK = 600
        private const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ACCOUNT_NAME = "account_name"
        private const val EXTRA_ACCOUNT_TYPE = "account_type"

        fun intent(context: Context, group: ContactGroup): Intent =
            Intent(context, GroupAddContactsActivity::class.java)
                .putExtra(EXTRA_GROUP_ID, group.id)
                .putExtra(EXTRA_TITLE, group.title)
                .putExtra(EXTRA_ACCOUNT_NAME, group.accountName)
                .putExtra(EXTRA_ACCOUNT_TYPE, group.accountType)
    }
}
