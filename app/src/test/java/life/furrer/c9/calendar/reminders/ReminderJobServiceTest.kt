package life.furrer.c9.calendar.reminders

import android.Manifest
import android.app.job.JobParameters
import android.app.job.JobScheduler
import life.furrer.c9.calendar.FakeCalendarProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** [ReminderJobService]'s own lifecycle: a run finishes and re-arms, a stop re-arms unless C9
 *  itself caused it. */
@RunWith(RobolectricTestRunner::class)
class ReminderJobServiceTest {

    private val context = RuntimeEnvironment.getApplication()
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    @Before
    fun setUp() {
        FakeCalendarProvider.reset()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create("com.android.calendar")
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    /** JobParameters has no public constructor; the system builds them. Every argument left at
     *  its type's zero value is enough for a job that only ever hands them back. */
    private fun params(stopReason: Int? = null): JobParameters {
        val constructor = JobParameters::class.java.declaredConstructors.maxBy { it.parameterCount }
        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { type ->
            when (type) {
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        val params = constructor.newInstance(*args.toTypedArray()) as JobParameters
        if (stopReason != null) {
            // What the hidden setStopReason sets; its signature changes between releases.
            JobParameters::class.java.getDeclaredField("mStopReason").apply { isAccessible = true }.setInt(params, stopReason)
        }
        return params
    }

    @Test
    fun `a run finishes and re-arms the job`() {
        val controller = Robolectric.buildService(ReminderJobService::class.java).create()
        val service = controller.get()
        assertNull(scheduler.getPendingJob(ReminderScheduler.JOB_ID))

        assertTrue(service.onStartJob(params()))

        val deadline = System.currentTimeMillis() + 3000
        while (scheduler.getPendingJob(ReminderScheduler.JOB_ID) == null) {
            check(System.currentTimeMillis() < deadline) { "not re-armed within 3s" }
            Thread.sleep(10)
        }
        assertTrue(shadowOf(service).isJobFinished)
    }

    @Test
    fun `a stop by the system re-arms`() {
        val service = Robolectric.buildService(ReminderJobService::class.java).create().get()

        assertEquals(false, service.onStopJob(params(JobParameters.STOP_REASON_TIMEOUT)))

        assertNotNull(scheduler.getPendingJob(ReminderScheduler.JOB_ID))
    }

    @Test
    fun `a stop C9 caused itself, by replacing or cancelling the job, schedules nothing more`() {
        val service = Robolectric.buildService(ReminderJobService::class.java).create().get()

        service.onStopJob(params(JobParameters.STOP_REASON_CANCELLED_BY_APP))

        assertNull(scheduler.getPendingJob(ReminderScheduler.JOB_ID))
    }
}
