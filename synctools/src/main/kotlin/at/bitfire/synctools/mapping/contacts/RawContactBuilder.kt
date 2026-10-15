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
    // super-primary unless the card prefers another value: a PREF moved on the server means the default was
    // changed there, so it is dropped and the picker asks.
    fun insertDataRows(
        dataRowUri: Uri,
        rawContactId: Long?,
        contact: Contact,
        batch: ContactsBatchOperation,
        readOnly: Boolean,
        superPrimary: Map<String, List<String>> = emptyMap()
    ) {
        for (factory in dataRowBuilderFactories) {
            val builder = factory.newInstance(dataRowUri, rawContactId, contact, readOnly)
            val rows = builder.build()
            superPrimary[factory.mimeType()]?.let { chosen ->
                val candidates = rows.filter { it.values[Data.IS_PRIMARY] == 1 }.ifEmpty { rows }
                findSameValue(factory.mimeType(), chosen, candidates.map { it.values[Data.DATA1] as? String })
                    ?.let { candidates[it].withValue(Data.IS_SUPER_PRIMARY, 1) }
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
