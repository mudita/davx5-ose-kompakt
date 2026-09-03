/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.TEST_ACCOUNT_NAME
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.mockAccount
import at.bitfire.davdroid.mockAuthState
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KompaktSyncEligibilityTest {

    companion object {
        private const val EMAIL = TEST_ACCOUNT_NAME
    }

    private val account = mockAccount()

    private lateinit var accountSettings: KompaktAccountSettings
    private lateinit var toggle: KompaktServiceToggle
    private lateinit var serviceRepository: DavServiceRepository
    private lateinit var eligibility: KompaktSyncEligibility

    @Before
    fun setUp() {
        accountSettings = mockk()
        grantScopes(KompaktOAuthGoogle.SCOPE_CALENDAR, KompaktOAuthGoogle.SCOPE_CONTACTS)

        toggle = mockk()
        every { toggle.isOn(account, any()) } returns true

        serviceRepository = mockk()
        coEvery { serviceRepository.getByAccountAndType(EMAIL, Service.TYPE_CALDAV) } returns
            Service(id = 7, accountName = EMAIL, type = Service.TYPE_CALDAV)
        coEvery { serviceRepository.getByAccountAndType(EMAIL, Service.TYPE_CARDDAV) } returns
            Service(id = 8, accountName = EMAIL, type = Service.TYPE_CARDDAV)

        eligibility = KompaktSyncEligibility(accountSettings, toggle, serviceRepository)
    }

    private fun grantScopes(vararg scopes: String) {
        every { accountSettings.getAuthState(account) } returns mockAuthState(*scopes)
    }

    @Test
    fun bothServicesWhenConsentedToggledOnAndConfigured() = runTest {
        assertEquals(
            listOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS),
            eligibility.enabledServices(account)
        )
    }

    @Test
    fun anUngrantedScopeExcludesItsService() = runTest {
        // Syncing an unproven grant anyway reaches Google as a 403, which deletes the home set and the
        // collections under it.
        grantScopes(KompaktOAuthGoogle.SCOPE_CALENDAR)

        assertEquals(listOf(KompaktSyncService.CALENDAR), eligibility.enabledServices(account))
    }

    @Test
    fun aSwitchedOffServiceIsExcluded() = runTest {
        every { toggle.isOn(account, KompaktSyncService.CALENDAR) } returns false

        assertEquals(listOf(KompaktSyncService.CONTACTS), eligibility.enabledServices(account))
    }

    @Test
    fun aServiceWithoutARowIsExcluded() = runTest {
        // No row means discovery has not created the service yet; a sync run would find nothing to do.
        coEvery { serviceRepository.getByAccountAndType(EMAIL, Service.TYPE_CARDDAV) } returns null

        assertEquals(listOf(KompaktSyncService.CALENDAR), eligibility.enabledServices(account))
    }

    @Test
    fun noServicesWithoutAStoredAuthorization() = runTest {
        every { accountSettings.getAuthState(account) } returns null

        assertEquals(emptyList<KompaktSyncService>(), eligibility.enabledServices(account))
    }

    @Test
    fun everyConditionHasToHoldAtOnce() = runTest {
        grantScopes(KompaktOAuthGoogle.SCOPE_CALENDAR)
        every { toggle.isOn(account, KompaktSyncService.CALENDAR) } returns false

        assertEquals(emptyList<KompaktSyncService>(), eligibility.enabledServices(account))
    }

}
