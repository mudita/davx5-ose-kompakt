/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.Context
import java.util.logging.Level
import java.util.logging.Logger

// Mirrors KompaktOS's android.meink.MuditaEinkParameters.RefreshMode: the meink service takes the ordinal,
// so the entry order is the contract. Activity.onResume re-applies the package's configured mode inside
// super.onResume(), so apply() only wins when called after it.
enum class KompaktEinkDisplayMode {
    AUTO,
    FAST,
    FAST_DITHER,
    TEXT,
    QUALITY;

    fun apply(context: Context) {
        val manager = context.getSystemService(MEINK_SERVICE) ?: return
        try {
            manager.javaClass
                .getMethod("setDisplayMode", Context::class.java, Int::class.javaPrimitiveType)
                .invoke(manager, context, ordinal)
        } catch (e: ReflectiveOperationException) {
            Logger.getLogger(javaClass.name).log(Level.WARNING, "Couldn't set e-ink display mode $this", e)
        }
    }

    private companion object {
        const val MEINK_SERVICE = "meink"
    }

}
