/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

// Separates "the source hasn't emitted yet" from whatever value it eventually carries, which a
// nullable or a default value cannot express.
internal sealed interface Reported<out T> {
    data object Pending : Reported<Nothing>
    data class Value<out T>(val value: T) : Reported<T>
}

enum class KompaktSyncSwitch { On, Off, ConsentMissing }

sealed interface KompaktSyncStatus {
    data object Resolving : KompaktSyncStatus
    data object NeverSynced : KompaktSyncStatus
    data object Syncing : KompaktSyncStatus
    data class Synced(val lastSync: String) : KompaktSyncStatus
    data class Failed(val lastSync: String?) : KompaktSyncStatus
}

data class KompaktServiceSyncState(
    val switch: KompaktSyncSwitch,
    val status: KompaktSyncStatus
)

// Consent alone would report On for a scope granted during re-auth, where no service, no discovery and
// no interval follow — so the interval, not the scope, decides between On and Off.
internal fun syncSwitch(consentGranted: Boolean, autoSyncEnabled: Boolean): KompaktSyncSwitch = when {
    !consentGranted -> KompaktSyncSwitch.ConsentMissing
    autoSyncEnabled -> KompaktSyncSwitch.On
    else -> KompaktSyncSwitch.Off
}

internal fun serviceSyncState(
    switch: KompaktSyncSwitch,
    syncing: Reported<Boolean>,
    lastSync: Reported<String?>,
    failed: Boolean
) = KompaktServiceSyncState(
    switch = switch,
    status = syncStatus(syncing, lastSync, failed)
)

// The status is deliberately independent of the switch, so a switched-off service keeps the last-sync
// time it earned; the cell decides not to show it.
private fun syncStatus(
    syncing: Reported<Boolean>,
    lastSync: Reported<String?>,
    failed: Boolean
): KompaktSyncStatus {
    if (syncing !is Reported.Value || lastSync !is Reported.Value) return KompaktSyncStatus.Resolving
    if (syncing.value) return KompaktSyncStatus.Syncing
    if (failed) return KompaktSyncStatus.Failed(lastSync.value)
    return lastSync.value?.let(KompaktSyncStatus::Synced) ?: KompaktSyncStatus.NeverSynced
}
