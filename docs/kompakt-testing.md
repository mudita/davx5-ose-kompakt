# Kompakt — testing notes

Practical recipes for testing the Kompakt account/sync flow on a device or emulator.

## Auto-sync interval (debug vs. release)

The linked-account screen now lists **Calendar** and **Contacts** as separate rows, each with its own
toggle and status line; the Contacts toggle is inert until contacts sync is implemented. The interval
that the Calendar toggle enables depends on the build type
(`KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS`):

| Build | Auto-sync interval |
|---|---|
| **Debug** (e.g. `assembleOseDebug`) | **15 minutes** — short, to make testing easier |
| **Release** (non-debug) | **24 hours** — production default |

So on a **debug** build, after enabling auto-sync, expect a background sync roughly every 15 min (Android may
batch/defer it a little — `WorkManager` periodic work has a minimum 15 min interval and is not exact). On a
release build it is once a day. To verify the actual scheduled interval on a device:

```bash
adb shell dumpsys jobscheduler | grep -A3 -i "at.bitfire.davdroid.mudita"
```

## Sync error handling — what shows when

The Kompakt "Linked Account" screen (`KompaktLinkedAccountModel` / `KompaktLinkedAccountScreen`) surfaces
five distinct sync problems, each with its own UI:

| Situation | Detected via | UI shown |
|---|---|---|
| Offline+ (before / during a sync, and before the login flow) | `KompaktOfflinePlus` via `KompaktConnectivity` | **"You're using Offline+"** message (`KompaktOfflinePlusSheet`) |
| No connectivity (before / during a sync) | `SyncConditions.internetAvailable()` pre-check + connectivity watcher | **"No internet connection"** message (`KompaktNoInternetSheet`) |
| Nothing switched on when Synchronize was tapped | `KompaktStartSyncUseCase` answers `NoneEligible` | **"Your sync is off"** dialog |
| OAuth token invalid / access revoked (HTTP 401) | `numAuthExceptions > 0` in the worker result, persisted to `AccountSettings.KEY_NEEDS_REAUTH` | **"Account not linked"** dialog (Link account / Cancel) |
| Any other failure: server 5xx/4xx≠401, malformed response, local/provider error, soft errors after retries | EVENTS `OneTimeSyncWorker` reaches `FAILED` (non-auth) on a user‑initiated (`armed`) sync | **"Account sync failed / Try again"** dialog |

The per-service alert icon (`KompaktSyncFailureSheet`) has its own "No internet connection" wording for
a stored `NetworkProblem` outcome, and deliberately has no Offline+ variant: an Offline+ event cancels
the in-flight worker before it writes an outcome, and periodic work never starts at all while the
connection is gone, so Offline+ cannot produce that row.

> The generic **"Account sync failed"** dialog only appears for the last category. Airplane mode / no
> network is routed to **"No internet connection"**, the Offline+ switch to **"You're using Offline+"**,
> and 401 to **"Account not linked"** — so none of those is the way to trigger the generic dialog.

## Offline+ — handling & testing

Offline+ is the hardware switch on the left side of the device; while Offline+ is on the Kompakt has no
connection at all. (The switch is moved *down* to turn Offline+ *on*, so everything below says on/off
rather than up/down.) It is reported ahead of "no internet" everywhere, because it is the cause rather than
the symptom — `KompaktConnectivity` ranks the two, and `KompaktStartSyncUseCase` asks about it before it
consults the network.

Where it shows:

- **Synchronize, or switching a service on** — the pre-flight answers `OfflinePlus`, so nothing is
  enqueued and the sheet names the switch.
- **During a running sync** — the watch in `KompaktSyncAttempt` cancels the in-flight runs. Unlike a
  connection that dropped by itself, this does **not** wait out `OFFLINE_GRACE_MS`: the switch was just
  moved, so there is no blip to absorb.
- **Link account** (both from settings and in onboarding) — asked before `KompaktLoginActivity` is
  launched, since the OAuth page would otherwise only reach the WebView's own "couldn't load" error.

On a device, just move the switch. Without one, the two broadcasts can be sent by hand — they are
received `RECEIVER_EXPORTED` with no permission, and the app must be in the foreground for its receiver
to be registered:

```bash
# Offline+ on
adb shell am broadcast -a android.intent.action.ACTION_HWSWITCH_LOCKED

# Offline+ off
adb shell am broadcast -a android.intent.action.ACTION_HWSWITCH_UNLOCKED
```

The *initial* state is not read from the broadcasts but from the `HWSwitch_lock` setting (`0` = Offline+
off, `1` = Offline+ on), so a broadcast alone will not survive a re-read:

```bash
adb shell settings get global HWSwitch_lock
```

The row is KompaktOS's own, so it is absent on any other hardware, where it reads as Offline+ off.
Confirmed on a device across a reboot taken with Offline+ already on: it still read `1` before any
broadcast had been sent.

Offline+ does **not** go through airplane mode — `airplane_mode_on` stays `0` while Offline+ is on, so
that is not the flag to watch. What does change alongside it is `wifi_on`, which goes to `0`.

## Forcing a generic sync error (Method A — break the calendar URL → HTTP 404)

