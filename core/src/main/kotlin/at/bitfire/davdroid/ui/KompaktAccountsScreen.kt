/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.account.AccountProgress
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.composable.ProgressBar

@Composable
fun KompaktAccountsScreen(
    initialSyncAccounts: Boolean,
    accountsDrawerHandler: AccountsDrawerHandler,
    onAddAccount: () -> Unit,
    onShowAccount: (Account) -> Unit,
    model: AccountsViewModel = hiltViewModel(
        creationCallback = { factory: AccountsViewModel.Factory ->
            factory.create(initialSyncAccounts)
        }
    )
) {
    val accounts by model.accountInfos.collectAsStateWithLifecycle(emptyList())
    val snackbarHostState = remember { SnackbarHostState() }

    KompaktTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                if (accounts.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                    ) {
                        ButtonMMD(onClick = onAddAccount) {
                            TextMMD(stringResource(R.string.calendar_accountsync_dialog_button_linkaccount))
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        for ((account, progress) in accounts)
                            KompaktAccountCard(
                                account = account,
                                progress = progress,
                                onClick = { onShowAccount(account) }
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun KompaktAccountCard(
    account: Account,
    progress: AccountProgress,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column {
            val progressAlpha = progress.rememberAlpha()
            when (progress) {
                AccountProgress.Active ->
                    ProgressBar(
                        modifier = Modifier
                            .alpha(progressAlpha)
                            .fillMaxWidth()
                    )
                AccountProgress.Pending,
                AccountProgress.Idle ->
                    ProgressBar(
                        progress = { 1f },
                        modifier = Modifier
                            .alpha(progressAlpha)
                            .fillMaxWidth()
                    )
            }

            Column(Modifier.padding(vertical = 12.dp)) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(48.dp)
                )
                TextMMD(
                    text = account.name,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}
