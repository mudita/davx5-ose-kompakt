/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.dav4jvm.okhttp.exception.HttpException
import at.bitfire.davdroid.resource.LocalResource
import at.bitfire.synctools.storage.LocalStorageException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.logging.Logger

class KompaktUploadFailureTest {

    private fun httpError(code: Int, body: String? = null, contentType: String = "application/xml; charset=utf-8") = HttpException(
        Response.Builder()
            .request(Request.Builder().url("https://www.googleapis.com/carddav/v1/principals/a/lists/default/c.vcf").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .apply { if (body != null) body(body.toResponseBody(contentType.toMediaType())) }
            .build()
    )

    private fun davError(namespace: String, element: String) =
        """<?xml version="1.0" encoding="utf-8"?><d:error xmlns:d="DAV:" xmlns:x="$namespace"><x:$element/></d:error>"""

    private val local = mockk<LocalResource> {
        every { fileName } returns "c.vcf"
        every { id } returns 7L
    }

    @Test
    fun `a rejected body stays confined to its resource`() {
        for (code in listOf(400, 413, 422, 423))
            assertTrue("HTTP $code", KompaktUploadFailure.isConfinedToResource(SyncException(httpError(code))))
    }

    @Test
    fun `errors about the account, collection or connection end the sync`() {
        for (code in listOf(401, 403, 404, 405, 407, 408, 409, 410, 411, 415, 417, 421, 426, 428, 429, 431, 500, 502, 503))
            assertFalse("HTTP $code", KompaktUploadFailure.isConfinedToResource(SyncException(httpError(code))))
    }

    @Test
    fun `a 403 ends the sync whatever its body says`() {
        val precondition = httpError(403, davError("urn:ietf:params:xml:ns:carddav", "valid-address-data"))
        val html = httpError(403, "<html><body>Forbidden</body></html>", "text/html")
        for (e in listOf(precondition, html))
            assertFalse(KompaktUploadFailure.isConfinedToResource(SyncException(e)))
    }

    @Test
    fun `non-HTTP failures end the sync`() {
        assertFalse(KompaktUploadFailure.isConfinedToResource(SyncException(IOException("offline"))))
        assertFalse(KompaktUploadFailure.isConfinedToResource(SyncException(LocalStorageException("provider"))))
        assertFalse(KompaktUploadFailure.isConfinedToResource(IllegalStateException()))
    }

    @Test
    fun `an unwrapped HTTP error is classified the same way`() {
        assertTrue(KompaktUploadFailure.isConfinedToResource(httpError(400)))
        assertFalse(KompaktUploadFailure.isConfinedToResource(httpError(401)))
    }

    @Test
    fun `a confined rejection is counted without failing the sync`() = runBlocking {
        val syncResult = SyncResult()
        KompaktUploadFailure.runConfined(local, syncResult, Logger.getAnonymousLogger()) {
            throw SyncException(httpError(400))
        }
        assertEquals(1L, syncResult.numRejectedUploads)
        assertFalse(syncResult.hasError())
    }

    @Test
    fun `any other failure is rethrown and not counted`() = runBlocking {
        val syncResult = SyncResult()
        val failure = SyncException(httpError(405))
        try {
            KompaktUploadFailure.runConfined(local, syncResult, Logger.getAnonymousLogger()) { throw failure }
            fail("expected the failure to be rethrown")
        } catch (e: SyncException) {
            assertSame(failure, e)
        }
        assertEquals(0L, syncResult.numRejectedUploads)
    }

}