This is the fastest, code‑free, deterministic way to make a manual sync fail with a non‑auth HTTP error
and show the **"Account sync failed / Try again"** dialog. It edits the selected calendar's CalDAV URL in
the app's Room DB (`services.db`) so the server returns 404.

Requires a debug build (so `run-as` works) and an already‑linked account.

```bash
# 1) stop the app so it doesn't cache the DB
adb shell am force-stop at.bitfire.davdroid.mudita

# 2) append garbage to the synced calendar's URL → server returns 404
adb shell "run-as at.bitfire.davdroid.mudita sqlite3 databases/services.db \
  \"UPDATE collection SET url = url || 'force404/' WHERE sync = 1;\""

# 3) open the app and tap 'Synchronize'
adb shell monkey -p at.bitfire.davdroid.mudita -c android.intent.category.LAUNCHER 1
```

Expected: the Calendar row shows **"Syncing now…"**, then the
**"Account sync failed / Try again"** dialog appears.

Restore afterwards:

```bash
adb shell am force-stop at.bitfire.davdroid.mudita
adb shell "run-as at.bitfire.davdroid.mudita sqlite3 databases/services.db \
  \"UPDATE collection SET url = replace(url, 'force404/', '') WHERE sync = 1;\""
```

Notes / caveats:
- A manual "Synchronize" does **not** re-run collection discovery, so the broken URL stays in effect
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
- **Every requested sync** is pre-checked in `KompaktStartSyncUseCase`, which answers `NoStorage` before it
  enqueues anything (manual workers have no storage constraint, so the guard has to be here — same pattern as
  the no-internet check). `KompaktSyncAttempt` maps that to `BlockedNoStorage`.
- **The UI message answers a blocked action, and nothing else.** `KompaktLinkedAccountModel` raises
  "Your storage is full" only on `BlockedNoStorage`, so it appears when the user asked for something that low
  storage stopped — **Synchronize**, or switching a service on — and never on screen entry or resume.
  Dismissing it is final until the next blocked request. This is the same shape as "No internet"; the device
  reports low storage on its own, so the app does not repeat that warning passively.

`KompaktStorage.isStorageLow(context)` mirrors the framework `StorageManager.getStorageLowBytes()` formula:
`min(sys_storage_threshold_max_bytes [default 500 MB], total * sys_storage_threshold_percentage% [default
10 %])`, read from `Settings.Global` (fallbacks `STORAGE_THRESHOLD_MAX_BYTES_DEFAULT` /
`STORAGE_THRESHOLD_PERCENTAGE_DEFAULT`).

### Testing

- **UI message + manual guard:** fill storage below the system threshold (e.g. `adb shell` write a large file
  until free space drops past the "storage running out" point), then open the linked-account screen → **no**
  message. Tap **Synchronize** → the **"Your storage is full"** message appears and no sync starts. Dismiss
  it, leave the screen and come back → still no message; tap **Synchronize** again → it returns. Switching a
  service on raises it too. Delete the file, tap **Synchronize** → it syncs.
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
`KompaktStorage.isStorageLow()` behind `KompaktStorageAvailability`, read once per requested sync by
`KompaktStartSyncUseCase` (manual workers carry no constraint); that is a state-read on a request, not a
recovery loop and not a poll.

> One-time confirmation on the target Mudita device: fill storage below the threshold → the automatic worker
> parks (no `SQLITE_FULL` loop), then free space → it resumes on its own within ~1 min. If for some reason it
> does **not** resume there, revisit a fallback (e.g. re-ensure the periodic worker on screen entry, or a 1 h
> retry); until then it's unnecessary.

## Method B (alternative — local provider error)

Revoke the calendar runtime permission, then sync:

```bash
adb shell pm revoke at.bitfire.davdroid.mudita android.permission.WRITE_CALENDAR
adb shell pm revoke at.bitfire.davdroid.mudita android.permission.READ_CALENDAR
# tap 'Synchronize' → Calendar Provider access error → "Account sync failed"
adb shell pm grant at.bitfire.davdroid.mudita android.permission.READ_CALENDAR
adb shell pm grant at.bitfire.davdroid.mudita android.permission.WRITE_CALENDAR
```

Less reliable than Method A — DAVx5 may treat a missing permission as "permissions required" rather than a
sync failure.

## Inspecting state on the device

```bash
# calendar collections + which one is selected for sync
adb shell "run-as at.bitfire.davdroid.mudita sqlite3 databases/services.db \
  \"SELECT id, sync, displayName, url FROM collection;\""

# Kompakt per-account flags (needs root, e.g. emulator)
adb root
adb shell "sqlite3 /data/system_ce/0/accounts_ce.db \
  \"SELECT e.key, e.value FROM accounts a JOIN extras e ON e.accounts_id=a._id \
    WHERE a.type='bitfire.at.davdroid.mudita' AND (e.key LIKE 'kompakt%' OR e.key LIKE 'sync_interval%');\""
```

## Auth (token) state export — testing

