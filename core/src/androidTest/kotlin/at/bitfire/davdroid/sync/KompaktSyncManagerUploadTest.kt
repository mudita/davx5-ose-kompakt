/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import at.bitfire.dav4jvm.okhttp.PropStat
import at.bitfire.dav4jvm.okhttp.Response
import at.bitfire.dav4jvm.okhttp.Response.HrefRelation
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.davdroid.TestUtils
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.network.HttpClientBuilder
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.resource.SyncState
import at.bitfire.davdroid.sync.account.TestAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit4.MockKRule
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.internal.http.StatusLine
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class KompaktSyncManagerUploadTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val mockKRule = MockKRule(this)

    @Inject @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var httpClientBuilder: HttpClientBuilder

    @Inject
    lateinit var syncManagerFactory: TestSyncManager.Factory

    @BindValue
    @RelaxedMockK
    lateinit var syncStatsRepository: DavSyncStatsRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private lateinit var account: Account
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        hiltRule.inject()
        TestUtils.setUpWorkManager(context, workerFactory)
        account = TestAccount.create()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        TestAccount.remove(account)
        NotificationManagerCompat.from(context).cancelAll()
        server.close()
    }


    @Test
    fun rejectedUpload_doesNotStopOtherUploadsOrDownload() = runTest {
        val rejected = dirtyResource("rejected.txt")
        val accepted = dirtyResource("accepted.txt")
        val collection = LocalTestCollection().apply {
            lastSyncState = SyncState(SyncState.Type.CTAG, "old-ctag")
            entries += rejected
            entries += accepted
        }
        server.dispatcher = dispatcher { request ->
            when (request.path) {
                "/rejected.txt" -> MockResponse().setResponseCode(400)
                "/accepted.txt" -> MockResponse().setResponseCode(204).setHeader("ETag", "\"accepted-etag\"")
                else -> MockResponse().setResponseCode(500)
            }
        }

        val syncManager = syncManager(collection).apply {
            listAllRemoteResult = listOf(
                member("rejected.txt", "old-etag"),
                member("accepted.txt", "accepted-etag"),
                member("changed-remotely.txt", "remote-etag")
            )
            assertDownloadRemote = mapOf(server.url("/changed-remotely.txt") to "remote-etag")
        }
        syncManager.performSync()

        assertFalse(syncManager.syncResult.hasError())
        assertEquals(1L, syncManager.syncResult.numRejectedUploads)
        assertTrue(rejected.dirty)
        assertFalse(accepted.dirty)
        assertTrue(syncManager.didListAllRemote)
        assertTrue(syncManager.didDownloadRemote)
    }

    @Test
    fun collectionWideUploadError_stillStopsSync() = runTest {
        val resource = dirtyResource("existing.txt")
        val collection = LocalTestCollection().apply {
            lastSyncState = SyncState(SyncState.Type.CTAG, "old-ctag")
            entries += resource
        }
        server.dispatcher = dispatcher { MockResponse().setResponseCode(405) }

        val syncManager = syncManager(collection)
        syncManager.performSync()

        assertTrue(syncManager.syncResult.hasError())
        assertEquals(1L, syncManager.syncResult.numHttpExceptions)
        assertEquals(0L, syncManager.syncResult.numRejectedUploads)
        assertTrue(resource.dirty)
        assertFalse(syncManager.didListAllRemote)
    }

    @Test
    fun rejectionOfFreshUploadAfter404_isConfined() = runTest {
        val resource = dirtyResource("vanished.txt")
        val collection = LocalTestCollection().apply {
            lastSyncState = SyncState(SyncState.Type.CTAG, "old-ctag")
            entries += resource
        }
        server.dispatcher = dispatcher { request ->
            if (request.getHeader("If-None-Match") != null)
                MockResponse().setResponseCode(400)
            else
                MockResponse().setResponseCode(404)
        }

        val syncManager = syncManager(collection)
        syncManager.performSync()

        assertFalse(syncManager.syncResult.hasError())
        assertEquals(1L, syncManager.syncResult.numRejectedUploads)
        assertTrue(resource.dirty)
        assertTrue(syncManager.didListAllRemote)
    }


    private fun dirtyResource(fileName: String) = LocalTestResource().apply {
        this.fileName = fileName
        eTag = "old-etag"
        dirty = true
    }

    private fun member(fileName: String, eTag: String) = Pair(
        Response(
            server.url("/"),
            server.url("/$fileName"),
            null,
            listOf(PropStat(listOf(GetETag(eTag)), StatusLine(Protocol.HTTP_1_1, 200, "OK")))
        ),
        HrefRelation.MEMBER
    )

    private fun dispatcher(onPut: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.method == "PROPFIND") cTagResponse() else onPut(request)
    }

    private fun cTagResponse() = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "text/xml")
        .setBody(
            """<?xml version="1.0" encoding="utf-8" ?>
            <multistatus xmlns="DAV:" xmlns:CALDAV="http://calendarserver.org/ns/">
              <response><href>/</href><propstat><prop><CALDAV:getctag>ctag1</CALDAV:getctag></prop></propstat></response>
            </multistatus>"""
        )

    private fun syncManager(localCollection: LocalTestCollection) = syncManagerFactory.create(
        account,
        httpClientBuilder.build(),
        SyncResult(),
        localCollection,
        mockk<Collection>(relaxed = true) {
            every { id } returns 1
            every { url } returns server.url("/")
        }
    )

}
