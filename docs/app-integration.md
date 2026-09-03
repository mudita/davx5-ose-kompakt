# DAVx⁵ Mudita — integration points for other apps

## Launching the account screen in onboarding mode

Another app on the device (e.g. the Mudita calendar app) can launch the account screen
(`KompaktAccountsActivity`) in **onboarding mode**. This is meant for first-run flows: the calling app
sends the user there to link an account, with an explicit way to back out.

The change versus the normal account screen is purely cosmetic and only applies **when no account is
linked yet**: the link screen drops its title and back arrow and shows a **"Skip"** button in the top app
bar instead. If an account is **already linked**, the normal screen is shown regardless of onboarding
mode.

### Contract

- **Action:** `at.bitfire.davdroid.mudita.action.ONBOARDING` (`KompaktAccountsActivity.ACTION_ONBOARDING`)
- **Target:** `KompaktAccountsActivity` — launch **explicitly** by component/package. The activity is
  already `exported`, so no extra intent-filter is required and no permission is needed.

### Caller — code

```kotlin
val intent = Intent("at.bitfire.davdroid.mudita.action.ONBOARDING")
    .setClassName("at.bitfire.davdroid.mudita", "at.bitfire.davdroid.ui.KompaktAccountsActivity")
context.startActivity(intent)
// or, if you want the skip/cancel result:
// startActivityForResult(intent, REQUEST_ONBOARDING)
```

### Behaviour & results

- **No account linked** → the link screen shows the **Skip** button (no title, no back arrow).
  - **Skip** finishes the activity with `RESULT_CANCELED`.
  - Linking succeeds → the account screen shows the linked account with the "Account linked" dialog and
    **stays in the foreground** — it is not finished and your app is not brought back automatically. The
    user returns to your app by pressing **Back**.
- **Account already linked** → the normal account screen is shown (onboarding mode has no effect).

### Notes / limitations

- Onboarding mode only affects the **empty / link** state. Once an account exists it is a no-op.
- A successful link gives **no automatic return to your app and no success result**: the account screen
  stays open and the user presses **Back** to return. (The only result your app receives is
  `RESULT_CANCELED`, from the **Skip** button.)

## Launching re-authorization for the linked account

Another app on the device (e.g. the Mudita calendar app) can send the user **straight into the
re-authorization flow** for the linked account — typically after it has detected via the auth-state
provider (below) that the account's OAuth token expired (`needs_reauth == 1`). This starts the Google
OAuth flow in place (refreshing the token, keeping all local data) instead of just opening the account
screen, so the user isn't shown the account screen's own re-auth prompt again.

### Contract

- **Action:** `at.bitfire.davdroid.mudita.action.REAUTH` (`KompaktAccountsActivity.ACTION_REAUTH`)
- **Target:** `KompaktAccountsActivity` — launch **explicitly** by component/package. The activity is
  already `exported`, so no extra intent-filter is required and no permission is needed (same as
  `ONBOARDING`).

### Caller — code

```kotlin
val intent = Intent("at.bitfire.davdroid.mudita.action.REAUTH")
    .setClassName("at.bitfire.davdroid.mudita", "at.bitfire.davdroid.ui.KompaktAccountsActivity")
context.startActivity(intent)
```

The re-auth target is the single linked account; the caller does not pass an account name.

### Behaviour & results

- **Account linked** → the account screen opens and immediately launches the in-place re-auth (Google
  OAuth). On success the token is refreshed, the persisted `needs_reauth` flag is cleared, and an
  `AUTH_STATE_CHANGED` broadcast fires (so a caller observing the auth state sees it flip to `0`). The
  account screen then **stays in the foreground** — it is not finished and your app is not brought back
  automatically; the user returns by pressing **Back**.
  - If the user authorizes a **different** Google account during re-auth, the new account is linked, the
    old one is removed, and the "Account linked" dialog is shown (same as the in-app flow).
  - If the user **cancels / backs out**, `needs_reauth` stays `1` and the account screen keeps showing
    the "Account link error" dialog, so the caller's own prompt reappears correctly on return.
- **No account linked** → the request is a no-op beyond showing the normal link screen (there is nothing
  to re-authorize).

### Notes / limitations

- The re-auth is launched **at most once per request**: it is honoured only on the activity's genuine
  first creation and guarded by a saved one-shot flag, so it is not re-triggered by a configuration
  change, a process-death restore, or Compose recomposition while the OAuth flow is on top.
- Re-auth gives **no automatic return to your app and no success result**: the user presses **Back** to
  return. Learn the outcome from the auth-state provider/broadcast below (the `needs_reauth` flag flips to
  `0` on success), not from a result.