The auth state is exposed to other apps two ways (see [`app-integration.md`](app-integration.md) and `KompaktAuthState`):
a read‑only `ContentProvider` at `content://at.bitfire.davdroid.mudita.kompakt.authstate/auth_state` (column
`needs_reauth` = 0/1 per account), and an `AUTH_STATE_CHANGED` broadcast fired **only on a transition**.
Both are guarded by the signature permission `at.bitfire.davdroid.mudita.permission.READ_AUTH_STATE`.

> Like `REQUEST_SYNC`, the signature permission means **`adb shell` cannot read the provider or receive the
> broadcast** (shell isn't same‑signed). So we test in two parts: drive/inspect the underlying state with
> `adb`, then read the exposed surface either from a same‑signed app or via a debug‑only relaxation.

### 1. Trigger a transition (valid -> token expired -> valid)

The flag flips -- and the push fires -- only through the **real sync path**, when EVENTS sync hits a 401
(`numAuthExceptions > 0` in `BaseSyncWorker` -> `kompakt_needs_reauth` set to `"1"`, `AUTH_STATE_CHANGED`
with `needs_reauth=1`). After re-linking and a clean sync it flips back (and fires `needs_reauth=0`).

> **`needs_reauth` is set only by a real HTTP 401.** `SyncManager.handleException` increments
> `numAuthExceptions` **only** for `UnauthorizedException`. Overwriting `auth_state` with a junk string
> like `'broken'` does **not** work: `AccountSettings.credentials()` calls `AuthState.jsonDeserialize`
> with no try/catch, so a non-JSON blob throws `JSONException` -> categorized as an *unclassified* error
> ("sync failed"), never a 401 -> the flag stays `0` and no push fires. The token must stay valid JSON;
> only its **contents** may be invalidated.

Revoke the account's access on the Google side
(Google Account -> Security -> third-party access -> remove DAVx5), then tap **Synchronize**. The
refresh token is now invalid -> AppAuth's refresh fails (`invalid_grant`) -> `OAuthInterceptor` returns
`null` -> the request goes out without a bearer -> server returns **401** -> `needs_reauth` flips to 1
and `AUTH_STATE_CHANGED` fires. Re-link the account to recover.

### 2. Inspect the source-of-truth flag (what the provider will report)

The provider reports `needs_reauth=1` exactly when the `kompakt_needs_reauth` extra is `"1"` -- verify it
with the accounts-DB query in *Inspecting state on the device* above.

### 3. Confirm the push actually fired (it is edge-triggered)

`AUTH_STATE_CHANGED` **and** the `notifyChange` on the provider URI fire only on a **transition**
(`nowNeedingReauth != wasNeedingReauth` in `BaseSyncWorker`), not on every failed sync. **The provider
reporting `needs_reauth=1` does not prove the push fired** -- the provider just reads the persisted flag.
If the flag was already `1` from an earlier failed sync, the next 401 is not a transition, so nothing is
emitted.

To observe a push, force a clean **`0 -> 1`** edge: clear the flag first (restore the token and run a
successful sync, or set the `kompakt_needs_reauth` extra to `NULL` + framework reload), confirm the
provider reports `0`, then trigger the 401 **once**.

> Don't rely on `adb shell dumpsys activity broadcasts | grep AUTH_STATE_CHANGED`: for a
> permission-protected *implicit* broadcast with no installed receiver the history is tiny.

Reliable ways to observe:
- A **`ContentObserver`** from a same-signed app on `KompaktAuthState.CONTENT_URI` (easiest -- you already
  know the provider works); see step 4.
- `KompaktAuthStatePublisher.publish` already logs on a debug build, so
  `adb logcat | grep AUTH_STATE_CHANGED` shows every publication.

### 4. Read the provider / receive the push (cross‑app — the real end‑to‑end test)

Needs a **same‑signed** app declaring `<uses-permission android:name="at.bitfire.davdroid.mudita.permission.READ_AUTH_STATE" />`:

```kotlin
val uri = Uri.parse("content://at.bitfire.davdroid.mudita.kompakt.authstate/auth_state")

// pull — current state
contentResolver.query(uri, null, null, null, null)?.use { c ->
    while (c.moveToNext())
        Log.i("authstate", "${c.getString(1)} needsReauth=${c.getInt(3)}")  // account_name, needs_reauth
}

// push — observe changes (recommended; immune to the Android 8+ implicit-broadcast limits)
contentResolver.registerContentObserver(uri, /* notifyForDescendants = */ false, observer)

// push — or a runtime receiver
registerReceiver(
    receiver,
    IntentFilter("at.bitfire.davdroid.mudita.action.AUTH_STATE_CHANGED"),
    "at.bitfire.davdroid.mudita.permission.READ_AUTH_STATE",
    null,
    Context.RECEIVER_EXPORTED
)
```

### 4b. Debug‑only shortcut (eyeball the cursor without a companion app)

In a **local** build only, temporarily remove `android:readPermission` from the `<provider>` in
`app-ose/src/main/AndroidManifest.xml` or start `adb` as `root`, then:

```bash
adb shell content query --uri content://at.bitfire.davdroid.mudita.kompakt.authstate/auth_state
```

Revert before committing — never ship the provider without the permission.
