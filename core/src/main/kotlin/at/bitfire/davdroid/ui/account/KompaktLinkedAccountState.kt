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
    data object ImportServiceNow : KompaktLinkedAccountDialog
    data object NewContactsConsent : KompaktLinkedAccountDialog
    data class RequestConsent(val service: KompaktSyncService) : KompaktLinkedAccountDialog
    /** Carries the service so the sheet can name it, rather than the screen remembering which was tapped. */
    data class ConfirmDisable(val service: KompaktSyncService) : KompaktLinkedAccountDialog
    data object ConfirmUnlink : KompaktLinkedAccountDialog
}

internal fun newContactsConsentVisible(
    contacts: KompaktSyncSwitch,
    alreadyShown: Boolean
): Boolean =
    contacts == KompaktSyncSwitch.ConsentMissing && !alreadyShown

/**
 * The one dialog to show, in precedence order. [confirmDisable] and [confirmUnlink] come last because
 * they are intents rather than conditions: a persistent problem the user has to deal with outranks a
 * confirmation.
 */
internal fun linkedAccountDialog(
    authError: Boolean,
    outOfStorage: Boolean,
    noInternet: Boolean,
    syncFailed: Boolean,
    newContactsConsent: Boolean = false,
    requestConsent: KompaktSyncService? = null,
    confirmDisable: KompaktSyncService? = null,
    importServiceNow: Boolean = false,
    confirmUnlink: Boolean = false
): KompaktLinkedAccountDialog? = when {
    authError -> KompaktLinkedAccountDialog.AuthError
    outOfStorage -> KompaktLinkedAccountDialog.OutOfStorage
    noInternet -> KompaktLinkedAccountDialog.NoInternet
    syncFailed -> KompaktLinkedAccountDialog.SyncFailed
    importServiceNow -> KompaktLinkedAccountDialog.ImportServiceNow
    requestConsent != null -> KompaktLinkedAccountDialog.RequestConsent(requestConsent)
    newContactsConsent -> KompaktLinkedAccountDialog.NewContactsConsent
    confirmDisable != null -> KompaktLinkedAccountDialog.ConfirmDisable(confirmDisable)
    confirmUnlink -> KompaktLinkedAccountDialog.ConfirmUnlink
    else -> null
}
