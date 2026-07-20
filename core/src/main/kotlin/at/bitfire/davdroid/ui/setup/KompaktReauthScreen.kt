/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.ui.composable.KompaktTheme

/**
 * Kompakt re-authorization screen for an existing [account] whose token expired. A pure renderer of
 * [KompaktReauthModel.ReauthState]:
 *  - **Authenticating** → the Google OAuth step.
 *  - **SwitchingToNewAccount** → the user signed in with a different account; link it through the normal
 *    [KompaktLoginScreen] pipeline (seeded with the token already obtained, so it skips OAuth), then
 *    remove the old account once the new one is fully set up. [account] is never unlinked on the
 *    same-account path, nor if the user backs out before the new account is linked.
 *  - **RemovingOldAccount** → brief progress while the old account is deleted.
 *  - **Failed** → the re-authorization didn't grant the Calendar scope; show the "Couldn't set up your
 *    account" error with a "Try again" that restarts the OAuth step.
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

        KompaktReauthModel.ReauthState.Failed ->
            KompaktLinkAccountScaffold(onNavUp = onNavUp) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                ) {
                    KompaktDetectResourcesPageContent(
                        failed = true,
                        onRetry = model::retry
                    )
                }
            }

        KompaktReauthModel.ReauthState.Refreshed ->
            LaunchedEffect(Unit) { onFinish(false) }    // same account refreshed in place — nothing linked

        is KompaktReauthModel.ReauthState.Done ->
            // switched only if the old account was actually removed; otherwise finish without success
            LaunchedEffect(Unit) { onFinish(state.switched) }
    }
}

/** The Google OAuth step, deciding whether the same or a different account was authorized. */
@Composable
private fun ReauthOAuth(
    account: Account,
    onNavUp: () -> Unit,
    onLogin: (LoginInfo) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    KompaktLinkAccountScaffold(
        onNavUp = onNavUp,
        snackbarHostState = snackbarHostState
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
