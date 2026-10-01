package life.furrer.c9.contacts

import android.app.job.JobScheduler
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FavoritesSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FavoritesSchedulerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val scheduler = shadowOf(context.getSystemService(JobScheduler::class.java))

    @Test
    fun `no group configured means no job`() {
        FavoritesScheduler.update(context)
        assertNull(scheduler.getPendingJob(FavoritesScheduler.JOB_ID))
    }

    @Test
    fun `a configured group schedules a debounced contacts content trigger`() {
        FavoritesSync.setFavoritesGroup(context, ContactGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid"))
        FavoritesScheduler.update(context)

        val job = scheduler.getPendingJob(FavoritesScheduler.JOB_ID)
        assertNotNull(job)
        assertEquals(FavoritesJobService::class.java.name, job.service.className)
        assertEquals(android.provider.ContactsContract.AUTHORITY_URI, job.triggerContentUris!!.single().uri)
        assertEquals(FavoritesScheduler.UPDATE_DELAY_MS, job.triggerContentUpdateDelay)
        assertEquals(FavoritesScheduler.MAX_DELAY_MS, job.triggerContentMaxDelay)
    }

    @Test
    fun `clearing the group cancels the job`() {
        FavoritesSync.setFavoritesGroup(context, ContactGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid"))
        FavoritesScheduler.update(context)
        FavoritesSync.setFavoritesGroup(context, null)
        FavoritesScheduler.update(context)
        assertNull(scheduler.getPendingJob(FavoritesScheduler.JOB_ID))
    }

    @Test
    fun `boot re-arms the job`() {
        FavoritesSync.setFavoritesGroup(context, ContactGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid"))
        FavoritesBootReceiver().onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(scheduler.getPendingJob(FavoritesScheduler.JOB_ID))
    }
}
