# SHP-1151 — Add Contacts/Calendar synchronization consent to an existing Google account

Design spec. Epic SHP-1148 ("M1: Two-way Google contacts synchronisation"). Builds on
[SHP-1159](2026-08-28-shp-1159-link-google-calendar-contacts-design.md), which explicitly named this
story as the owner of "granting a missing consent to an already-linked account."

## Problem

SHP-1159 lets a first-time link grant Calendar, Contacts, or both — whichever scopes the user
approves. Whatever wasn't granted then stays missing forever: the Linked Account screen reports it as
`ConsentMissing`, but nothing lets the user go back and grant it later, and an existing user who linked
before SHP-1159 shipped has no way to discover that Contacts sync is now available at all.

This story adds that path: request the missing scope for an already-linked account, apply it to the
same account in place, and create the one service that was missing — without re-running the whole
linking flow or touching the service that already works.

## Scope

In scope: the "Enable"/"Not now" consent dialog (both directions — Contacts missing or Calendar
missing), the reduced-scope OAuth request, applying the result to the existing account, creating the
newly-authorized service, and the one-time Contacts discovery nudge for pre-SHP-1159 accounts.

Out of scope, owned by sibling stories in the same epic:

| Concern | Story | Status at time of writing |
|---|---|---|
| Toggle behaviour once consent is already granted (auto-sync on/off, mid-sync cancellation) | SHP-1156 (Contacts), SHP-1158 (Calendar) | Development in Progress |
| First Contacts import ("Import now" / "Not now") | SHP-1152 | Ready for Refinement |
| Detecting a scope the user *revoked* during general re-authentication | SHP-1180 | Ready for BA |
| Per-service sync status, error dialogs in settings generally | SHP-1155, SHP-1149 | Ready for Refinement |

A consequence worth stating plainly, mirroring SHP-1159's own boundary: this story does not implement
Contacts' or Calendar's granted-toggle on/off behaviour. The Contacts toggle's "already granted" branch
stays the same stub it is today (`/* SHP-1156 */`); this story only fills in the `ConsentMissing`
branch for both services.

## Prerequisite outside the codebase

Shared with SHP-1159, not a new requirement: both OAuth clients in `KompaktGoogleOAuthClients` need
`https://www.googleapis.com/auth/carddav` on their consent-screen scope list. If SHP-1159 has landed
this by the time this story ships, nothing further is needed here.

One thing that *is* new and needs on-device confirmation once the scope is registered: that Google's
`include_granted_scopes` parameter (see *Key decision* below) actually returns the union of old and new
scopes for these specific OAuth clients. Nothing in this spec can confirm that from a compile.

## What existing code already provides

- `KompaktGrantedServices.fromAuthState(authState)` already maps a persisted `AuthState`'s scope set to
  the DAVx5 service types it consents to. This story adds no new mapping — it reuses this one, the same
  way SHP-1159 and `KompaktReauthModel` already do.
- `KompaktLinkedAccountModel.readContactsSwitch()` already derives `ConsentMissing` / `On` / `Off` for
  Contacts from the persisted `AuthState` plus the CONTACTS sync interval. This story generalizes that
  derivation to Calendar, which today only has two states (`On`/`Off`) because Calendar used to be the
  one scope every account was guaranteed to have.
- `KompaktLoginActivity` already branches on an extra to run a second mode beside "new link": reauth,
  driven by `KompaktReauthModel`, reuses the same OAuth/WebView/PKCE launch but skips the detect-resources
  UI entirely because reauth never creates a service. This story adds a **third** mode with the same
  shape: its own extras, its own post-OAuth model, and — like reauth — no detect-resources UI, because
  discovery here is silent background work, not a user-facing step.
- `KompaktModalSheet` is the existing "explain + confirm" dialog, already used for unlink and for
  confirming a calendar-sync disable. This story's "Enable / Not now" dialog is another instance of it,
  not a new component.

## What's missing (the actual gap this story closes)

No code anywhere — upstream or Kompakt — adds a service to an account that already exists.
`AccountRepository.createBlocking` is gated on the account name **not** already existing: it calls
Android's `AccountManager` account creation first, which fails for an existing name, and
`createBlocking` returns `null` without touching any `Service` row. The reusable per-service pieces
(`DavServiceRepository.insertOrReplaceBlocking`, the home-set/collection inserts, enqueuing
`RefreshCollectionsWorker`) exist and are public, but they are only ever assembled together inside
`AccountRepository`'s **private** `insertService`, itself reachable only through that gated
`createBlocking`.

`KompaktReauthModel` has the identical gap today: if a re-authorization ever grants a scope the account
never had a service for, it updates the stored token and enqueues a sync, but never creates the new
service — there is nothing to sync. That's `KompaktReauthModel`'s to fix, and belongs with SHP-1180
("changed consents during reauthentication"), not this story. It's flagged here because this story
builds the exact primitive SHP-1180 would need to close it.

## Key decision: how the reduced-scope request stays safe to persist

