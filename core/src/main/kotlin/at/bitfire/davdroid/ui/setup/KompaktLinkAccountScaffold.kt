/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar
import com.mudita.mmd.components.text.TextMMD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktLinkAccountScaffold(
    onNavUp: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    KompaktTheme {
        Scaffold(
            modifier = modifier,
            topBar = {
                KompaktTopAppBar(
                    title = {
                        TextMMD(
                            text = stringResource(RFrontitude.string.calendar_accountsync_dialog_button_linkaccount),
                            style = KompaktTypography900.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavUp) {
                            Icon(
                                painter = painterResource(R.drawable.ic_kompakt_arrow_left),
                                contentDescription = stringResource(R.string.navigate_up)
                            )
                        }
                    }
                )
            },
            snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
            content = content
        )
    }
}
