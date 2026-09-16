/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

enum class KompaktOfflineCause { OfflinePlus, NoNetwork }

/**
 * Whether the device can reach the network, and when it cannot, why — watched by everything that has
 * to react to the connection going rather than ask once before starting.
 *
 * Offline+ takes the connection down with it, so both inputs report a problem and only the order they
 * happen to arrive in would decide which one a collector sees. Ranking them here means the cause is
 * reported whichever arrives first, and the symptom only when it is the whole story.
 */
class KompaktConnectivity @Inject constructor(
    private val offlinePlus: KompaktOfflinePlus,
    private val network: KompaktNetworkAvailability
) {

    /** Emits `null` while the network is usable. Both inputs seed, so this answers without waiting for a change. */
    fun observe(): Flow<KompaktOfflineCause?> =
        combine(offlinePlus.observe(), network.observe()) { offlinePlusOn, online ->
            when {
                offlinePlusOn -> KompaktOfflineCause.OfflinePlus
                !online -> KompaktOfflineCause.NoNetwork
                else -> null
            }
        }.distinctUntilChanged()

}