AC 9 requires the consent screen to show only the missing permission, not the one already granted. But
consent for *both* services is read from one persisted `AuthState`'s scope set
(`KompaktGrantedServices.fromAuthState`) — so whatever scope set this flow ends up persisting has to
still contain the other service's already-granted scope, or that service silently reads as
`ConsentMissing` again, breaking AC 12.

**A. Request only the missing scope, with Google's `include_granted_scopes=true`.** Google returns an
access token whose granted-scope set is the *union* of every scope previously granted to this user for
this OAuth client plus the newly granted one — while the consent screen itself shows only the new
permission being requested. `updateAuthState` can then overwrite the stored `AuthState` wholesale, as it
already does for reauth, without losing the other service's consent. Chosen.

**B. Request only the missing scope, then manually merge scope sets before persisting.** Rejected: the
actual access token Google issues for a reduced-scope grant only carries that reduced scope. A locally
merged bookkeeping set would claim consent our token doesn't have — the other service's next API call
would 401 against reality, not just against our local read of it.

**C. Always request both scopes**, the same as reauth already does. Rejected: violates AC 9's letter,
and re-surfaces a consent checkbox for a permission already granted — exactly what this story exists to
avoid.

So `KompaktOAuthGoogle.signIn` gains a `scopes` parameter (defaulting to both, so every existing caller
— login, reauth — is unaffected) and an `includeGrantedScopes: Boolean` flag that sets Google's
`include_granted_scopes` request parameter. Only this story's new flow passes a single-scope set with
the flag on.

## Design

### 1. Generalize consent state to Calendar

`KompaktLinkedAccountModel`'s Contacts-only `readContactsSwitch` / `readContactsConsent` /
`readContactsAutoSyncEnabled` triplet becomes one shared, service-parameterized helper. Calendar's
`_calendarSwitch` gains the same `ConsentMissing` branch Contacts already has, driven by
`Service.TYPE_CALDAV in KompaktGrantedServices.fromAuthState(...)`. Both switches now re-read on the
same `authStateChanges` observer that already drives Contacts today, so a consent change — from this
story's flow or from a future reauth — lands without a screen restart.

### 2. New repository primitive

```kotlin
// AccountRepository
fun addServiceBlocking(
    accountName: String,
    type: String,
    info: DavResourceFinder.Configuration.ServiceInfo
): Long
```

