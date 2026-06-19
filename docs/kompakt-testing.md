# Kompakt — testing notes

Practical recipes for testing the Kompakt account/sync flow on a device or emulator.

## Sync error handling — what shows when

The Kompakt "Linked Account" screen (`KompaktLinkedAccountModel` / `KompaktLinkedAccountScreen`) surfaces
three distinct sync problems, each with its own UI:

| Situation | Detected via | UI shown |
|---|---|---|
| No connectivity (before / during a sync) | `SyncConditions.internetAvailable()` pre-check + connectivity watcher | **"No internet connection"** message (`KompaktMessageSheet`) |
| OAuth token invalid / access revoked (HTTP 401) | `numAuthExceptions > 0` in the worker result, persisted to `AccountSettings.KEY_NEEDS_REAUTH` | **"Account not linked"** dialog (Link account / Cancel) |
| Any other failure: server 5xx/4xx≠401, malformed response, local/provider error, soft errors after retries | EVENTS `OneTimeSyncWorker` reaches `FAILED` (non-auth) on a user‑initiated (`armed`) sync | **"Account sync failed / Try again"** dialog |

> The generic **"Account sync failed"** dialog only appears for the third category. Airplane mode / no
> network is routed to **"No internet connection"**, and 401 to **"Account not linked"** — so those are
> *not* the way to trigger the generic dialog.

## Forcing a generic sync error (Method A — break the calendar URL → HTTP 404)

This is the fastest, code‑free, deterministic way to make a manual sync fail with a non‑auth HTTP error
and show the **"Account sync failed / Try again"** dialog. It edits the selected calendar's CalDAV URL in
the app's Room DB (`services.db`) so the server returns 404.

Requires a debug build (so `run-as` works) and an already‑linked account.

```bash
# 1) stop the app so it doesn't cache the DB
adb shell am force-stop at.bitfire.davdroid

# 2) append garbage to the synced calendar's URL → server returns 404
adb shell "run-as at.bitfire.davdroid sqlite3 databases/services.db \
  \"UPDATE collection SET url = url || 'force404/' WHERE sync = 1;\""

# 3) open the app and tap 'Synchronize now'
adb shell monkey -p at.bitfire.davdroid -c android.intent.category.LAUNCHER 1
```

Expected: after "Loading data…", the **"Account sync failed / Try again"** dialog appears.

Restore afterwards:

```bash
adb shell am force-stop at.bitfire.davdroid
adb shell "run-as at.bitfire.davdroid sqlite3 databases/services.db \
  \"UPDATE collection SET url = replace(url, 'force404/', '') WHERE sync = 1;\""
```

Notes / caveats:
- A manual "Synchronize now" does **not** re-run collection discovery, so the broken URL stays in effect
  for the test. If a periodic/background sync or a re-discovery runs, the real collection may be re-added
  (with `sync = false`); if the state gets messy, just unlink and re-link the account.
- If the server returns **401** for the bogus path (instead of 404), you'll get "Account not linked"
  instead — use a different suffix, or fall back to Method B.

## Out of storage — handling & testing

### What happens

- **Automatic sync (periodic + content-triggered one-time)** carries a
  `Constraints.setRequiresStorageNotLow(true)` constraint (`SyncWorkerManager.buildPeriodic` /
  `buildOneTime` for non-manual). While the system reports storage critically low, WorkManager **parks** the
  worker (never dispatches it → no `trySetRunning` → no `SQLITE_FULL` tight loop) and **auto-runs it the
  moment the system reports `STORAGE_OK`** again. No cancel/re-enable logic, no app interaction needed.
  This constraint uses the system's own low-storage signal (`DeviceStorageMonitorService`,
  `ACTION_DEVICE_STORAGE_LOW/OK`), i.e. the same `min(500 MB, 10 %)` threshold as `KompaktStorage`.
- **Manual "Sync now"** is pre-checked in `KompaktLinkedAccountModel.syncNow()`: if
  `KompaktStorage.isStorageLow(context)`, it shows the **"Your storage is full"** message and does not enqueue
  (manual workers have no storage constraint, so we must guard here — same pattern as the no-internet check).
- **The UI message is persistent/live**, like the re-auth dialog: `showOutOfStorage` is seeded from
  `KompaktStorage.isStorageLow()` on screen entry and re-checked on `ON_RESUME`
  (`model.refreshStorageState()`), so entering the app with low storage shows the message immediately and it
  clears once space frees.

