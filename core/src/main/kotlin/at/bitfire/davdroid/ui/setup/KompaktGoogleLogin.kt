/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import at.bitfire.davdroid.R
import java.util.logging.Level
import java.util.logging.Logger

object KompaktGoogleLogin : LoginType {

    override val title: Int
        get() = R.string.login_type_google

    override val helpUrl: Uri?
        get() = null

    @Composable
    override fun LoginScreen(
        snackbarHostState: SnackbarHostState,
        initialLoginInfo: LoginInfo,
        onLogin: (LoginInfo) -> Unit
    ) {
        val model: KompaktGoogleLoginViewModel = hiltViewModel(
            creationCallback = { factory: KompaktGoogleLoginViewModel.Factory ->
                factory.create(loginInfo = initialLoginInfo)
            }
        )

        val uiState = model.uiState

        LaunchedEffect(uiState.result) {
            if (uiState.result != null) {
                onLogin(uiState.result)
                model.resetResult()
            }
        }

        LaunchedEffect(uiState.error) {
            if (uiState.error != null)
                snackbarHostState.showSnackbar(uiState.error)
        }

        val authRequestContract = rememberLauncherForActivityResult(model.authorizationContract()) { authResponse ->
            if (authResponse != null)
                model.authenticate(authResponse)
            else
                model.authCodeFailed()
        }

        LaunchedEffect(Unit) {
            try {
                authRequestContract.launch(model.signIn())
            } catch (e: ActivityNotFoundException) {
                Logger.getGlobal().log(Level.WARNING, "Couldn't start OAuth intent", e)
                model.signInFailed()
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            CircularProgressIndicator()
        }
    }

}
