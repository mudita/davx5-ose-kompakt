# Kompakt — integration points for other apps

## Launching the account screen in onboarding mode

Another app on the device (e.g. the Mudita calendar app) can launch the Kompakt account screen in
**onboarding mode**. This is meant for first-run flows: the calling app sends the user to Kompakt to link
an account, with an explicit way to back out.

The change versus the normal account screen is purely cosmetic and only applies **when no account is
linked yet**: the link screen drops its title and back arrow and shows a **"Skip"** button in the top app
bar instead. If an account is **already linked**, the normal screen is shown regardless of onboarding
mode.

### Contract

- **Action:** `com.davx5.ose.action.ONBOARDING` (`KompaktAccountsActivity.ACTION_ONBOARDING`)
- **Target:** `KompaktAccountsActivity` — launch **explicitly** by component/package. The activity is
  already `exported`, so no extra intent-filter is required and no permission is needed.

### Caller — code

```kotlin
val intent = Intent("com.davx5.ose.action.ONBOARDING")
    .setClassName("at.bitfire.davdroid", "at.bitfire.davdroid.ui.KompaktAccountsActivity")
context.startActivity(intent)
// or, if you want the skip/cancel result:
// startActivityForResult(intent, REQUEST_ONBOARDING)
```

### Behaviour & results

- **No account linked** → the link screen shows the **Skip** button (no title, no back arrow).
  - **Skip** finishes the activity with `RESULT_CANCELED`.
  - Linking succeeds → the normal linked-account screen with the "Account linked" dialog is shown (the
    user stays in Kompakt; the activity is not auto-finished).
- **Account already linked** → the normal account screen is shown (onboarding mode has no effect).

### Notes / limitations

- Onboarding mode only affects the **empty / link** state. Once an account exists it is a no-op.
- Because the user stays in Kompakt after linking, there is no automatic return to the caller on success;
  the caller returns to its own UI when the user navigates back.

## Requesting a sync from another app

Another app on the device (e.g. the Mudita calendar app) can request a sync of the
linked account by sending a broadcast to the Kompakt app.

The request is **throttled**: a sync is only enqueued if the last successful sync finished at least
**15 minutes** ago (or no successful sync has ever happened). If a successful sync completed less than 15
minutes ago, the broadcast is a **no‑op**.

### Contract

- **Action:** `com.davx5.ose.action.REQUEST_SYNC`
- **Target package:** `at.bitfire.davdroid` (the broadcast must be explicit — set the package)
- **Permission:** `com.davx5.ose.permission.TRIGGER_SYNC` — **`signature`** protection level

### Conditions that must be met

1. **Same signing key.** The permission is `protectionLevel="signature"`, so the calling app must be
   signed with the **same certificate** as the Kompakt app (e.g. both platform‑signed on the Mudita
   device). A differently‑signed app is rejected by the system.
2. The caller must **declare** the permission with `<uses-permission>` (below).
3. The broadcast must be **explicit** (target package `at.bitfire.davdroid`); implicit broadcasts for a
   custom action won't be delivered on modern Android.
4. **At least 15 minutes** must have elapsed since the last successful sync (otherwise the request is
   silently ignored).
5. An account must be linked. With no linked account the broadcast is a no‑op.
6. Normal sync conditions still apply afterwards (e.g. connectivity) — the broadcast only *enqueues* a
   manual sync; it does not bypass the lack of a network.

### Caller — manifest

```xml
<uses-permission android:name="com.davx5.ose.permission.TRIGGER_SYNC" />
```

### Caller — code

```kotlin
val intent = Intent("com.davx5.ose.action.REQUEST_SYNC")
    .setPackage("at.bitfire.davdroid")
context.sendBroadcast(intent)
```

### What happens

If at least 15 minutes have passed since the last successful sync,
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
