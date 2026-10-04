package life.furrer.c9.contacts

import android.app.job.JobScheduler
import life.furrer.nine.contacts.ContactStore.ContactGroup
import life.furrer.nine.contacts.FavoritesSync
import life.furrer.nine.contacts.VoicemailSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GroupSyncSchedulerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val scheduler = shadowOf(context.getSystemService(JobScheduler::class.java))

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
    }

    // A C9 used for its calendar alone must not be woken by every contacts change.
    @Test
    fun `without contacts write permission the job is not armed`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_CONTACTS)
        VoicemailSync.setGroup(context, ContactGroup(6L, "Send to voicemail", "me@dav", "bitfire.at.davdroid"))
        GroupSyncScheduler.update(context)
        assertNull(scheduler.getPendingJob(GroupSyncScheduler.JOB_ID))
    }

    // Voicemail repairs a contact whose aggregate lost the flag even with no group chosen, so
    // its sync always wants lining up.
    @Test
    fun `no group configured still arms the job for the voicemail repair`() {
        GroupSyncScheduler.update(context)
        val job = scheduler.getPendingJob(GroupSyncScheduler.JOB_ID)
        assertNotNull(job)
        assertEquals(GroupSyncJobService::class.java.name, job.service.className)
    }

    @Test
    fun `only favorites without a group would need no job`() {
        assertFalse(FavoritesSync.needsLineUps(context))
        assertTrue(VoicemailSync.needsLineUps(context))
    }

    @Test
    fun `a configured group schedules a debounced contacts content trigger`() {
        FavoritesSync.setGroup(context, ContactGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid"))
        GroupSyncScheduler.update(context)

        val job = scheduler.getPendingJob(GroupSyncScheduler.JOB_ID)
        assertNotNull(job)
        assertEquals(GroupSyncJobService::class.java.name, job.service.className)
        assertEquals(android.provider.ContactsContract.AUTHORITY_URI, job.triggerContentUris!!.single().uri)
        assertEquals(GroupSyncScheduler.UPDATE_DELAY_MS, job.triggerContentUpdateDelay)
        assertEquals(GroupSyncScheduler.MAX_DELAY_MS, job.triggerContentMaxDelay)
    }

    @Test
    fun `clearing the favorites group keeps the job armed for voicemail`() {
        FavoritesSync.setGroup(context, ContactGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid"))
        GroupSyncScheduler.update(context)
        FavoritesSync.setGroup(context, null)
        GroupSyncScheduler.update(context)
        assertNotNull(scheduler.getPendingJob(GroupSyncScheduler.JOB_ID))
    }

    @Test
    fun `a voicemail group alone schedules the job too`() {
        VoicemailSync.setGroup(context, ContactGroup(6L, "Send to voicemail", "me@dav", "bitfire.at.davdroid"))
        GroupSyncScheduler.update(context)
        assertNotNull(scheduler.getPendingJob(GroupSyncScheduler.JOB_ID))
    }

    @Test
    fun `boot re-arms the job`() {
        FavoritesSync.setGroup(context, ContactGroup(5L, "Favorites", "me@dav", "bitfire.at.davdroid"))
        GroupSyncBootReceiver().onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(scheduler.getPendingJob(GroupSyncScheduler.JOB_ID))
    }
}
