/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.bitfire.davdroid.ui.KompaktTypography500
import at.bitfire.davdroid.ui.KompaktTypography900
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * A single action (label + click handler) used by [KompaktDialog].
 */
data class KompaktDialogAction(
    val label: String,
    val onClick: () -> Unit
)

/**
 * Reusable Mudita Kompakt dialog, anchored to the bottom of the screen.
 *
 * Matches the Figma "(NEW) Dialog" component: an optional leading icon, a centered title and
 * message, a high-emphasis (filled) confirm button and an optional outlined dismiss button.
 *
 * The content is wrapped in [KompaktTheme], so the MMD buttons pick up the e-ink color scheme
 * automatically — [ButtonMMD] renders filled (accent background) and [OutlinedButtonMMD] outlined.
 *
 * @param onDismissRequest   called when the dialog is dismissed (tap outside / back button)
 * @param title              centered title text ((NEW) Title/Medium/900)
 * @param text               centered body text ((NEW) Body/Medium/500)
 * @param confirm            the high-emphasis (filled) action
 * @param dismiss            optional secondary (outlined) action; usually "Cancel"
 * @param icon               optional leading icon shown above the title
 */
@Composable
fun KompaktDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirm: KompaktDialogAction,
    modifier: Modifier = Modifier,
    dismiss: KompaktDialogAction? = null,
    icon: Painter? = null
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        KompaktTheme {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // 3dp accent top border (Figma "Inner divider")
                    HorizontalDivider(
                        thickness = 3.dp,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 12.dp)
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
                            // high-emphasis filled confirm button (accent background, inverted text)
                            ButtonMMD(
                                onClick = confirm.onClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp)
                            ) {
                                TextMMD(
                                    text = confirm.label,
                                    style = KompaktTypography900.labelLarge
                                )
                            }

                            // outlined dismiss button
                            if (dismiss != null)
                                OutlinedButtonMMD(
                                    onClick = dismiss.onClick,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 24.dp)
                                ) {
                                    TextMMD(
                                        text = dismiss.label,
                                        style = KompaktTypography900.labelLarge
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}
