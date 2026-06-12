/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.KompaktLinkedAccountModel.SyncResult
import at.bitfire.davdroid.ui.composable.KompaktBottomBar
import at.bitfire.davdroid.ui.composable.KompaktDottedDivider
import at.bitfire.davdroid.ui.composable.KompaktFramedIcon
import at.bitfire.davdroid.ui.composable.KompaktListCell
import at.bitfire.davdroid.ui.composable.KompaktModalSheet
import at.bitfire.davdroid.ui.composable.KompaktTheme
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/**
 * Stateful entry point for the Kompakt "Linked Account" detail screen: collects the view model state
 * and delegates rendering to the stateless [KompaktLinkedAccountContent].
 */
@Composable
fun KompaktLinkedAccountScreen(
    account: Account,
    onBack: () -> Unit,
    showAccountLinkedDialog: Boolean = false,
    onAccountLinkedDialogDismiss: () -> Unit = {},
    model: KompaktLinkedAccountModel = hiltViewModel(
        creationCallback = { factory: KompaktLinkedAccountModel.Factory ->
            factory.create(account)
        }
    )
) {
    val autoSyncEnabled by model.autoSyncEnabled.collectAsStateWithLifecycle()
    val lastSync by model.lastSyncFormatted.collectAsStateWithLifecycle()
    val syncing by model.syncing.collectAsStateWithLifecycle()
    val syncResult by model.syncResult.collectAsStateWithLifecycle()

    KompaktLinkedAccountContent(
        email = model.email,
        autoSyncEnabled = autoSyncEnabled,
        lastSync = lastSync,
        syncing = syncing,
        syncResult = syncResult,
        showAccountLinkedDialog = showAccountLinkedDialog,
        onBack = onBack,
        onToggleAutoSync = model::setAutoSync,
        onSyncNow = model::syncNow,
        onUnlink = model::unlink,
        onConsumeSyncResult = model::consumeSyncResult,
        onAccountLinkedDialogDismiss = onAccountLinkedDialogDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktLinkedAccountContent(
    email: String,
    autoSyncEnabled: Boolean,
    lastSync: String?,
    syncing: Boolean,
    syncResult: SyncResult?,
    showAccountLinkedDialog: Boolean,
    onBack: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onUnlink: () -> Unit,
    onConsumeSyncResult: () -> Unit,
    onAccountLinkedDialogDismiss: () -> Unit
) {
    var showUnlinkDialog by remember { mutableStateOf(false) }
    var showDisableAutoSyncDialog by remember { mutableStateOf(false) }

    KompaktTheme {
        Scaffold(
            topBar = {
                TopAppBarMMD(
                    title = {
                        TextMMD(
                            text = stringResource(R.string.common_label_linkedaccount),
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
                    },
                    actions = {
                        IconButton(onClick = { showUnlinkDialog = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_kompakt_logout),
                                contentDescription = stringResource(R.string.kompakt_linkedaccount_unlink)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AccountHeader(email = email)

                    // Calendar / Auto synchronization cell with toggle
                    KompaktListCell(
                        icon = painterResource(R.drawable.ic_kompakt_calendar),
                        title = stringResource(R.string.common_label_calendar),
                        subtitle = stringResource(R.string.calendar_accountsync_toggle_button_autosyncronization),
                        trailing = {
                            SwitchMMD(
                                checked = autoSyncEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) onToggleAutoSync(true)
                                    else showDisableAutoSyncDialog = true
                                }
                            )
                        }
                    )

                    // Explanatory text (always visible)
                    TextMMD(
                        text = stringResource(R.string.calendar_accountsync_notification_yourcalendarsyncseach),
                        style = KompaktTypography500.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    // "Or" with dotted dividers
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        KompaktDottedDivider(modifier = Modifier.weight(1f))
                        TextMMD(
                            text = stringResource(R.string.common_label_or),
                            style = KompaktTypography500.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        KompaktDottedDivider(modifier = Modifier.weight(1f))
                    }

                    // Synchronize now
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        OutlinedButtonMMD(
                            onClick = onSyncNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            TextMMD(
                                text = stringResource(R.string.calendar_accountsync_button_synchronizenow),
                                style = KompaktTypography900.labelMedium
                            )
                        }
                    }

                    // Last synchronization cell
                    KompaktDottedDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                    KompaktListCell(
                        icon = painterResource(R.drawable.ic_kompakt_success),
                        title = stringResource(R.string.calendar_accountsync_label_lastsynchronization),
                        subtitle = lastSync ?: stringResource(R.string.kompakt_linkedaccount_never_synced)
                    )
                }

                SyncStatusBar(
                    syncing = syncing,
                    syncResult = syncResult,
                    onDismissSuccess = onConsumeSyncResult,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (showUnlinkDialog) {
        KompaktModalSheet(
            onDismissRequest = { showUnlinkDialog = false },
            title = stringResource(R.string.calendar_accountsync_dialog_h1_unlinkaccount),
            text = stringResource(R.string.calendar_accountsync_dialog_body_thiswillremoveanythingimportedfrom),
            icon = painterResource(R.drawable.ic_kompakt_alert),
            confirmLabel = stringResource(R.string.calendar_accountsync_dialog_button_unlink),
            onConfirm = {
                showUnlinkDialog = false
                onUnlink()
            },
            dismissLabel = stringResource(R.string.common_dialog_button_cancel),
            onDismiss = { showUnlinkDialog = false }
        )
    }

    if (showDisableAutoSyncDialog) {
        KompaktModalSheet(
            onDismissRequest = { showDisableAutoSyncDialog = false },
            title = stringResource(R.string.calendar_accountsync_dialog_h1_disableautosync),
            text = stringResource(R.string.calendar_accountsync_dialog_body_nothingwillsynchronize),
            icon = painterResource(R.drawable.ic_kompakt_alert),
            confirmLabel = stringResource(R.string.calendar_accountsync_dialog_button_disable),
            onConfirm = {
                showDisableAutoSyncDialog = false
                onToggleAutoSync(false)
            },
            dismissLabel = stringResource(R.string.common_dialog_button_cancel),
            onDismiss = { showDisableAutoSyncDialog = false }
        )
    }

    if (showAccountLinkedDialog) {
        KompaktModalSheet(
            onDismissRequest = onAccountLinkedDialogDismiss,
            title = stringResource(R.string.calendar_accountsync_dialog_h1_accountlinked),
            text = stringResource(R.string.calendar_accountsync_dialog_body_csyncnowtoimportyour, email),
            icon = painterResource(R.drawable.ic_kompakt_success),
            confirmLabel = stringResource(R.string.calendar_accountsync_dialog_button_syncnow),
            onConfirm = {
                onAccountLinkedDialogDismiss()
                onSyncNow()
            },
            dismissLabel = stringResource(R.string.common_dialog_button_later),
            onDismiss = onAccountLinkedDialogDismiss
        )
    }

    if (syncResult == SyncResult.Failure) {
        KompaktModalSheet(
            onDismissRequest = onConsumeSyncResult,
            title = stringResource(R.string.calendar_accountsync_error_dialog_h1_accountsyncfailed),
            text = stringResource(R.string.calendar_accountsync_error_dialog_body_wecouldntsyncronizewithyyour),
            icon = painterResource(R.drawable.ic_kompakt_alert),
            confirmLabel = stringResource(R.string.common_dialog_button_tryagain),
            onConfirm = {
                onConsumeSyncResult()
                onSyncNow()
            },
            dismissLabel = stringResource(R.string.common_dialog_button_cancel),
            onDismiss = onConsumeSyncResult
        )
    }
}

/** Google icon + account email header. */
@Composable
private fun AccountHeader(email: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        KompaktFramedIcon(painter = painterResource(R.drawable.ic_google_g))
        TextMMD(
            text = email,
            style = KompaktTypography900.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Bottom status bar: a "Loading data…" indicator while syncing, or a dismissible "Data synchronized"
 * snackbar once a user-initiated sync succeeds. Renders nothing otherwise.
 */
@Composable
private fun SyncStatusBar(
    syncing: Boolean,
    syncResult: SyncResult?,
    onDismissSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        syncing ->
            KompaktBottomBar(
                modifier = modifier,
                horizontalArrangement = Arrangement.Center,
                verticalPadding = 20.dp
            ) {
                CircularProgressIndicatorMMD(size = 24.dp)
                Spacer(modifier = Modifier.width(12.dp))
                TextMMD(
                    text = stringResource(R.string.common_status_loadingdata),
                    style = KompaktTypography900.labelLarge
                )
            }

        syncResult == SyncResult.Success ->
            KompaktBottomBar(modifier = modifier) {
                TextMMD(
                    text = stringResource(R.string.calendar_accountsync_toast_datasynchroniszed),
                    style = KompaktTypography500.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = onDismissSuccess) {
                    Icon(
                        painter = painterResource(R.drawable.ic_kompakt_close),
                        contentDescription = stringResource(R.string.common_dialog_button_cancel),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
    }
}


// previews

private const val PREVIEW_EMAIL = "mike.tyson@gmail.com"
private const val PREVIEW_LAST_SYNC = "14.05.2026 · 11:30"

@Preview
@Composable
private fun KompaktLinkedAccountContent_Idle_Preview() {
    KompaktLinkedAccountContent(
        email = PREVIEW_EMAIL,
        autoSyncEnabled = true,
        lastSync = PREVIEW_LAST_SYNC,
        syncing = false,
        syncResult = null,
        showAccountLinkedDialog = false,
        onBack = {},
        onToggleAutoSync = {},
        onSyncNow = {},
        onUnlink = {},
        onConsumeSyncResult = {},
        onAccountLinkedDialogDismiss = {}
    )
}

@Preview
@Composable
private fun KompaktLinkedAccountContent_Syncing_Preview() {
    KompaktLinkedAccountContent(
        email = PREVIEW_EMAIL,
        autoSyncEnabled = true,
        lastSync = PREVIEW_LAST_SYNC,
        syncing = true,
        syncResult = null,
        showAccountLinkedDialog = false,
        onBack = {},
        onToggleAutoSync = {},
        onSyncNow = {},
        onUnlink = {},
        onConsumeSyncResult = {},
        onAccountLinkedDialogDismiss = {}
    )
}

@Preview
@Composable
private fun KompaktLinkedAccountContent_Success_Preview() {
    KompaktLinkedAccountContent(
        email = PREVIEW_EMAIL,
        autoSyncEnabled = true,
        lastSync = PREVIEW_LAST_SYNC,
        syncing = false,
        syncResult = SyncResult.Success,
        showAccountLinkedDialog = false,
        onBack = {},
        onToggleAutoSync = {},
        onSyncNow = {},
        onUnlink = {},
        onConsumeSyncResult = {},
        onAccountLinkedDialogDismiss = {}
    )
}
