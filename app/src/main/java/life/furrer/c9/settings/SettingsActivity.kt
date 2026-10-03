package life.furrer.c9.settings

import life.furrer.c9.calendar.CalendarStore
import life.furrer.c9.calendar.reminders.ReminderNotifications
import life.furrer.c9.calendar.reminders.ReminderPermission
import life.furrer.c9.calendar.reminders.Reminders
import android.content.Intent
import life.furrer.c9.contacts.FavoritesGroupActivity
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SectionScope
import life.furrer.nine.core.settings.SettingsScreen

private const val MIN_DURATION_MINUTES = 15
private const val MAX_DURATION_MINUTES = 480
private const val DURATION_STEP_MINUTES = 15
private const val MIN_SNOOZE_MINUTES = 5
private const val MAX_SNOOZE_MINUTES = 60
private const val SNOOZE_STEP_MINUTES = 5

/** Calendar and Contacts each get their own section — reached by holding `*` from either
 *  surface's main screen, or as the last "* menu" item — sharing everything core's Settings
 *  framework already supplies (Theme, About). One Activity class handles both launcher entries
 *  (via the surface id core's [NineSettingsActivity.intentFor] threads through), rather than two,
 *  since the row-list/selection/key-handling plumbing is identical either way. */
class SettingsActivity : NineSettingsActivity() {

    /** Back from the permission dialog or the system's notification settings: whatever is due
     *  shows now, if reminders can show at all. */
    override fun onResume() {
        super.onResume()
        if (Prefs.reminderNotifications(this)) Reminders.refreshInBackground(this)
    }

    override fun buildAppSections(screen: SettingsScreen, surface: NineSurface) {
        if (surface.id == "calendar") {
            screen.section("Calendar") { buildCalendarSection(this) }
        }
        if (surface.id == "contacts") {
            screen.section("Contacts") { buildContactsSection(this) }
        }
    }

    private fun buildCalendarSection(scope: SectionScope) {
        // The calendar this row is currently pointing at — the same "unset falls back to the
        // first one" resolution EventEditActivity applies when actually creating an event, so
        // what's shown here always matches what a new event would get.
        scope.choice(
            label = "Default calendar",
            options = { CalendarStore.queryCalendars(this) },
            get = { CalendarStore.queryCalendars(this).firstOrNull { it.id == Prefs.defaultCalendarId(this) } },
            set = { calendar -> Prefs.setDefaultCalendarId(this, calendar?.id) },
            render = { it.displayName },
        )
        scope.intRange(
            label = "Default duration",
            min = MIN_DURATION_MINUTES,
            max = MAX_DURATION_MINUTES,
            step = DURATION_STEP_MINUTES,
            get = { Prefs.defaultEventDurationMinutes(this) },
            set = { Prefs.setDefaultEventDurationMinutes(this, it) },
            render = ::formatDuration,
        )
        // Off only for a second calendar app that already shows reminders — both notifying
        // would make every reminder ring twice.
        scope.toggle(
            label = "Reminder notifications",
            get = { Prefs.reminderNotifications(this) },
            set = { on ->
                Prefs.setReminderNotifications(this, on)
                if (on) {
                    ReminderPermission.ensureAllowed(this)
                    Reminders.refreshInBackground(this)
                } else {
                    ReminderNotifications.cancelAll(this)
                }
            },
        )
        scope.intRange(
            label = "Snooze reminders for",
            min = MIN_SNOOZE_MINUTES,
            max = MAX_SNOOZE_MINUTES,
            step = SNOOZE_STEP_MINUTES,
            get = { Prefs.reminderSnoozeMinutes(this) },
            set = { Prefs.setReminderSnoozeMinutes(this, it) },
            render = { "$it min" },
        )
    }

    private fun buildContactsSection(scope: SectionScope) {
        // -1 (null here) means "ask each time," matched to a real account by name+type since
        // list position isn't a stable identity.
        scope.choice(
            label = "Default address book",
            options = { ContactStore.queryWritableAccounts(this) },
            get = {
                ContactStore.queryWritableAccounts(this)
                    .firstOrNull { it.name == Prefs.defaultAccountName(this) && it.type == Prefs.defaultAccountType(this) }
            },
            set = { account -> Prefs.setDefaultAccount(this, account?.name, account?.type) },
            render = { it.name },
            nullLabel = "ask each time",
        )
        // Value is read live by the row each render; the picker is a separate screen, and
        // NineSettingsActivity re-renders on resume so a choice shows up on return.
        scope.action(
            label = "Favorites group",
            value = { favoritesGroupLabel(FavoritesSync.favoritesGroupState(this)) },
            onSelect = { startActivity(Intent(this, FavoritesGroupActivity::class.java)) },
        )
        numberFormatRow(scope)
    }

    private fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            remainder == 0 -> "${hours}h"
            hours == 0 -> "${remainder}m"
            else -> "${hours}h ${remainder}m"
        }
    }
}

/** What the Favorites group row shows: the group and where it lives, or why there isn't one. */
internal fun favoritesGroupLabel(state: FavoritesSync.GroupState): String = when (state) {
    FavoritesSync.GroupState.None -> "none (starred only)"
    is FavoritesSync.GroupState.Present -> "${state.group.title} · ${state.group.accountName}"
    is FavoritesSync.GroupState.Missing -> "${state.stored.title}: not found"
}