## Requesting a sync from another app

Another app on the device (e.g. the Mudita calendar app) can request a sync of the
linked account by sending a broadcast to DAVx⁵ Mudita.

The request is **throttled**: a sync is only enqueued if the last successful sync finished at least
**15 minutes** ago (or no successful sync has ever happened). If a successful sync completed less than 15
minutes ago, the broadcast is a **no‑op**.

Synchronization is requested **per service**. Each of Calendar and Contacts is enqueued only when the
user has that service's sync toggle switched on, has granted its Google permission, and that service is
configured. A service failing any of those is skipped — so a request may now enqueue **nothing at all**,
even for a linked and otherwise healthy account.

### Contract

- **Action:** `at.bitfire.davdroid.mudita.action.REQUEST_SYNC`
- **Target package:** `at.bitfire.davdroid.mudita` (the broadcast must be explicit — set the package)
- **Permission:** `at.bitfire.davdroid.mudita.permission.TRIGGER_SYNC` — **`signature`** protection level

### Conditions that must be met

1. **Same signing key.** The permission is `protectionLevel="signature"`, so the calling app must be
   signed with the **same certificate** as DAVx⁵ Mudita (e.g. both platform‑signed on the Mudita
   device). A differently‑signed app is rejected by the system.
2. The caller must **declare** the permission with `<uses-permission>` (below).
3. The broadcast must be **explicit** (target package `at.bitfire.davdroid.mudita`); implicit broadcasts for a
   custom action won't be delivered on modern Android.
4. **At least 15 minutes** must have elapsed since the last successful sync (otherwise the request is
   silently ignored).
5. An account must be linked. With no linked account the broadcast is a no‑op.
6. Normal sync conditions still apply afterwards (e.g. connectivity) — the broadcast only *enqueues* a
   manual sync; it does not bypass the lack of a network.
7. **At least one service must be eligible.** A service is skipped when its sync toggle is off, when the
   account has not granted that service's Google permission, or when the service is not configured. If
   no service qualifies, the broadcast is a no‑op and nothing is enqueued.

### Caller — manifest

```xml
<uses-permission android:name="at.bitfire.davdroid.mudita.permission.TRIGGER_SYNC" />
```

### Caller — code

```kotlin
val intent = Intent("at.bitfire.davdroid.mudita.action.REQUEST_SYNC")
    .setPackage("at.bitfire.davdroid.mudita")
context.sendBroadcast(intent)
```

### What happens

If at least 15 minutes have passed since the last successful sync, `KompaktSyncRequestReceiver` (in
DAVx⁵ Mudita) enqueues a one‑time manual sync for every linked account — but **only for the services
that qualify** (see condition 7 above), so it may enqueue for one service, both, or neither. This is the
same path used by the in‑app "Synchronize now" button. The "Last synchronization" timestamp updates on
successful completion (and is left unchanged on failure).

A **successful** manual sync (this broadcast or the in‑app button) also **pushes the next automatic
(periodic) sync back by a full interval, counting from now** — so triggering a manual sync resets the
automatic schedule and avoids an automatic sync running again right away. Implemented in `BaseSyncWorker`
(on a successful `manual` sync) → `AutomaticSyncManager.reschedulePeriodic(...)` (re‑enqueues the periodic
worker with `CANCEL_AND_REENQUEUE`). A failed manual sync does not reschedule.

### Notes / limitations

- It's a fire‑and‑forget broadcast: there is no result/callback to the caller. Observe the effect via the
  calendar contents / the account screen, not a return value.