Extracted from the existing private `insertService` (same signature shape), made public, with the
`AndroidAccountUtils.createAccount` step removed — the Android account already exists on this path.
Does what `insertService` already does for a freshly-created account: insert the `Service` row plus its
home sets and collections, set the CardDAV group method, enqueue `RefreshCollectionsWorker`, and update
automatic sync. The caller is responsible for confirming no row of that type already exists first (this
story's flow always does, via the diff in step 4) — like `insertOrReplaceBlocking` underneath it, a
second call for a type that already has a row would replace it, which is never the intended path here.

**Accepted exception to "add files, don't edit upstream files":** `addServiceBlocking` is added
directly to `AccountRepository.kt` rather than a new `Kompakt*` file. The convention exists to keep an
otherwise-untouched upstream file conflict-free on rebase, and that no longer applies here —
`AccountRepository.kt` already carries a Kompakt edit from SHP-511 ("Notify that account was deleted"),
so it is not an upstream file this changes the status of. A same-file addition next to that one is a
smaller diff than routing through a second class purely to keep a file that is already ours file-clean.
Don't re-flag this in review.

### 3. Reduced-scope OAuth request

`KompaktOAuthGoogle.signIn(email, customClientId, scopes = SCOPES, includeGrantedScopes = false)`. This
story's flow calls it with `scopes = setOf(missingScope, "openid", "email")` and
`includeGrantedScopes = true`.

### 4. Launch and apply — a third `KompaktLoginActivity` mode

New extras on `KompaktLoginActivity`: the target account name and which service type is missing (the
screen already knows this from `state.calendar.switch` / `state.contacts.switch`, so it's passed
explicitly rather than re-derived). A new `KompaktAddConsentModel`, sibling to `KompaktReauthModel`:

```kotlin
sealed interface AddConsentState {
    data object Authenticating : AddConsentState
    data object Applying : AddConsentState
    data object Granted : AddConsentState
    data object Denied : AddConsentState        // AC 16 — user cancelled/declined; no error surfaces
    data object Failed : AddConsentState        // AC 17 — technical failure; retry offered
}
```

Unlike reauth, a different Google account authenticating here is **not** a switch-account path (AC 10)
— it's classified the same as `Failed`, since the additional permission must land on the same account.
On success: `updateAuthState(newAuthState)`, then run `DavResourceFinder.findInitialConfiguration()`
(the same call `LoginScreenViewModel` already makes) and act only on the `ServiceInfo` for the
requested type — the already-linked service's rows are never touched, satisfying AC 12. Call
`addServiceBlocking` for it, then set its sync interval to
`KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS`, mirroring what SHP-1159 already does for a first-time
grant — this is what makes the toggle read `On` immediately (AC 15), with no detect-resources UI and no
extra waiting step.

### 5. Screen wiring

Both `KompaktServiceSyncCell.onCheckedChange` handlers gain one new branch, checked first: turning the
toggle on while its `switch == ConsentMissing` shows a `KompaktModalSheet` ("Enable" / "Not now" — AC
4-6) instead of reaching the existing granted-path handler. "Not now" just dismisses — no state changes,
no OAuth launch (AC 6, 7). "Enable" launches the new `KompaktLoginActivity` mode via an
`ActivityResultLauncher`, the same pattern `onReauthorize` already uses. On `Denied`, the screen returns
silently (AC 16). On `Failed`, a new `KompaktLinkedAccountDialog.ConsentGrantFailed` case surfaces with
a retry action that relaunches the same flow (AC 17). The Contacts toggle's granted-path branch is left
exactly as it is today — `/* SHP-1156 */`.

### 6. One-time Contacts discovery nudge

A new `AccountManager` user-data key (e.g. `KEY_CONTACTS_CONSENT_PROMPT_SHOWN`), read once when the
screen first collects state. Shown automatically — same dialog as the toggle-triggered one — only when
Calendar is already granted **and** Contacts reads `ConsentMissing` **and** the flag isn't set yet;
setting the flag happens the moment the dialog is shown, regardless of Enable/Not now/dismiss, so it
never reappears (AC 1). This does not apply symmetrically to Calendar: a Calendar-missing account can
only exist from a fresh SHP-1159 partial grant, where the user already saw the consent screen at link
time, so there's nothing to "discover" — AC 3 covers that case as toggle-only, with no automatic popup.

## Acceptance criteria traceability

| AC | Where it is satisfied |
|---|---|
| 1 | Design §6 — one-time flag, Contacts-only |
| 2, 3 | Design §5 — `ConsentMissing` branch on both toggles; §1 gives Calendar the state to check |
| 4, 5, 6 | Design §5 — `KompaktModalSheet` before launch; "Not now" starts nothing |
| 7 | Design §1, §5 — no state mutation on "Not now"; the other service's switch is untouched |
| 8 | Design §4 — the OAuth webview, launched the same way reauth's is |
| 9 | Design §3, Key decision — single-scope request with `include_granted_scopes` |
| 10 | Design §4 — mismatched account classified as `Failed`, not a switch |
| 11 | Design §4 — `updateAuthState` in place on the same account; `addServiceBlocking` adds a row, never a second account |
| 12 | Key decision (the scope union) + Design §4 (only the requested type's rows are touched) |
| 13, 14 | Design §4 — the service row (and therefore any sync) only exists after `addServiceBlocking` runs, which only runs after a successful grant |
| 15 | Design §4 — sync interval set immediately on success |
| 16 | Design §4, §5 — `Denied` state, silent return |
| 17 | Design §4, §5 — `Failed` state, `ConsentGrantFailed` dialog, retry restarts `Authenticating` |
| 18 | Upstream `KEY_AUTH_STATE` / Room `Service` persistence — no new storage mechanism, same as SHP-1159 AC 14 |

## Verification

Unit tests in `core`:

- the generalized consent-switch derivation, for both services, across `ConsentMissing`/`On`/`Off`;
- `KompaktOAuthGoogle.signIn` carries the right scope set and `include_granted_scopes` parameter for a
  single-scope request, alongside the existing PKCE guard in `KompaktOAuthGoogleTest`;
- `AddConsentState` classification: same account + granted scope → `Granted`; different account →
  `Failed`; empty grant → `Denied`.

Compile and lint as the repo requires: `:app-ose:assembleDebug`, `:core:lintDebug`,
`:app-ose:lintOseDebug`, `:core:testDebugUnitTest`.

**Cannot be claimed from a compile** and needs a real Kompakt with the `carddav` scope registered: the
reduced consent screen itself, confirming `include_granted_scopes` actually unions the scopes in the
returned token for these OAuth clients, and each direction of the missing-permission flow end to end.

## Contract and downstream notes

- **`docs/app-integration.md` needs no change.** Same reasoning as SHP-1159: the auth-state contract it
  publishes is per account, not per service.
- **Resolve once SHP-1156/1158 land.** The Contacts toggle's granted-path branch (turning an
  already-consented service on/off) stays a stub in this story. Wiring it is those stories' job, not a
  blocker to shipping this one.
- **Resolve once SHP-1180 is refined.** `KompaktReauthModel`'s identical "grants a new scope, never
  creates the service" gap is out of scope here, but should reuse `AccountRepository.addServiceBlocking`
  once SHP-1180 is ready to fix it — no need to design that mechanism twice.
- **Not addressed.** SHP-1152's "Import now / Not now" prompt doesn't exist yet for the original link
  flow either. Granting Contacts consent through this story's flow triggers no import UI — an existing
  gap, not a new one.
