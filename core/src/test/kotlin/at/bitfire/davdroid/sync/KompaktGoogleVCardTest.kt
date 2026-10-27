/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.provider.ContactsContract.CommonDataKinds.Im
import androidx.core.content.contentValuesOf
import at.bitfire.synctools.mapping.contacts.Contact
import at.bitfire.synctools.mapping.contacts.ContactReader
import at.bitfire.synctools.mapping.contacts.ContactWriter
import at.bitfire.synctools.mapping.contacts.LabeledProperty
import at.bitfire.synctools.mapping.contacts.handler.ImHandler
import at.bitfire.synctools.vcard.VCardParser
import at.bitfire.synctools.vcard.property.XAbDate
import ezvcard.VCardVersion
import ezvcard.parameter.ImppType
import ezvcard.property.Email
import ezvcard.property.Impp
import ezvcard.property.Nickname
import ezvcard.property.Organization
import ezvcard.property.Telephone
import ezvcard.util.PartialDate
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.StringWriter
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktGoogleVCardTest {

    private val google =
        "https://www.googleapis.com/carddav/v1/principals/test%40gmail.com/lists/default/".toHttpUrl()
    private val other = "https://dav.example.com/addressbooks/test/contacts/".toHttpUrl()

    private suspend fun download(body: String, url: okhttp3.HttpUrl = google): Contact {
        val vCard = VCardParser().parse(
            ("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:test\r\nFN:Test\r\n$body\r\nEND:VCARD\r\n").reader()
        ).first()
        return KompaktGoogleVCard.read(url, vCard) { ContactReader.fromVCard(it) }
    }

    private fun upload(
        contact: Contact,
        url: okhttp3.HttpUrl = google,
        version: VCardVersion = VCardVersion.V3_0
    ): List<String> {
        val writer = KompaktGoogleVCard.write(url, contact) {
            ContactWriter(it, version, "test")
        }
        val out = StringWriter()
        writer.writeVCard(out)
        return out.toString().lines().map { it.replace("X-ABLABEL", "X-ABLabel") }
    }

    private fun contact() = Contact().apply { uid = "test"; displayName = "Test" }


    // FN

    private fun nameless() = Contact().apply {
        uid = "12e0d0e2-ad0c-45a6-adfb-1b6f0ea033f5"
        nickName = LabeledProperty(Nickname().apply { values.add("Nick Only") })
        emails += LabeledProperty(Email("only@example.com"))
        phoneNumbers += LabeledProperty(Telephone("+48 600 111 222"))
        jobTitle = "Job title"
    }

    @Test
    fun `a contact without a name goes up with an empty FN`() {
        val lines = upload(nameless())

        assertTrue(lines.contains("FN:"))
        assertTrue(lines.contains("N:;;;;"))
    }

    @Test
    fun `a company-only contact goes up with an empty FN`() {
        val contact = Contact().apply { organization = Organization().apply { values.add("Company Only") } }

        assertTrue(upload(contact).contains("FN:"))
    }

    @Test
    fun `a contact with a name keeps it as FN`() {
        val contact = nameless().apply { displayName = "Ewa Test"; givenName = "Ewa"; familyName = "Test" }

        assertTrue(upload(contact).contains("FN:Ewa Test"))
    }

    @Test
    fun `another server keeps upstream's fallback FN`() {
        assertTrue(upload(nameless(), other).contains("FN:Nick Only"))
    }


    // SIP

    @Test
    fun `upload writes a SIP address as X-SIP with its type and label`() {
        val contact = contact().apply {
            impps += LabeledProperty(Impp.sip("work@sip.example.com").apply { types += ImppType.WORK })
            impps += LabeledProperty(Impp.sip("office@sip.example.com"), "Office")
        }

        val lines = upload(contact)

        assertTrue(lines.contains("X-SIP;TYPE=work:work@sip.example.com"))
        assertTrue(lines.contains("item1.X-SIP:office@sip.example.com"))
        assertTrue(lines.contains("item1.X-ABLabel:Office"))
        assertFalse(lines.any { it.contains("IMPP") })
    }

    @Test
    fun `an IM with protocol Sip goes up as an IM, not as a SIP address`() {
        val contact = contact()
        ImHandler.handle(
            contentValuesOf(Im.DATA to "probe", Im.PROTOCOL to Im.PROTOCOL_CUSTOM, Im.CUSTOM_PROTOCOL to "Sip"),
            contact
        )

        val lines = upload(contact)

        assertTrue(lines.contains("IMPP;X-SERVICE-TYPE=Sip:x-apple:probe"))
        assertFalse(lines.any { it.contains("X-SIP") })
    }

    @Test
    fun `a SIP address goes down and up unchanged`() = runTest {
        val contact = download("X-SIP;TYPE=WORK:probe@sip.example.com")

        assertTrue(upload(contact).contains("X-SIP;TYPE=work:probe@sip.example.com"))
    }


    // Dates

    private fun googleForm(served: String) =
        if (served == "0001-01-01" || served == "0001-01-02") served else "0000" + served.substring(4)

    private val servedDates = listOf(
        "0001-09-11" to "--0909",
        "0001-03-02" to "--0229",
        "0001-02-29" to "--0227",
        "0001-01-03" to "--0101",
        "0001-12-31" to "--1229",
        "0001-01-01" to "--1230",
        "0001-01-02" to "--1231"
    )

    @Test
    fun `download reads a year-1 custom date as a date without a year`() = runTest {
        for ((served, partial) in servedDates) {
            val contact = download("item1.X-ABDATE:$served\r\nitem1.X-ABLabel:Party")

            val date = contact.customDates.single().property
            assertNull(served, date.date)
            assertEquals(served, PartialDate.parse(partial), date.partialDate)
        }
    }

    @Test
    fun `upload writes a date without a year in Google's year-0 form`() {
        for ((served, partial) in servedDates) {
            val contact = contact().apply {
                customDates += LabeledProperty(XAbDate(PartialDate.parse(partial)), "Party")
            }

            val expected = googleForm(served)
            assertTrue("$partial → $expected", upload(contact).contains("item1.X-ABDATE:$expected"))
        }
    }

    @Test
    fun `a yearless custom date goes down and up as Google sends it`() = runTest {
        for ((served, _) in servedDates) {
            val contact = download("item1.X-ABDATE:$served\r\nitem1.X-ABLabel:Party")

            val expected = googleForm(served)
            assertTrue(served, upload(contact).contains("item1.X-ABDATE:$expected"))
        }
    }

    @Test
    fun `a vCard 4 partial date goes up in Google's year-0 form`() {
        val contact = contact().apply {
            customDates += LabeledProperty(XAbDate(PartialDate.parse("--0909")), "Party")
        }

        assertTrue(upload(contact, version = VCardVersion.V4_0).contains("item1.X-ABDATE:0000-09-11"))
    }

    @Test
    fun `the year-0 date keeps its other parameters`() {
        val contact = contact().apply {
            customDates += LabeledProperty(
                XAbDate(PartialDate.parse("--0909")).apply { addParameter("X-TEST", "kept") },
                "Party"
            )
        }

        assertTrue(upload(contact).any { it.startsWith("item1.X-ABDATE;") && it.contains("X-TEST=kept") &&
                !it.contains("X-APPLE-OMIT-YEAR") && it.endsWith(":0000-09-11") })
    }

    @Test
    fun `dates with a year and birthdays are left alone`() = runTest {
        val contact = download("BDAY;X-APPLE-OMIT-YEAR=1604:1604-12-24\r\nitem1.X-ABDATE:2015-06-01\r\nitem1.X-ABLabel:Start")

        assertEquals(LocalDate.of(2015, 6, 1), contact.customDates.single().property.date)
        val lines = upload(contact)
        assertTrue(lines.contains("item1.X-ABDATE:2015-06-01"))
        assertTrue(lines.contains("BDAY;X-APPLE-OMIT-YEAR=1604:1604-12-24"))
    }


    // Other servers

    @Test
    fun `another server gets upstream's mapping in both directions`() = runTest {
        val contact = download("item1.X-ABDATE:0001-09-11\r\nitem1.X-ABLabel:Party", other)
        assertEquals(LocalDate.of(1, 9, 11), contact.customDates.single().property.date)

        contact.impps += LabeledProperty(Impp.sip("probe@sip.example.com"))
        val lines = upload(contact, url = other)
        assertTrue(lines.contains("IMPP:sip:probe@sip.example.com"))
        assertTrue(lines.any { it.endsWith("X-ABDATE:0001-09-11") })
    }

}
