package life.furrer.c9

import android.app.Application
import io.github.ninetailsflip.update.Updates
import life.furrer.nine.core.KeyHelp
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.c9.settings.SettingsActivity
import life.furrer.c9.contacts.C9ContactsActions
import life.furrer.c9.contacts.GroupSyncScheduler
import life.furrer.c9.contacts.groupSyncs
import life.furrer.c9.calendar.reminders.Reminders
import life.furrer.nine.contacts.ContactsHost

class C9Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Nine.init(c9App)
        Updates.init(this, "NineTailsFlip/c9")
        ContactsHost.init(C9ContactsActions)
        // Content-trigger jobs don't survive a force-stop, so every process start re-arms the job
        // and catches up on whatever changed meanwhile.
        GroupSyncScheduler.update(this)
        if (groupSyncs.any { it.needsLineUps(this) }) GroupSyncScheduler.lineUpAsync(this)
        // Same for reminders: after a force-stop neither C9's alarm nor its job is left, and a
        // pass re-arms both (and fires whatever came due meanwhile).
        Reminders.ensureRunning(this)
    }
}

private val c9App = NineApp(
    id = "c9",
    name = "C9",
    tagline = "Calendars and Contacts on 9 keys",
    settingsActivity = SettingsActivity::class.java,
    contactsSurfaceId = "contacts",
    surfaces = listOf(
        NineSurface(
            id = "calendar",
            name = "C9 Calendar",
            introText = """
                C9's Calendar surface shows your events in Day, 3-Day, Week or Agenda view. Move the
                selection with Up/Down, page the view backward or forward with Left/Right, and press
                OK to open what's selected.

                Press "#" to cycle between views, "0" to jump to today (hold it to pick any date), and
                "*" to open the menu for creating, editing or deleting events, searching, and choosing
                which calendars are shown. Hold "*" to jump straight into Settings.

                When an event's reminder is due, C9 shows a notification. Open it from the
                notification shade to see the event, or Snooze or Dismiss it right there; Settings
                sets the snooze length, or turns reminders off if another calendar app already
                shows them.
            """.trimIndent(),
            keyMap = listOf(
                KeyHelp("Up / Down", "Move the selection within the current view."),
                KeyHelp(
                    "Left / Right",
                    "Page the view backward/forward (a day, 3 days, or a week, depending on the " +
                        "current view).",
                ),
                KeyHelp("OK", "Open the selected event, or the selected day in Agenda/Week."),
                KeyHelp("1-9", "Jump to that event's position in the current view."),
                KeyHelp("0", "Jump to today."),
                KeyHelp("Hold 0", "Open the date picker (go to date)."),
                KeyHelp("#", "Cycle the view: Day → 3-Day → Week → Agenda."),
                KeyHelp("*", "Open the menu: new event, edit/delete, search, calendars, sync, settings."),
                KeyHelp("Hold *", "Open Settings."),
                KeyHelp("Back", "Close the current screen."),
            ),
        ),
        NineSurface(
            id = "contacts",
            name = "C9 Contacts",
            introText = """
                C9's Contacts surface lists your contacts, grouped alphabetically. Move the selection
                with Up/Down, jump between letter groups with Left/Right, and press OK to open a
                contact.

                Type any digit to search by name or number, the same T9 way as everywhere else. Press
                "#" to show only favorites, and the call key to dial the selected contact. Press "*" —
                or hold OK — to open the menu for creating, editing or deleting contacts and managing
                groups and accounts. Hold "*" to jump straight into Settings.
            """.trimIndent(),
            keyMap = listOf(
                KeyHelp("Up / Down", "Move the selection."),
                KeyHelp("Left / Right", "Jump to the next/previous letter group."),
                KeyHelp("OK", "Open the selected contact."),
                KeyHelp("Hold OK", "Open the menu."),
                KeyHelp("Digits", "Type to search contacts by name or number."),
                KeyHelp("#", "Toggle showing favorites only."),
                KeyHelp("Call key", "Dial the selected contact."),
                KeyHelp("*", "Open the menu: new contact, edit/delete, groups, accounts, sync, settings."),
                KeyHelp("Hold *", "Open Settings."),
                KeyHelp("Back", "Clear the search, then close."),
            ),
        ),
    ),
)
