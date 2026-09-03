/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.account.KompaktLinkedAccountModel.ReauthPhase
import at.bitfire.davdroid.ui.composable.KompaktFramedIcon
import at.bitfire.davdroid.ui.composable.KompaktMessageSheet
import at.bitfire.davdroid.ui.composable.KompaktModalSheet
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar
import at.bitfire.davdroid.ui.setup.KompaktLoginActivity
import com.mudita.frontitude.R as RFrontitude
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD

data class KompaktLinkedAccountActions(
    val onBack: () -> Unit = {},
    val onToggleService: (KompaktSyncService, Boolean) -> Unit = { _, _ -> },
    val onSyncNow: () -> Unit = {},
    val onUnlink: () -> Unit = {},
    val onConsumeDialog: () -> Unit = {},
    val onFailureClick: () -> Unit = {},
    val onAccountLinkedDialogDismiss: () -> Unit = {},
    val onReauthorize: () -> Unit = {}
)

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
    onAccountSwitched: (oldAccountName: String) -> Unit = {},
    initialReauth: Boolean = false,
    model: KompaktLinkedAccountModel = hiltViewModel(
        // Key by account so switching the linked account (unlink A → link B) builds a fresh
        // ViewModel instead of reusing the cached one for the previous account (SHP-571).
        key = account.name,
        creationCallback = { factory: KompaktLinkedAccountModel.Factory ->
            factory.create(account, initialReauth)
        }
    )
) {
    val state by model.state.collectAsStateWithLifecycle()

    // Free storage has no change notification, so re-check it whenever the screen comes to the
    // foreground. The re-auth flag is observed instead.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        model.refreshStorageState()
    }

    // re-authorize the existing account in place (refresh OAuth token, keeping all local data)
    val context = LocalContext.current
    val reauthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // re-read the persisted flag: cleared if re-auth succeeded, still set if it was aborted/failed
        model.onReauthResult()
        // RESULT_OK from the re-auth flow means a different account was linked (a switch) — surface the
        // "Account linked" dialog, just like the normal add-account flow
        if (result.resultCode == Activity.RESULT_OK)
            // pass the re-auth target — the stable old account this screen owns — instead of letting
            // the caller read it from the live accounts flow (which could already report the new one)
            onAccountSwitched(account.name)
    }
    val onReauthorize = {
        reauthLauncher.launch(
            Intent(context, KompaktLoginActivity::class.java)
                .putExtra(KompaktLoginActivity.EXTRA_REAUTH_ACCOUNT_NAME, account.name)
        )
    }

    LaunchedEffect(state.reauthPhase) {
        if (state.reauthPhase == ReauthPhase.PENDING_LAUNCH) {
            model.onReauthLaunchStarted()
            onReauthorize()
        }
    }

    if (state.reauthPhase == ReauthPhase.SHOW_CONTENT) {
        KompaktLinkedAccountContent(
            state = state,
            actions = KompaktLinkedAccountActions(
                onBack = onBack,
                onToggleService = model::setServiceSync,
                onSyncNow = model::syncNow,
                onUnlink = model::unlink,
                onConsumeDialog = model::consumeDialog,
                onFailureClick = model::consumeDialog,
                onAccountLinkedDialogDismiss = onAccountLinkedDialogDismiss,
                onReauthorize = onReauthorize
            ),
            showAccountLinkedDialog = showAccountLinkedDialog
        )
    } else {
        KompaktTheme {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktLinkedAccountContent(
    state: KompaktLinkedAccountState,
    actions: KompaktLinkedAccountActions,
    showAccountLinkedDialog: Boolean
) {
    var showUnlinkDialog by remember { mutableStateOf(false) }
    var serviceToDisable by remember { mutableStateOf<KompaktSyncService?>(null) }

    KompaktTheme {
        Scaffold(
            topBar = {
                KompaktTopAppBar(
                    title = {
                        TextMMD(
                            text = stringResource(RFrontitude.string.common_label_linkedaccount),
                            style = KompaktTypography900.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
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
                                contentDescription = stringResource(RFrontitude.string.calendar_accountsync_dialog_h1_removeaccount)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                // Withheld too, or it would enqueue a sync for services whose stored state is unread.
                if (!state.isLoading)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        OutlinedButtonMMD(
                            onClick = actions.onSyncNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            TextMMD(
                                text = stringResource(RFrontitude.string.common_button_synchronize),
                                style = KompaktTypography900.labelMedium
                            )
                        }
                    }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                AccountHeader(email = state.email)

                // Revealed together, so the list repaints once instead of per row as each settles.
                if (!state.isLoading) {
                    KompaktServiceSyncCell(
                        title = stringResource(RFrontitude.string.common_label_calendar),
                        state = state.calendar,
                        onCheckedChange = { enabled ->
                            if (enabled) actions.onToggleService(KompaktSyncService.CALENDAR, true)
                            else serviceToDisable = KompaktSyncService.CALENDAR
                        },
                        onFailureClick = actions.onFailureClick,
                        showDivider = true
                    )

                    KompaktServiceSyncCell(
                        title = stringResource(RFrontitude.string.common_label_contacts),
                        state = state.contacts,
                        onCheckedChange = { enabled ->
                            if (enabled) actions.onToggleService(KompaktSyncService.CONTACTS, true)
                            else serviceToDisable = KompaktSyncService.CONTACTS
                        },
                        onFailureClick = actions.onFailureClick
                    )
                }
            }
        }
    }

    if (showUnlinkDialog) {
        KompaktModalSheet(
            onDismissRequest = { showUnlinkDialog = false },
            title = stringResource(RFrontitude.string.calendar_accountsync_dialog_h1_removeaccount),
            text = stringResource(RFrontitude.string.calendar_accountsync_dialog_body_youwontseedatafromyourgoogle),
            icon = painterResource(R.drawable.ic_kompakt_alert),
            confirmLabel = stringResource(RFrontitude.string.calendar_accountsync_error_dialog_button_removeaccount),
            onConfirm = {
                showUnlinkDialog = false
                actions.onUnlink()
            },
            dismissLabel = stringResource(RFrontitude.string.common_dialog_button_cancel),
            onDismiss = { showUnlinkDialog = false }
        )
    }

    serviceToDisable?.let { service ->
        KompaktModalSheet(
            onDismissRequest = { serviceToDisable = null },
            // TODO Contacts reuses the calendar title, which names the wrong service. Needs a
            //  contacts or service-neutral key from Frontitude (SHP-1156).
            title = stringResource(RFrontitude.string.calendar_accountsync_dialog_h1_disablecalendarsync),
            text = stringResource(RFrontitude.string.calendar_accountsync_dialog_body_nothingwillsynchronizewithyour),
            icon = painterResource(R.drawable.ic_kompakt_alert),
            confirmLabel = stringResource(RFrontitude.string.common_button_disable),
            onConfirm = {
                serviceToDisable = null
                actions.onToggleService(service, false)
            },
            dismissLabel = stringResource(RFrontitude.string.common_dialog_button_cancel),
            onDismiss = { serviceToDisable = null }
        )
    }

    if (showAccountLinkedDialog) {
        KompaktModalSheet(
            onDismissRequest = actions.onAccountLinkedDialogDismiss,
            title = stringResource(RFrontitude.string.calendar_accountsync_dialog_h1_accountlinked),
            text = stringResource(RFrontitude.string.calendar_accountsync_dialog_body_synchronizenowtoimportyourselected),
            icon = painterResource(R.drawable.ic_kompakt_success),
            confirmLabel = stringResource(RFrontitude.string.calendar_accountsync_dialog_button_syncnow),
            onConfirm = {
                actions.onAccountLinkedDialogDismiss()
                actions.onSyncNow()
            },
            dismissLabel = stringResource(RFrontitude.string.common_dialog_button_later),
            onDismiss = actions.onAccountLinkedDialogDismiss
        )
    }

    when (state.dialog) {
        KompaktLinkedAccountDialog.AuthError ->
            // token expired / access revoked: persistent until re-auth succeeds. Offer re-linking
            // (in place, keeping local data), or unlink and go back to the home screen.
            KompaktModalSheet(
                onDismissRequest = {}, // unreachable by design — all dismiss paths locked
                title = stringResource(RFrontitude.string.calendar_accountsync_error_dialog_h1_accountlinkerror),
                text = stringResource(RFrontitude.string.calendar_accountsync_error_dialog_body_linkyouraccountagaintocontinue),
                icon = painterResource(R.drawable.ic_kompakt_alert),
                confirmLabel = stringResource(RFrontitude.string.calendar_accountsync_dialog_button_linkaccount),
                onConfirm = actions.onReauthorize,   // flag stays set; cleared on successful re-auth + reload
                dismissLabel = stringResource(RFrontitude.string.calendar_accountsync_error_dialog_button_removeaccount),
                shouldDismissOnBackPress = false,
                shouldDismissOnClickOutside = false,
                onDismiss = actions.onUnlink
            )

        KompaktLinkedAccountDialog.OutOfStorage ->
            KompaktMessageSheet(
                onDismissRequest = actions.onConsumeDialog,
                title = stringResource(RFrontitude.string.common_error_dialog_h1_storageisfull),
                text = stringResource(RFrontitude.string.common_error_dialog_body_changestorage),
                icon = painterResource(R.drawable.ic_kompakt_alert),
                buttonLabel = stringResource(RFrontitude.string.common_dialog_button_cancel)
            )

        KompaktLinkedAccountDialog.NoInternet ->
            KompaktMessageSheet(
                onDismissRequest = actions.onConsumeDialog,
                title = stringResource(RFrontitude.string.common_label_nointernetconnection),
                text = stringResource(RFrontitude.string.common_error_body_opensettingstocheck),
                icon = painterResource(R.drawable.ic_kompakt_alert)
            )

        KompaktLinkedAccountDialog.SyncFailed ->
            KompaktModalSheet(
                onDismissRequest = actions.onConsumeDialog,
                title = stringResource(RFrontitude.string.calendar_accountsync_error_dialog_h1_accountsyncfailed),
                text = stringResource(RFrontitude.string.calendar_accountsync_error_dialog_body_wecouldntsyncronizewithyyour),
                icon = painterResource(R.drawable.ic_kompakt_alert),
                confirmLabel = stringResource(RFrontitude.string.common_dialog_button_tryagain),
                onConfirm = {
                    actions.onConsumeDialog()
                    actions.onSyncNow()
                },
                dismissLabel = stringResource(RFrontitude.string.common_dialog_button_cancel),
                onDismiss = actions.onConsumeDialog
            )

        null -> {}
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
            .padding(top = 24.dp, bottom = 32.dp)
            .padding(horizontal = 16.dp)
    ) {
        KompaktFramedIcon(painter = painterResource(R.drawable.ic_google_g))
        TextMMD(
            text = email,
            style = KompaktTypography900.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


// previews

private const val PREVIEW_EMAIL = "very.long.mike.tyson@gmail.very.long.com"
private const val PREVIEW_LAST_SYNC = "Today 11:30"

private fun previewState(
    calendar: KompaktServiceSyncState,
    contacts: KompaktServiceSyncState
) = KompaktLinkedAccountState(email = PREVIEW_EMAIL, calendar = calendar, contacts = contacts)

private fun on(status: KompaktSyncStatus) = KompaktServiceSyncState(KompaktSyncSwitch.On, status)

@Preview
@Composable
private fun KompaktLinkedAccountContent_BothSynced_Preview() {
    KompaktLinkedAccountContent(
        state = previewState(
            on(KompaktSyncStatus.Synced(PREVIEW_LAST_SYNC)),
            on(KompaktSyncStatus.Synced(PREVIEW_LAST_SYNC))
        ),
        actions = KompaktLinkedAccountActions(),
        showAccountLinkedDialog = false
    )
}

@Preview
@Composable
private fun KompaktLinkedAccountContent_BothNeverSynced_Preview() {
    KompaktLinkedAccountContent(
        state = previewState(on(KompaktSyncStatus.NeverSynced), on(KompaktSyncStatus.NeverSynced)),
        actions = KompaktLinkedAccountActions(),
        showAccountLinkedDialog = false
    )
}

@Preview
@Composable
private fun KompaktLinkedAccountContent_CalendarOnAndContactsConsentMissing_Preview() {
    KompaktLinkedAccountContent(
        state = previewState(
            on(KompaktSyncStatus.Synced(PREVIEW_LAST_SYNC)),
            KompaktServiceSyncState(KompaktSyncSwitch.ConsentMissing, KompaktSyncStatus.NeverSynced)
        ),
        actions = KompaktLinkedAccountActions(),
        showAccountLinkedDialog = false
    )
}

@Preview
@Composable
private fun KompaktLinkedAccountContent_MixedSyncingAndSynced_Preview() {
    KompaktLinkedAccountContent(
        state = previewState(
            on(KompaktSyncStatus.Syncing),
            on(KompaktSyncStatus.Synced(PREVIEW_LAST_SYNC))
        ),
        actions = KompaktLinkedAccountActions(),
        showAccountLinkedDialog = false
    )
}

@Preview
@Composable
private fun KompaktLinkedAccountContent_FailedAndNeverSynced_Preview() {
    KompaktLinkedAccountContent(
        state = previewState(
            on(KompaktSyncStatus.Failed("26.10.2025 11:00")),
            on(KompaktSyncStatus.NeverSynced)
        ),
        actions = KompaktLinkedAccountActions(),
        showAccountLinkedDialog = false
    )
}

// Renders the header alone: that is the loading face, not an empty screen.
@Preview
@Composable
private fun KompaktLinkedAccountContent_Loading_Preview() {
    KompaktLinkedAccountContent(
        state = previewState(
            on(KompaktSyncStatus.Synced(PREVIEW_LAST_SYNC)),
            KompaktServiceSyncState(KompaktSyncSwitch.Resolving, KompaktSyncStatus.Resolving)
        ),
        actions = KompaktLinkedAccountActions(),
        showAccountLinkedDialog = false
    )
}
