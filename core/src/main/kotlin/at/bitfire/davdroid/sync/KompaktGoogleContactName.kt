/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.ContentValues
import android.provider.ContactsContract.RawContacts
import androidx.core.content.contentValuesOf
import at.bitfire.dav4jvm.okhttp.DavResource
import at.bitfire.dav4jvm.okhttp.exception.HttpException
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.davdroid.resource.LocalAddressBook
import at.bitfire.davdroid.resource.LocalContact
import at.bitfire.synctools.storage.contacts.AddressContract.RawContactColumns
import at.bitfire.synctools.vcard.VCardParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

// Google renames a card created by `PUT <uuid>.vcf` without saying so, and the next listing would then replace
// the local contact. Google still serves the card at the uploaded URL, and its UID is the new name. Google's
// names never end in `.vcf`, so a contact still named `<uuid>.vcf` is one whose new name has not been read yet.
object KompaktGoogleContactName {

    internal sealed interface Lookup {
        data class Found(val fileName: String, val eTag: String?) : Lookup
        data class Temporary(val error: Exception) : Lookup
        data object Permanent : Lookup
    }

    private const val UPLOADED_SUFFIX = ".vcf"
    private val GOOGLE_CARDDAV_HOSTS = setOf("apidata.googleusercontent.com", "www.googleapis.com")

    internal fun appliesTo(collectionUrl: HttpUrl): Boolean =
        collectionUrl.host in GOOGLE_CARDDAV_HOSTS && collectionUrl.encodedPath.startsWith("/carddav/")

    suspend fun <T> keepNamesAround(
        httpClient: OkHttpClient,
        collectionUrl: HttpUrl,
        addressBook: LocalAddressBook,
        logger: Logger,
        upload: suspend () -> T
    ): T {
        if (!appliesTo(collectionUrl))
            return upload()

        val result = try {
            upload()
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive)
                try {
                    storeServerNames(httpClient, collectionUrl, addressBook, logger)
                } catch (storeError: Exception) {
                    e.addSuppressed(storeError)
                }
            throw e
        }
        storeServerNames(httpClient, collectionUrl, addressBook, logger)?.let { throw it }
        return result
    }

    // Returns the first temporary error, after every contact has been named or reset. Throwing it fails the sync
    // before the listing, which would otherwise insert Google's card next to a contact that was reset to dirty.
    private suspend fun storeServerNames(
        httpClient: OkHttpClient,
        collectionUrl: HttpUrl,
        addressBook: LocalAddressBook,
        logger: Logger
    ): Exception? {
        val waiting = addressBook.queryContacts(
            "${RawContactColumns.FILENAME} LIKE ? AND NOT ${RawContacts.DELETED}",
            arrayOf("%$UPLOADED_SUFFIX")
        )
        val lookups = coroutineScope {
            waiting.mapNotNull { contact ->
                val uploadedName = contact.fileName ?: return@mapNotNull null
                async {
                    contact to runInterruptible { lookUpServerName(httpClient, collectionUrl, uploadedName, logger) }
                }
            }.awaitAll()
        }

        var firstTemporaryError: Exception? = null
        for ((contact, lookup) in lookups)
            when (lookup) {
                is Lookup.Found -> {
                    update(contact, contentValuesOf(
                        RawContactColumns.FILENAME to lookup.fileName,
                        RawContactColumns.ETAG to lookup.eTag
                    ))
                    logger.info("Google renamed ${contact.fileName} to ${lookup.fileName}; kept contact ${contact.id}")
                }
                is Lookup.Temporary -> {
                    update(contact, contentValuesOf(
                        RawContactColumns.FILENAME to null,
                        RawContactColumns.ETAG to null,
                        RawContacts.DIRTY to 1
                    ))
                    logger.info("Will upload contact ${contact.id} again: couldn't read Google's name for ${contact.fileName}")
                    firstTemporaryError = firstTemporaryError ?: lookup.error
                }
                Lookup.Permanent -> {}
            }
        return firstTemporaryError
    }

    private fun update(contact: LocalContact, values: ContentValues) {
        val androidContact = contact.androidContact
        androidContact.addressBook.provider.update(androidContact.rawContactSyncURI(), values, null, null)
    }

    internal fun lookUpServerName(
        httpClient: OkHttpClient,
        collectionUrl: HttpUrl,
        uploadedName: String,
        logger: Logger
    ): Lookup {
        val url = collectionUrl.newBuilder().addPathSegment(uploadedName).build()
        return try {
            var lookup: Lookup = Lookup.Permanent
            DavResource(httpClient, url).get("text/vcard", null) { response ->
                val uid = response.body.charStream().use { reader ->
                    VCardParser().parse(reader).firstOrNull()?.uid?.value
                }
                if (uid != null && isGoogleName(uid, uploadedName))
                    lookup = Lookup.Found(uid, GetETag.fromResponse(response)?.eTag)
            }
            lookup
        } catch (e: HttpException) {
            val status = e.statusCode ?: 0
            logger.log(Level.WARNING, "Couldn't read Google's name for $url (HTTP $status)", e)
            if (status >= 500 || status == 429 || status == 401) Lookup.Temporary(e) else Lookup.Permanent
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Couldn't read Google's name for $url", e)
            Lookup.Temporary(e)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't read Google's name for $url", e)
            Lookup.Permanent
        }
    }

    private fun isGoogleName(uid: String, uploadedName: String): Boolean =
        uid.isNotBlank() && '/' !in uid && !uid.endsWith(UPLOADED_SUFFIX) && "$uid$UPLOADED_SUFFIX" != uploadedName

}
