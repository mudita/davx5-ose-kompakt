/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bitfire.davdroid.ui.KompaktTypography500
import at.bitfire.davdroid.ui.KompaktTypography900
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Bottom-anchored modal matching the Figma "(NEW) Dialog": an optional leading icon, a centered title
 * and body, a high-emphasis (filled) confirm button and an optional outlined dismiss button.
 *
 * Backed by MMD's [ModalBottomSheetMMD] (square top, transparent scrim, default 3dp drag handle). The
 * content is wrapped in [ThemeMMD] so colors resolve to the e-ink scheme regardless of caller scope.
 *
 * @param onDismissRequest   called on swipe-down / tap-outside
 * @param title              centered title ((NEW) Title/Medium/900)
 * @param text               centered body ((NEW) Body/Medium/500)
 * @param confirmLabel       label of the high-emphasis (filled) action
 * @param onConfirm          confirm action
 * @param icon               optional leading icon shown above the title
 * @param dismissLabel       optional label of the secondary (outlined) action; usually "Cancel"
 * @param onDismiss          optional secondary action; required for [dismissLabel] to render
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktModalSheet(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    icon: Painter? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null
) {
    ThemeMMD {
        ModalBottomSheetMMD(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            ) {
                // icon + texts
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
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

                // buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ButtonMMD(
                        onClick = onConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp)
                    ) {
                        TextMMD(
                            text = confirmLabel,
                            style = KompaktTypography900.labelLarge
                        )
                    }

                    if (dismissLabel != null && onDismiss != null)
                        OutlinedButtonMMD(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp)
                        ) {
                            TextMMD(
                                text = dismissLabel,
                                style = KompaktTypography900.labelLarge
                            )
                        }
                }
            }
        }
    }
}
