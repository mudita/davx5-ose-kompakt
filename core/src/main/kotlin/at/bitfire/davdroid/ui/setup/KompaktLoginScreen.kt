/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.composable.KompaktScreen
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar
import com.mudita.mmd.components.text.TextMMD

/**
 * Kompakt variant of [LoginScreen].
 *
 * Behaves exactly like [LoginScreen] for the login-type, login-details and resource-detection steps,
 * but **skips the [AccountDetailsPage]** (account name + contact group method) entirely. The Kompakt
 * flow always uses the suggested defaults that the upstream view model already prepares before
 * showing that page: the account name is the user's e-mail and the contact group method is the
 * default ([at.bitfire.synctools.vcard.GroupMethod.GROUP_VCARDS]).
 *
 * As soon as the flow reaches [LoginScreenViewModel.Page.AccountDetails] the account is created
 * automatically and [onFinish] is invoked with the new account. The details form is only shown as a
 * fallback if automatic account creation fails, so the user can correct the problem and retry.
 */
@Composable
fun KompaktLoginScreen(
    initialLoginInfo: LoginInfo = LoginInfo(),
    skipLoginTypePage: Boolean = false,
    initialLoginType: LoginType = UrlLogin,
    onNavUp: () -> Unit,
    onFinish: (Account?) -> Unit
) {
    val model: LoginScreenViewModel = hiltViewModel { factory: LoginScreenViewModel.Factory ->
        factory.create(initialLoginType, skipLoginTypePage, initialLoginInfo)
    }

    // handle back/up navigation
    BackHandler {
        model.navBack()
    }
    if (model.finish) {
        onFinish(null)
        return
    }

    val accountState by model.accountDetailsUiState.collectAsStateWithLifecycle()

    // Auto-create the account (with the prepared defaults) the first time we reach the account
    // details step, instead of showing the form.
    var creationTriggered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model.page) {
        if (model.page == LoginScreenViewModel.Page.AccountDetails && !creationTriggered) {
            creationTriggered = true
            model.createAccount()
        }
    }
    // Don't navigate to the Linked Account screen until account setup (collection discovery) is done,
    // so the "Account linked / Sync now" dialog only appears when sync is actually ready.
    val finalizeModel: KompaktLoginFinalizeModel = hiltViewModel()
    val setupReady by finalizeModel.ready.collectAsStateWithLifecycle()
    LaunchedEffect(accountState.createdAccount) {
        accountState.createdAccount?.let { finalizeModel.awaitSetup(it) }
    }
    LaunchedEffect(setupReady, accountState.createdAccount) {
        val account = accountState.createdAccount
        if (account != null && setupReady)
            onFinish(account)
    }

    KompaktLoginScreenContent(
        page = model.page,
        // fall back to the editable details form only if automatic creation failed
        showAccountDetailsForm = accountState.couldNotCreateAccount,
        onNavUp = onNavUp,
        onFinish = onFinish
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun KompaktLoginScreenContent(
    page: LoginScreenViewModel.Page,
    showAccountDetailsForm: Boolean,
    onNavUp: () -> Unit = {},
    onFinish: (newAccount: Account?) -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    KompaktScreen {
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

                when (page) {
                    LoginScreenViewModel.Page.LoginType ->
                        LoginTypePage(snackbarHostState = snackbarHostState)

                    LoginScreenViewModel.Page.LoginDetails ->
                        LoginDetailsPage(snackbarHostState = snackbarHostState)

                    LoginScreenViewModel.Page.DetectResources ->
                        KompaktDetectResourcesPage()

                    LoginScreenViewModel.Page.AccountDetails ->
                        if (showAccountDetailsForm)
                            // automatic creation failed: let the user fix it and retry
                            AccountDetailsPage(
                                snackbarHostState = snackbarHostState,
                                onAccountCreated = { account -> onFinish(account) }
                            )
                        else
                            // creating the account and waiting for setup (collection discovery) to finish
                            KompaktSetupProgress()
                }

            }
        }
    }
}
