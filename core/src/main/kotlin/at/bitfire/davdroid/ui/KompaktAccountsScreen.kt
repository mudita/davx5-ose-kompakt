/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude
import at.bitfire.davdroid.ui.account.KompaktLinkedAccountScreen
import at.bitfire.davdroid.ui.composable.KompaktFramedIcon
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.setup.KompaktLoginActivity
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar

/**
 * Kompakt account entry screen.
 *
 * The Kompakt flow allows only a single linked account, so this screen either shows the empty
 * "Link account" state (no account) or the [KompaktLinkedAccountScreen] detail view (one account).
 */
@Composable
fun KompaktAccountsScreen(
    initialReauth: Boolean = false,
    onBack: () -> Unit,
    onboarding: Boolean = false,
    onSkip: () -> Unit = onBack,
    model: AccountsViewModel = hiltViewModel(
        // Never true: upstream's init syncs every authority, ignoring the per-service toggles.
        creationCallback = { factory: AccountsViewModel.Factory ->
            factory.create(false)
        }
    )
) {
    val context = LocalContext.current
    val accounts by model.accountInfos.collectAsStateWithLifecycle(initialValue = null)
    val account = accounts?.firstOrNull()?.name

    // Set when the Kompakt login flow returns successfully, so the "Account linked" modal is shown
    // once on top of the linked-account detail screen. Survives the recomposition that happens while
    // the accounts flow catches up with the newly created account.
    var justLinked by rememberSaveable { mutableStateOf(false) }

    // The account we just switched away from during a re-auth. Its removal has already completed, but the
    // accounts flow can still report it for a frame or two; keep showing the loading state until it
    // disappears, so the just-removed account never flashes under the "Account linked" dialog (SHP-555).
    var switchedFromAccount by rememberSaveable { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        justLinked = false
        switchedFromAccount = null
    }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            justLinked = true
    }
    val onAddAccount = {
        loginLauncher.launch(Intent(context, KompaktLoginActivity::class.java))
    }

    when {
        // Still loading, or a link/switch just succeeded and the accounts flow hasn't caught up yet:
        //  - add-from-empty → wait for the new account to appear
        //  - switch → wait for the just-removed account to disappear
        accounts == null
            || (justLinked && account == null)
            || (switchedFromAccount != null && account?.name == switchedFromAccount) ->
            KompaktTheme {
                Box(modifier = Modifier.fillMaxSize())
            }

        account == null ->
            KompaktLinkAccountScreen(
                onAddAccount = onAddAccount,
                onBack = onBack,
                onboarding = onboarding,
                onSkip = onSkip
            )

        else ->
            KompaktLinkedAccountScreen(
                account = account,
                onBack = onBack,
                showAccountLinkedDialog = justLinked,
                initialReauth = initialReauth,
                onAccountLinkedDialogDismiss = {
                    justLinked = false
                    switchedFromAccount = null
                },
                // a re-auth that switched to a different account linked a new one → show "Account linked";
                // remember the account being replaced so we don't render it while the flow still reports it
                onAccountSwitched = { oldAccountName ->
                    justLinked = true
                    switchedFromAccount = oldAccountName
                }
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KompaktLinkAccountScreen(
    onAddAccount: () -> Unit,
    onBack: () -> Unit,
    onboarding: Boolean = false,
    onSkip: () -> Unit = onBack
) {
    KompaktTheme {
        Scaffold(
            topBar = {
                if (onboarding)
                    // Onboarding mode: no title, back arrow or bottom divider — just a "Skip" action.
                    KompaktTopAppBar(
                        title = {},
                        showDivider = false,
                        actions = {
                            OutlinedButtonMMD(
                                onClick = onSkip,
                                // 12dp + the 4dp the top bar already adds = 16dp from the screen edge.
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                TextMMD(
                                    text = stringResource(RFrontitude.string.common_button_skip),
                                    style = KompaktTypography900.labelMedium
                                )
                            }
                        }
                    )
                else
                    KompaktTopAppBar(
                        title = {
                            TextMMD(
                                text = stringResource(RFrontitude.string.common_label_linkedaccount),
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
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    KompaktFramedIcon(
                        painter = painterResource(R.drawable.ic_google_g),
                        boxSize = 64.dp,
                        cornerRadius = 13.dp,
                        iconSize = 37.dp
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextMMD(
                            text = stringResource(RFrontitude.string.calendar_accountsync_dialog_h1_linkagoogleaccount),
                            style = KompaktTypography900.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextMMD(
                            text = stringResource(RFrontitude.string.settings_twowaygoogle_body_synchronizeyourcalendarandcontacts),
                            style = KompaktTypography500.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    ButtonMMD(
                        onClick = onAddAccount,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        TextMMD(
                            text = stringResource(RFrontitude.string.calendar_accountsync_dialog_button_linkaccount),
                            style = KompaktTypography900.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun KompaktLinkAccountScreen_Preview() {
    KompaktLinkAccountScreen(onAddAccount = {}, onBack = {})
}

@Preview(showBackground = true)
@Composable
private fun KompaktLinkAccountScreen_Onboarding_Preview() {
    KompaktLinkAccountScreen(onAddAccount = {}, onBack = {}, onboarding = true, onSkip = {})
}
