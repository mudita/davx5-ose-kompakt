/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.composable.KompaktFramedIcon
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar
import com.mudita.mmd.components.text.TextMMD

/**
 * Hosts the Offline+ overlay for a Kompakt screen: keeps [KompaktOfflinePlusState] in sync with the
 * hardware switch and, while Offline+ is on, draws [KompaktOfflinePlusOverlay] on top of the screen.
 *
 * Placed once at the Kompakt composition root by `KompaktScreen`, so every screen is covered without
 * the theme having to know about this feature. The hardware listener is skipped in `@Preview`.
 */
@Composable
fun KompaktOfflinePlusHost() {
    if (!LocalInspectionMode.current)
        ObserveOfflinePlusHardware()

    val enabled by KompaktOfflinePlusState.enabled.collectAsStateWithLifecycle()
    if (enabled) {
        val activity = LocalActivity.current
        KompaktOfflinePlusOverlay(
            onBack = {
                (activity as? OnBackPressedDispatcherOwner)?.onBackPressedDispatcher?.onBackPressed()
            }
        )
    }
}

/**
 * Full-screen overlay shown on top of any Kompakt screen while the hardware Offline+ switch is on.
 * Tells the user that account synchronization is blocked and how to re-enable it.
 *
 * @param onBack invoked when the user taps the back arrow (normal up/back navigation)
 */
@Composable
fun KompaktOfflinePlusOverlay(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            KompaktTopAppBar(
                title = {
                    TextMMD(
                        text = stringResource(R.string.common_label_linkedaccount),
                        style = KompaktTypography900.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_kompakt_arrow_left),
                            contentDescription = stringResource(R.string.navigate_up)
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                KompaktFramedIcon(
                    painter = painterResource(R.drawable.ic_kompakt_offline_plus),
                    boxSize = 64.dp,
                    cornerRadius = 18.dp,
                    iconSize = 37.dp
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(
                        text = stringResource(R.string.common_all_error_h1_youreusingoffline),
                        style = KompaktTypography900.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextMMD(
                        text = stringResource(R.string.calendar_accountsync_error_dialog_body_usetheleftsideswitchtoallowaccountsyncronization),
                        style = KompaktTypography500.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun KompaktOfflinePlusOverlay_Preview() {
    KompaktTheme {
        KompaktOfflinePlusOverlay(onBack = {})
    }
}
