/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.synctools.storage.contacts

import android.Manifest
import android.content.ContentProviderClient
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email as EmailRow
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Base64
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import at.bitfire.synctools.mapping.contacts.Contact
import at.bitfire.synctools.mapping.contacts.ContactReader
import at.bitfire.synctools.mapping.contacts.LabeledProperty
import at.bitfire.synctools.storage.LocalStorageException
import at.bitfire.synctools.storage.contacts.AddressContract.asSyncAdapter
import at.bitfire.synctools.vcard.VCardParser
import at.bitfire.synctools.vcard.property.XAbDate
import ezvcard.property.Birthday
import ezvcard.property.Email
import ezvcard.util.PartialDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.io.FileNotFoundException
import java.io.StringReader
import java.time.LocalDate

class AndroidContactTest {

    companion object {
        @JvmField
        @ClassRule
        val permissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)!!

        private lateinit var provider: ContentProviderClient
        private lateinit var addressBook: AndroidAddressBook

        @BeforeClass
        @JvmStatic
        fun connect() {
            val context = InstrumentationRegistry.getInstrumentation().context
            provider = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!
            assertNotNull(provider)

            addressBook = TestAddressBook.create(provider)
        }

        @AfterClass
        @JvmStatic
        fun disconnect() {
            TestAddressBook.remove(addressBook)
            @Suppress("DEPRECATION")
            provider.release()
        }
    }


    @Test
    fun testAddAndReadContact() {
        val samplePhoto = Base64.decode("/9j/4AAQSkZJRgABAQEASABIAAD//gATQ3JlYXRlZCB3aXRoIEdJTVD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wgARCAAFAAUDAREAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACP/EABQBAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhADEAAAAVSf/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9oADAMBAAIAAwAAABCf/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPxB//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPxB//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxB//9k=", Base64.DEFAULT)

        val vcard = Contact()
        vcard.displayName = "Mya Contact"
        vcard.prefix = "Magª"
        vcard.givenName = "Mya"
        vcard.familyName = "Contact"
        vcard.suffix = "BSc"
        vcard.phoneticGivenName = "Först"
        vcard.phoneticMiddleName = "Mittelerde"
        vcard.phoneticFamilyName = "Fämilie"
        vcard.birthDay = Birthday(LocalDate.parse("1980-04-16"))
        vcard.customDates += LabeledProperty(XAbDate(PartialDate.parse("--0102")), "Custom Date")
        vcard.photo = samplePhoto

        val contact = AndroidContact(addressBook, vcard, null, null)
        contact.add()

        val contact2 = addressBook.findContactById(contact.id!!)
        try {
            val vcard2 = contact2.getContact()
            assertEquals(vcard.displayName, vcard2.displayName)
            assertEquals(vcard.prefix, vcard2.prefix)
            assertEquals(vcard.givenName, vcard2.givenName)
            assertEquals(vcard.familyName, vcard2.familyName)
            assertEquals(vcard.suffix, vcard2.suffix)
            assertEquals(vcard.phoneticGivenName, vcard2.phoneticGivenName)
            assertEquals(vcard.phoneticMiddleName, vcard2.phoneticMiddleName)
            assertEquals(vcard.phoneticFamilyName, vcard2.phoneticFamilyName)
            assertEquals(vcard.birthDay, vcard2.birthDay)
            assertArrayEquals(vcard.customDates.toArray(), vcard2.customDates.toArray())
            assertNotNull(vcard.photo)
        } finally {
            contact2.delete()
        }
    }

    @Test
    fun testInvalidPREF() = runTest {
        val vCard = "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "FN:Test\r\n" +
                "TEL;CELL=;PREF=:+12345\r\n" +
                "EMAIL;PREF=invalid:test@example.com\r\n" +
                "END:VCARD\r\n"
        val contacts = parseVCards(vCard)

        val dbContact = AndroidContact(addressBook, contacts.first(), null, null)
        dbContact.add()

        val dbContact2 = addressBook.findContactById(dbContact.id!!)
        try {
            val contact2 = dbContact2.getContact()
            assertEquals("Test", contact2.displayName)
            assertEquals("+12345", contact2.phoneNumbers.first().property.text)
            assertEquals("test@example.com", contact2.emails.first().property.value)
        } finally {
            dbContact2.delete()
        }
    }

    @Test
    @MediumTest
    fun testLargeTransactionManyRows() {
        val vcard = Contact()
        vcard.displayName = "Large Transaction (many rows)"
        for (i in 0 until 4000)
            vcard.emails += LabeledProperty(Email("test$i@example.com"))

        val contact = AndroidContact(addressBook, vcard, null, null)
        contact.add()

        val contact2 = addressBook.findContactById(contact.id!!)
        try {
            val vcard2 = contact2.getContact()
            assertEquals(4000, vcard2.emails.size)
        } finally {
            contact2.delete()
        }
    }

    @Test(expected = LocalStorageException::class)
    fun testLargeTransactionSingleRow() {
        val vcard = Contact()
        vcard.displayName = "Large Transaction (one row which is too large)"

        // 1 MB eTag ... have fun
        val data = CharArray(1024*1024) { 'x' }
        val eTag = String(data)

        val contact = AndroidContact(addressBook, vcard, null, eTag)
        contact.add()
    }

    @Test(expected = FileNotFoundException::class)
    fun testGetContactNotFound() {
        val values = ContentValues()
        values.put(ContactsContract.RawContacts._ID, Long.MAX_VALUE)
        values.put(AddressContract.RawContactColumns.FILENAME, "nonexistent.vcf")
        values.put(AddressContract.RawContactColumns.ETAG, "etag")
        AndroidContact(addressBook, values).getContact()
    }


    @Test
    fun testUpdate_KeepsSuperPrimary_PreferredByCard() {
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1)
        try {
            chooseDefault(contact.id!!, Phone.CONTENT_ITEM_TYPE, "+44 7700 900003")
            chooseDefault(contact.id!!, EmailRow.CONTENT_ITEM_TYPE, "three@example.com")

            // the user's choice was uploaded; Google reformats the number and the email's case
            contact.update(googleCard(
                numbers = listOf("+44 7700 900001", "+44 7700 900002", "+447700900003"), prefNumber = 3,
                emails = listOf("one@example.com", "Three@Example.com"), prefEmail = 2
            ))

            assertEquals(
                mapOf("+44 7700 900001" to Flags(0, 0), "+44 7700 900002" to Flags(0, 0), "+447700900003" to Flags(1, 1)),
                dataFlags(contact.id!!, Phone.CONTENT_ITEM_TYPE)
            )
            assertEquals(
                mapOf("one@example.com" to Flags(0, 0), "Three@Example.com" to Flags(1, 1)),
                dataFlags(contact.id!!, EmailRow.CONTENT_ITEM_TYPE)
            )
            assertFalse(isDirty(contact.id!!))
        } finally {
            contact.delete()
        }
    }

    @Test
    fun testUpdate_KeepsSuperPrimary_CardPrefersNothing() {
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1)
        try {
            chooseDefault(contact.id!!, Phone.CONTENT_ITEM_TYPE, "+44 7700 900003")
            chooseDefault(contact.id!!, EmailRow.CONTENT_ITEM_TYPE, "three@example.com")

            contact.update(googleCard(prefNumber = 0, prefEmail = 0))

            assertEquals(Flags(1, 1), dataFlags(contact.id!!, Phone.CONTENT_ITEM_TYPE)["+44 7700 900003"])
            assertEquals(Flags(1, 1), dataFlags(contact.id!!, EmailRow.CONTENT_ITEM_TYPE)["three@example.com"])
            assertFalse(isDirty(contact.id!!))
        } finally {
            contact.delete()
        }
    }

    @Test
    fun testUpdate_KeepsSuperPrimary_DuplicateNumber() {
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1)
        try {
            chooseDefault(contact.id!!, Phone.CONTENT_ITEM_TYPE, "+44 7700 900003")

            contact.update(googleCard(numbers = listOf("+44 7700 900003", "+44 7700 900002", "+44 7700 900003"), prefNumber = 3, prefEmail = 1))

            assertEquals(
                listOf("+44 7700 900003" to Flags(0, 0), "+44 7700 900002" to Flags(0, 0), "+44 7700 900003" to Flags(1, 1)),
                dataFlagsInOrder(contact.id!!, Phone.CONTENT_ITEM_TYPE)
            )
        } finally {
            contact.delete()
        }
    }

    @Test
    fun testUpdate_KeepsSuperPrimary_NationalNowInternational() {
        val national = listOf("601 111 111", "601 222 222", "601 333 333")
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1, numbers = national)
        try {
            chooseDefault(contact.id!!, Phone.CONTENT_ITEM_TYPE, "601 333 333")
            val e164 = normalizedNumber(contact.id!!, "601 333 333")
            assumeTrue("the device's country doesn't make 601 333 333 a valid number", e164 != null)

            contact.update(googleCard(numbers = national.dropLast(1) + e164!!, prefNumber = 3, prefEmail = 1))

            assertEquals(Flags(1, 1), dataFlags(contact.id!!, Phone.CONTENT_ITEM_TYPE)[e164])
        } finally {
            contact.delete()
        }
    }

    @Test
    fun testUpdate_DropsSuperPrimary_PrefMovedOnServer() {
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1)
        try {
            chooseDefault(contact.id!!, Phone.CONTENT_ITEM_TYPE, "+44 7700 900003")

            contact.update(googleCard(prefNumber = 1, prefEmail = 1))

            assertEquals(
                mapOf("+44 7700 900001" to Flags(1, 0), "+44 7700 900002" to Flags(0, 0), "+44 7700 900003" to Flags(0, 0)),
                dataFlags(contact.id!!, Phone.CONTENT_ITEM_TYPE)
            )
            assertFalse(isDirty(contact.id!!))
        } finally {
            contact.delete()
        }
    }

    @Test
    fun testUpdate_DropsSuperPrimary_DeletedOnServer() {
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1)
        try {
            chooseDefault(contact.id!!, Phone.CONTENT_ITEM_TYPE, "+44 7700 900003")

            contact.update(googleCard(numbers = listOf("+44 7700 900001", "+44 7700 900002"), prefNumber = 1, prefEmail = 1))

            assertEquals(
                mapOf("+44 7700 900001" to Flags(1, 0), "+44 7700 900002" to Flags(0, 0)),
                dataFlags(contact.id!!, Phone.CONTENT_ITEM_TYPE)
            )
            assertFalse(isDirty(contact.id!!))
        } finally {
            contact.delete()
        }
    }

    @Test
    fun testUpdate_MergedContact_KeepsLocalSuperPrimary() {
        val local = insertLocalRawContact("+44 7700 900011", "+44 7700 900012")
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1)
        try {
            chooseDefault(local, Phone.CONTENT_ITEM_TYPE, "+44 7700 900012")
            join(local, contact.id!!)

            contact.update(googleCard(prefNumber = 2, prefEmail = 1))

            assertEquals(Flags(1, 1), dataFlags(local, Phone.CONTENT_ITEM_TYPE)["+44 7700 900012"])
            assertEquals(
                mapOf("+44 7700 900001" to Flags(0, 0), "+44 7700 900002" to Flags(1, 0), "+44 7700 900003" to Flags(0, 0)),
                dataFlags(contact.id!!, Phone.CONTENT_ITEM_TYPE)
            )
            val aggregateId = aggregateId(local)
            assertEquals(aggregateId, aggregateId(contact.id!!))
            assertEquals(1, superPrimaryCount(aggregateId, Phone.CONTENT_ITEM_TYPE))
        } finally {
            contact.delete()
            provider.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, local).asSyncAdapter(), null, null)
        }
    }

    @Test
    fun testAdd_PrefIsPrimaryOnly() {
        val contact = addGoogleCard(prefNumber = 1, prefEmail = 1)
        try {
            assertEquals(Flags(1, 0), dataFlags(contact.id!!, Phone.CONTENT_ITEM_TYPE)["+44 7700 900001"])
            assertEquals(Flags(1, 0), dataFlags(contact.id!!, EmailRow.CONTENT_ITEM_TYPE)["one@example.com"])
        } finally {
            contact.delete()
        }
    }


    private data class Flags(val isPrimary: Int, val isSuperPrimary: Int)

    private fun googleCard(
        numbers: List<String> = listOf("+44 7700 900001", "+44 7700 900002", "+44 7700 900003"),
        prefNumber: Int,
        emails: List<String> = listOf("one@example.com", "three@example.com"),
        prefEmail: Int
    ): Contact = parseVCards(
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Default Number\r\n" +
                numbers.mapIndexed { i, number ->
                    "TEL;TYPE=CELL${if (i + 1 == prefNumber) ",pref" else ""}:$number\r\n"
                }.joinToString("") +
                emails.mapIndexed { i, email ->
                    "EMAIL;TYPE=INTERNET${if (i + 1 == prefEmail) ",pref" else ""}:$email\r\n"
                }.joinToString("") +
                "END:VCARD\r\n"
    ).first()

    private fun addGoogleCard(
        prefNumber: Int,
        prefEmail: Int,
        numbers: List<String> = listOf("+44 7700 900001", "+44 7700 900002", "+44 7700 900003")
    ): AndroidContact {
        val contact = AndroidContact(addressBook, googleCard(numbers, prefNumber, prefEmail = prefEmail), null, null)
        contact.add()
        return addressBook.findContactById(contact.id!!)
    }

    // like the Contacts app: not as sync adapter, so the provider also sets IS_PRIMARY and DIRTY
    private fun chooseDefault(rawContactId: Long, mimeType: String, value: String) {
        val values = ContentValues()
        values.put(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
        val updated = provider.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DATA1}=?",
            arrayOf(rawContactId.toString(), mimeType, value)
        )
        assertEquals(1, updated)
        assertTrue(isDirty(rawContactId))
    }

    private fun normalizedNumber(rawContactId: Long, number: String): String? =
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(Phone.NORMALIZED_NUMBER),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${Phone.NUMBER}=?",
            arrayOf(rawContactId.toString(), Phone.CONTENT_ITEM_TYPE, number),
            null
        )!!.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun dataFlagsInOrder(rawContactId: Long, mimeType: String): List<Pair<String, Flags>> =
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1, ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.IS_SUPER_PRIMARY),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawContactId.toString(), mimeType),
            ContactsContract.Data._ID
        )!!.use { cursor ->
            buildList {
                while (cursor.moveToNext())
                    add(cursor.getString(0) to Flags(cursor.getInt(1), cursor.getInt(2)))
            }
        }

    private fun insertLocalRawContact(vararg numbers: String): Long {
        val rawContactId = ContentUris.parseId(provider.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
            putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
            putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
        })!!)
        provider.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Default Number")
        })
        for (number in numbers)
            provider.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                put(Phone.NUMBER, number)
                put(Phone.TYPE, Phone.TYPE_MOBILE)
            })
        return rawContactId
    }

    private fun join(rawContactId1: Long, rawContactId2: Long) {
        provider.update(ContactsContract.AggregationExceptions.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rawContactId1)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawContactId2)
        }, null, null)
    }

    private fun aggregateId(rawContactId: Long): Long =
        provider.query(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
            arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun superPrimaryCount(contactId: Long, mimeType: String): Int =
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.IS_SUPER_PRIMARY}!=0",
            arrayOf(contactId.toString(), mimeType),
            null
        )!!.use { it.count }

    private fun dataFlags(rawContactId: Long, mimeType: String): Map<String, Flags> =
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1, ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.IS_SUPER_PRIMARY),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawContactId.toString(), mimeType),
            null
        )!!.use { cursor ->
            buildMap {
                while (cursor.moveToNext())
                    put(cursor.getString(0), Flags(cursor.getInt(1), cursor.getInt(2)))
            }
        }

    private fun isDirty(rawContactId: Long): Boolean =
        provider.query(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
            arrayOf(ContactsContract.RawContacts.DIRTY), null, null, null
        )!!.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }

    private fun parseVCards(vCardStr: String): List<Contact> =
        VCardParser().parse(StringReader(vCardStr)).map { vCard ->
            runBlocking {
                ContactReader(vCard).toContact()
            }
        }

}
