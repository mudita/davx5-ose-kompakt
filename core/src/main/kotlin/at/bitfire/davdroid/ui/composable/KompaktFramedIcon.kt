/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An icon centered inside a rounded 2dp accent border box — the framed "Google icon" used on the
 * Kompakt link / linked-account screens.
 */
@Composable
fun KompaktFramedIcon(
    painter: Painter,
    modifier: Modifier = Modifier,
    boxSize: Dp = 48.dp,
    cornerRadius: Dp = 10.dp,
    iconSize: Dp = 28.dp,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(boxSize)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.onBackground,
                shape = RoundedCornerShape(cornerRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize)
        )
    }
}
