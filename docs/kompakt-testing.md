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
