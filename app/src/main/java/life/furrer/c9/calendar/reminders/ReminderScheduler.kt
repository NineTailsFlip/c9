package life.furrer.c9.calendar.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders as ReminderRows
import android.util.Log
import java.time.ZoneId
import life.furrer.c9.core.Prefs

/** The provider-facing half of C9's own reminder scheduling (the timing rules are
 *  [ReminderSchedule]'s): reads instances, reminders and alert rows, inserts the rows of reminders
 *  that came due, and keeps two things armed while reminders are on —
 *
 *  - one exact alarm of C9's own for the next reminder time ([arm]), so a reminder fires on time
 *    whether or not the provider ever gets round to scheduling it;
 *  - a JobScheduler content-trigger job on the calendar provider ([ensureJob]), so any change —
 *    C9's own saves, a DAVx5 sync, the provider adding or deleting alert rows — re-plans the
 *    alarm and runs a pass that takes down notifications whose alert is gone. A content-trigger
 *    job is one-shot and dropped by a reboot or force-stop: it's re-armed after every run, by
 *    every pass, and from [life.furrer.c9.C9Application].
 *
 *  Everything here blocks on the provider — call it off the main thread. */
object ReminderScheduler {

    const val JOB_ID = 9002

    /** Debounce a burst of changes (a sync writes many rows) into one pass... */
    internal const val UPDATE_DELAY_MS = 3_000L

    /** ...but never starve behind a sync that keeps changing things. */
    internal const val MAX_DELAY_MS = 30_000L

    /** When the provider couldn't be read: try again in a while rather than never. */
    internal const val RETRY_MS = 15 * 60_000L

    /** A query that came back without a cursor: the provider isn't there (yet — e.g. right at
     *  boot). Not "no reminders", which would only look again a day later. */
    class ProviderUnavailable(what: String) : IllegalStateException("calendar provider gave no cursor for $what")

    /** The longest reminder lead time looked for at all — the instances window grows to fit
     *  longer reminders (see [look]), up to this; past it the provider's own scheduling is all
     *  there is, as before. */
    internal const val MAX_LEAD_MS = 31 * 24 * 60 * 60_000L

    private const val DAY_MS = 24 * 60 * 60_000L

    /** IN lists are batched to stay well inside SQLite's bound-argument limit. */
    private const val BATCH = 200

    private val INSTANCE_PROJECTION = arrayOf(
        Instances.EVENT_ID,
        Instances.BEGIN,
        Instances.END,
        Instances.ALL_DAY,
        Instances.SELF_ATTENDEE_STATUS,
    )

    /** The calendar's own VISIBLE flag, the same filter the provider's scheduler applies. */
    private const val VISIBLE_SELECTION = "${Instances.VISIBLE}=1"

    private const val ALERTS_SINCE_SELECTION = "${CalendarAlerts.ALARM_TIME}>=?"

    /** Only METHOD_ALERT, like the provider's scheduler: what it'd never write a row for (email,
     *  SMS, an account's "default"), no other calendar app notifies for either. */
    private const val ALERT_METHOD = "${ReminderRows.METHOD}=${ReminderRows.METHOD_ALERT}"

