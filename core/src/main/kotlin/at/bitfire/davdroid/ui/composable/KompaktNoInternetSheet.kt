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

@Composable
fun KompaktNoInternetSheet(onDismissRequest: () -> Unit) {
    KompaktMessageSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(RFrontitude.string.common_label_nointernetconnection),
        text = stringResource(RFrontitude.string.common_error_body_opensettingstocheck),
        icon = painterResource(R.drawable.ic_kompakt_alert)
    )
}

@Preview(showBackground = true)
@Composable
private fun KompaktNoInternetSheet_Preview() {
    KompaktNoInternetSheet(onDismissRequest = {})
}
