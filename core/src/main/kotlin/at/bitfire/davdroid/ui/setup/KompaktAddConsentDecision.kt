/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import at.bitfire.davdroid.network.KompaktGrantedServices
import net.openid.appauth.AuthState

/**
 * Classifies the result of requesting a missing Calendar/Contacts consent for an already-linked
 * [target account][classifyAddConsentResult]. Unlike [KompaktReauthModel]'s equivalent
 * classification, a different Google account authorizing here is never a switch — the additional
 * permission must land on the same account, so it's folded into [AddConsentOutcome.AccountMismatch]
 * instead.
 *
 * Two checks beyond the account match guard against `include_granted_scopes` not behaving as
 * expected: [requestedType] must actually be in the returned grant (a non-empty grant that's missing
 * the one scope this flow asked for is not success), and the grant must still cover every
 * [previouslyGranted] service (if Google doesn't echo the union back, AppAuth falls back to the
 * *requested* scope alone, which would otherwise read as the other service's consent being revoked).
 */
sealed interface AddConsentOutcome {
    data class Granted(val grantedServices: Set<String>) : AddConsentOutcome
    data object AccountMismatch : AddConsentOutcome
    data object NotGranted : AddConsentOutcome
}

fun classifyAddConsentResult(
    targetAccountName: String,
    requestedType: String,
    previouslyGranted: Set<String>,
    grantedEmail: String?,
    authState: AuthState
): AddConsentOutcome {
    if (grantedEmail != null && !grantedEmail.equals(targetAccountName, ignoreCase = true))
        return AddConsentOutcome.AccountMismatch

    val granted = KompaktGrantedServices.fromAuthState(authState)
    if (requestedType !in granted || !granted.containsAll(previouslyGranted))
        return AddConsentOutcome.NotGranted

    return AddConsentOutcome.Granted(granted)
}
