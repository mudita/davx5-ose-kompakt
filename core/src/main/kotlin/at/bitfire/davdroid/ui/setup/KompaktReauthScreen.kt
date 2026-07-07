/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar
import com.mudita.mmd.components.text.TextMMD

/**
 * Kompakt re-authorization screen for an existing [account] whose token expired. A pure renderer of
 * [KompaktReauthModel.ReauthState]:
 *  - **Authenticating** → the Google OAuth step.
 *  - **SwitchingToNewAccount** → the user signed in with a different account; link it through the normal
 *    [KompaktLoginScreen] pipeline (seeded with the token already obtained, so it skips OAuth), then
 *    remove the old account once the new one is fully set up. [account] is never unlinked on the
 *    same-account path, nor if the user backs out before the new account is linked.
 *  - **RemovingOldAccount** → brief progress while the old account is deleted.
 *  - **Refreshed / Done** → finish.
 */
@Composable
fun KompaktReauthScreen(
    account: Account,
    onNavUp: () -> Unit,
    onFinish: (switched: Boolean) -> Unit,
    model: KompaktReauthModel = hiltViewModel()
) {
    when (val state = model.state.collectAsStateWithLifecycle().value) {
        KompaktReauthModel.ReauthState.Authenticating ->
            ReauthOAuth(
                account = account,
                onNavUp = onNavUp,
                onLogin = { loginInfo -> model.apply(account, loginInfo) }
            )

        is KompaktReauthModel.ReauthState.SwitchingToNewAccount ->
            KompaktLoginScreen(
                // the LoginInfo already carries the OAuth token, so the pipeline starts at detection
                initialLoginInfo = state.loginInfo,
                skipLoginTypePage = true,
                initialLoginType = KompaktGoogleLogin,
                onNavUp = onNavUp,
                onFinish = { newAccount ->
                    if (newAccount != null)
                        model.completeSwitch(account)   // new account linked → remove the old one
                    else
                        onFinish(false)                 // backed out before linking → keep the old account
                }
            )

        KompaktReauthModel.ReauthState.RemovingOldAccount ->
            KompaktTheme {
                KompaktSetupProgress(Modifier.fillMaxSize())
            }

        KompaktReauthModel.ReauthState.Refreshed ->
            LaunchedEffect(Unit) { onFinish(false) }    // same account refreshed in place — nothing linked

        is KompaktReauthModel.ReauthState.Done ->
            // switched only if the old account was actually removed; otherwise finish without success
            LaunchedEffect(Unit) { onFinish(state.switched) }
    }
}

/** The Google OAuth step, deciding whether the same or a different account was authorized. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReauthOAuth(
    account: Account,
    onNavUp: () -> Unit,
    onLogin: (LoginInfo) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    KompaktTheme {
        Scaffold(
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
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                KompaktGoogleLogin.LoginScreen(
                    snackbarHostState = snackbarHostState,
                    // pre-fill the email as the OAuth login hint so the user re-auths the same account
                    initialLoginInfo = LoginInfo(credentials = Credentials(username = account.name)),
                    onLogin = onLogin
                )
            }
        }
    }
}
