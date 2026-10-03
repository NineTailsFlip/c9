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
import java.time.ZoneId

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
     *  wider query for just those events. */
    fun look(context: Context, now: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderTimes {
        val (existing, pending) = queryAlerts(context, now)
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
            ) ?: return@flatMap emptyList()
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
        ) ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getLong(c.getColumnIndexOrThrow(ReminderRows.EVENT_ID)) to c.getInt(c.getColumnIndexOrThrow(ReminderRows.MINUTES)))
                }
            }
        }
    }

    /** Every alert row from the slack window on, whatever its state (to dedupe against), and the
     *  alarm times of the SCHEDULED ones still ahead (to wake up for). */
    private fun queryAlerts(context: Context, now: Long): Pair<Set<AlertKey>, List<Long>> {
        val cursor = context.contentResolver.query(
            CalendarAlerts.CONTENT_URI,
            arrayOf(CalendarAlerts.EVENT_ID, CalendarAlerts.BEGIN, CalendarAlerts.ALARM_TIME, CalendarAlerts.STATE),
            ALERTS_SINCE_SELECTION,
            arrayOf((now - ReminderSchedule.SLACK_MS).toString()),
            null,
        ) ?: return emptySet<AlertKey>() to emptyList()
        val keys = mutableSetOf<AlertKey>()
        val pending = mutableListOf<Long>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val alarmTime = c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.ALARM_TIME))
                keys += AlertKey(
                    c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.EVENT_ID)),
                    c.getLong(c.getColumnIndexOrThrow(CalendarAlerts.BEGIN)),
                    alarmTime,
                )
                if (c.getInt(c.getColumnIndexOrThrow(CalendarAlerts.STATE)) == CalendarAlerts.STATE_SCHEDULED && alarmTime > now) {
                    pending += alarmTime
                }
            }
        }
        return keys to pending
    }

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
    fun insert(context: Context, due: List<DueReminder>, now: Long) {
        due.forEach { reminder ->
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
            runCatching { context.contentResolver.insert(CalendarAlerts.CONTENT_URI, values) }
        }
    }

    /** [look] and [arm] for whatever comes next — for callers that only add a future alert (a
     *  snooze) and leave the firing to the pass the alarm runs. Something already due makes that
     *  "now". */
    fun armNext(context: Context, now: Long) {
        val times = runCatching { look(context, now) }.getOrNull()
        arm(context, if (times == null) now + RETRY_MS else if (times.due.isNotEmpty()) now else times.nextAlarm)
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
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun isJobArmed(context: Context): Boolean =
        context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null

    /** Arms the content-trigger job — unless it's armed already ([force] aside): re-scheduling a
     *  job resets its trigger, which would drop a change it had just seen and was waiting out the
     *  debounce for. */
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
}

/** Runs a reminder pass when the calendar provider changed — an event saved or synced, alert rows
 *  added or deleted — whether or not C9 is open: re-plans the alarm and takes down notifications
 *  whose alert a sync adapter just deleted (the provider sends no EVENT_REMINDER for those). Its
 *  own thread, not Async's: the job can outlive every Activity. */
class ReminderJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                Reminders.refresh(app, armJob = false)
            } finally {
                jobFinished(params, false)
                // Re-armed only now, so the pass's own writes don't trigger a pointless second run.
                // Forced: this run may still count as pending until the finish above lands.
                if (Reminders.active(app)) ReminderScheduler.ensureJob(app, force = true)
            }
        }.start()
        return true
    }

    /** Cut short: a pass is idempotent, so just make sure there's a next time. */
    override fun onStopJob(params: JobParameters): Boolean {
        if (Reminders.active(applicationContext)) ReminderScheduler.ensureJob(applicationContext, force = true)
        return false
    }
}
