/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/**
 * Kompakt top app bar: [TopAppBarMMD] with its built-in bottom divider disabled, followed by a
 * correctly-styled 2dp [HorizontalDivider] (the MMD divider doesn't render the bottom border
 * properly on this device). Use this instead of [TopAppBarMMD] across Kompakt screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KompaktTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    showDivider: Boolean = true
) {
    Column {
        TopAppBarMMD(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            showDivider = false
        )
        if (showDivider)
            HorizontalDivider(
                thickness = 3.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
    }
}
