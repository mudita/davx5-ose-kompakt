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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar
import com.mudita.mmd.components.text.TextMMD

/**
 * Kompakt re-authorization screen: runs the Google OAuth flow for an EXISTING [account] and writes the
 * fresh token in place (via [KompaktReauthModel]), preserving the account and its local data. Reuses
 * the styled [KompaktGoogleLogin] OAuth UI (connecting / "couldn't connect" + retry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktReauthScreen(
    account: Account,
    onNavUp: () -> Unit,
    onFinish: () -> Unit,
    model: KompaktReauthModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val done by model.done.collectAsStateWithLifecycle()
    if (done) {
        onFinish()
        return
    }

    KompaktTheme {
        Scaffold(
            topBar = {
                KompaktTopAppBar(
                    title = {
                        TextMMD(
                            text = stringResource(R.string.kompakt_login_title),
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
                    onLogin = { loginInfo -> model.apply(account, loginInfo) }
                )
            }
        }
    }
}
