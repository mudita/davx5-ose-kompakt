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

enum class KompaktLinkedAccountDialog { AuthError, OutOfStorage, NoInternet, SyncFailed, NewContactsConsent }

// No Calendar guard: linking always sets [alreadyShown] up front (KompaktLoginFinalizeModel does it
// unconditionally), so any account old enough for Calendar to still read ConsentMissing already has
// alreadyShown == true, and !alreadyShown alone already excludes it.
internal fun newContactsConsentVisible(
    contacts: KompaktSyncSwitch,
    alreadyShown: Boolean
): Boolean =
    contacts == KompaktSyncSwitch.ConsentMissing && !alreadyShown

// One field, so at most one sheet renders — newContactsConsent is checked last: it is a dismissible
// offer, not an error, so any of the other four locks it out rather than stacking behind it.
internal fun linkedAccountDialog(
    authError: Boolean,
    outOfStorage: Boolean,
    noInternet: Boolean,
    syncFailed: Boolean,
    newContactsConsent: Boolean
): KompaktLinkedAccountDialog? = when {
    authError -> KompaktLinkedAccountDialog.AuthError
    outOfStorage -> KompaktLinkedAccountDialog.OutOfStorage
    noInternet -> KompaktLinkedAccountDialog.NoInternet
    syncFailed -> KompaktLinkedAccountDialog.SyncFailed
    newContactsConsent -> KompaktLinkedAccountDialog.NewContactsConsent
    else -> null
}