- Only one sync per account + data type runs at a time; if a sync is already running, the request is
  coalesced/queued (it won't run a second concurrent sync).
- Testing from `adb` shell is **not** possible because of the signature permission (shell isn't
  same‑signed); it can only be exercised from a same‑signed app.

## Reading the authentication (token) state from another app

Another app on the device (e.g. the Mudita calendar app) can find out whether a linked account's OAuth
token has **expired / needs re‑authorization**, so it can react in its own UI (e.g. prompt the user to
re‑login) instead of silently failing to sync.

DAVx⁵ Mudita detects this during sync: an HTTP 401 from the server raises `numAuthExceptions`, which sets a
per‑account `needs_reauth` flag (cleared again on the next clean sync). That flag is exposed two ways,
both guarded by the **same** signature condition as `REQUEST_SYNC`.

### Contract

- **Source of truth (pull):** `ContentProvider` at
  `content://at.bitfire.davdroid.mudita.kompakt.authstate/auth_state` (`KompaktAuthState.CONTENT_URI`),
  **read‑only**. One row per linked account with columns:
  - `_id` (Int) — row index
  - `account_name` (String)
  - `account_type` (String)
  - `needs_reauth` (Int) — **1** if the token is invalid and needs re‑authorization, **0** otherwise
- **Change notification (push):** broadcast action `at.bitfire.davdroid.mudita.action.AUTH_STATE_CHANGED`
  (`KompaktAuthState.ACTION_AUTH_STATE_CHANGED`), sent **only on a state transition** for an account,
  with extras `account_name` (String) and `needs_reauth` (Int). The provider URI is also notified via
  `ContentResolver.notifyChange`, so a `ContentObserver` works too.
- **Permission:** `at.bitfire.davdroid.mudita.permission.READ_AUTH_STATE` — **`signature`** protection level. Required
  to query the provider **and** to receive the broadcast. Same‑signing condition as above; the caller
  must `<uses-permission>` it.

### Caller — manifest

```xml
<uses-permission android:name="at.bitfire.davdroid.mudita.permission.READ_AUTH_STATE" />
```

### Caller — code

Pull the current state at any time:

```kotlin
val uri = Uri.parse("content://at.bitfire.davdroid.mudita.kompakt.authstate/auth_state")
context.contentResolver.query(uri, null, null, null, null)?.use { c ->
    while (c.moveToNext()) {
        val name = c.getString(c.getColumnIndexOrThrow("account_name"))
        val needsReauth = c.getInt(c.getColumnIndexOrThrow("needs_reauth")) == 1
        // …react…
    }
}
```

Get notified of changes — **`ContentObserver` is recommended** (not subject to the Android 8+
implicit‑broadcast limitations for manifest receivers):

```kotlin
context.contentResolver.registerContentObserver(uri, /* notifyForDescendants = */ false, observer)
// observer.onChange() → re‑query the provider for the new state
```

Alternatively, a **runtime‑registered** `BroadcastReceiver` on `at.bitfire.davdroid.mudita.action.AUTH_STATE_CHANGED`
(the app must hold `READ_AUTH_STATE`).

### Notes / limitations

- The provider is **read‑only**; other apps cannot mutate the auth state.
- The broadcast fires only on **transitions** (valid→invalid and invalid→valid), not on every failed
  sync. For the absolute current state, always query the provider.
- Same as `REQUEST_SYNC`: it **cannot** be tested from `adb` shell because of the signature permission;
  exercise it from a same‑signed app.

## Requesting logout (account removal) from another app

Another app on the device (e.g. the Mudita calendar app) can request that all linked accounts be removed
from the device by sending a broadcast to DAVx⁵ Mudita. This is the counterpart to the account‑linking
onboarding flow: when the user cancels or logs out in the calling app, the same action should remove the
linked account so calendars and events disappear from `CalendarContract`.

### Contract

- **Action:** `at.bitfire.davdroid.mudita.action.LOGOUT`
- **Target package:** `at.bitfire.davdroid.mudita` (the broadcast must be explicit — set the package)
- **Permission:** `at.bitfire.davdroid.mudita.permission.LOGOUT` — **`signature`** protection level

### Conditions that must be met

1. **Same signing key.** The permission is `protectionLevel="signature"`, so the calling app must be
   signed with the **same certificate** as DAVx⁵ Mudita (e.g. both platform‑signed on the Mudita
   device). A differently‑signed app is rejected by the system.
2. The caller must **declare** the permission with `<uses-permission>` (below).
3. The broadcast must be **explicit** (target package `at.bitfire.davdroid.mudita`); implicit broadcasts for a
   custom action won't be delivered on modern Android.

### Caller — manifest

```xml
<uses-permission android:name="at.bitfire.davdroid.mudita.permission.LOGOUT" />
```

### Caller — code

```kotlin
val intent = Intent("at.bitfire.davdroid.mudita.action.LOGOUT")
    .setPackage("at.bitfire.davdroid.mudita")
context.sendBroadcast(intent)
```

### What happens

`KompaktLogoutRequestReceiver` (in DAVx⁵ Mudita) iterates over all accounts of type
`bitfire.at.davdroid.mudita` and calls `AccountRepository.delete(accountName)` for each one.
`delete()` calls `AccountManager.removeAccountExplicitly`, then removes any linked CardDAV address‑book
accounts and deletes the account row from the local database. As a result, all calendars and events
previously synced from the server disappear from `CalendarContract`.

### Notes / limitations

- It's a **fire‑and‑forget** broadcast: there is no result or callback. The calling app should observe
  the effect via the absence of calendar data, not a return value.
- If no account is linked, the broadcast is a no‑op.
- Testing from `adb` shell is **not** possible because of the signature permission; it can only be
  exercised from a same‑signed app.
