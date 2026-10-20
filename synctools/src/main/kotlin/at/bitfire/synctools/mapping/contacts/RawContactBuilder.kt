/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.synctools.mapping.contacts

import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.telephony.PhoneNumberUtils
import at.bitfire.synctools.mapping.contacts.builder.EmailBuilder
import at.bitfire.synctools.mapping.contacts.builder.EventBuilder
import at.bitfire.synctools.mapping.contacts.builder.GroupMembershipBuilder
import at.bitfire.synctools.mapping.contacts.builder.ImBuilder
import at.bitfire.synctools.mapping.contacts.builder.NicknameBuilder
import at.bitfire.synctools.mapping.contacts.builder.NoteBuilder
import at.bitfire.synctools.mapping.contacts.builder.OrganizationBuilder
import at.bitfire.synctools.mapping.contacts.builder.PhoneBuilder
import at.bitfire.synctools.mapping.contacts.builder.PhotoBuilder
import at.bitfire.synctools.mapping.contacts.builder.RelationBuilder
import at.bitfire.synctools.mapping.contacts.builder.SipAddressBuilder
import at.bitfire.synctools.mapping.contacts.builder.StructuredNameBuilder
import at.bitfire.synctools.mapping.contacts.builder.StructuredPostalBuilder
import at.bitfire.synctools.mapping.contacts.builder.UnknownPropertiesBuilder
import at.bitfire.synctools.mapping.contacts.builder.WebsiteBuilder
import at.bitfire.synctools.storage.contacts.AndroidAddressBook
import at.bitfire.synctools.storage.contacts.ContactsBatchOperation

class RawContactBuilder(addressBook: AndroidAddressBook) {

    private val dataRowBuilderFactories = mutableListOf(
        EmailBuilder.Factory,
        EventBuilder.Factory,
        GroupMembershipBuilder.Factory(addressBook, addressBook.groupMethod),
        ImBuilder.Factory,
        NicknameBuilder.Factory,
        NoteBuilder.Factory,
        OrganizationBuilder.Factory,
        PhoneBuilder.Factory,
        PhotoBuilder.Factory,
        RelationBuilder.Factory,
        SipAddressBuilder.Factory,
        StructuredNameBuilder.Factory,
        StructuredPostalBuilder.Factory,
        UnknownPropertiesBuilder.Factory,
        WebsiteBuilder.Factory
    )

    // Kompakt: superPrimary maps a MIME type to the forms of the value that was super-primary. It stays
    // super-primary while the card prefers the same value or none. When the card prefers another value that
    // is on the card next to the old one, the default was changed on the server: with followServer it moves
    // to the card's preferred value, otherwise it is dropped and the picker asks. followServer is false when
    // the device has a change the server never got, so the card's PREF may be the server's automatic pick
    // rather than a newer choice. A default whose value is gone from the card is always dropped.
    fun insertDataRows(
        dataRowUri: Uri,
        rawContactId: Long?,
        contact: Contact,
        batch: ContactsBatchOperation,
        readOnly: Boolean,
        superPrimary: Map<String, List<String>> = emptyMap(),
        followServer: Boolean = false
    ) {
        for (factory in dataRowBuilderFactories) {
            val builder = factory.newInstance(dataRowUri, rawContactId, contact, readOnly)
            val rows = builder.build()
            superPrimary[factory.mimeType()]?.let { chosen ->
                val preferred = rows.filter { it.values[Data.IS_PRIMARY] == 1 }
                val candidates = preferred.ifEmpty { rows }
                val same = findSameValue(factory.mimeType(), chosen, candidates.map { it.values[Data.DATA1] as? String })
                when {
                    same != null -> candidates[same].withValue(Data.IS_SUPER_PRIMARY, 1)
                    followServer && preferred.size == 1 &&
                        findSameValue(factory.mimeType(), chosen, rows.map { it.values[Data.DATA1] as? String }) != null ->
                        preferred.single().withValue(Data.IS_SUPER_PRIMARY, 1)
                }
            }
            batch += rows
        }
    }

    fun builderMimeTypes(): Set<String> {
        val mimeTypes = mutableSetOf<String>()
        for (factory in dataRowBuilderFactories)
            mimeTypes += factory.mimeType()
        return mimeTypes
    }

    companion object {

        // Kompakt: the server may have reformatted the value: spacing, the case of an email, or a national number
        // in international form, which matches the provider's E.164 of the old row. Numbers are never matched
        // loosely (PhoneNumberUtils.compare matches on the last digits), so a changed number loses the default
        // instead of moving it to another number.
        internal fun findSameValue(mimeType: String, chosen: List<String>, candidates: List<String?>): Int? {
            val index = if (mimeType == Phone.CONTENT_ITEM_TYPE) {
                val numbers = chosen.map { PhoneNumberUtils.normalizeNumber(it) }.filter { it.isNotEmpty() }
                candidates.indexOfFirst { it != null && PhoneNumberUtils.normalizeNumber(it) in numbers }
            } else
                candidates.indexOfFirst { candidate -> chosen.any { it.equals(candidate, ignoreCase = true) } }
            return index.takeIf { it >= 0 }
        }

    }

}
