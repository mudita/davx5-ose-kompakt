/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import androidx.work.WorkManager
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.UUID

// Robolectric so Account carries its name and type: the JVM stub leaves both null, under which
// workerName produces "null/null" on either side of a comparison and proves nothing.
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktSyncWorkTest {

    private val account = Account("user@example.com", "bitfire.at.davdroid.mudita")
    private val syncWorkerManager = mockk<SyncWorkerManager>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    private val syncWork = KompaktSyncWorkImpl(syncWorkerManager, workManager)

    @Test
    fun enqueuesTheServicesOwnDataTypeAndReturnsItsRunId() = runTest {
        val id = UUID.randomUUID()
        every {
            syncWorkerManager.enqueueOneTimeReturningId(account, SyncDataType.CONTACTS, manual = true)
        } returns id

        assertEquals(id, syncWork.enqueue(account, KompaktSyncService.CONTACTS, manual = true))

        // Never the all-authorities call: it fans out over SyncDataType.entries and ignores toggles.
        verify(exactly = 0) {
            syncWorkerManager.enqueueOneTimeAllAuthorities(any(), any(), any(), any(), any())
        }
        verify(exactly = 0) {
            syncWorkerManager.enqueueOneTimeReturningId(any(), SyncDataType.EVENTS, any(), any(), any(), any())
        }
    }

    @Test
    fun cancelsOnlyThatServicesUniqueWork() = runTest {
        syncWork.cancel(account, KompaktSyncService.CALENDAR)

        verify {
            workManager.cancelUniqueWork(OneTimeSyncWorker.workerName(account, SyncDataType.EVENTS))
        }
        verify(exactly = 0) {
            workManager.cancelUniqueWork(OneTimeSyncWorker.workerName(account, SyncDataType.CONTACTS))
        }
    }

}
