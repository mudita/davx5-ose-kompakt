/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import at.bitfire.davdroid.db.AppDatabase
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.db.KompaktSyncOutcomeDao
import at.bitfire.davdroid.sync.KompaktSyncFailure
import at.bitfire.davdroid.sync.SyncDataType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KompaktSyncOutcomeRepositoryTest {

    private companion object {
        const val SERVICE_ID = 7L
    }

    private lateinit var dao: KompaktSyncOutcomeDao
    private lateinit var repository: KompaktSyncOutcomeRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)

        val db = mockk<AppDatabase>()
        every { db.kompaktSyncOutcomeDao() } returns dao

        repository = KompaktSyncOutcomeRepository(db)
    }

    @Test
    fun `record writes the values it was given`() = runTest {
        repository.record(
            serviceId = SERVICE_ID,
            dataType = SyncDataType.EVENTS,
            succeeded = false,
            cause = KompaktSyncFailure.ServerProblem.name,
            trigger = "MANUAL",
            detail = "detail"
        )

        val written = slot<KompaktSyncOutcome>()
        coVerify { dao.insertOrReplace(capture(written)) }
        assertEquals(SERVICE_ID, written.captured.serviceId)
        assertEquals(SyncDataType.EVENTS.name, written.captured.dataType)
        assertEquals(false, written.captured.succeeded)
        assertEquals(KompaktSyncFailure.ServerProblem.name, written.captured.cause)
        assertEquals("MANUAL", written.captured.trigger)
        assertEquals("detail", written.captured.detail)
    }

    @Test
    fun `record stamps the row with the time it was written`() = runTest {
        val before = System.currentTimeMillis()

        repository.record(
            serviceId = SERVICE_ID,
            dataType = SyncDataType.CONTACTS,
            succeeded = true,
            cause = null,
            trigger = "AUTOMATIC",
            detail = null
        )

        val written = slot<KompaktSyncOutcome>()
        coVerify { dao.insertOrReplace(capture(written)) }
        assertTrue(written.captured.at >= before)
        assertEquals(null, written.captured.cause)
    }

    @Test
    fun `get asks the dao for that service and data type`() = runTest {
        val row = mockk<KompaktSyncOutcome>()
        coEvery { dao.get(SERVICE_ID, SyncDataType.CONTACTS.name) } returns row

        assertSame(row, repository.get(SERVICE_ID, SyncDataType.CONTACTS))
    }

    @Test
    fun `observe asks the dao for that service and data type`() {
        val rows = emptyFlow<KompaktSyncOutcome?>()
        every { dao.observe(SERVICE_ID, SyncDataType.EVENTS.name) } returns rows

        assertSame(rows, repository.observe(SERVICE_ID, SyncDataType.EVENTS))
    }

}
