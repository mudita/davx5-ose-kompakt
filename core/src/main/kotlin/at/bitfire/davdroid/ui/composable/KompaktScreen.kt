/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import at.bitfire.davdroid.ui.KompaktOfflinePlusHost

/**
 * Root wrapper for every Kompakt screen: applies [KompaktTheme] (styling only) and hosts app-global
 * overlays — currently the Offline+ notice via [KompaktOfflinePlusHost].
 *
 * Use this instead of [KompaktTheme] at the root of a Kompakt screen so that app-global UI (which
 * must appear regardless of which screen the user is on) is added in one place, without the theme
 * having to carry feature logic.
 */
@Composable
fun KompaktScreen(
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit
) {
    KompaktTheme(windowInsets = windowInsets) {
        content()
        KompaktOfflinePlusHost()
    }
}
