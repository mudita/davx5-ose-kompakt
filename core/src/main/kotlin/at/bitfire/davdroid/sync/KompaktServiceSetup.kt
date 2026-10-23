/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Brings each row of a service to a syncable state: its collections fetched, then its defaults applied.
 *
 * Follows the row only while the stored token grants the service: re-authorization and added consent
 * write the row before they store the token, and a fetch on the old token comes back empty.
 */
class KompaktServiceSetup @Inject constructor(
    private val serviceRepository: DavServiceRepository,
    private val accountSettings: KompaktAccountSettings,
    private val collectionDiscovery: KompaktCollectionDiscovery,
    private val initDefaults: KompaktInitDefaults,
    private val logger: Logger
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun keepSetUp(account: Account, service: KompaktSyncService) {
        accountSettings.observeAuthState(account)
            .map { service.isConsented(it) }
            .distinctUntilChanged()
            .flatMapLatest { granted ->
                if (granted)
                    serviceRepository.getServiceFlow(account.name, service.serviceType).map { it?.id }
                else
                    flowOf(null)
            }
            .distinctUntilChanged()
            .collectLatest { rowId ->
                // Let through rather than filtered out: a null is what cancels a setup still running for
                // a grant just withdrawn or a row just removed.
                rowId ?: return@collectLatest
                try {
                    collectionDiscovery.ensure(account, service)
                    initDefaults.ensureApplied(account, service)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Couldn't set up $service for $account", e)
                }
            }
    }

}
