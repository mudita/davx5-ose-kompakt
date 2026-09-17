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
import dagger.Lazy
import kotlinx.coroutines.runInterruptible
import javax.inject.Inject

/**
 * Gives a consented service the [at.bitfire.davdroid.db.Service] row it needs before anything can be
 * synced or scheduled for it.
 *
 * A re-authorization requests every scope, so consent can exist with no row behind it — the row is
 * only written for the services that were being linked. Discovery is a network round-trip, so callers
 * must have established connectivity first.
 */
class KompaktServiceProvisioning @Inject constructor(
    private val accountSettings: KompaktAccountSettings,
    private val accountRepository: AccountRepository,
    private val serviceRepository: DavServiceRepository,
    private val resourceFinderFactory: DavResourceFinder.Factory,
    private val oAuthGoogle: KompaktOAuthGoogle,
    private val syncWork: KompaktSyncWork,
    private val automaticSyncManager: Lazy<AutomaticSyncManager>
) {

    /**
     * Gives the service everything it needs to be synced, discovering it first if it has no
     * [at.bitfire.davdroid.db.Service] row yet. Idempotent.
     *
     * `false` if the row is absent and could not be discovered, so the caller must leave it alone.
     */
    suspend fun ensureProvisioned(account: Account, service: KompaktSyncService): Boolean {
        if (serviceRepository.getByAccountAndType(account.name, service.serviceType) != null)
            return true

        val authState = accountSettings.getAuthState(account) ?: return false
        val credentials = Credentials(authState = authState)
        val config = runInterruptible {
            resourceFinderFactory
                .create(oAuthGoogle.baseUri(account.name), credentials)
                .findInitialConfiguration()
        }
        val discovered = when (service) {
            KompaktSyncService.CALENDAR -> config.calDAV
            KompaktSyncService.CONTACTS -> config.cardDAV
        } ?: return false

        accountRepository.addServiceBlocking(account.name, service, discovered)
        return true
    }

    /**
     * Undoes [ensureProvisioned] and everything the first sync built on it, so the account is left exactly as it
     * was before this service was ever consented: no row, no collections, no stored interval, no
     * applied-defaults marker, and no synced copies on the device.
     *
     * A later [ensureProvisioned] therefore takes the first-grant path — discovery, then [KompaktInitDefaults]
     * writing the selection and the interval — rather than needing anything remembered.
     */
    suspend fun clearProvisioning(account: Account, service: KompaktSyncService) {
        // Cancelled here rather than through the interval, which would re-arm the periodic worker for as
        // long as the row is still there.
        syncWork.cancel(account, service)

        accountRepository.removeService(account.name, service)

        accountSettings.clearSyncInterval(account, service.dataType)
        accountSettings.clearDefaultsApplied(account, service)

        // With the row gone this takes the no-service branch, which is what disables the periodic worker
        // and the content trigger.
        automaticSyncManager.get().updateAutomaticSync(account, service.dataType)
    }

}
