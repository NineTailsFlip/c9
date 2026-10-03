package life.furrer.c9.calendar.reminders

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import life.furrer.c9.core.Prefs

/** POST_NOTIFICATIONS, asked for once from the calendar right after calendar access is in place
 *  — the moment the app starts being able to remind anyone. A refusal is final as far as C9 is
 *  concerned: no blocking screen and no re-asking on every launch, the calendar works exactly
 *  the same, reminders just stay silent until it's allowed in the system's App info. */
object ReminderPermission {

    const val REQUEST_CODE = 0x0A1E

    fun isGranted(activity: Activity): Boolean =
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun maybeRequest(activity: Activity) {
        if (!Prefs.reminderNotifications(activity)) return
        if (Prefs.askedNotificationPermission(activity) || isGranted(activity)) return
        Prefs.setAskedNotificationPermission(activity)
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
    }

    /** From Settings, turning reminders on: an explicit "yes, remind me", so asked again even
     *  after an earlier dismissal (Android itself stops showing the dialog after two refusals). */
    fun requestIfMissing(activity: Activity) {
        if (isGranted(activity)) return
        Prefs.setAskedNotificationPermission(activity)
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
    }
}
