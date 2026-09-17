/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.app.Activity
import android.content.Intent
import at.bitfire.davdroid.sync.KompaktSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A re-authorization outcome crosses an activity boundary as names, so nothing but this encoder and
 * these two readers knows the encoding — and a rename on either enum has to keep surviving the trip.
 */
@RunWith(RobolectricTestRunner::class)
class KompaktLoginActivityTest {

    private fun encode(result: KompaktReauthResult) = KompaktLoginActivity.reauthActivityResult(result).second

    private fun code(result: KompaktReauthResult) = KompaktLoginActivity.reauthActivityResult(result).first

    // A caller that reads the code as "something came back" would act on a refresh that determined
    // nothing and on a switch that removed nothing, both of which carry no data at all.
    @Test
    fun `only a cancellation is not RESULT_OK`() {
        assertEquals(Activity.RESULT_CANCELED, code(KompaktReauthResult.Cancelled))
        assertEquals(Activity.RESULT_OK, code(KompaktReauthResult.Refreshed(consent = null)))
        assertEquals(Activity.RESULT_OK, code(KompaktReauthResult.Switched(removedAccount = null)))
    }

    @Test
    fun `every consent state survives the round trip`() {
        for (calendar in KompaktConsentState.entries)
            for (contacts in KompaktConsentState.entries) {
                val consent = mapOf(
                    KompaktSyncService.CALENDAR to calendar,
                    KompaktSyncService.CONTACTS to contacts
                )
                assertEquals(
                    consent,
                    KompaktLoginActivity.consentChangeFrom(encode(KompaktReauthResult.Refreshed(consent)))
                )
            }
    }

    @Test
    fun `an empty change stays empty rather than becoming absent`() {
        assertEquals(
            emptyMap<KompaktSyncService, KompaktConsentState>(),
            KompaktLoginActivity.consentChangeFrom(encode(KompaktReauthResult.Refreshed(emptyMap())))
        )
    }

    @Test
    fun `the removed account survives the round trip`() {
        assertEquals(
            "someone@gmail.com",
            KompaktLoginActivity.switchedFromAccount(
                encode(KompaktReauthResult.Switched(removedAccount = "someone@gmail.com"))
            )
        )
    }

    // The caller waits for the named account to leave the accounts flow, so a switch that removed
    // nothing must not name one.
    @Test
    fun `a switch that removed nothing carries nothing`() {
        assertNull(encode(KompaktReauthResult.Switched(removedAccount = null)))
    }

    @Test
    fun `an undetermined change carries nothing`() {
        assertNull(encode(KompaktReauthResult.Refreshed(consent = null)))
    }

    @Test
    fun `a cancelled re-authorization carries nothing`() {
        assertNull(encode(KompaktReauthResult.Cancelled))
    }

    // Also what a caller sees when the activity was killed before it could answer.
    @Test
    fun `no data reads as neither a switch nor a change`() {
        assertNull(KompaktLoginActivity.switchedFromAccount(data = null))
        assertNull(KompaktLoginActivity.consentChangeFrom(data = null))
        assertNull(KompaktLoginActivity.switchedFromAccount(Intent()))
        assertNull(KompaktLoginActivity.consentChangeFrom(Intent()))
    }

    // The caller checks for a switch first, so the two must never be encoded together.
    @Test
    fun `a switch carries no consent change and a refresh names no account`() {
        assertNull(
            KompaktLoginActivity.consentChangeFrom(
                encode(KompaktReauthResult.Switched(removedAccount = "someone@gmail.com"))
            )
        )
        assertNull(
            KompaktLoginActivity.switchedFromAccount(
                encode(KompaktReauthResult.Refreshed(mapOf(KompaktSyncService.CALENDAR to KompaktConsentState.REVOKED)))
            )
        )
    }

}
