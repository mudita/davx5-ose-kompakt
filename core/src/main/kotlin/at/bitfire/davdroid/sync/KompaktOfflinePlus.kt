/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.ContentResolver
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.util.broadcastReceiverFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/**
 * The Offline+ switch, a piece of Kompakt hardware that disconnects the device for as long as it is on.
 *
 * Both the broadcasts and the setting are KompaktOS's own, so on any other hardware nothing arrives and
 * the setting is absent — which reads as Offline+ off rather than as an error.
 */
object KompaktOfflinePlusSwitch {

    const val ACTION_ON = "android.intent.action.ACTION_HWSWITCH_LOCKED"
    const val ACTION_OFF = "android.intent.action.ACTION_HWSWITCH_UNLOCKED"

    /** The KompaktOS setting holding the switch position. */
    const val STATE_KEY = "HWSwitch_lock"

    /** `0` means Offline+ is off and anything else that it is on; an absent key also reads as off. */
    @WorkerThread
    fun isOn(resolver: ContentResolver): Boolean =
        try {
            Settings.Global.getInt(resolver, STATE_KEY, 0) != 0
        } catch (e: Exception) {
            // Answering "off" is what keeps this usable off a Kompakt, but it is also the bug this
            // feature exists to fix, so a failure that is not simply absent hardware must be visible.
            Logger.getGlobal().log(Level.WARNING, "Couldn't read $STATE_KEY; assuming Offline+ is off", e)
            false
        }

    /** `null` for an action that is neither, so an unrelated broadcast cannot be read as a state. */
    fun stateOf(action: String?): Boolean? =
        when (action) {
            ACTION_ON -> true
            ACTION_OFF -> false
            else -> null
        }

}

/**
 * Whether Offline+ is on, asked once before starting a sync or watched while one runs — the same pair
 * of questions, and the same two answers, as [KompaktNetworkAvailability].
 *
 * Offline+ takes the whole connection down, so it is always accompanied by the network going away. It
 * is asked separately because it is the *reason*: a user who turned Offline+ on is told that, rather
 * than being asked to check a connection they turned off themselves.
 */
class KompaktOfflinePlus @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /** **Must not be called on the main thread**: it reads a setting through a [ContentResolver]. */
    @WorkerThread
    fun isOn(): Boolean = KompaktOfflinePlusSwitch.isOn(context.contentResolver)

    /**
     * Seeded before it listens, because the broadcasts only report a *change*: a collector that started
     * with the switch already down would otherwise wait for the user to lift it to learn anything. The
     * seed is also what lets a caller combine this with another flow without stalling on it.
     */
    fun observe(): Flow<Boolean> {
        val filter = IntentFilter().apply {
            addAction(KompaktOfflinePlusSwitch.ACTION_ON)
            addAction(KompaktOfflinePlusSwitch.ACTION_OFF)
        }
        return broadcastReceiverFlow(
            context = context,
            filter = filter,
            flags = ContextCompat.RECEIVER_EXPORTED,
            immediate = false
        )
            .mapNotNull { intent -> KompaktOfflinePlusSwitch.stateOf(intent.action) }
            .onStart { emit(KompaktOfflinePlusSwitch.isOn(context.contentResolver)) }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

}
