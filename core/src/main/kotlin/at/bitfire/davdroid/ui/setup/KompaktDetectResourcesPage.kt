/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.composable.KompaktFramedIcon
import at.bitfire.davdroid.ui.composable.KompaktTheme
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Kompakt variant of [DetectResourcesPage], shown right after a successful Google authorization while
 * the account is being configured (resource detection).
 *
 * Restyles both the in-progress and the failure states to match the other Kompakt e-ink login screens:
 * a framed Google icon, a status line and either an [CircularProgressIndicatorMMD] (while configuring)
 * or a "Try again" button (on failure). Retry goes back to the login-details step, which re-runs the
 * whole Google sign-in.
 */
@Composable
fun KompaktDetectResourcesPage(
    model: LoginScreenViewModel = viewModel()
) {
    val uiState = model.detectResourcesUiState
    KompaktDetectResourcesPageContent(
        failed = uiState.foundNothing || uiState.loginValidationFailed,
        onRetry = model::navBack
    )
}

@Composable
fun KompaktDetectResourcesPageContent(
    failed: Boolean,
    onRetry: () -> Unit
) {
    if (!failed) {
        // configuring the account
        KompaktSetupProgress()
        return
    }

    // resource detection failed (no service found / login validation failed):
    // show a message and let the user retry the whole sign-in
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KompaktFramedIcon(painter = painterResource(R.drawable.ic_google_g))
            TextMMD(
                text = stringResource(RFrontitude.string.calendar_accountsync_error_h1_couldntsetupyouraccount),
                style = KompaktTypography900.labelMedium,
                textAlign = TextAlign.Center
            )
            ButtonMMD(
                onClick = onRetry,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                TextMMD(
                    text = stringResource(RFrontitude.string.common_dialog_button_tryagain),
                    style = KompaktTypography900.labelMedium
                )
            }
        }
    }
}

/**
 * Centered "Setting up your account…" progress UI (framed Google icon + status line + spinner).
 * Shown while the account is being configured — during resource detection and while the Kompakt
 * login flow waits for collection discovery to finish before navigating on.
 */
@Composable
fun KompaktSetupProgress(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KompaktFramedIcon(painter = painterResource(R.drawable.ic_google_g))
            TextMMD(
                text = stringResource(RFrontitude.string.calendar_accountsync_status_settingupyouraccount),
                style = KompaktTypography900.labelMedium,
                textAlign = TextAlign.Center
            )
            CircularProgressIndicatorMMD(size = 24.dp)
        }
    }
}


@Preview
@Composable
private fun KompaktDetectResourcesPage_Configuring_Preview() {
    KompaktTheme {
        KompaktDetectResourcesPageContent(failed = false, onRetry = {})
    }
}

@Preview
@Composable
private fun KompaktDetectResourcesPage_Failed_Preview() {
    KompaktTheme {
        KompaktDetectResourcesPageContent(failed = true, onRetry = {})
    }
}
