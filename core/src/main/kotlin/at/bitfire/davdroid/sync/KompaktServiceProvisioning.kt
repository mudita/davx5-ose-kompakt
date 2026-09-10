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
    private val oAuthGoogle: KompaktOAuthGoogle
) {

    /** `false` if the row is absent and could not be discovered, so the caller must leave it alone. */
    suspend fun ensureRow(account: Account, service: KompaktSyncService): Boolean {
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

}
