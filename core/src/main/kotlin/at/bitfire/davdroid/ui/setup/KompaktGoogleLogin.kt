/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.composable.KompaktFramedIcon
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD
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

        val authRequestContract = rememberLauncherForActivityResult(model.authorizationContract()) { authResponse ->
            if (authResponse != null)
                model.authenticate(authResponse)
            else
                model.authCodeFailed()
        }

        val launchSignIn = {
            try {
                authRequestContract.launch(model.signIn())
            } catch (e: ActivityNotFoundException) {
                Logger.getGlobal().log(Level.WARNING, "Couldn't start OAuth intent", e)
                model.signInFailed()
            }
        }

        LaunchedEffect(Unit) {
            launchSignIn()
        }

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

                if (uiState.error != null) {
                    // authorization failed (e.g. user cancelled, no browser, token exchange error):
                    // show a message and let the user retry the whole sign-in
                    TextMMD(
                        text = stringResource(RFrontitude.string.calendar_accountsync_error_h1_couldntconnecttogoogle),
                        style = KompaktTypography900.labelMedium,
                        textAlign = TextAlign.Center
                    )
                    ButtonMMD(
                        onClick = {
                            model.clearError()
                            launchSignIn()
                        },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        TextMMD(
                            text = stringResource(RFrontitude.string.common_dialog_button_tryagain),
                            style = KompaktTypography900.labelMedium
                        )
                    }
                } else {
                    TextMMD(
                        text = stringResource(RFrontitude.string.calendar_accountsync_status_connectingtogoogle),
                        style = KompaktTypography900.labelMedium,
                        textAlign = TextAlign.Center
                    )
                    CircularProgressIndicatorMMD(size = 24.dp)
                }
            }
        }
    }

}
