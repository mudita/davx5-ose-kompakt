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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.composable.KompaktFramedIcon
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.setup.KompaktLoginActivity
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/**
 * Kompakt account entry screen.
 *
 * The Kompakt flow allows only a single linked account, so this screen either shows the empty
 * "Link account" state (no account) or the [KompaktLinkedAccountScreen] detail view (one account).
 */
@Composable
fun KompaktAccountsScreen(
    initialSyncAccounts: Boolean,
    accountsDrawerHandler: AccountsDrawerHandler,
    onBack: () -> Unit,
    model: AccountsViewModel = hiltViewModel(
        creationCallback = { factory: AccountsViewModel.Factory ->
            factory.create(initialSyncAccounts)
        }
    )
) {
    val context = LocalContext.current
    val accounts by model.accountInfos.collectAsStateWithLifecycle(emptyList())
    val account = accounts.firstOrNull()?.name

    // Set when the Kompakt login flow returns successfully, so the "Account linked" modal is shown
    // once on top of the linked-account detail screen. Survives the recomposition that happens while
    // the accounts flow catches up with the newly created account.
    var justLinked by rememberSaveable { mutableStateOf(false) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            justLinked = true
    }
    val onAddAccount = {
        loginLauncher.launch(Intent(context, KompaktLoginActivity::class.java))
    }

    if (account == null)
        KompaktLinkAccountScreen(onAddAccount = onAddAccount)
    else
        KompaktLinkedAccountScreen(
            account = account,
            onBack = onBack,
            showAccountLinkedDialog = justLinked,
            onAccountLinkedDialogDismiss = { justLinked = false }
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KompaktLinkAccountScreen(
    onAddAccount: () -> Unit
) {
    KompaktTheme {
        Scaffold(
            topBar = {
                TopAppBarMMD(
                    title = {
                        TextMMD(
                            text = stringResource(R.string.common_label_linkedaccount),
                            style = KompaktTypography900.titleMedium
                        )
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
                            text = stringResource(R.string.calendar_accountsync_dialog_h1_linkagoogleaccount),
                            style = KompaktTypography900.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextMMD(
                            text = stringResource(R.string.calendar_accountsync_body_connectwithyourmaingoogle),
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
                            text = stringResource(R.string.calendar_accountsync_dialog_button_linkaccount),
                            style = KompaktTypography900.titleMedium
                        )
                    }
                }
            }
        }
    }
}
