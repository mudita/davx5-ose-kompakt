/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.ui.setup.KompaktConsentState
import dagger.Lazy
import kotlinx.coroutines.runInterruptible
import net.openid.appauth.AuthState
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Gives a consented service the [at.bitfire.davdroid.db.Service] row it needs before anything can be
 * synced or scheduled for it.
 *
 * Discovery is a network round-trip, so callers must have established connectivity first.
 */
class KompaktServiceProvisioning @Inject constructor(
    private val accountSettings: KompaktAccountSettings,
    private val accountRepository: AccountRepository,
    private val serviceRepository: DavServiceRepository,
    private val resourceFinderFactory: DavResourceFinder.Factory,
    private val oAuthGoogle: KompaktOAuthGoogle,
    private val syncWork: KompaktSyncWork,
    private val automaticSyncManager: Lazy<AutomaticSyncManager>,
    private val logger: Logger
) {

    /** `false` if the row is absent and could not be discovered, so the caller must leave it alone. */
    suspend fun ensureRow(account: Account, service: KompaktSyncService): Boolean {
        if (hasRow(account, service))
            return true
        val authState = accountSettings.getAuthState(account) ?: return false
        return provision(account, service, authState)
    }

    /** [ensureRow] against a token the account does not hold yet, so a grant can be stored only once
     *  the service behind it exists. Nothing is written on `false`. */
    suspend fun provision(account: Account, service: KompaktSyncService, authState: AuthState): Boolean {
        if (hasRow(account, service))
            return true
        val discovered = discover(account, service, authState) ?: return false
        accountRepository.addServiceBlocking(account.name, service, discovered)
        return true
    }

    /**
     * A row for each service [consent] granted, nothing left of each one it revoked.
     *
     * Granting runs first so that a `false` reaches the caller with nothing removed. Repeating a change
     * already applied is a no-op, so a caller that failed after this may retry the whole authorization.
     */
    suspend fun applyConsentChange(
        account: Account,
        consent: Map<KompaktSyncService, KompaktConsentState>,
        authState: AuthState
    ): Boolean {
        for ((service, state) in consent)
            if (state == KompaktConsentState.GRANTED && !provision(account, service, authState)) {
                logger.warning("Discovery found no $service for $account; leaving the grant unrecorded")
                return false
            }
        for ((service, state) in consent)
            if (state == KompaktConsentState.REVOKED && !clearProvisioning(account, service))
                return false
        return true
    }

    private suspend fun hasRow(account: Account, service: KompaktSyncService) =
        serviceRepository.getByAccountAndType(account.name, service.serviceType) != null

    private suspend fun discover(
        account: Account,
        service: KompaktSyncService,
        authState: AuthState
    ): DavResourceFinder.Configuration.ServiceInfo? {
        val credentials = Credentials(authState = authState)
        // Thread interruption is how DavResourceFinder notices cancellation; without runInterruptible,
        // cancelling this coroutine leaves the OkHttp calls running to their own timeouts.
        val config = runInterruptible {
            resourceFinderFactory
                .create(oAuthGoogle.baseUri(account.name), credentials)
                .findInitialConfiguration()
        }
        return when (service) {
            KompaktSyncService.CALENDAR -> config.calDAV
            KompaktSyncService.CONTACTS -> config.cardDAV
        }
    }

    /**
     * Undoes [ensureRow] and everything the first sync built on it, so the account is left exactly as it
     * was before this service was ever consented: no row, no collections, no stored interval, no
     * applied-defaults marker, and no synced copies on the device.
     *
     * A later [ensureRow] therefore takes the first-grant path — discovery, then [KompaktInitDefaults]
     * writing the selection and the interval — rather than needing anything remembered.
     *
     * Leaves everything in place, and answers `false`, when the synced copies could not be removed, so
     * the service is either fully cleared or untouched.
     */
    suspend fun clearProvisioning(account: Account, service: KompaktSyncService): Boolean {
        // Cancelled here rather than through the interval, which would re-arm the periodic worker for as
        // long as the row is still there.
        syncWork.cancel(account, service)

        // Clearing the interval while the row survives is worse than clearing nothing: updateAutomaticSync
        // would then find a service to schedule for and no stored interval, and fall back to the four-hour
        // default — arming the periodic worker for the service the user just revoked.
        if (!accountRepository.removeService(account.name, service)) {
            logger.warning("Couldn't remove $service for $account; leaving it provisioned")
            return false
        }

        accountSettings.clearSyncInterval(account, service.dataType)
        accountSettings.clearDefaultsApplied(account, service)

        // With the row gone this takes the no-service branch, which is what disables the periodic worker
        // and the content trigger.
        automaticSyncManager.get().updateAutomaticSync(account, service.dataType)
        return true
    }

}
