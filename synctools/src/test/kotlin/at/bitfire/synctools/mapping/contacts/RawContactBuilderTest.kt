/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.synctools.mapping.contacts

import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import at.bitfire.synctools.storage.contacts.AndroidAddressBook
import at.bitfire.synctools.storage.contacts.ContactsBatchOperation
import at.bitfire.synctools.vcard.GroupMethod
import ezvcard.property.Telephone
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawContactBuilderTest {

    private val addressBook = mockk<AndroidAddressBook>(relaxed = true) {
        every { groupMethod } returns GroupMethod.GROUP_VCARDS
    }

    @Test
    fun testInsertDataRows_SuperPrimary_PreferredByCard() {
        assertEquals(
            listOf(null, null, 1),
            superPrimaryFlags(prefNumber = 3, superPrimary = listOf("+48 601 333 333"))
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimary_CardPrefersAnother_Dirty() {
        assertEquals(
            listOf(null, null, null),
            superPrimaryFlags(prefNumber = 1, superPrimary = listOf("+48 601 333 333"))
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimary_CardPrefersAnother_FollowServer() {
        assertEquals(
            listOf(1, null, null),
            superPrimaryFlags(prefNumber = 1, superPrimary = listOf("+48 601 333 333"), followServer = true)
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimary_CardPrefersNothing_FollowServer() {
        assertEquals(
            listOf(null, null, 1),
            superPrimaryFlags(prefNumber = null, superPrimary = listOf("+48 601 333 333"), followServer = true)
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimary_DeletedOnServer_FollowServer() {
        assertEquals(
            listOf(null, null, null),
            superPrimaryFlags(prefNumber = 1, superPrimary = listOf("+48 601 444 444"), followServer = true)
        )
    }

    @Test
    fun testInsertDataRows_NoSuperPrimary_FollowServer() {
        assertEquals(listOf(null, null, null), superPrimaryFlags(prefNumber = 1, superPrimary = null, followServer = true))
    }

    @Test
    fun testInsertDataRows_SuperPrimaryEmail_CardPrefersAnother_FollowServer() {
        assertEquals(
            listOf(1, null),
            emailSuperPrimaryFlags(prefEmail = 1, superPrimary = listOf("two@example.com"), followServer = true)
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimaryEmail_CardPrefersAnother_Dirty() {
        assertEquals(
            listOf(null, null),
            emailSuperPrimaryFlags(prefEmail = 1, superPrimary = listOf("two@example.com"), followServer = false)
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimary_CardPrefersNothing() {
        assertEquals(
            listOf(null, null, 1),
            superPrimaryFlags(prefNumber = null, superPrimary = listOf("+48 601 333 333"))
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimary_DeletedOnServer() {
        assertEquals(
            listOf(null, null, null),
            superPrimaryFlags(prefNumber = 1, superPrimary = listOf("+48 601 444 444"))
        )
    }

    @Test
    fun testInsertDataRows_SuperPrimary_DuplicateNumberPreferredByCard() {
        assertEquals(
            listOf(null, null, 1),
            superPrimaryFlags(
                prefNumber = 3, superPrimary = listOf("+48 601 333 333"),
                numbers = listOf("+48 601 333 333", "+48 601 222 222", "+48 601 333 333")
            )
        )
    }

    @Test
    fun testInsertDataRows_NoSuperPrimary() {
        assertEquals(listOf(null, null, null), superPrimaryFlags(prefNumber = 1, superPrimary = null))
    }


    @Test
    fun testFindSameValue_Phone_Reformatted() {
        assertEquals(1, RawContactBuilder.findSameValue(Phone.CONTENT_ITEM_TYPE, listOf("+48 601 234 567"),
            listOf("+48 601 111 111", "+48-601-234-567")))
    }

    @Test
    fun testFindSameValue_Phone_NationalToInternational() {
        // the provider's E.164 of the old national number
        assertEquals(0, RawContactBuilder.findSameValue(Phone.CONTENT_ITEM_TYPE, listOf("601 234 567", "+48601234567"),
            listOf("+48 601 234 567", "+48 601 111 111")))
    }

    @Test
    fun testFindSameValue_Phone_InternationalToNational() {
        assertNull(RawContactBuilder.findSameValue(Phone.CONTENT_ITEM_TYPE, listOf("+48 601 234 567", "+48601234567"),
            listOf("601 234 567")))
    }

    @Test
    fun testFindSameValue_Phone_SharedTailOtherPrefix() {
        assertNull(RawContactBuilder.findSameValue(Phone.CONTENT_ITEM_TYPE, listOf("+48 601 234 567", "+48601234567"),
            listOf("+48 501 234 567", "+44 7601 234 567")))
    }

    @Test
    fun testFindSameValue_Phone_Changed() {
        assertNull(RawContactBuilder.findSameValue(Phone.CONTENT_ITEM_TYPE, listOf("+48 601 234 567"),
            listOf("+48 601 234 568", null)))
    }

    @Test
    fun testFindSameValue_Phone_NoDigits() {
        assertNull(RawContactBuilder.findSameValue(Phone.CONTENT_ITEM_TYPE, listOf("n/a"), listOf("unknown")))
    }

    @Test
    fun testFindSameValue_Email_IgnoresCase() {
        assertEquals(0, RawContactBuilder.findSameValue(Email.CONTENT_ITEM_TYPE, listOf("three@example.com"),
            listOf("Three@Example.com")))
    }

    @Test
    fun testFindSameValue_Email_Deleted() {
        assertNull(RawContactBuilder.findSameValue(Email.CONTENT_ITEM_TYPE, listOf("three@example.com"),
            listOf("one@example.com")))
    }


    private fun superPrimaryFlags(
        prefNumber: Int?,
        superPrimary: List<String>?,
        numbers: List<String> = listOf("+48 601 111 111", "+48 601 222 222", "+48 601 333 333"),
        followServer: Boolean = false
    ): List<Any?> {
        val contact = Contact()
        numbers.forEachIndexed { i, number ->
            contact.phoneNumbers += LabeledProperty(Telephone(number).apply {
                if (i + 1 == prefNumber)
                    pref = 1
            })
        }
        val batch = ContactsBatchOperation(mockk())
        RawContactBuilder(addressBook).insertDataRows(
            Uri.EMPTY, 1, contact, batch, false,
            superPrimary?.let { mapOf(Phone.CONTENT_ITEM_TYPE to it) }.orEmpty(),
            followServer
        )
        return batch.queue
            .map { it.values }
            .filter { it[Phone.MIMETYPE] == Phone.CONTENT_ITEM_TYPE }
            .map { it[Phone.IS_SUPER_PRIMARY] }
    }

    private fun emailSuperPrimaryFlags(prefEmail: Int?, superPrimary: List<String>, followServer: Boolean): List<Any?> {
        val contact = Contact()
        listOf("one@example.com", "two@example.com").forEachIndexed { i, address ->
            contact.emails += LabeledProperty(ezvcard.property.Email(address).apply {
                if (i + 1 == prefEmail)
                    pref = 1
            })
        }
        val batch = ContactsBatchOperation(mockk())
        RawContactBuilder(addressBook).insertDataRows(
            Uri.EMPTY, 1, contact, batch, false, mapOf(Email.CONTENT_ITEM_TYPE to superPrimary), followServer
        )
        return batch.queue
            .map { it.values }
            .filter { it[Email.MIMETYPE] == Email.CONTENT_ITEM_TYPE }
            .map { it[Email.IS_SUPER_PRIMARY] }
    }

}
