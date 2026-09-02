/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

// Robolectric so Account carries its name: the JVM stub leaves it null and the filter reads it.
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktSyncEligibilityTest {

    private val account = Account("user@example.com", "bitfire.at.davdroid.mudita")

    private val accountSettings = mockk<KompaktAccountSettings>()
    private val toggle = mockk<KompaktServiceToggle>()
    private val serviceRepository = mockk<DavServiceRepository>()

    private val eligibility = KompaktSyncEligibility(accountSettings, toggle, serviceRepository)

    private fun consentTo(vararg services: KompaktSyncService) {
        every { accountSettings.getAuthState(any()) } returns mockk<AuthState> {
            every { scopeSet } returns services.map { it.scope }.toSet()
        }
    }

    private fun serviceRowExistsFor(vararg services: KompaktSyncService) {
        for (service in KompaktSyncService.entries)
            coEvery { serviceRepository.getByAccountAndType(account.name, service.serviceType) } returns
                if (service in services) mockk<Service>() else null
    }

    @Before
    fun everythingEnabled() {
        consentTo(*KompaktSyncService.entries.toTypedArray())
        serviceRowExistsFor(*KompaktSyncService.entries.toTypedArray())
        every { toggle.isOn(any(), any()) } returns true
    }

    @Test
    fun bothServicesWhenEverythingIsInPlace() = runTest {
        assertEquals(
            listOf(KompaktSyncService.CALENDAR, KompaktSyncService.CONTACTS),
            eligibility.enabledServices(account)
        )
    }

    @Test
    fun excludesASwitchedOffService() = runTest {
        every { toggle.isOn(any(), KompaktSyncService.CONTACTS) } returns false

        assertEquals(listOf(KompaktSyncService.CALENDAR), eligibility.enabledServices(account))
    }

    @Test
    fun excludesAServiceThatIsOnButHasLostConsent() = runTest {
        // A re-auth can apply a token carrying only one scope, leaving the other switched on with its
        // consent gone. Enqueuing then reaches Google as a 403.
        consentTo(KompaktSyncService.CALENDAR)

        assertEquals(listOf(KompaktSyncService.CALENDAR), eligibility.enabledServices(account))
    }

    @Test
    fun excludesAServiceWithNoServiceRow() = runTest {
        // Syncer.sync calls updateCollections unconditionally, and an absent service row yields an
        // empty collection map -- deleting every local collection with no database match.
        serviceRowExistsFor(KompaktSyncService.CONTACTS)

        assertEquals(listOf(KompaktSyncService.CONTACTS), eligibility.enabledServices(account))
    }

    @Test
    fun noServicesWithoutAnyStoredAuthorization() = runTest {
        every { accountSettings.getAuthState(any()) } returns null

        assertEquals(emptyList<KompaktSyncService>(), eligibility.enabledServices(account))
    }

}
