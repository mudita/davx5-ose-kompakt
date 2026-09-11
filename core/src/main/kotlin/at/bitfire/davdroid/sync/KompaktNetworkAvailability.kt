/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.WorkerThread
import androidx.core.content.getSystemService
import at.bitfire.davdroid.settings.AccountSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * Whether the network is usable, asked once before starting a sync or watched while one runs.
 *
 * The two answers are deliberately not the same predicate. [isAvailable] is upstream's check, which
 * additionally honours the account's ignore-VPNs setting but reads [AccountSettings] and may throw;
 * [observe] must stay cheap and safe enough to run on every connectivity callback, so it only tracks
 * validated networks. A VPN-only connection is therefore refused by [isAvailable] while [observe] still
 * reports online — accepted, because widening the watch would mean an AccountManager read per callback.
 */
class KompaktNetworkAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val syncConditionsFactory: SyncConditions.Factory
) {

    /** **Must not be called on the main thread**, because [AccountSettings.Factory] must not be. */
    @WorkerThread
    fun isAvailable(account: Account): Boolean =
        syncConditionsFactory.create(accountSettingsFactory.create(account)).internetAvailable()

    fun observe(): Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        val callback = object: ConnectivityManager.NetworkCallback() {
            val networks = hashSetOf<Network>()
            override fun onAvailable(network: Network) { networks += network; trySend(networks.isNotEmpty()) }
            override fun onLost(network: Network) { networks -= network; trySend(networks.isNotEmpty()) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        // Seeded first, because registerNetworkCallback reports onAvailable only for networks that
        // already match and onLost only for ones it matched itself: with nothing connected it reports
        // nothing at all, and a collector waiting for `false` would wait for the life of the flow. Any
        // callback that follows supersedes this value.
        trySend(connectivityManager.hasValidatedNetwork())
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    // The default network only, so a matching non-default one reads as offline until its onAvailable
    // arrives -- which the caller's grace period absorbs.
    private fun ConnectivityManager.hasValidatedNetwork(): Boolean =
        activeNetwork?.let(::getNetworkCapabilities)?.let { capabilities ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

}
