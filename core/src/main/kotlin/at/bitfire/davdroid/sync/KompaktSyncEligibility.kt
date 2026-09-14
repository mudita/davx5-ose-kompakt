/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import androidx.annotation.WorkerThread
import at.bitfire.davdroid.settings.KompaktAccountSettings
import javax.inject.Inject

/**
 * What the account has permitted and what the user has chosen — the two facts that decide whether a
 * service may sync at all.
 *
 * Deliberately says nothing about whether the service is *configured*: a missing row or unapplied
 * defaults are things [KompaktStartSyncUseCase] fixes rather than reasons to refuse, so they are that
 * component's business.
 */
class KompaktSyncEligibility @Inject constructor(
    private val accountSettings: KompaktAccountSettings,
    private val toggle: KompaktServiceToggle
) {

    /** **Must not be called on the main thread.** One [KompaktAccountSettings] read for both services. */
    @WorkerThread
    fun consented(account: Account): Set<KompaktSyncService> {
        val authState = accountSettings.getAuthState(account)
        return KompaktSyncService.entries.filterTo(mutableSetOf()) { it.isConsented(authState) }
    }

    /**
     * **Must not be called on the main thread.** A service is off both when the user switched it off
     * and when no default has ever been written, so this alone cannot tell those apart — see
     * [KompaktInitDefaults.isApplied].
     */
    @WorkerThread
    fun switchedOn(account: Account): Set<KompaktSyncService> =
        KompaktSyncService.entries.filterTo(mutableSetOf()) { toggle.isOn(account, it) }

}
