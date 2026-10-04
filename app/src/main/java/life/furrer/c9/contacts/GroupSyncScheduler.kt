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
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.contacts.VoicemailSync
import life.furrer.nine.contacts.GroupSync
import life.furrer.nine.core.util.Async

/** C9's group syncs: favorites (STARRED) and send-to-voicemail, each with its optional group. */
internal val groupSyncs = listOf(FavoritesSync, VoicemailSync)

/** Everything that keeps the group syncs' line-ups running in the background, in one place so
 *  the settings picker, [life.furrer.c9.C9Application] and [GroupSyncBootReceiver] share it.
 *  One job lines up every sync that [GroupSync.needsLineUps] — so it stays armed even without
 *  any group chosen, for the voicemail flag's repair.
 *
 *  A content-trigger job is one-shot and can't be persisted: it is gone after it fires, after a
 *  reboot and after a force-stop. So it's re-armed at the end of every run, from the boot
 *  receiver, and from Application.onCreate (which also runs one line-up itself — the recovery
 *  path after a force-stop, when nothing else would notice changes made meanwhile). */
object GroupSyncScheduler {

    const val JOB_ID = 9001

    /** Debounce a burst of changes (a DAVx5 sync touches many rows) into one run... */
    internal const val UPDATE_DELAY_MS = 5_000L

    /** ...but never starve behind a sync that keeps changing things. */
    internal const val MAX_DELAY_MS = 60_000L

    /** Arms the job when any sync has work to do, cancels it when none has — or when C9 can't
     *  write contacts, so a C9 used for its calendar alone isn't woken by every contacts sync. A
     *  later grant takes effect at the next process start, which calls this again. */
    fun update(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val canWrite = ContactStore.hasPermission(context) && ContactStore.hasWritePermission(context)
        if (!canWrite || groupSyncs.none { it.needsLineUps(context) }) {
            scheduler.cancel(JOB_ID)
            return
        }
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, GroupSyncJobService::class.java))
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

    /** Lines up every sync that needs it, in turn. Blocking. */
    fun lineUpAll(context: Context) {
        groupSyncs.filter { it.needsLineUps(context) }.forEach { it.lineUp(context) }
    }

    /** One line-up on Async's shared thread, for the foreground callers (a picker choice, process
     *  start): they have no job to hold open, and sharing the thread keeps it ordered after any
     *  provider queries those screens queued. */
    fun lineUpAsync(context: Context) {
        val app = context.applicationContext
        Async.run(background = { lineUpAll(app) }, onResult = {})
    }
}

/** Runs [GroupSyncScheduler.lineUpAll] when the contacts DB changed (e.g. after DAVx5 synced), whether or
 *  not C9 is open. Uses its own thread rather than Async's: the job can outlive every Activity,
 *  and a slow line-up must not queue the UI's provider reads behind it when the app is opened. */
class GroupSyncJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                GroupSyncScheduler.lineUpAll(app)
            } finally {
                // Re-arm only now: scheduling earlier would make the job's own writes trigger a
                // pointless second run (the line-up converges, but it still costs queries).
                GroupSyncScheduler.update(app)
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    /** The system cut the run short; the line-up is idempotent, so just re-arm for next time. */
    override fun onStopJob(params: JobParameters): Boolean {
        GroupSyncScheduler.update(applicationContext)
        return false
    }
}

/** Re-arms the job after a reboot, which drops it. */
class GroupSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) GroupSyncScheduler.update(context)
    }
}
