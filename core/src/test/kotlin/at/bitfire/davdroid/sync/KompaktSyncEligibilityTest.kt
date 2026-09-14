/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.mockAuthState
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KompaktSyncEligibilityTest {

    private val account = mockAccount()

    private lateinit var accountSettings: KompaktAccountSettings
    private lateinit var toggle: KompaktServiceToggle
    private lateinit var eligibility: KompaktSyncEligibility

    @Before
    fun setUp() {
        accountSettings = mockk()
        grantScopes(KompaktOAuthGoogle.SCOPE_CALENDAR, KompaktOAuthGoogle.SCOPE_CONTACTS)

        toggle = mockk()
        every { toggle.isOn(account, any()) } returns true

        eligibility = KompaktSyncEligibility(accountSettings, toggle)
    }

    private fun grantScopes(vararg scopes: String) {
        every { accountSettings.getAuthState(account) } returns mockAuthState(*scopes)
    }

    @Test
    fun everyGrantedScopeIsConsented() {
        assertEquals(
            setOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS),
            eligibility.consented(account)
        )
    }

    @Test
    fun anUngrantedScopeIsNotConsented() {
        // Syncing an unproven grant anyway reaches Google as a 403, which deletes the home set and the
        // collections under it.
        grantScopes(KompaktOAuthGoogle.SCOPE_CALENDAR)

        assertEquals(setOf(KompaktSyncService.CALENDAR), eligibility.consented(account))
    }

    @Test
    fun nothingIsConsentedWithoutAStoredAuthorization() {
        every { accountSettings.getAuthState(account) } returns null

        assertEquals(emptySet<KompaktSyncService>(), eligibility.consented(account))
    }

    @Test
    fun everySwitchedOnServiceIsReported() {
        assertEquals(
            setOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS),
            eligibility.switchedOn(account)
        )
    }

    @Test
    fun aSwitchedOffServiceIsNotReported() {
        every { toggle.isOn(account, KompaktSyncService.CALENDAR) } returns false

        assertEquals(setOf(KompaktSyncService.CONTACTS), eligibility.switchedOn(account))
    }

    // The two answers are independent on purpose: consent can be revoked with the switch left on, and a
    // switch reads off before any default has been written. Composing them is the caller's job.
    @Test
    fun consentAndTheSwitchAreReportedSeparately() {
        grantScopes(KompaktOAuthGoogle.SCOPE_CALENDAR)
        every { toggle.isOn(account, KompaktSyncService.CALENDAR) } returns false

        assertEquals(setOf(KompaktSyncService.CALENDAR), eligibility.consented(account))
        assertEquals(setOf(KompaktSyncService.CONTACTS), eligibility.switchedOn(account))
    }

}
