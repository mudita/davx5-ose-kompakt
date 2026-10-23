/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.logging.Logger

class KompaktCollectionDiscoveryTest {

    private val account = mockAccount()

    private val serviceRepository = mockk<DavServiceRepository>()
    private val collectionRepository = mockk<DavCollectionRepository>()
    private val syncWork = mockk<KompaktSyncWork>(relaxed = true)

    private val discovery = KompaktCollectionDiscovery(
        serviceRepository = serviceRepository,
        collectionRepository = collectionRepository,
        syncWork = syncWork,
        logger = mockk<Logger>(relaxed = true)
    )

    /** A CardDAV row as [SERVICE_ID], holding [collections]. */
    private fun contactsRow(vararg collections: Collection) {
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns
            Service(SERVICE_ID, account.name, Service.TYPE_CARDDAV)
        coEvery { collectionRepository.getByService(SERVICE_ID) } returns collections.toList()
    }

    private fun noContactsRow() {
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CARDDAV) } returns null
    }

    @Test
    fun `a row without collections has them fetched`() = runTest {
        contactsRow()
        coEvery { collectionRepository.getByService(SERVICE_ID) } returnsMany listOf(emptyList(), listOf(mockk()))
        coEvery { syncWork.refreshCollectionsAndWait(SERVICE_ID) } returns true

        assertTrue(discovery.ensure(account, KompaktSyncService.CONTACTS))
    }

    // Upstream's refresh ignores a 4xx, so Google refusing it still reads as success.
    @Test
    fun `a refresh that succeeds without finding any collections fetched nothing`() = runTest {
        contactsRow()
        coEvery { syncWork.refreshCollectionsAndWait(SERVICE_ID) } returns true

        assertFalse(discovery.ensure(account, KompaktSyncService.CONTACTS))
    }

    @Test
    fun `a row that has collections is not refreshed`() = runTest {
        contactsRow(mockk())

        assertFalse(discovery.ensure(account, KompaktSyncService.CONTACTS))
        verify(exactly = 0) { syncWork.enqueueRefreshCollections(any()) }
        coVerify(exactly = 0) { syncWork.refreshCollectionsAndWait(any()) }
    }

    @Test
    fun `a service without a row is not refreshed`() = runTest {
        noContactsRow()

        assertFalse(discovery.ensure(account, KompaktSyncService.CONTACTS))
        coVerify(exactly = 0) { syncWork.refreshCollectionsAndWait(any()) }
    }

    @Test
    fun `a refresh that fails fetched nothing`() = runTest {
        contactsRow()
        coEvery { syncWork.refreshCollectionsAndWait(SERVICE_ID) } returns false

        assertFalse(discovery.ensure(account, KompaktSyncService.CONTACTS))
    }

    @Test
    fun `a refresh that never finishes is given up on`() = runTest {
        contactsRow()
        coEvery { syncWork.refreshCollectionsAndWait(SERVICE_ID) } coAnswers { awaitCancellation() }

        assertFalse(discovery.ensure(account, KompaktSyncService.CONTACTS))
    }

    // A broadcast receiver has seconds to finish, not the network round-trip a refresh takes.
    @Test
    fun `a caller that cannot wait only has the refresh started`() = runTest {
        contactsRow()

        assertFalse(discovery.ensure(account, KompaktSyncService.CONTACTS, await = false))
        verify { syncWork.enqueueRefreshCollections(SERVICE_ID) }
        coVerify(exactly = 0) { syncWork.refreshCollectionsAndWait(any()) }
    }

    private companion object {
        const val SERVICE_ID = 7L
    }

}