`KompaktStorage.isStorageLow(context)` mirrors the framework `StorageManager.getStorageLowBytes()` formula:
`min(sys_storage_threshold_max_bytes [default 500 MB], total * sys_storage_threshold_percentage% [default
10 %])`, read from `Settings.Global` (fallbacks `STORAGE_THRESHOLD_MAX_BYTES_DEFAULT` /
`STORAGE_THRESHOLD_PERCENTAGE_DEFAULT`).

### Testing

- **UI message + manual guard:** fill storage below the system threshold (e.g. `adb shell` write a large file
  until free space drops past the "storage running out" point), open the linked-account screen → the
  **"Your storage is full"** message appears immediately; tapping **Synchronize now** keeps showing it and
  does not start a sync. Delete the file → on next resume the message clears.
- **Automatic park & auto-resume:** with auto-sync on, fill storage low → the periodic/one-time sync workers
  sit ENQUEUED with the storage-not-low constraint unmet (no `SQLITE_FULL` loop in logcat). Free space → the
  worker runs automatically (no app interaction), typically within ~1 min. Verified on the emulator
  (target SDK 36): `dumpsys jobscheduler` shows the job's `STORAGE_NOT_LOW` flip from unsatisfied to satisfied,
  then `PeriodicSyncWorker called … Worker result SUCCESS`.
- **Quick UI check:** temporarily raise `STORAGE_THRESHOLD_MAX_BYTES_DEFAULT` /
  `STORAGE_THRESHOLD_PERCENTAGE_DEFAULT` in `KompaktStorage` above current free space, or preview the
  `KompaktMessageSheet` UI directly.

### Why no extra "storage watcher" of our own (design decision)

`requiresStorageNotLow` is **enforced by the platform JobScheduler**, not by an app-side broadcast receiver:
`dumpsys jobscheduler` lists the sync job with `Required constraints: STORAGE_NOT_LOW`, and JobScheduler's own
`StorageController` satisfies/unsatisfies it server-side. Consequences:

- The deprecation of delivering `ACTION_DEVICE_STORAGE_LOW` to apps targeting API ≥26 **does not affect us** —
  we never receive that broadcast; JobScheduler tracks the condition itself.
- The signal comes from `DeviceStorageMonitorService` — the **same** component that raises the system
  "storage running out / some functions may not work" notification. So if the device shows that warning
  (it does on Mudita), the constraint works from the same source. The threshold also matches `KompaktStorage`
  (`min(500 MB, 10 %)`).

Therefore we deliberately **do not add our own polling / re-enable mechanism** for automatic sync — it would be
redundant with the platform. The only first-party storage check we keep is the synchronous
`KompaktStorage.isStorageLow()` used for the live UI message and the manual-sync pre-check (manual workers carry
no constraint); that is state-read, not a recovery loop.

> One-time confirmation on the target Mudita device: fill storage below the threshold → the automatic worker
> parks (no `SQLITE_FULL` loop), then free space → it resumes on its own within ~1 min. If for some reason it
> does **not** resume there, revisit a fallback (e.g. re-ensure the periodic worker on screen entry, or a 1 h
> retry); until then it's unnecessary.

## Method B (alternative — local provider error)

Revoke the calendar runtime permission, then sync:

```bash
adb shell pm revoke at.bitfire.davdroid android.permission.WRITE_CALENDAR
adb shell pm revoke at.bitfire.davdroid android.permission.READ_CALENDAR
# tap 'Synchronize now' → Calendar Provider access error → "Account sync failed"
adb shell pm grant at.bitfire.davdroid android.permission.READ_CALENDAR
adb shell pm grant at.bitfire.davdroid android.permission.WRITE_CALENDAR
```

Less reliable than Method A — DAVx5 may treat a missing permission as "permissions required" rather than a
sync failure.

## Inspecting state on the device

```bash
# calendar collections + which one is selected for sync
adb shell "run-as at.bitfire.davdroid sqlite3 databases/services.db \
  \"SELECT id, sync, displayName, url FROM collection;\""

# Kompakt per-account flags (needs root, e.g. emulator)
adb root
adb shell "sqlite3 /data/system_ce/0/accounts_ce.db \
  \"SELECT e.key, e.value FROM accounts a JOIN extras e ON e.accounts_id=a._id \
    WHERE a.type='bitfire.at.davdroid' AND (e.key LIKE 'kompakt%' OR e.key LIKE 'sync_interval%');\""
```
