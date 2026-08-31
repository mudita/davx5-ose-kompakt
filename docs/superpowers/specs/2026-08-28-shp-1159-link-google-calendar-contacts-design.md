# SHP-1159 — Link a Google account for Calendar and Contacts for the first time

Design spec. Epic SHP-1148 ("M1: Two-way Google contacts synchronisation").

## Problem

The Kompakt linking flow authorizes Calendar only. `KompaktOAuthGoogle` requests
`.../auth/calendar` plus `openid` and `email`, so the CardDAV probe during resource detection has no
token to present, no CardDAV service is ever created, and the Contacts section that SHP-1160 added to
the Linked Account screen is hardcoded to "consent missing".

This story extends the first-time linking flow to request Contacts consent as well, to honour a
partial grant, and to configure only the services the user actually authorized.

## Scope

In scope: the OAuth scope set, reading which scopes were granted, creating the DAVx5 services that
match the grant, discovering the Google address books, and the Contacts consent state the Linked
Account screen renders.

Out of scope, and owned by sibling stories:

| Concern | Story |
|---|---|
| Preselecting an address book collection for sync | SHP-1156 |
| Contacts toggle behaviour | SHP-1156 |
| First Contacts import ("Import now" / "Not now") | SHP-1152 |
| Per-service sync status and last-sync time | SHP-1155 |
| Manual sync per service | SHP-1157 |
| Granting a missing consent to an already-linked account | SHP-1151 |
| Consent changes during re-authorization | SHP-1180 |

A consequence worth stating plainly: between this story and SHP-1156 a linked Contacts service is
configured but cannot move any data, because no address book collection is selected for sync. That is
the agreed boundary, not an oversight.

## Prerequisite outside the codebase

Both OAuth clients registered in `KompaktGoogleOAuthClients`, and the fallback client, must have
`https://www.googleapis.com/auth/carddav` added to their consent-screen scope list in Google Cloud
Console. It is a sensitive scope, so the client may have to pass verification again.

Until that is done, sign-in fails **at Google** (`invalid_scope`, or an unverified-app warning) rather
than in our code — the same class of failure `KompaktGoogleOAuthClients` already warns about for an
unregistered signing key. Everything in this spec compiles and unit-tests without it; nothing can be
confirmed on a device until it lands.

## What upstream already provides

Most of the Contacts plumbing is upstream code this fork has been suppressing, not code to write.

- Upstream's `OAuthGoogle` already requests `.../auth/carddav`, and the **same**
  `apidata.googleusercontent.com` base URI already drives CardDAV discovery over well-known URLs. Our
  `KompaktOAuthGoogle` simply dropped that scope.
- `AccountRepository.createBlocking` inserts a CardDAV service and enqueues `RefreshCollectionsWorker`
  whenever the detected configuration has a CardDAV part. That covers AC 16, 17 and 19.
- `createBlocking` returns `null` when an account of that name already exists, and the account name is
  the e-mail parsed from the ID token, so AC 18 (idempotency) already holds.
- `LoginScreenViewModel` proceeds past detection when **either** service is found, and its account-name
  fallback chain reaches `LoginInfo.suggestedAccountName` — the ID-token e-mail — so a Contacts-only
  link still names the account correctly even though the CalDAV principal supplied no e-mail.
- The granted scope set is persisted per account under `AccountSettings.KEY_AUTH_STATE`, so AC 14
  (survives a restart) needs no new storage.

## Key decision: consent is the granted scope set

"Does this account have Contacts consent?" can be answered three ways.

**A. Read the scope set from the persisted `AuthState`.** `KompaktReauthModel` already does exactly
this for Calendar. Chosen.

**B. Treat "a CardDAV service row exists" as consent.** Rejected — it is a correctness bug, not just
an inelegance. A transient discovery failure is indistinguishable from a denied scope, so the user
would be told to grant a permission they already granted; SHP-1151's "grant the missing consent" flow
would then re-request a scope Google already holds and silently do nothing.

**C. Let Kompakt own detection and creation** — run `DavResourceFinder` once per granted service and
call `createBlocking` with a filtered configuration. Fully explicit and dependent on no server
behaviour, but it duplicates upstream's detection state, retry, account-name suggestion and group
method into a `Kompakt*` twin: a large new surface to carry across every rebase, bought for something
one scope read already gives us. Rejected for now; revisit if SHP-1151 or SHP-1180 force per-service
detection anyway.

