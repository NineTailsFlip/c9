package life.furrer.c9.contacts

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.core.util.Async

/** Everything that keeps the favorites line-up running in the background, in one place so the
 *  settings picker, [life.furrer.c9.C9Application] and [FavoritesBootReceiver] share it.
 *
 *  A content-trigger job is one-shot and can't be persisted: it is gone after it fires, after a
 *  reboot and after a force-stop. So it's re-armed at the end of every run, from the boot
 *  receiver, and from Application.onCreate (which also runs one line-up itself — the recovery
 *  path after a force-stop, when nothing else would notice changes made meanwhile). */
object FavoritesScheduler {

    const val JOB_ID = 9001

    /** Debounce a burst of changes (a DAVx5 sync touches many rows) into one run... */
    internal const val UPDATE_DELAY_MS = 5_000L

    /** ...but never starve behind a sync that keeps changing things. */
    internal const val MAX_DELAY_MS = 60_000L

    /** Arms the job when a favorites group is configured, cancels it when not. */
    fun update(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (FavoritesSync.favoritesGroup(context) == null) {
            scheduler.cancel(JOB_ID)
            return
        }
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, FavoritesJobService::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    ContactsContract.AUTHORITY_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                ),
            )
            .setTriggerContentUpdateDelay(UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(MAX_DELAY_MS)
            .build()
        scheduler.schedule(info)
    }

    /** One line-up on Async's shared thread, for the foreground callers (a picker choice, process
     *  start): they have no job to hold open, and sharing the thread keeps it ordered after any
     *  provider queries those screens queued. */
    fun lineUpAsync(context: Context) {
        val app = context.applicationContext
        Async.run(background = { FavoritesSync.lineUp(app) }, onResult = {})
    }
}

/** Runs [FavoritesSync.lineUp] when the contacts DB changed (e.g. after DAVx5 synced), whether or
 *  not C9 is open. Uses its own thread rather than Async's: the job can outlive every Activity,
 *  and a slow line-up must not queue the UI's provider reads behind it when the app is opened. */
class FavoritesJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                FavoritesSync.lineUp(app)
            } finally {
                // Re-arm only now: scheduling earlier would make the job's own writes trigger a
                // pointless second run (the line-up converges, but it still costs queries).
                FavoritesScheduler.update(app)
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    /** The system cut the run short; the line-up is idempotent, so just re-arm for next time. */
    override fun onStopJob(params: JobParameters): Boolean {
        FavoritesScheduler.update(applicationContext)
        return false
    }
}

/** Re-arms the job after a reboot, which drops it. */
class FavoritesBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) FavoritesScheduler.update(context)
    }
}
