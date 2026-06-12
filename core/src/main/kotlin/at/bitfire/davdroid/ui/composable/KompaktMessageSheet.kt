/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.KompaktTypography500
import at.bitfire.davdroid.ui.KompaktTypography900
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Bottom-anchored informational sheet no-action variant: an optional leading icon, a centered title
 * nd body, and a close (X) button in the top-right corner. Unlike [KompaktModalSheet] there are no
 * action buttons — the sheet is dismiss-only (X tap / swipe-down).
 *
 * Backed by MMD's [ModalBottomSheetMMD] and wrapped in [ThemeMMD] so colors resolve to the e-ink
 * scheme regardless of caller scope.
 *
 * @param onDismissRequest   called on close-icon tap, swipe-down or tap-outside
 * @param title              centered title
 * @param text               centered body
 * @param icon               optional leading icon shown above the title
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktMessageSheet(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    icon: Painter? = null
) {
    ThemeMMD {
        ModalBottomSheetMMD(
            onDismissRequest = onDismissRequest,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 24.dp)
                ) {
                    if (icon != null)
                        Icon(
                            painter = icon,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextMMD(
                            text = title,
                            style = KompaktTypography900.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextMMD(
                            text = text,
                            style = KompaktTypography500.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_kompakt_close),
                        contentDescription = stringResource(R.string.common_dialog_button_cancel),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}


@Preview
@Composable
private fun KompaktMessageSheet_Preview() {
    KompaktMessageSheet(
        onDismissRequest = {},
        title = stringResource(R.string.common_label_nointernetconnection),
        text = stringResource(R.string.common_error_body_opensettingstocheck),
        icon = painterResource(R.drawable.ic_kompakt_alert)
    )
}
