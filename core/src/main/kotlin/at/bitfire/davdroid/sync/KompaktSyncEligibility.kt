/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import javax.inject.Inject

class KompaktSyncEligibility @Inject constructor(
    private val accountSettings: KompaktAccountSettings,
    private val toggle: KompaktServiceToggle,
    private val serviceRepository: DavServiceRepository
) {

    suspend fun enabledServices(account: Account): List<KompaktSyncService> {
        val authState = accountSettings.getAuthState(account)
        return KompaktSyncService.entries.filter { service ->
            service.isConsented(authState) &&
                toggle.isOn(account, service) &&
                serviceRepository.getByAccountAndType(account.name, service.serviceType) != null
        }
    }

}
