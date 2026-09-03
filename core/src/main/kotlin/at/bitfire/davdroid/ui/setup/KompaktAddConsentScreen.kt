/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.ui.composable.KompaktTheme
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A pure renderer of [KompaktAddConsentModel.AddConsentState] — the screen shown while granting a
 * missing Calendar/Contacts consent for an already-linked [account].
 */
@Composable
fun KompaktAddConsentScreen(
    account: Account,
    serviceType: String,
    onNavUp: () -> Unit,
    onFinish: () -> Unit,
    model: KompaktAddConsentModel = hiltViewModel(
        creationCallback = { factory: KompaktAddConsentModel.Factory ->
            factory.create(account, serviceType)
        }
    )
) {
    when (model.state.collectAsStateWithLifecycle().value) {
        KompaktAddConsentModel.AddConsentState.Authenticating ->
            AddConsentOAuth(model = model, onNavUp = onNavUp)

        KompaktAddConsentModel.AddConsentState.Applying ->
            KompaktTheme {
                KompaktSetupProgress(Modifier.fillMaxSize())
            }

        // Cancelling/declining leaves the toggle off with no error; just return.
        KompaktAddConsentModel.AddConsentState.Denied ->
            LaunchedEffect(Unit) { onFinish() }

        KompaktAddConsentModel.AddConsentState.Granted ->
            LaunchedEffect(Unit) { onFinish() }

        KompaktAddConsentModel.AddConsentState.Failed ->
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
    }
}

/** The Google OAuth step: launches the reduced-scope request and classifies the result. */
@Composable
private fun AddConsentOAuth(
    model: KompaktAddConsentModel,
    onNavUp: () -> Unit
) {
    val authRequestContract = rememberLauncherForActivityResult(model.authorizationContract()) { authResponse ->
        if (authResponse != null)
            model.authenticate(authResponse)
        else
            model.signInFailed()
    }

    LaunchedEffect(Unit) {
        try {
            authRequestContract.launch(model.signIn())
        } catch (e: ActivityNotFoundException) {
            Logger.getGlobal().log(Level.WARNING, "Couldn't start OAuth intent", e)
            model.signInFailed()
        }
    }

    KompaktLinkAccountScaffold(onNavUp = onNavUp) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            KompaktSetupProgress(Modifier.fillMaxSize())
        }
    }
}
