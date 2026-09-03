/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.sync.KompaktSyncService
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

sealed interface KompaktLinkedAccountDialog {
    data object AuthError : KompaktLinkedAccountDialog
    data object OutOfStorage : KompaktLinkedAccountDialog
    data object NoInternet : KompaktLinkedAccountDialog
    data object SyncFailed : KompaktLinkedAccountDialog
    /** Carries the service so the sheet can name it, rather than the screen remembering which was tapped. */
    data class ConfirmDisable(val service: KompaktSyncService) : KompaktLinkedAccountDialog
}

/**
 * The one dialog to show, in precedence order. [confirmDisable] comes last because it is an intent
 * rather than a condition: a persistent problem the user has to deal with outranks a confirmation.
 */
internal fun linkedAccountDialog(
    authError: Boolean,
    outOfStorage: Boolean,
    noInternet: Boolean,
    syncFailed: Boolean,
    confirmDisable: KompaktSyncService?
): KompaktLinkedAccountDialog? = when {
    authError -> KompaktLinkedAccountDialog.AuthError
    outOfStorage -> KompaktLinkedAccountDialog.OutOfStorage
    noInternet -> KompaktLinkedAccountDialog.NoInternet
    syncFailed -> KompaktLinkedAccountDialog.SyncFailed
    confirmDisable != null -> KompaktLinkedAccountDialog.ConfirmDisable(confirmDisable)
    else -> null
}
