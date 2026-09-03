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

// Resolving is the position before the stored settings have been read. The screen withholds the row
// until it settles, so a tap cannot act on a value it does not have yet.
enum class KompaktSyncSwitch { Resolving, On, Off, ConsentMissing }

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
) {

    val isLoading: Boolean
        get() = switch == KompaktSyncSwitch.Resolving || status == KompaktSyncStatus.Resolving

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

// Consent only vetoes: a scope granted during a re-auth brings no service, no discovery and no
// interval with it, so the interval is what separates On from Off.
internal fun kompaktSyncSwitch(consented: Boolean, on: Boolean): KompaktSyncSwitch = when {
    !consented -> KompaktSyncSwitch.ConsentMissing
    on -> KompaktSyncSwitch.On
    else -> KompaktSyncSwitch.Off
}

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
