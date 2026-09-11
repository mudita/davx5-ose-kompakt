/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.sync.KompaktSyncFailure
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
    /** Synchronize was asked for while no service was switched on, so nothing could start. */
    data object SyncOff : KompaktLinkedAccountDialog
    data object OutOfStorage : KompaktLinkedAccountDialog
    data object NoInternet : KompaktLinkedAccountDialog
    /** Carries what to retry, so Try again re-syncs only the services that actually failed. */
    data class SyncFailed(val retry: Set<KompaktSyncService>) : KompaktLinkedAccountDialog
    /** One service's stored cause, opened by tapping its alert icon. */
    data class ExplainSyncFailure(
        val service: KompaktSyncService,
        val cause: KompaktSyncFailure
    ) : KompaktLinkedAccountDialog
    data object NewContactsConsent : KompaktLinkedAccountDialog
    data class RequestConsent(val service: KompaktSyncService) : KompaktLinkedAccountDialog
    /** Carries the service so the sheet can name it, rather than the screen remembering which was tapped. */
    data class ConfirmDisable(val service: KompaktSyncService) : KompaktLinkedAccountDialog
}

internal fun newContactsConsentVisible(
    contacts: KompaktSyncSwitch,
    alreadyShown: Boolean
): Boolean =
    contacts == KompaktSyncSwitch.ConsentMissing && !alreadyShown

/**
 * The one dialog to show, in precedence order. [confirmDisable] comes last because it is an intent
 * rather than a condition: a persistent problem the user has to deal with outranks a confirmation.
 *
 * [syncOff] outranks both environment dialogs for the same reason `KompaktStartSyncUseCase` answers
 * eligibility before consulting storage and the network: a switched-off account told to check its
 * connection is being answered a question it did not ask.
 */
internal fun linkedAccountDialog(
    authError: Boolean,
    outOfStorage: Boolean,
    noInternet: Boolean,
    syncFailed: Set<KompaktSyncService>?,
    explainSyncFailure: KompaktLinkedAccountDialog.ExplainSyncFailure? = null,
    newContactsConsent: Boolean = false,
    requestConsent: KompaktSyncService? = null,
    confirmDisable: KompaktSyncService? = null,
    syncOff: Boolean = false
): KompaktLinkedAccountDialog? = when {
    authError -> KompaktLinkedAccountDialog.AuthError
    syncOff -> KompaktLinkedAccountDialog.SyncOff
    outOfStorage -> KompaktLinkedAccountDialog.OutOfStorage
    noInternet -> KompaktLinkedAccountDialog.NoInternet
    syncFailed != null -> KompaktLinkedAccountDialog.SyncFailed(syncFailed)
    explainSyncFailure != null -> explainSyncFailure
    requestConsent != null -> KompaktLinkedAccountDialog.RequestConsent(requestConsent)
    newContactsConsent -> KompaktLinkedAccountDialog.NewContactsConsent
    confirmDisable != null -> KompaktLinkedAccountDialog.ConfirmDisable(confirmDisable)
    else -> null
}
