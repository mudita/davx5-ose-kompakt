/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp

/**
 * A horizontal dotted divider matching the Figma "DividerSeparatorHorizontal" (dotted) style.
 * MMD only provides a solid [com.mudita.mmd.components.divider.HorizontalDividerMMD].
 */
@Composable
fun KompaktDottedDivider(modifier: Modifier = Modifier) {
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
