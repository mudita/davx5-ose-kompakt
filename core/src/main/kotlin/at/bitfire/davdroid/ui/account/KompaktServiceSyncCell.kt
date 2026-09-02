/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.composable.KompaktListCell
import com.mudita.frontitude.R as RFrontitude
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.switcher.SwitchMMD

@Composable
fun KompaktServiceSyncCell(
    title: String,
    state: KompaktServiceSyncState,
    onCheckedChange: (Boolean) -> Unit,
    onFailureClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false
) {
    val syncOn = state.switch == KompaktSyncSwitch.On
    // Not SwitchMMD's `enabled`, which also greys the control: while resolving it should look normal
    // and simply not act.
    val acceptsInput = state.switch != KompaktSyncSwitch.Resolving
    KompaktListCell(
        title = title,
        subtitle =
            if (syncOn) statusText(state.status)
            else stringResource(RFrontitude.string.calendar_accountsync_status_syncisoff),
        modifier = modifier,
        leading = {
            if (syncOn)
                StatusIcon(status = state.status, onFailureClick = onFailureClick)
            else
                Icon(
                    painter = painterResource(R.drawable.ic_kompakt_sync_off),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
        },
        trailing = {
            SwitchMMD(checked = syncOn, onCheckedChange = { if (acceptsInput) onCheckedChange(it) })
        },
        showDivider = showDivider
    )
}

@Composable
private fun statusText(status: KompaktSyncStatus): String = when (status) {
    KompaktSyncStatus.Resolving -> ""

    KompaktSyncStatus.NeverSynced ->
        stringResource(RFrontitude.string.calendar_accountsync_status_notsyncedyet)

    KompaktSyncStatus.Syncing -> stringResource(RFrontitude.string.common_status_synchronizing)

    is KompaktSyncStatus.Synced ->
        stringResource(RFrontitude.string.calendar_accountsync_status_lastsync, status.lastSync)

    is KompaktSyncStatus.Failed ->
        status.lastSync
            ?.let { stringResource(RFrontitude.string.calendar_accountsync_status_lastsync, it) }
            ?: stringResource(RFrontitude.string.calendar_accountsync_status_notsyncedyet)
}

@Composable
private fun StatusIcon(status: KompaktSyncStatus, onFailureClick: () -> Unit) {
    when (status) {
        KompaktSyncStatus.Resolving -> Box(modifier = Modifier.size(28.dp))

        KompaktSyncStatus.Syncing -> CircularProgressIndicatorMMD(size = 24.dp)

        KompaktSyncStatus.NeverSynced ->
            Icon(
                painter = painterResource(R.drawable.ic_kompakt_alert),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )

        is KompaktSyncStatus.Synced ->
            Icon(
                painter = painterResource(R.drawable.ic_kompakt_success),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )

        is KompaktSyncStatus.Failed ->
            Icon(
                painter = painterResource(R.drawable.ic_kompakt_alert),
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onFailureClick)
            )
    }
}


// previews

private const val CELL_PREVIEW_LAST_SYNC = "Today 11:30"

private fun cellPreviewState(switch: KompaktSyncSwitch, status: KompaktSyncStatus) =
    KompaktServiceSyncState(switch, status)

@Preview
@Composable
private fun KompaktServiceSyncCell_Off_Preview() {
    // carries a Synced status underneath: a switched-off row hides a status it still holds
    KompaktServiceSyncCell(
        "Calendar",
        cellPreviewState(KompaktSyncSwitch.Off, KompaktSyncStatus.Synced(CELL_PREVIEW_LAST_SYNC)),
        {}, {}
    )
}

@Preview
@Composable
private fun KompaktServiceSyncCell_ConsentMissing_Preview() {
    KompaktServiceSyncCell(
        "Contacts",
        cellPreviewState(KompaktSyncSwitch.ConsentMissing, KompaktSyncStatus.NeverSynced),
        {}, {}
    )
}

@Preview
@Composable
private fun KompaktServiceSyncCell_Resolving_Preview() {
    KompaktServiceSyncCell(
        "Calendar",
        cellPreviewState(KompaktSyncSwitch.On, KompaktSyncStatus.Resolving),
        {}, {}
    )
}

@Preview
@Composable
private fun KompaktServiceSyncCell_NeverSynced_Preview() {
    KompaktServiceSyncCell(
        "Calendar",
        cellPreviewState(KompaktSyncSwitch.On, KompaktSyncStatus.NeverSynced),
        {}, {}
    )
}

@Preview
@Composable
private fun KompaktServiceSyncCell_Syncing_Preview() {
    KompaktServiceSyncCell(
        "Calendar",
        cellPreviewState(KompaktSyncSwitch.On, KompaktSyncStatus.Syncing),
        {}, {}
    )
}

@Preview
@Composable
private fun KompaktServiceSyncCell_Synced_Preview() {
    KompaktServiceSyncCell(
        "Calendar",
        cellPreviewState(KompaktSyncSwitch.On, KompaktSyncStatus.Synced(CELL_PREVIEW_LAST_SYNC)),
        {}, {}
    )
}

@Preview
@Composable
private fun KompaktServiceSyncCell_Failed_Preview() {
    KompaktServiceSyncCell(
        "Calendar",
        cellPreviewState(KompaktSyncSwitch.On, KompaktSyncStatus.Failed("26.10.2025 11:00")),
        {}, {}
    )
}
