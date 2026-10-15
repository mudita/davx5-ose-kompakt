/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.synctools.mapping.contacts.builder

import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import at.bitfire.synctools.mapping.contacts.Contact
import at.bitfire.synctools.storage.BatchOperation
import at.bitfire.synctools.vcard.property.CustomType
import ezvcard.parameter.EmailType
import java.util.LinkedList
import java.util.logging.Level

class EmailBuilder(dataRowUri: Uri, rawContactId: Long?, contact: Contact, readOnly: Boolean)
    : DataRowBuilder(Factory.mimeType(), dataRowUri, rawContactId, contact, readOnly) {

    override fun build(): List<BatchOperation.CpoBuilder> {
        val result = LinkedList<BatchOperation.CpoBuilder>()
        var primary: BatchOperation.CpoBuilder? = null
        var primaryRank = Int.MAX_VALUE
        for (labeledEmail in contact.emails) {
            if (labeledEmail.property.value.isNullOrBlank())
                continue

            val email = labeledEmail.property
            val types = email.types

            // preferred email address?
            var pref: Int? = null
            try {
                pref = email.pref
            } catch(e: IllegalStateException) {
                logger.log(Level.FINER, "Can't understand email PREF", e)
            }
            var prefRank = pref
            if (types.contains(EmailType.PREF)) {
                prefRank = prefRank ?: 1
                types -= EmailType.PREF
            }

            var typeCode = Email.TYPE_OTHER
            var typeLabel: String? = null
            if (labeledEmail.label != null) {
                typeCode = Email.TYPE_CUSTOM
                typeLabel = labeledEmail.label
            } else {
                for (type in types)
                    when (type) {
                        EmailType.HOME -> typeCode = Email.TYPE_HOME
                        EmailType.WORK -> typeCode = Email.TYPE_WORK
                        CustomType.Email.MOBILE -> typeCode = Email.TYPE_MOBILE
                    }
            }

            val row = newDataRow()
                    .withValue(Email.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, email.value)
                    .withValue(Email.TYPE, typeCode)
                    .withValue(Email.LABEL, typeLabel)
                    .withValue(Email.IS_PRIMARY, 0)
            result += row
            if (prefRank != null && prefRank < primaryRank) {
                primary = row
                primaryRank = prefRank
            }
        }
        // Kompakt: PREF maps to IS_PRIMARY only, and only the most preferred one. IS_SUPER_PRIMARY is the
        // user's device-wide default; writing it (even as 0) would override the choice on every download.
        primary?.withValue(Email.IS_PRIMARY, 1)
        return result
    }


    object Factory: DataRowBuilder.Factory<EmailBuilder> {
        override fun mimeType() = Email.CONTENT_ITEM_TYPE
        override fun newInstance(dataRowUri: Uri, rawContactId: Long?, contact: Contact, readOnly: Boolean) =
            EmailBuilder(dataRowUri, rawContactId, contact, readOnly)
    }

}