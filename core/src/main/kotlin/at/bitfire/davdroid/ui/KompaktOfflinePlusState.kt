/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import at.bitfire.davdroid.util.broadcastReceiverFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger

/**
 * Tracks whether the Mudita Kompakt hardware "Offline+" switch is enabled.
 *
 * Offline+ blocks account synchronization; while it is on, every Kompakt screen shows a full-screen
 * overlay (see [KompaktOfflinePlusOverlay]) regardless of where the user currently is.
 *
 * State comes from two Kompakt-specific sources:
 * - the system broadcasts [ACTION_HWSWITCH_LOCKED] (on) / [ACTION_HWSWITCH_UNLOCKED] (off), and
 * - the initial value read from [IRQKEY_STATE_PATH] (`0` = off, anything else = on).
 *
 * On non-Kompakt devices the broadcasts never arrive and the sysfs file does not exist; both cases
 * are treated as "Offline+ off" without raising an error.
 *
 * Lightweight process-wide singleton, mirroring [ForegroundTracker].
 */
object KompaktOfflinePlusState {

    const val ACTION_HWSWITCH_LOCKED = "android.intent.action.ACTION_HWSWITCH_LOCKED"
    const val ACTION_HWSWITCH_UNLOCKED = "android.intent.action.ACTION_HWSWITCH_UNLOCKED"

    const val IRQKEY_STATE_PATH = "/sys/bus/platform/drivers/pmic-codec-accdet/irqkey_state"

    private val logger = Logger.getGlobal()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun update(enabled: Boolean) {
        _enabled.value = enabled
    }

    /**
     * Reads the current Offline+ state from [IRQKEY_STATE_PATH].
     *
     * @return `true` if Offline+ is enabled; `false` if it is disabled, the file is missing
     * (non-Kompakt device) or cannot be read.
     */
    fun readInitialState(): Boolean =
        try {
            val raw = File(IRQKEY_STATE_PATH).readText().trim()
            raw.isNotEmpty() && raw != "0"
        } catch (e: Exception) {
            // file absent (other phones) or not readable -> treat as Offline+ off, no error
            logger.fine("Offline+ irqkey_state unavailable: ${e.message}")
            false
        }

}

/**
 * Keeps [KompaktOfflinePlusState] in sync with the hardware switch while a Kompakt screen is in the
 * foreground.
 *
 * Driven by [repeatOnLifecycle]`(STARTED)`: every time the screen (re)enters the foreground it
 * re-reads the sysfs value **and** registers the broadcast receiver; when it leaves the foreground the
 * receiver is unregistered. Re-reading on every resume recovers the correct state even if a switch
 * toggle's broadcast was missed while we were not listening (e.g. the app was backgrounded).
 */
@Composable
fun ObserveOfflinePlusHardware() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val filter = IntentFilter().apply {
                addAction(KompaktOfflinePlusState.ACTION_HWSWITCH_LOCKED)
                addAction(KompaktOfflinePlusState.ACTION_HWSWITCH_UNLOCKED)
            }
            broadcastReceiverFlow(
                context = context,
                filter = filter,
                flags = ContextCompat.RECEIVER_EXPORTED,
                immediate = false
            )
                .onStart {
                    // re-read the hardware state on entering the foreground, before listening, in case
                    // a toggle's broadcast was missed while we weren't registered
                    val initial = withContext(Dispatchers.IO) { KompaktOfflinePlusState.readInitialState() }
                    KompaktOfflinePlusState.update(initial)
                }
                .collect { intent ->
                    when (intent.action) {
                        KompaktOfflinePlusState.ACTION_HWSWITCH_LOCKED -> KompaktOfflinePlusState.update(true)
                        KompaktOfflinePlusState.ACTION_HWSWITCH_UNLOCKED -> KompaktOfflinePlusState.update(false)
                    }
                }
        }
    }
}
