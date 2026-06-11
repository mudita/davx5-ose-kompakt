/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.composable.KompaktDialog
import at.bitfire.davdroid.ui.composable.KompaktDialogAction
import at.bitfire.davdroid.ui.composable.KompaktTheme
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktLinkedAccountScreen(
    account: Account,
    onBack: () -> Unit,
    model: KompaktLinkedAccountModel = hiltViewModel(
        creationCallback = { factory: KompaktLinkedAccountModel.Factory ->
            factory.create(account)
        }
    )
) {
    val autoSyncEnabled by model.autoSyncEnabled.collectAsStateWithLifecycle()
    val lastSync by model.lastSyncFormatted.collectAsStateWithLifecycle()
    var showUnlinkDialog by remember { mutableStateOf(false) }

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Google icon + account email
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.onBackground,
                                shape = RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_google_g),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    TextMMD(
                        text = model.email,
                        style = KompaktTypography900.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Calendar / Auto synchronization cell with toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_kompakt_calendar),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        TextMMD(
                            text = stringResource(R.string.common_label_calendar),
                            style = KompaktTypography900.labelSmall
                        )
                        TextMMD(
                            text = stringResource(R.string.calendar_accountsync_toggle_button_autosyncronization),
                            style = KompaktTypography500.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    SwitchMMD(
                        checked = autoSyncEnabled,
                        onCheckedChange = model::setAutoSync
                    )
                }

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
                        onClick = model::syncNow,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_kompakt_success),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        TextMMD(
                            text = stringResource(R.string.calendar_accountsync_label_lastsynchronization),
                            style = KompaktTypography900.labelSmall
                        )
                        TextMMD(
                            text = lastSync ?: stringResource(R.string.kompakt_linkedaccount_never_synced),
                            style = KompaktTypography500.bodyMedium
                        )
                    }
                }
            }
        }
    }

    if (showUnlinkDialog) {
        KompaktDialog(
            onDismissRequest = { showUnlinkDialog = false },
            title = stringResource(R.string.kompakt_linkedaccount_unlink_dialog_title),
            text = stringResource(R.string.kompakt_linkedaccount_unlink_dialog_message),
            icon = painterResource(R.drawable.ic_kompakt_alert),
            confirm = KompaktDialogAction(
                label = stringResource(R.string.kompakt_linkedaccount_unlink_dialog_confirm),
                onClick = {
                    showUnlinkDialog = false
                    model.unlink()
                }
            ),
            dismiss = KompaktDialogAction(
                label = stringResource(R.string.kompakt_linkedaccount_unlink_dialog_cancel),
                onClick = { showUnlinkDialog = false }
            )
        )
    }
}

/**
 * A horizontal dotted divider matching the Figma "DividerSeparatorHorizontal" (dotted) style.
 * MMD only provides a solid [com.mudita.mmd.components.divider.HorizontalDividerMMD].
 */
@Composable
private fun KompaktDottedDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onBackground
    Canvas(modifier = modifier.height(2.dp)) {
        val stroke = 2.dp.toPx()
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()), 0f)
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = stroke,
            pathEffect = dashEffect
        )
    }
}