So the granted scope set is authoritative. Service rows are a *consequence* of consent, never the
source of truth for it.

## Design

### Granted-services helper

A new pure file, `core/src/main/kotlin/at/bitfire/davdroid/network/KompaktGrantedServices.kt`, maps an
`AuthState` to the set of DAVx5 service types it consents to (`Service.TYPE_CALDAV`,
`Service.TYPE_CARDDAV`). No Android dependencies, so it is unit-testable in `core` — which is where
tests must live, since `app-ose` has none.

Every consumer of consent goes through this one function — the login view model, the Linked Account
model, and the re-auth model — with one exception: the finalize model reads the DB service list
instead, since a service row exists only for a granted scope, which makes the two sets identical there
and the service list far cheaper to read.

### 1. Request the Contacts scope

`KompaktOAuthGoogle` gains `SCOPE_CONTACTS = "https://www.googleapis.com/auth/carddav"` in its
companion, added to `SCOPES` alongside `SCOPE_CALENDAR`.

The request then carries calendar, carddav, `openid` and `email` and nothing else, satisfying AC 3
("only the permissions required"). AC 4 — that each permission reads as belonging to its
synchronization feature — is Google's consent-screen wording, determined entirely by which scopes are
registered and requested; there is nothing to implement beyond requesting the right two.

### 2. Refuse to link when nothing was granted

`KompaktGoogleLoginViewModel.authenticate()` computes the granted services after the token exchange.
When the set is empty it produces **no** `LoginInfo` and reports an error instead, so the flow never
reaches detection or account creation: nothing is created and no partial authorization is stored
(AC 11, 12, 13).

Google may also return `access_denied` outright when the user checks nothing and continues. Both paths
end on the same screen.

### 3. Distinguish "refused consent" from "couldn't reach Google"

`KompaktGoogleLogin`'s existing error state reads "Couldn't connect to Google". A refused scope is not
a connection failure, and the re-auth flow already established
`calendar_accountsync_error_h1_couldntsetupyouraccount` for precisely this situation.

So `KompaktGoogleLoginViewModel.UiState` carries a second error kind, and the screen picks the copy
accordingly. Both render the same "Try again" button, which restarts the whole sign-in. Both strings
already exist in `frontitude` (`calendar_accountsync_error_h1_couldntsetupyouraccount`,
`calendar_accountsync_error_h1_couldntconnecttogoogle`, `common_dialog_button_tryagain`); no new copy
is needed and none may be hand-written into `frontitude`.

### 4. Wait for the discovered services, whichever they are

`KompaktLoginFinalizeModel` is already the post-creation hook — it runs between account creation and
navigating to the Linked Account screen. `awaitSetup` currently waits for the **CalDAV** service's
`RefreshCollectionsWorker`, so a Contacts-only link would skip the wait entirely and reach the
"Account linked" dialog before the address books exist. It must wait for every service the account
actually has.

Services only exist for granted scopes — Google rejects the probe for a scope the token lacks — so
"every service this account has" and "every service consented to" are the same set here, and the
simpler of the two is the one to read.

**An earlier draft of this spec also had this step delete any service whose scope was not granted.
That is dropped.** `ServiceDao` and `DavServiceRepository` have no per-service delete, so it would
mean editing two upstream files for a case that cannot arise in this flow: an ungranted service is
never discovered, and were one somehow created, its every request would 401 anyway. The scenario that
*can* produce a stale service — re-authorizing with reduced consent — belongs to SHP-1180, which is
where the cost of a per-service delete should be weighed.

This satisfies AC 6, 7 and 8: with both scopes granted, one account is configured for both services;
with one, only that service is configured; the other stays unavailable until SHP-1151 supplies the
missing consent.

One honest caveat about AC 5. Upstream's `DavResourceFinder.findInitialConfiguration()` probes CalDAV
**and** CardDAV unconditionally, and this fork does not intervene there (that is approach C, rejected
above). So an ungranted service's well-known URL is still requested once during detection. The token
carries no such scope, Google rejects the request, and no data is read or stored — the guarantee AC 5
actually asks for. Suppressing the request itself would mean owning detection.


### 5. Report real Contacts consent on the Linked Account screen

