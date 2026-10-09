/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.ContentProviderClient
import android.content.ContentValues
import android.net.Uri
import android.os.RemoteException
import android.provider.ContactsContract.RawContacts
import at.bitfire.dav4jvm.okhttp.exception.HttpException
import at.bitfire.davdroid.resource.LocalAddressBook
import at.bitfire.davdroid.resource.LocalContact
import at.bitfire.davdroid.sync.KompaktGoogleContactName.Lookup
import at.bitfire.synctools.storage.contacts.AddressContract.RawContactColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.IOException
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktGoogleContactNameTest {

    private val logger = Logger.getLogger(javaClass.name)
    private val googleCollection =
        "https://apidata.googleusercontent.com/carddav/v1/principals/test%40gmail.com/lists/default/".toHttpUrl()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun card(uid: String?) = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Test Person\r\nN:Person;Test;;;\r\n")
        if (uid != null)
            append("UID:$uid\r\n")
        append("END:VCARD\r\n")
    }

    private fun cardResponse(uid: String?, eTag: String? = "\"2026-10-09T01:00:00.000-07:00\"") =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/vcard; charset=UTF-8")
            .apply { if (eTag != null) setHeader("ETag", eTag) }
            .setBody(card(uid))

    private fun lookUp() = KompaktGoogleContactName.lookUpServerName(client, googleCollection, "upload-uuid.vcf", logger)


    @Test
    fun appliesTo_googleCardDav() {
        assertTrue(KompaktGoogleContactName.appliesTo(googleCollection))
        assertTrue(KompaktGoogleContactName.appliesTo("https://www.googleapis.com/carddav/v1/principals/x/lists/default/".toHttpUrl()))
    }

    @Test
    fun appliesTo_notGoogleCardDav() {
        assertFalse(KompaktGoogleContactName.appliesTo("https://apidata.googleusercontent.com/caldav/v2/x/events/".toHttpUrl()))
        assertFalse(KompaktGoogleContactName.appliesTo("https://dav.example.com/carddav/v1/x/".toHttpUrl()))
        assertFalse(KompaktGoogleContactName.appliesTo("http://localhost:8080/carddav/v1/x/".toHttpUrl()))
    }


    @Test
    fun lookUp_readsUidAndETag() {
        server.enqueue(cardResponse("2759d0418e581ab3"))

        assertEquals(Lookup.Found("2759d0418e581ab3", "2026-10-09T01:00:00.000-07:00"), lookUp())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.endsWith("/lists/default/upload-uuid.vcf"))
        assertEquals("text/vcard", request.getHeader("Accept"))
    }

    @Test
    fun lookUp_withoutETag() {
        server.enqueue(cardResponse("2759d0418e581ab3", eTag = null))

        assertEquals(Lookup.Found("2759d0418e581ab3", null), lookUp())
    }

    @Test
    fun lookUp_cardNotRenamedIsPermanent() {
        server.enqueue(cardResponse("upload-uuid"))

        assertEquals(Lookup.Permanent, lookUp())
    }

    @Test
    fun lookUp_unusableUidIsPermanent() {
        for (uid in listOf(null, "upload-uuid.vcf", "other.vcf", "a/b", "   ")) {
            server.enqueue(cardResponse(uid))
            assertEquals("uid=$uid", Lookup.Permanent, lookUp())
        }
    }

    @Test
    fun lookUp_permanentFailures() {
        for (code in listOf(404, 410, 403))
            server.enqueue(MockResponse().setResponseCode(code))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not a vcard"))

        repeat(4) {
            assertEquals(Lookup.Permanent, lookUp())
        }
    }

    @Test
    fun lookUp_temporaryFailures() {
        for (code in listOf(500, 502, 503, 429, 401))
            server.enqueue(MockResponse().setResponseCode(code))

        repeat(5) {
            val lookup = lookUp()
            assertTrue("$lookup", lookup is Lookup.Temporary && lookup.error is HttpException)
        }

        server.shutdown()
        val unreachable = lookUp()
        assertTrue("$unreachable", unreachable is Lookup.Temporary && unreachable.error is IOException)
    }


    private class Fixture {
        val provider = mockk<ContentProviderClient>(relaxed = true)
        val writes = mutableMapOf<Long, ContentValues>()
        val addressBook = mockk<LocalAddressBook>()

        fun contact(id: Long, fileName: String?): LocalContact {
            val uri = mockk<Uri>()
            every { provider.update(uri, any(), any(), any()) } answers {
                writes[id] = secondArg()
                1
            }
            return mockk {
                every { this@mockk.id } returns id
                every { this@mockk.fileName } returns fileName
                every { androidContact.addressBook.provider } returns provider
                every { androidContact.rawContactSyncURI() } returns uri
            }
        }

        fun waiting(vararg ids: Long) {
            every { addressBook.queryContacts(any(), any()) } returns ids.map { contact(it, "upload-$it.vcf") }
        }
    }

    private fun dispatchByContact(responses: Map<Long, MockResponse>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) =
            responses.entries.first { request.path!!.endsWith("upload-${it.key}.vcf") }.value
    }

    @Test
    fun keepNamesAround_selectsContactsStillNamedAfterTheirUpload() = runTest {
        val f = Fixture()
        val where = slot<String>()
        val args = slot<Array<String>>()
        every { f.addressBook.queryContacts(capture(where), capture(args)) } returns emptyList()

        KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) { Unit }

        assertTrue(where.captured, where.captured.contains("${RawContactColumns.FILENAME} LIKE ?"))
        assertTrue(where.captured, where.captured.contains("NOT ${RawContacts.DELETED}"))
        assertArrayEquals(arrayOf("%.vcf"), args.captured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keepNamesAround_storesGoogleName() = runTest {
        val f = Fixture()
        f.waiting(1)
        server.enqueue(cardResponse("2759d0418e581ab3"))

        val result = KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) { true }

        assertTrue(result)
        val stored = f.writes.getValue(1)
        assertEquals("2759d0418e581ab3", stored.getAsString(RawContactColumns.FILENAME))
        assertEquals("2026-10-09T01:00:00.000-07:00", stored.getAsString(RawContactColumns.ETAG))
        assertFalse(stored.containsKey(RawContacts.DIRTY))
    }

    @Test
    fun keepNamesAround_temporaryFailureResetsTheContactAndFailsTheSync() = runTest {
        val f = Fixture()
        f.waiting(1)
        server.enqueue(MockResponse().setResponseCode(503))

        try {
            KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) { true }
            fail("a temporary failure must fail the sync")
        } catch (e: HttpException) {
            assertEquals(503, e.statusCode)
        }

        val reset = f.writes.getValue(1)
        assertTrue(reset.containsKey(RawContactColumns.FILENAME))
        assertNull(reset.getAsString(RawContactColumns.FILENAME))
        assertNull(reset.getAsString(RawContactColumns.ETAG))
        assertEquals(1, reset.getAsInteger(RawContacts.DIRTY))
    }

    @Test
    fun keepNamesAround_otherContactsAreNamedBeforeTheSyncFails() = runTest {
        val f = Fixture()
        f.waiting(1, 2)
        server.dispatcher = dispatchByContact(mapOf(
            1L to cardResponse("google1"),
            2L to MockResponse().setResponseCode(502)
        ))

        try {
            KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) { true }
            fail("a temporary failure must fail the sync")
        } catch (e: HttpException) {
            assertEquals(502, e.statusCode)
        }

        assertEquals("google1", f.writes.getValue(1).getAsString(RawContactColumns.FILENAME))
        assertEquals(1, f.writes.getValue(2).getAsInteger(RawContacts.DIRTY))
    }

    @Test
    fun keepNamesAround_permanentFailureLeavesTheContactAndSyncs() = runTest {
        val f = Fixture()
        f.waiting(1)
        server.enqueue(MockResponse().setResponseCode(404))

        val result = KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) { 7 }

        assertEquals(7, result)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun keepNamesAround_failedUploadRethrowsItsOwnErrorAfterNaming() = runTest {
        val f = Fixture()
        f.waiting(1, 2)
        server.dispatcher = dispatchByContact(mapOf(
            1L to cardResponse("google1"),
            2L to MockResponse().setResponseCode(500)
        ))
        val uploadError = IOException("HTTP 502 on another PUT")

        try {
            KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) {
                throw uploadError
            }
            fail("upload failure must be rethrown")
        } catch (e: IOException) {
            assertSame(uploadError, e)
        }

        assertEquals("google1", f.writes.getValue(1).getAsString(RawContactColumns.FILENAME))
        assertEquals(1, f.writes.getValue(2).getAsInteger(RawContacts.DIRTY))
    }

    @Test
    fun keepNamesAround_namingErrorDoesNotHideTheUploadError() = runTest {
        val f = Fixture()
        val storeError = RemoteException("provider gone")
        every { f.addressBook.queryContacts(any(), any()) } throws storeError
        val uploadError = IOException("HTTP 502 on a PUT")

        try {
            KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) {
                throw uploadError
            }
            fail("upload failure must be rethrown")
        } catch (e: IOException) {
            assertSame(uploadError, e)
            assertSame(storeError, e.suppressed.single())
        }
    }

    @Test
    fun keepNamesAround_writeErrorFailsTheSync() = runTest {
        val f = Fixture()
        f.waiting(1)
        server.enqueue(cardResponse("google1"))
        every { f.provider.update(any(), any(), any(), any()) } throws RemoteException("provider gone")

        try {
            KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) { Unit }
            fail("a provider error must fail the sync")
        } catch (_: RemoteException) {
        }
    }

    @Test
    fun keepNamesAround_doesNothingAfterCancellation() = runTest {
        val f = Fixture()
        f.waiting(1)

        launch {
            try {
                KompaktGoogleContactName.keepNamesAround(client, googleCollection, f.addressBook, logger) {
                    // what upstream's upload phase throws when the sync is cancelled: a wrapped, non-cancellation error
                    currentCoroutineContext().job.cancel()
                    throw IOException("wrapped cancellation")
                }
            } catch (_: IOException) {
            } catch (_: CancellationException) {
            }
        }.join()

        verify(exactly = 0) { f.addressBook.queryContacts(any(), any()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keepNamesAround_leavesOtherServersAlone() = runTest {
        val f = Fixture()

        val result = KompaktGoogleContactName.keepNamesAround(
            client, "https://dav.example.com/addressbooks/x/".toHttpUrl(), f.addressBook, logger
        ) { 42 }

        assertEquals(42, result)
        verify(exactly = 0) { f.addressBook.queryContacts(any(), any()) }
    }

}
