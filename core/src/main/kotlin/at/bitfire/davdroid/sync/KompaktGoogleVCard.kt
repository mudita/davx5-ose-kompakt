/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.synctools.mapping.contacts.Contact
import at.bitfire.synctools.mapping.contacts.ContactWriter
import at.bitfire.synctools.vcard.property.XAbDate
import at.bitfire.synctools.vcard.property.XSip
import ezvcard.VCard
import ezvcard.property.RawProperty
import ezvcard.util.PartialDate
import okhttp3.HttpUrl
import java.time.LocalDate
import java.time.MonthDay
import java.util.Locale

// Google-only vCard forms: Google's yearless custom dates, and X-SIP, the only SIP form Google keeps. They let
// these fields survive the full-vCard upload DAVx5 makes on any edit of a Google contact.
object KompaktGoogleVCard {

    private const val SIP = "sip"
    private const val LEAP_YEAR = 2000

    suspend fun read(collectionUrl: HttpUrl, vCard: VCard, read: suspend (VCard) -> Contact): Contact {
        if (KompaktGoogleContactName.appliesTo(collectionUrl))
            datesFromGoogle(vCard)
        return read(vCard)
    }

    fun write(collectionUrl: HttpUrl, contact: Contact, newWriter: (Contact) -> ContactWriter): ContactWriter {
        val writer = newWriter(contact)
        if (!KompaktGoogleContactName.appliesTo(collectionUrl))
            return writer

        val vCard = writer.vCard
        formattedNameOnlyFromName(contact, vCard)
        xSipFromSipAddresses(vCard)
        datesToGoogle(vCard)
        return writer
    }


    // FN: without a name, Google splits FN into first, middle and last name, so upstream's fallback FN (nickname,
    // email, phone, UID) would become one. Google serves a contact without a name with an empty FN.

    private fun formattedNameOnlyFromName(contact: Contact, vCard: VCard) {
        if (contact.displayName.isNullOrBlank())
            vCard.setFormattedName("")
    }


    // SIP: Google stores IMPP:sip: as an IM without a protocol, but keeps X-SIP verbatim.

    private fun xSipFromSipAddresses(vCard: VCard) {
        for (impp in vCard.impps.filter { it.protocol == SIP }) {
            val xSip = XSip(impp.handle)
            xSip.group = impp.group
            for (type in impp.types)
                xSip.parameters.addType(type.value)
            vCard.removeProperty(impp)
            vCard.addProperty(xSip)
        }
    }


    // Dates: Google prints a yearless date as year 0 converted to the Julian calendar (two days later) without an
    // era, which reads as year 1. It accepts the same shift back in year 0, or year 1 once it crosses into January.

    private fun datesFromGoogle(vCard: VCard) {
        for (date in vCard.getProperties(XAbDate::class.java)) {
            if (date.getParameter(Contact.DATE_PARAMETER_OMIT_YEAR) != null)
                continue
            val served = servedInYear1(date) ?: continue
            val day = served.atYear(LEAP_YEAR).minusDays(2)
            date.partialDate = PartialDate.builder().month(day.monthValue).date(day.dayOfMonth).build()
        }
    }

    // 27 Feb is served as 0001-02-29, which does not exist, so ez-vcard reads it as a partial date
    private fun servedInYear1(date: XAbDate): MonthDay? {
        (date.date as? LocalDate)?.let { full ->
            return if (full.year == 1) MonthDay.from(full) else null
        }
        val partial = date.partialDate ?: return null
        return if (partial.year == 1 && partial.month != null && partial.date != null)
            MonthDay.of(partial.month, partial.date)
        else
            null
    }

    private fun datesToGoogle(vCard: VCard) {
        for (date in vCard.getProperties(XAbDate::class.java).toList()) {
            val day = yearless(date) ?: continue
            val shifted = day.atYear(LEAP_YEAR).plusDays(2)
            val year = if (shifted.year == LEAP_YEAR) 0 else 1
            // raw, because ez-vcard prints year 0 as 0001 (year of era) for a date and as 0 for a partial date
            val raw = RawProperty(
                "X-ABDATE",
                String.format(Locale.ROOT, "%04d-%02d-%02d", year, shifted.monthValue, shifted.dayOfMonth)
            )
            raw.group = date.group
            for ((name, values) in date.parameters)
                if (!name.equals(Contact.DATE_PARAMETER_OMIT_YEAR, ignoreCase = true))
                    raw.parameters.putAll(name, values)
            vCard.removeProperty(date)
            vCard.addProperty(raw)
        }
    }

    private fun yearless(date: XAbDate): MonthDay? {
        date.partialDate?.let { partial ->
            return if (partial.year == null && partial.month != null && partial.date != null)
                MonthDay.of(partial.month, partial.date)
            else
                null
        }
        val full = date.date as? LocalDate ?: return null
        val omitYear = date.getParameter(Contact.DATE_PARAMETER_OMIT_YEAR)?.toIntOrNull() ?: return null
        return if (full.year == omitYear) MonthDay.from(full) else null
    }

}
