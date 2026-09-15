/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude

/**
 * Says that Offline+ is why an action could not reach the network — raised by both the linked-account
 * screen and the link action, which is why the copy lives here rather than at either call site.
 */
@Composable
fun KompaktOfflinePlusSheet(onDismissRequest: () -> Unit) {
    KompaktMessageSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(RFrontitude.string.common_all_error_h1_youreusingoffline),
        text = stringResource(RFrontitude.string.calendar_accountsync_error_dialog_body_usetheleftsideswitchtoallowaccountsyncronization),
        icon = painterResource(R.drawable.ic_kompakt_alert)
    )
}

@Preview(showBackground = true)
@Composable
private fun KompaktOfflinePlusSheet_Preview() {
    KompaktOfflinePlusSheet(onDismissRequest = {})
}
