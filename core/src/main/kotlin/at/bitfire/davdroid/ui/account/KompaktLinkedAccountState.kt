/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.ui.account.KompaktLinkedAccountModel.ReauthPhase

data class KompaktLinkedAccountState(
    val email: String,
    val calendar: KompaktServiceSyncState,
    val contacts: KompaktServiceSyncState,
    val dialog: KompaktLinkedAccountDialog? = null,
    val reauthPhase: ReauthPhase = ReauthPhase.SHOW_CONTENT
) {

    val isLoading: Boolean
        get() = calendar.isLoading || contacts.isLoading

}

enum class KompaktLinkedAccountDialog { AuthError, OutOfStorage, NoInternet, SyncFailed }

internal fun linkedAccountDialog(
    authError: Boolean,
    outOfStorage: Boolean,
    noInternet: Boolean,
    syncFailed: Boolean
): KompaktLinkedAccountDialog? = when {
    authError -> KompaktLinkedAccountDialog.AuthError
    outOfStorage -> KompaktLinkedAccountDialog.OutOfStorage
    noInternet -> KompaktLinkedAccountDialog.NoInternet
    syncFailed -> KompaktLinkedAccountDialog.SyncFailed
    else -> null
}
