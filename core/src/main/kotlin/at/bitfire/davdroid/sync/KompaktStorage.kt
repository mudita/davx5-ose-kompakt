/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.Context
import android.os.StatFs
import android.provider.Settings

/**
 * Kompakt: shared free-storage check, used by the sync workers, the worker scheduler and the
 * linked-account ViewModel to react consistently when the device is critically low on space.
 */
object KompaktStorage {

    /**
     * Fallbacks mirroring AOSP DeviceStorageMonitorService / StorageManager.getStorageLowBytes() when the
     * corresponding Settings.Global overrides are absent. The system's low-storage threshold (the "storage
     * running out / some functions may not work" state) is
     * min([STORAGE_THRESHOLD_MAX_BYTES_DEFAULT], total * [STORAGE_THRESHOLD_PERCENTAGE_DEFAULT]%).
     */
    const val STORAGE_THRESHOLD_MAX_BYTES_DEFAULT = 500L * 1024 * 1024   // sys_storage_threshold_max_bytes
    const val STORAGE_THRESHOLD_PERCENTAGE_DEFAULT = 10                  // sys_storage_threshold_percentage

    /**
     * `true` if free storage is below the level at which Android itself considers the device critically low
     * (the "storage running out / some functions may not work" state) — far above any flat few-MB heuristic.
     * Mirrors the @hide StorageManager.getStorageLowBytes() formula:
     * min(sys_storage_threshold_max_bytes, total * sys_storage_threshold_percentage%). The Settings.Global
     * key strings are stable public keys even though the SYS_STORAGE_THRESHOLD_* constants are @hide.
     */
    fun isStorageLow(context: Context): Boolean =
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            val resolver = context.contentResolver
            val percentage = Settings.Global.getString(resolver, "sys_storage_threshold_percentage")
                ?.toIntOrNull() ?: STORAGE_THRESHOLD_PERCENTAGE_DEFAULT
            val maxBytes = Settings.Global.getString(resolver, "sys_storage_threshold_max_bytes")
                ?.toLongOrNull() ?: STORAGE_THRESHOLD_MAX_BYTES_DEFAULT
            val lowBytes = minOf(maxBytes, stat.totalBytes * percentage / 100)
            stat.availableBytes < lowBytes
        } catch (_: Exception) {
            false
        }

}
