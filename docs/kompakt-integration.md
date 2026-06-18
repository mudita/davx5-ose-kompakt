# Kompakt — integration points for other apps

## Triggering a manual sync from another app

Another app on the device (e.g. the Mudita calendar app) can request an immediate **manual** sync of the
linked account by sending a broadcast to the Kompakt app.

### Contract

- **Action:** `com.davx5.ose.action.SYNC_NOW`
- **Target package:** `at.bitfire.davdroid` (the broadcast must be explicit — set the package)
- **Permission:** `com.davx5.ose.permission.TRIGGER_SYNC` — **`signature`** protection level

### Conditions that must be met

1. **Same signing key.** The permission is `protectionLevel="signature"`, so the calling app must be
   signed with the **same certificate** as the Kompakt app (e.g. both platform‑signed on the Mudita
   device). A differently‑signed app is rejected by the system.
2. The caller must **declare** the permission with `<uses-permission>` (below).
3. The broadcast must be **explicit** (target package `at.bitfire.davdroid`); implicit broadcasts for a
   custom action won't be delivered on modern Android.
4. An account must be linked. With no linked account the broadcast is a no‑op.
5. Normal sync conditions still apply afterwards (e.g. connectivity) — the broadcast only *enqueues* a
   manual sync; it does not bypass the lack of a network.

### Caller — manifest

```xml
<uses-permission android:name="com.davx5.ose.permission.TRIGGER_SYNC" />
```

### Caller — code

```kotlin
val intent = Intent("com.davx5.ose.action.SYNC_NOW")
    .setPackage("at.bitfire.davdroid")
context.sendBroadcast(intent)
```

### What happens

`KompaktSyncRequestReceiver` (in the Kompakt app) enqueues a one‑time manual sync for every linked
account via `SyncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)` — the same path used
by the in‑app "Synchronize now" button and the sync widget. The "Last synchronization" timestamp updates
on successful completion (and is left unchanged on failure).

A **successful** manual sync (this broadcast or the in‑app button) also **pushes the next automatic
(periodic) sync back by a full interval, counting from now** — so triggering a manual sync resets the
automatic schedule and avoids an automatic sync running again right away. Implemented in `BaseSyncWorker`
(on a successful `manual` sync) → `AutomaticSyncManager.reschedulePeriodic(...)` (re‑enqueues the periodic
worker with `CANCEL_AND_REENQUEUE`). A failed manual sync does not reschedule.

### Notes / limitations

- It's a fire‑and‑forget broadcast: there is no result/callback to the caller. Observe the effect via the
  calendar contents / the Kompakt screen, not a return value.
- Only one sync per account + data type runs at a time; if a sync is already running, the request is
  coalesced/queued (it won't run a second concurrent sync).
- Testing from `adb` shell is **not** possible because of the signature permission (shell isn't
  same‑signed); it can only be exercised from a same‑signed app.