    /** Works out what's due and when to look next. Instances for every visible event starting
     *  within [ReminderSchedule.LOOKAHEAD_MS] (widened by a day either side: all-day instances
     *  are UTC-based), plus — only when some reminder's lead time is longer than that — a second,
     *  wider query for just those events.
     *
     *  First, all-day rows written at another UTC offset (before a timezone change or a DST
     *  switch) are moved to the alarm time today's offset gives ([ReminderSchedule.realign]) —
     *  before anything is planned or fired from them, so none rings at the old wall-clock time. */
    fun look(context: Context, now: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderTimes {
        var (existing, pending) = queryAlerts(context, now)
        if (realign(context, ReminderSchedule.realign(existing, now, zone)) > 0) {
            val again = queryAlerts(context, now)
            existing = again.first
            pending = again.second
        }
        val from = now - ReminderSchedule.SLACK_MS - DAY_MS
        val instances = queryInstances(context, from, now + ReminderSchedule.LOOKAHEAD_MS + DAY_MS, null).toMutableList()
        val minutes = queryAlertMinutes(context, instances.map { it.eventId }.distinct())
        val long = queryLongReminders(context)
        if (long.isNotEmpty()) {
            val lead = minOf(long.values.flatten().max() * 60_000L, MAX_LEAD_MS)
            val known = instances.map { it.eventId to it.begin }.toSet()
            instances += queryInstances(context, from, now + lead + DAY_MS, long.keys).filter { (it.eventId to it.begin) !in known }
        }
        val minutesByEvent = (minutes.keys + long.keys).associateWith { id ->
            (minutes[id].orEmpty() + long[id].orEmpty()).distinct()
        }
        return ReminderSchedule.plan(instances, minutesByEvent, existing, pending, now, zone)
    }

    private fun queryInstances(context: Context, from: Long, to: Long, eventIds: Collection<Long>?): List<UpcomingInstance> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val batches = eventIds?.chunked(BATCH) ?: listOf(null)
        return batches.flatMap { batch ->
            val selection = if (batch == null) VISIBLE_SELECTION else "$VISIBLE_SELECTION AND ${inList(Instances.EVENT_ID, batch.size)}"
            val cursor = context.contentResolver.query(
                uri,
                INSTANCE_PROJECTION,
                selection,
                batch?.map { it.toString() }?.toTypedArray(),
                null,
            ) ?: throw ProviderUnavailable("instances")
            cursor.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            UpcomingInstance(
                                eventId = c.getLong(c.getColumnIndexOrThrow(Instances.EVENT_ID)),
                                begin = c.getLong(c.getColumnIndexOrThrow(Instances.BEGIN)),
                                end = c.getLong(c.getColumnIndexOrThrow(Instances.END)),
                                allDay = c.intOr(Instances.ALL_DAY, 0) != 0,
                                selfAttendeeStatus = c.intOr(Instances.SELF_ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_NONE),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Each event's METHOD_ALERT lead times, in minutes. */
    private fun queryAlertMinutes(context: Context, eventIds: List<Long>): Map<Long, List<Int>> =
        eventIds.chunked(BATCH).fold(mutableMapOf<Long, MutableList<Int>>()) { acc, batch ->
            queryReminders(context, "$ALERT_METHOD AND ${inList(ReminderRows.EVENT_ID, batch.size)}", batch.map { it.toString() })
                .forEach { (id, minutes) -> acc.getOrPut(id) { mutableListOf() } += minutes }
            acc
        }

    /** METHOD_ALERT reminders longer than the regular lookahead, whichever event they're on —
     *  normally none, so normally one cheap query. */
    private fun queryLongReminders(context: Context): Map<Long, List<Int>> =
        queryReminders(
            context,
            "$ALERT_METHOD AND ${ReminderRows.MINUTES}>?",
            listOf((ReminderSchedule.LOOKAHEAD_MS / 60_000L).toString()),
        ).groupBy({ it.first }, { it.second })

    private fun queryReminders(context: Context, selection: String, args: List<String>): List<Pair<Long, Int>> {
        val cursor = context.contentResolver.query(
            ReminderRows.CONTENT_URI,
            arrayOf(ReminderRows.EVENT_ID, ReminderRows.MINUTES),
            selection,
            args.toTypedArray(),
            null,
        ) ?: throw ProviderUnavailable("reminders")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getLong(c.getColumnIndexOrThrow(ReminderRows.EVENT_ID)) to c.getInt(c.getColumnIndexOrThrow(ReminderRows.MINUTES)))
                }
            }
        }
    }

    /** Every alert row from the slack window on (widened by the largest offset shift, for
     *  all-day rows from before one), whatever its state, to dedupe against — and the alarm times
     *  of the SCHEDULED ones still ahead, to wake up for. */
    private fun queryAlerts(context: Context, now: Long): Pair<List<ExistingAlert>, List<Long>> {
        val cursor = context.contentResolver.query(
            CalendarAlerts.CONTENT_URI,
            arrayOf(
                CalendarAlerts._ID,
                CalendarAlerts.EVENT_ID,
                CalendarAlerts.BEGIN,
                CalendarAlerts.ALARM_TIME,
                CalendarAlerts.STATE,
                CalendarAlerts.MINUTES,
                CalendarAlerts.ALL_DAY,
            ),
            ALERTS_SINCE_SELECTION,
            arrayOf((now - ReminderSchedule.SLACK_MS - ReminderSchedule.MAX_OFFSET_SHIFT_MS).toString()),
            null,
        ) ?: throw ProviderUnavailable("alerts")
        val rows = mutableListOf<ExistingAlert>()
        val pending = mutableListOf<Long>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val alarmTime = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.ALARM_TIME))
                val minutesIndex = c.getColumnIndexOrThrow(CalendarAlerts.MINUTES)
                val state = c.getInt(c.getColumnIndexOrThrow(CalendarAlerts.STATE))
                rows += ExistingAlert(
                    eventId = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.EVENT_ID)),
                    begin = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.BEGIN)),
                    alarmTime = alarmTime,
                    minutes = if (c.isNull(minutesIndex)) null else c.getInt(minutesIndex),
                    id = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts._ID)),
                    state = state,
                    allDay = c.intOr(CalendarAlerts.ALL_DAY, 0) != 0,
                )
                if (state == CalendarAlerts.STATE_SCHEDULED && alarmTime > now) {
                    pending += alarmTime
                }
            }
        }
        return rows to pending
    }

    /** Applies [ReminderSchedule.realign]'s moves and deletes; returns how many landed.
     *
     *  A move only if the row is still SCHEDULED at the alarm time it was read with — one another
     *  pass or app fired or moved meanwhile isn't this pass's any more; CalendarProvider2 runs an
     *  update by selection straight against the table. A move onto an alarm time some other row
     *  holds fails on the table's UNIQUE (alarmTime, begin, event_id) and leaves the row be.
     *
     *  A delete goes through the single-row Uri, without that guard: the provider's delete by
     *  selection (for a caller that isn't a sync adapter) marks the row's *event* dirty and
     *  duplicates it, as if the event had been edited — a sync for nothing. The guard matters
     *  less there: another reminder row for the same time is already there to ring. */
    private fun realign(context: Context, moves: List<Realignment>): Int =
        moves.count { move ->
            val done = runCatching {
                if (move.alarmTime == null) {
                    context.contentResolver.delete(ContentUris.withAppendedId(CalendarAlerts.CONTENT_URI, move.alertId), null, null)
                } else {
                    val values = ContentValues().apply { put(CalendarAlerts.ALARM_TIME, move.alarmTime) }
                    val args = arrayOf(move.alertId.toString(), CalendarAlerts.STATE_SCHEDULED.toString(), move.from.toString())
                    context.contentResolver.update(CalendarAlerts.CONTENT_URI, values, REALIGN_SELECTION, args)
                }
            }.getOrDefault(0) == 1
            Log.i(TAG, "all-day alert ${move.alertId}: ${move.from} -> ${move.alarmTime ?: "deleted"}${if (done) "" else " (failed)"}")
            done
        }

    /** Through the table Uri: the provider's single-row Uri ignores any selection. Only the
     *  table's own columns, as its update runs straight against CalendarAlerts. */
    private const val REALIGN_SELECTION =
        "${CalendarAlerts._ID}=? AND ${CalendarAlerts.STATE}=? AND ${CalendarAlerts.ALARM_TIME}=?"

    private fun inList(column: String, size: Int): String = "$column IN (${List(size) { "?" }.joinToString(",")})"

    private fun Cursor.intOr(column: String, fallback: Int): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getInt(index)
    }

    /** Inserts a SCHEDULED row per [due] reminder — `CalendarAlerts.insert`, field for field, so
     *  the provider's cleanup keeps it (its begin/end match an instance, its minutes a reminder)
     *  and its scheduler sees the reminder as taken care of. The table is UNIQUE on
     *  event/begin/alarm time: should the provider have written the same row in the meantime, the
     *  insert fails and that row is the one that fires. */
    fun insert(context: Context, due: List<DueReminder>, now: Long): Int =
        due.count { reminder ->
            val values = ContentValues().apply {
                put(CalendarAlerts.EVENT_ID, reminder.eventId)
                put(CalendarAlerts.BEGIN, reminder.begin)
                put(CalendarAlerts.END, reminder.end)
                put(CalendarAlerts.ALARM_TIME, reminder.alarmTime)
                put(CalendarAlerts.CREATION_TIME, now)
                put(CalendarAlerts.RECEIVED_TIME, 0L)
                put(CalendarAlerts.NOTIFY_TIME, 0L)
                put(CalendarAlerts.STATE, CalendarAlerts.STATE_SCHEDULED)
                put(CalendarAlerts.MINUTES, reminder.minutes)
            }
            runCatching { context.contentResolver.insert(CalendarAlerts.CONTENT_URI, values) }.getOrNull() != null
        }

    /** [look] and [arm] for whatever comes next — for callers that only add a future alert (a
     *  snooze) and leave the firing to the pass the alarm runs. Something already due makes that
     *  "now". */
    fun armNext(context: Context, now: Long) {
        val times = lookOrNull(context, now)
        arm(context, if (times == null) retryAt(context, now) else if (times.due.isNotEmpty()) now else times.nextAlarm)
    }

    /** [look], or null (logged) when the provider couldn't be read. */
    fun lookOrNull(context: Context, now: Long): ReminderTimes? =
        runCatching { look(context, now) }.onFailure { Log.w(TAG, "couldn't plan reminders", it) }.getOrNull()

    /** When to try again after a pass that couldn't plan: in [RETRY_MS] — or at the alarm already
     *  armed, if that's sooner and still ahead, so a failed look never pushes back a reminder it
     *  had already found. */
    fun retryAt(context: Context, now: Long): Long {
        val armed = Prefs.reminderAlarmAt(context)
        return if (armed != null && armed > now && armed < now + RETRY_MS) armed else now + RETRY_MS
    }

    private fun alarmIntent(context: Context): Intent =
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMINDER_DUE)

    /** The one alarm, replaced on every call. Exact when allowed — C9 declares USE_EXACT_ALARM,
     *  which a calendar app gets without asking — and the closest inexact one otherwise, so a
     *  reminder is late rather than lost. Lint only knows SCHEDULE_EXACT_ALARM;
     *  canScheduleExactAlarms() is the actual check. */
    @SuppressLint("MissingPermission")
    fun arm(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            alarmIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = alarms.canScheduleExactAlarms() &&
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }.isSuccess
        if (!exact) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        Prefs.setReminderAlarmAt(context, at)
    }

    /** Hands off: neither the alarm nor the job, until a pass finds reminders wanted again. */
    fun disarm(context: Context) {
        PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            alarmIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { pending ->
            context.getSystemService(AlarmManager::class.java)?.cancel(pending)
            pending.cancel()
        }
        Prefs.setReminderAlarmAt(context, null)
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    /** Whether C9's alarm is (still) there — a force-stop cancels it along with the job. */
    fun isAlarmArmed(context: Context): Boolean =
        PendingIntent.getBroadcast(context, REQUEST_ALARM, alarmIntent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null

    fun isJobArmed(context: Context): Boolean =
        context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null

    /** Arms the content-trigger job — unless it's armed already ([force] aside): every pass
     *  calls this, and replacing a pending job each time would only restart its debounce and
     *  stop it if it's running. */
    fun ensureJob(context: Context, force: Boolean = false) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!force && scheduler.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, ReminderJobService::class.java))
            .addTriggerContentUri(
                // The provider notifies this one Uri for every change it makes, whatever table.
                JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS),
            )
            .setTriggerContentUpdateDelay(UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(MAX_DELAY_MS)
            .build()
        runCatching { scheduler.schedule(info) }
    }

    private const val REQUEST_ALARM = 1

    internal const val TAG = "C9Reminders"
}

