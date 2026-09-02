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
    val showContactsDiscoveryNudge: Boolean = false
)

enum class KompaktLinkedAccountDialog { AuthError, OutOfStorage, NoInternet, SyncFailed }

// Deliberately one-directional: a missing Calendar consent can only come from a partial grant at link
// time, where the user already saw the choice. [nudgeSettled] is what separates such an account from one
// linked before Contacts sync could be granted at all — linking settles it up front.
internal fun contactsDiscoveryNudgeVisible(
    calendar: KompaktSyncSwitch,
    contacts: KompaktSyncSwitch,
    nudgeSettled: Boolean
): Boolean =
    calendar != KompaktSyncSwitch.ConsentMissing &&
        contacts == KompaktSyncSwitch.ConsentMissing &&
        !nudgeSettled

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