`KompaktLinkedAccountModel` currently holds a constant `contactsState` whose own comment says a later
story replaces it with a real per-service source. This is that story.

The Contacts switch is derived from the persisted `AuthState`: `ConsentMissing` when the carddav scope
is absent, otherwise `On`/`Off` from the CONTACTS sync interval. It is re-read through the same
`authStateChanges` `ContentObserver` that the `needsReauth` flag already uses, so a consent change
lands without a screen restart.

The Contacts status stays `NeverSynced` (AC 10) — nothing can have synced yet, and real status is
SHP-1155's job. AC 15 (manage both services from Linked Account settings) is then satisfied by the
sections SHP-1160 already built.

### 6. Set the Contacts sync interval explicitly

Creating a CardDAV service makes `AutomaticSyncManager.updateAutomaticSync` read
`getSyncInterval(CONTACTS)`, which with no key set falls back to upstream's `DEFAULT_SYNC_INTERVAL` of
four hours. Contacts would quietly acquire a four-hourly periodic worker at link time.

When Contacts consent is granted, the flow therefore sets the CONTACTS interval explicitly to
`KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS` (24 h; 15 min in debug), mirroring what
`KompaktInitDefaults` does for EVENTS on first setup. That also makes the toggle read **On**, which is
what SHP-1156 and SHP-1155 expect from a granted consent.

This moves no data: no collection is preselected, so those syncs are no-ops until SHP-1156. The
alternative — leaving upstream's four-hour default in place — is simply wrong for this device.

## Acceptance criteria traceability

| AC | Where it is satisfied |
|---|---|
| 1 | Unchanged — the flow is already offered when no account is linked |
| 2 | Unchanged — `KompaktGoogleLogin` launches the request immediately |
| 3 | Design §1 — calendar, carddav, `openid`, `email`, nothing more |
| 4 | Design §1 — Google's consent screen, driven by the registered scopes |
| 5 | Design §4 — detection's probe is rejected by Google and no service is created, so no data is read |
| 6 | Design §4 — one account, both services, when both scopes are granted |
| 7, 8 | Design §4, §5 — only granted services configured; the other reads `ConsentMissing` |
| 9, 10 | Design §5 — Contacts status stays `NeverSynced` after linking |
| 11, 12, 13 | Design §2, §3 — empty grant produces no `LoginInfo`; "Try again" |
| 14 | Upstream `KEY_AUTH_STATE` persistence; no new storage |
| 15 | SHP-1160's sections, now backed by real consent |
| 16, 17, 18 | Upstream `AccountRepository.createBlocking` |
| 19 | Upstream `RefreshCollectionsWorker`, enqueued for the CardDAV service |

## Verification

Unit tests in `core`:

- the authorization request carries both data scopes, alongside the existing PKCE guard in
  `KompaktOAuthGoogleTest`;
- the granted-services mapping, including both scopes, each scope alone, and neither;
- the reconciliation decision — which services survive a given grant.

Keeping the mapping and the reconciliation decision as pure functions avoids needing a ViewModel test
harness that `core` does not currently have.

Compile and lint as the repo requires: `:app-ose:assembleDebug`, `:core:lintDebug`,
`:app-ose:lintOseDebug`, `:core:testDebugUnitTest`.

**Cannot be claimed from a compile** and needs a real Kompakt with the OAuth client updated: the
consent screen itself, granular checkbox selection, each partial-grant combination, and address-book
discovery.

## Contract and downstream notes

- **`docs/app-integration.md` needs no change.** The auth-state contract it publishes is per account
  (`needs_reauth`), not per service; nothing it names moves.
- **Downstream blocker, not this story.** Contacts sync will need the runtime `READ_CONTACTS` and
  `WRITE_CONTACTS` grants. They are declared through `synctools`, but upstream's `PermissionsScreen` is
  unreachable on Kompakt, so something must grant them before SHP-1156 or SHP-1152 can work.
- **Known edge, deliberately not fixed here.** `KompaktInitDefaults.ensureApplied` returns `NOT_READY`
  forever for a Contacts-only account, because it looks up a CalDAV service that does not exist, so it
  re-attempts on every `syncNow`. Harmless today — it is a fast no-op that enqueues nothing — and it
  belongs with SHP-1157's per-service manual sync.
