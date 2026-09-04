/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.content.Context
import android.util.Base64
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.network.OAuthIntegration
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.KompaktSyncService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

// A1 changed apply()'s control flow (discovery before any write); this covers the state mapping in
// authenticate()/apply() that guards it, plus the branches that were previously untested: Denied
// versus Failed, the "row already exists" skip, and the discovery-failure path from A1.
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktAddConsentModelTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val logger = Logger.getGlobal()

    // Real, not mocked: it only builds request objects and a base URI, no network involved, and reusing
    // it is how a fake AuthorizationResponse/TokenResponse below get real, decodable scope/idToken data.
    private val oAuthGoogle = KompaktOAuthGoogle(context, OAuthIntegration(context), logger)

    private val account = Account("user@example.com", "type")

    private val accountRepository = mockk<AccountRepository>()
    private val authService = mockk<AuthorizationService>()
    private val initDefaults = mockk<KompaktInitDefaults>()
    private val kompaktAccountSettings = mockk<KompaktAccountSettings>()
    private val oAuthIntegration = mockk<OAuthIntegration>()
    private val resourceFinderFactory = mockk<DavResourceFinder.Factory>()
    private val serviceRepository = mockk<DavServiceRepository>()

    private fun model(service: KompaktSyncService) =
        KompaktAddConsentModel(
            account = account,
            service = service,
            accountRepository = accountRepository,
            authService = authService,
            initDefaults = initDefaults,
            kompaktAccountSettings = kompaktAccountSettings,
            oAuthGoogle = oAuthGoogle,
            oAuthIntegration = oAuthIntegration,
            resourceFinderFactory = resourceFinderFactory,
            serviceRepository = serviceRepository,
            ioDispatcher = UnconfinedTestDispatcher(),
            logger = logger
        )

    private fun grantedAuthState(vararg scopes: String, mismatchedEmail: String? = null): AuthState {
        val request = oAuthGoogle.signIn(email = null, customClientId = "test.apps.googleusercontent.com")
        val response = AuthorizationResponse.Builder(request).setScopes(*scopes).build()
        val authState = AuthState(response, null)
        if (mismatchedEmail != null) {
            // Refresh-token grant, not the authorization-code exchange: the fabricated response above
            // never carries a real code, and this is the only grant type that doesn't need one.
            val tokenRequest = TokenRequest.Builder(request.configuration, request.clientId)
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken("dummy-refresh-token")
                .build()
            val tokenResponse = TokenResponse.Builder(tokenRequest)
                .setIdToken(fakeIdToken(mismatchedEmail))
                .build()
            authState.update(tokenResponse, null)
        }
        return authState
    }

    private fun fakeIdToken(email: String): String {
        val payload = JSONObject().put("email", email).toString()
        val payloadB64 = Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "header.$payloadB64.sig"
    }

    @Test
    fun `authorization without the requested scope is Denied, not Failed`() = runTest {
        every { kompaktAccountSettings.getAuthState(account) } returns null
        coEvery { oAuthIntegration.authenticate(any(), any()) } returns
            grantedAuthState(KompaktOAuthGoogle.SCOPE_CONTACTS)

        val vm = model(KompaktSyncService.CALENDAR)
        vm.authenticate(mockk())

        assertEquals(KompaktAddConsentModel.AddConsentState.Denied, vm.state.value)
    }

    @Test
    fun `a different Google account is Failed, not applied`() = runTest {
        every { kompaktAccountSettings.getAuthState(account) } returns null
        coEvery { oAuthIntegration.authenticate(any(), any()) } returns
            grantedAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR, mismatchedEmail = "someone-else@example.com")

        val vm = model(KompaktSyncService.CALENDAR)
        vm.authenticate(mockk())

        assertEquals(KompaktAddConsentModel.AddConsentState.Failed, vm.state.value)
    }

    @Test
    fun `an exception from the token exchange is Failed`() = runTest {
        every { kompaktAccountSettings.getAuthState(account) } returns null
        coEvery { oAuthIntegration.authenticate(any(), any()) } throws RuntimeException("token exchange failed")

        val vm = model(KompaktSyncService.CALENDAR)
        vm.authenticate(mockk())

        assertEquals(KompaktAddConsentModel.AddConsentState.Failed, vm.state.value)
    }

    @Test
    fun `granting consent for a service that already has a row skips discovery`() = runTest {
        val existingService = mockk<at.bitfire.davdroid.db.Service> { every { id } returns 42L }
        every { kompaktAccountSettings.getAuthState(account) } returns null
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CALDAV) } returns existingService
        coEvery { oAuthIntegration.authenticate(any(), any()) } returns
            grantedAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR)
        coEvery { kompaktAccountSettings.updateAuthState(account, any()) } returns Unit
        coEvery { kompaktAccountSettings.setReauthNeeded(account, false) } returns Unit
        coEvery { initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, 42L) } returns
            KompaktInitDefaults.Outcome.APPLIED

        val vm = model(KompaktSyncService.CALENDAR)
        vm.authenticate(mockk())

        assertEquals(KompaktAddConsentModel.AddConsentState.Granted, vm.state.value)
        // resourceFinderFactory is unstubbed: reaching it would throw, so a Granted result here already
        // proves discovery was skipped. The explicit verify below documents that on purpose.
        coVerify(exactly = 0) { accountRepository.addServiceBlocking(any(), any(), any()) }
    }

    @Test
    fun `granting consent for a new service that discovery can't find leaves the grant unrecorded`() = runTest {
        val emptyConfig = DavResourceFinder.Configuration(
            cardDAV = null,
            calDAV = null,
            encountered401 = false,
            logs = ""
        )
        val finder = mockk<DavResourceFinder> { every { findInitialConfiguration() } returns emptyConfig }
        every { kompaktAccountSettings.getAuthState(account) } returns null
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CALDAV) } returns null
        every { resourceFinderFactory.create(any(), any()) } returns finder
        coEvery { oAuthIntegration.authenticate(any(), any()) } returns
            grantedAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR)

        val vm = model(KompaktSyncService.CALENDAR)
        vm.authenticate(mockk())

        assertEquals(KompaktAddConsentModel.AddConsentState.Failed, vm.state.value)
        coVerify(exactly = 0) { accountRepository.addServiceBlocking(any(), any(), any()) }
        coVerify(exactly = 0) { kompaktAccountSettings.updateAuthState(any(), any()) }
    }

    @Test
    fun `granting consent for a newly discovered service is applied`() = runTest {
        val discovered = DavResourceFinder.Configuration.ServiceInfo()
        val config = DavResourceFinder.Configuration(
            cardDAV = null,
            calDAV = discovered,
            encountered401 = false,
            logs = ""
        )
        val finder = mockk<DavResourceFinder> { every { findInitialConfiguration() } returns config }
        every { kompaktAccountSettings.getAuthState(account) } returns null
        coEvery { serviceRepository.getByAccountAndType(account.name, Service.TYPE_CALDAV) } returns null
        every { resourceFinderFactory.create(any(), any()) } returns finder
        every { accountRepository.addServiceBlocking(account.name, KompaktSyncService.CALENDAR, discovered) } returns 7L
        coEvery { oAuthIntegration.authenticate(any(), any()) } returns
            grantedAuthState(KompaktOAuthGoogle.SCOPE_CALENDAR)
        coEvery { kompaktAccountSettings.updateAuthState(account, any()) } returns Unit
        coEvery { kompaktAccountSettings.setReauthNeeded(account, false) } returns Unit
        coEvery { initDefaults.maybeApply(account, KompaktSyncService.CALENDAR, 7L) } returns
            KompaktInitDefaults.Outcome.APPLIED

        val vm = model(KompaktSyncService.CALENDAR)
        vm.authenticate(mockk())

        assertEquals(KompaktAddConsentModel.AddConsentState.Granted, vm.state.value)
    }

}
