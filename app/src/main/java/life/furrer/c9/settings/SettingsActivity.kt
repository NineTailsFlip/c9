package life.furrer.c9.settings

import life.furrer.c9.calendar.CalendarStore
import life.furrer.nine.contacts.ContactStore
import life.furrer.c9.core.Prefs
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SectionScope
import life.furrer.nine.core.settings.SettingsScreen

private const val MIN_DURATION_MINUTES = 15
private const val MAX_DURATION_MINUTES = 480
private const val DURATION_STEP_MINUTES = 15

/** Calendar and Contacts each get their own section — reached by holding `*` from either
 *  surface's main screen, or as the last "* menu" item — sharing everything core's Settings
 *  framework already supplies (Theme, About). One Activity class handles both launcher entries
 *  (via the surface id core's [NineSettingsActivity.intentFor] threads through), rather than two,
 *  since the row-list/selection/key-handling plumbing is identical either way. */
class SettingsActivity : NineSettingsActivity() {

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
