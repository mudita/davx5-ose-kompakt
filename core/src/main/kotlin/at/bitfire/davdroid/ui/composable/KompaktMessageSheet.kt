/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mudita.frontitude.R as RFrontitude
import at.bitfire.davdroid.ui.KompaktTypography500
import at.bitfire.davdroid.ui.KompaktTypography900
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Bottom-anchored informational sheet, no high-emphasis action: an optional leading icon, a centered
 * title and body, and one way to dismiss. It has two Figma variants, selected by [buttonLabel]:
 *
 * - **[buttonLabel] = null** — a close (X) button in the top-right corner (e.g. the "No internet" message).
 * - **[buttonLabel] != null** — a single full-width outlined button at the bottom and no X (e.g. the
 *   "Your storage is full" dialog, Figma node 14195:20146).
 *
 * Unlike [KompaktModalSheet] there is no filled/confirm action — the button only dismisses.
 *
 * Backed by MMD's [ModalBottomSheetMMD] and wrapped in [ThemeMMD] so colors resolve to the e-ink
 * scheme regardless of caller scope.
 *
 * @param onDismissRequest   called on close-icon tap, swipe-down or tap-outside
 * @param title              centered title ((NEW) Title/Medium/900)
 * @param text               centered body ((NEW) Body/Medium/500)
 * @param icon               optional leading icon shown above the title
 * @param buttonLabel        optional bottom outlined button label ((NEW) Label/Large/900); when set, the
 *                           corner close (X) is omitted
 * @param onButtonClick      bottom button action; defaults to [onDismissRequest]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktMessageSheet(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    icon: Painter? = null,
    buttonLabel: String? = null,
    onButtonClick: () -> Unit = onDismissRequest
) {
    ThemeMMD {
        ModalBottomSheetMMD(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            if (buttonLabel != null) {
                // Bottom-button variant (Figma "(NEW) Dialog"): icon + texts, then a full-width outlined
                // button; no corner X.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    MessageContent(icon, title, text, Modifier.padding(bottom = 2.dp))
                    OutlinedButtonMMD(
                        onClick = onButtonClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp)
                    ) {
                        TextMMD(
                            text = buttonLabel,
                            style = KompaktTypography900.labelLarge
                        )
                    }
                }
            } else {
                // Dismiss-only variant: close (X) in the top-right corner, no bottom button.
                Box(modifier = Modifier.fillMaxWidth()) {
                    MessageContent(
                        icon, title, text,
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 24.dp)
                    )
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_kompakt_close),
                            contentDescription = stringResource(RFrontitude.string.common_dialog_button_cancel),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The shared icon (optional) + centered title + body block. */
@Composable
private fun MessageContent(
    icon: Painter?,
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
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
}


@Preview
@Composable
private fun KompaktMessageSheet_CloseIcon_Preview() {
    KompaktMessageSheet(
        onDismissRequest = {},
        title = stringResource(RFrontitude.string.common_label_nointernetconnection),
        text = stringResource(RFrontitude.string.common_error_body_opensettingstocheck),
        icon = painterResource(R.drawable.ic_kompakt_alert)
    )
}

@Preview
@Composable
private fun KompaktMessageSheet_BottomButton_Preview() {
    KompaktMessageSheet(
        onDismissRequest = {},
        title = stringResource(RFrontitude.string.common_error_dialog_h1_storageisfull),
        text = stringResource(RFrontitude.string.common_error_dialog_body_changestorage),
        icon = painterResource(R.drawable.ic_kompakt_alert),
        buttonLabel = stringResource(RFrontitude.string.common_dialog_button_cancel)
    )
}
