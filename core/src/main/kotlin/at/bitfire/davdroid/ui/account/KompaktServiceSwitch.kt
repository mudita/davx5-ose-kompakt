/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktServiceToggle
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.KompaktSyncWork
import at.bitfire.davdroid.sync.isConsented
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

class KompaktServiceSwitch @Inject constructor(
    private val accountSettings: KompaktAccountSettings,
    private val toggle: KompaktServiceToggle,
    private val syncWork: KompaktSyncWork
) {

    fun read(account: Account, service: KompaktSyncService): KompaktSyncSwitch =
        kompaktSyncSwitch(
            consented = service.isConsented(accountSettings.getAuthState(account)),
            on = toggle.isOn(account, service)
        )

    fun observe(account: Account, service: KompaktSyncService): Flow<KompaktSyncSwitch> =
        combine(
            accountSettings.observeAuthState(account),
            toggle.observe(account, service)
        ) { authState, on ->
            kompaktSyncSwitch(consented = service.isConsented(authState), on = on)
        }.distinctUntilChanged()

    // Persist before cancelling: the persisted write is what disables the periodic worker and the
    // content trigger, so cancelling first leaves a window for the scheduler to restart the run.
    //
    // The defaults marker is deliberately not written here. It records that collection selection ran,
    // and claiming that from a toggle would stop selection ever happening; the stored interval this
    // writes is already the only record of the user's choice, and KompaktInitDefaults respects it.
    suspend fun setEnabled(account: Account, service: KompaktSyncService, on: Boolean) {
        toggle.set(account, service, on)
        if (!on)
            syncWork.cancel(account, service)
    }

}
