/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import at.bitfire.davdroid.R
import at.bitfire.davdroid.sync.KompaktSyncFailure
import at.bitfire.davdroid.ui.composable.KompaktModalSheet
import at.bitfire.davdroid.ui.composable.KompaktTheme
import com.mudita.frontitude.R as RFrontitude

/**
 * Why one service's last sync failed, opened by tapping its alert icon.
 *
 * Built on `KompaktModalSheet` rather than `KompaktMessageSheet`: this needs a confirm action *and* a
 * way out, and the message sheet drops its close affordance as soon as it is given a button.
 *
 * [KompaktSyncFailure.AuthExpired] is not special-cased here. While the account needs re-authorization
 * the non-cancelable "Account link error" sheet is already up and outranks every other dialog, so no
 * cell is tappable; if the flag has since been cleared by the other service, the token is fine and the
 * generic message is the honest one.
 */
@Composable
fun KompaktSyncFailureSheet(
    cause: KompaktSyncFailure,
    onRetry: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val unreachable = cause == KompaktSyncFailure.NetworkProblem
    KompaktModalSheet(
        onDismissRequest = onDismissRequest,
        icon = painterResource(R.drawable.ic_kompakt_alert),
        title = stringResource(
            if (unreachable) {
                RFrontitude.string.common_label_nointernetconnection
            } else {
                RFrontitude.string.calendar_accountsync_error_dialog_h1_accountsyncfailed
            }
        ),
        text = stringResource(
            if (unreachable) {
                RFrontitude.string.common_error_body_opensettingstocheck
            } else {
                RFrontitude.string.calendar_accountsync_error_dialog_body_wecouldntsyncronizewithyyour
            }
        ),
        confirmLabel = stringResource(RFrontitude.string.common_dialog_button_tryagain),
        onConfirm = onRetry,
        dismissLabel = stringResource(RFrontitude.string.common_dialog_button_cancel),
        onDismiss = onDismissRequest
    )
}

@Preview
@Composable
private fun KompaktSyncFailureSheet_ServerProblem_Preview() {
    KompaktTheme {
        KompaktSyncFailureSheet(KompaktSyncFailure.ServerProblem, {}, {})
    }
}

@Preview
@Composable
private fun KompaktSyncFailureSheet_NetworkProblem_Preview() {
    KompaktTheme {
        KompaktSyncFailureSheet(KompaktSyncFailure.NetworkProblem, {}, {})
    }
}
