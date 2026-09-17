/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude

@Composable
fun KompaktOfflinePlusLinkingSheet(onDismissRequest: () -> Unit) {
    KompaktOfflinePlusSheet(
        body = RFrontitude.string.calendar_accountsync_error_dialog_body_usetheleftsideswitchtoallowaccountlinking,
        onDismissRequest = onDismissRequest
    )
}

@Composable
fun KompaktOfflinePlusSyncSheet(onDismissRequest: () -> Unit) {
    KompaktOfflinePlusSheet(
        body = RFrontitude.string.calendar_accountsync_error_dialog_body_usetheleftsideswitchtoallowaccountsyncronization,
        onDismissRequest = onDismissRequest
    )
}

@Composable
private fun KompaktOfflinePlusSheet(@StringRes body: Int, onDismissRequest: () -> Unit) {
    KompaktMessageSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(RFrontitude.string.common_all_error_h1_youreusingoffline),
        text = stringResource(body),
        icon = painterResource(R.drawable.ic_kompakt_alert)
    )
}

@Preview(showBackground = true)
@Composable
private fun KompaktOfflinePlusLinkingSheet_Preview() {
    KompaktOfflinePlusLinkingSheet(onDismissRequest = {})
}

@Preview(showBackground = true)
@Composable
private fun KompaktOfflinePlusSyncSheet_Preview() {
    KompaktOfflinePlusSyncSheet(onDismissRequest = {})
}
