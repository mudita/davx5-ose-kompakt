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
    val reauthPhase: ReauthPhase = ReauthPhase.SHOW_CONTENT,
    val showNewContactsConsent: Boolean = false
) {

    val isLoading: Boolean
        get() = calendar.isLoading || contacts.isLoading

}

enum class KompaktLinkedAccountDialog { AuthError, OutOfStorage, NoInternet, SyncFailed }

// Deliberately one-directional: a missing Calendar consent can only come from a partial grant at link
// time, where the user already saw the choice. [alreadyShown] is what separates such an account from one
// linked before Contacts sync could be granted at all — linking sets it up front.
internal fun newContactsConsentVisible(
    calendar: KompaktSyncSwitch,
    contacts: KompaktSyncSwitch,
    alreadyShown: Boolean
): Boolean =
    calendar != KompaktSyncSwitch.Resolving &&
        calendar != KompaktSyncSwitch.ConsentMissing &&
        contacts == KompaktSyncSwitch.ConsentMissing &&
        !alreadyShown

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