/** [work] on a thread of its own, with whatever it throws logged rather than taking the whole
 *  process down — a provider hiccup in a background pass mustn't crash the app (and the next
 *  trigger, or the alarm every pass leaves armed, tries again). */
internal fun reminderThread(work: () -> Unit, after: () -> Unit = {}): Thread =
    Thread {
        try {
            work()
        } catch (e: Exception) {
            Log.w(ReminderScheduler.TAG, "reminder pass failed", e)
        } finally {
            after()
        }
    }.apply { start() }

/** Runs a reminder pass when the calendar provider changed — an event saved or synced, alert rows
 *  added or deleted — whether or not C9 is open: re-plans the alarm and takes down notifications
 *  whose alert a sync adapter just deleted (the provider sends no EVENT_REMINDER for those). Its
 *  own thread, not Async's: the job can outlive every Activity. */
class ReminderJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        reminderThread(
            work = { Reminders.refresh(app, armJob = false) },
            after = {
                jobFinished(params, false)
                // Forced: jobFinished only posts to the main looper, so this run usually still
                // counts as pending (and running) here. Replacing it hands the changes its trigger
                // recorded meanwhile — this pass's own writes among them — to the new job, so one
                // more pass follows a few seconds later. That one writes nothing, so it ends there;
                // re-arming before the pass instead would only make the change window wider.
                if (Reminders.active(app)) ReminderScheduler.ensureJob(app, force = true)
            },
        )
        return true
    }

    /** Cut short: a pass is idempotent, so just make sure there's a next time — unless the app
     *  itself stopped it, by replacing it with the next job (the forced re-arm above) or by
     *  cancelling it (hands off): either way there's nothing to schedule here. */
    override fun onStopJob(params: JobParameters): Boolean {
        if (params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP) return false
        if (Reminders.active(applicationContext)) ReminderScheduler.ensureJob(applicationContext, force = true)
        return false
    }
}
