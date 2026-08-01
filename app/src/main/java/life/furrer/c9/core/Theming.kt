package life.furrer.c9.core

import android.app.Activity
import life.furrer.c9.R

/** Call before setContentView (ideally right at the top of onCreate) so the optional monospace
 *  font setting applies — every plain TextView picks it up via the theme's default style (see
 *  Theme.C9.Monospace in themes.xml), including ones rebuilt later, with no per-view changes.
 *  Returns the value it applied, so long-lived activities (see recreateIfFontChanged) can
 *  remember what they were created with. */
fun Activity.applyConfiguredTheme(): Boolean {
    val useMonospace = Prefs.useMonospaceFont(this)
    if (useMonospace) {
        setTheme(R.style.Theme_C9_Monospace)
    }
    return useMonospace
}

/** setTheme() only affects views created afterward, so an activity that stays alive in the
 *  background won't pick up a font change made while some other screen was in front — its own
 *  onResume must notice and recreate() itself. Returns true if it did, so callers can skip the
 *  rest of onResume's work that pass. */
fun Activity.recreateIfFontChanged(appliedMonospaceFont: Boolean): Boolean {
    if (Prefs.useMonospaceFont(this) != appliedMonospaceFont) {
        recreate()
        return true
    }
    return false
}

/** Same idea for AlertDialogs, which use their own dialog theme rather than the activity's. */
fun Activity.dialogThemeResId(): Int =
    if (Prefs.useMonospaceFont(this)) R.style.Theme_C9_Dialog_Monospace else R.style.Theme_C9_Dialog
