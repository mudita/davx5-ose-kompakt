# Per-service sync toggle management (SHP-1158 Calendar, SHP-1156 Contacts)

**Date:** 2026-09-02
**Branch:** `task/SHP-1158-per-service-sync-toggle`, cut from `task/SHP-1158-account-data-seam`
(`79242b123`), which is three commits on `release/1.1.0`.
**Status:** design, approved in brainstorming.
**Supersedes:** `backup/SHP-1156-attempt0` (abandoned) and `task/SHP-1158-calendar-sync-toggle`
(unfinished). Neither document is on this branch. What survives from them is cited below where it is
used; the rest is deliberately dropped.

## The one-line summary

One toggle implementation, parameterised by a two-entry service enum, instantiated for Calendar and
Contacts. The toggle gates automatic sync, manual sync and external sync requests, cancels an active
run when switched off, and triggers one when switched on.

---

## Contract change — read this first

`docs/app-integration.md` changes, and the Kompakt calendar app's expectation changes with it.

`KompaktSyncRequestReceiver` today calls `SyncWorkerManager.enqueueOneTimeAllAuthorities`, which fans
out over every `SyncDataType` entry and ignores every toggle. AC 6.2 requires a switched-off service to
be excluded from synchronization, and an external request is a synchronization trigger like any other.
So the receiver enqueues only enabled services, and **a `REQUEST_SYNC` broadcast may now enqueue
nothing at all.**

The published contract lists six conditions under which the broadcast is a no-op. A **seventh** is
added: no service is currently eligible — every service is switched off, has no consent, or has no
`Service` row. Nothing about the action, target package, permission or the 15-minute throttle changes,
so no caller code has to change for the broadcast to keep being accepted.

A second, smaller change in the same area: today the receiver skips an entire account when
`KompaktInitDefaults.ensureApplied` reports `NOT_READY`. That gate becomes per-service, so an account
whose Calendar is not ready can still have Contacts enqueued. This is strictly more permissive and sits
inside the same no-op condition.

**The calendar app's owner needs telling.** No code change is required of them, but "I broadcast,
therefore a sync runs" stops being true, and that is the assumption a caller is most likely to hold.
The doc is updated in the same commit as the receiver.

---

## Scope

**In:** SHP-1158 (Manage Google Calendar synchronization) and SHP-1156 (Manage Google Contacts
synchronization). The two stories are the same acceptance criteria with the noun swapped, so they are
one implementation instantiated twice.

**Out, with owners:**

| Story | Why not here |
|---|---|
| SHP-1155 view sync progress and results | Owns the status wording and the last-sync format. `lastSyncFormatter` produces `dd.MM.yyyy · HH:mm`; AC 9 there requires `Today 11:30` / `Yesterday 11:30` / `Mon 11:00`. `KompaktServiceSyncCell`'s own preview constant is a format the code cannot produce. |
| SHP-1150 handle failed manual synchronization | Needs a persisted per-service sync result. Non-terminal periodic work retains no `outputData`, so a scheduled failure is not observable at all today. |
| SHP-1157 manual synchronization | Owns the "no service enabled" dialog and per-service request throttling. With everything off, "Synchronize now" here stays a silent no-op. |
| SHP-1151 add consent to an existing account | Owns what happens when a `ConsentMissing` switch is tapped. This design produces that state for the first time; wiring the tap is theirs. |
| Address-book collection selection | Blocked on the D1–D3 data-loss fixes. See *The line this design does not cross*. |

---

## What already holds, and what does not

Verified by reading the code on this branch, not inherited from either attempt's table.

| AC | Today |
|---|---|
| 1, 5 — toggle present, on and off | holds for Calendar; the Contacts switch is literally `onCheckedChange = {}` |
| 2 — default follows link-time consent | see *Consent stops being an invariant* |
| 3.1, 6.1 — automatic sync enabled/disabled immediately | holds; `AccountSettings.setSyncInterval` performs `AutomaticSyncManager.updateAutomaticSync` |
| 4 — once per 24 h | holds; `KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS` |
| 8 — switching off deletes nothing | holds; no path deletes on an interval change |
| 7.4, 7.5 — a cancelled run is not a success, previous time preserved | holds **given** the ordering in *Switching off*; `SyncManager` calls `logSyncTime` only at completion and only when the result has no error |
| **3.2, 6.2 — manual-sync eligibility follows the toggle** | **missing** — both enqueue sites ignore it |
| **7.1–7.3 — switching off terminates the active sync** | **missing** — nothing cancels |
| **9 — switching on triggers a sync** | **missing** |
| **all of SHP-1156** | **missing** |

Three behaviours to add, plus the second service.

### Consent stops being an invariant, in this release

Until now a linked account necessarily carried Calendar consent: `KompaktReauthModel` refused a token
whose scope set lacked the calendar scope, and at link time a denied scope makes Google's CalDAV
endpoint answer 403, so no service is discovered and no account is created. AC 2 therefore reduced to
"default on".

`origin/task/SHP-1159-link-calendar-contacts`, in *Development in Review*, removes that. It adds the
CardDAV scope to the requested set, adds `KompaktGrantedServices` (granted scopes to `Service.TYPE_*`),
and rewrites `KompaktReauthModel` to apply a token granting **either** scope. Its own AC 7 and AC 8 make
granular consent explicit: only authorized services are configured, and an unauthorized service stays
unavailable until the user consents again.

So consent is a real per-service input, for Calendar as well as Contacts, and
`KompaktSyncSwitch.ConsentMissing` — shipped by SHP-1160 and produced by nothing — gets its first
producer.

Neither `KompaktGrantedServices` nor `SCOPE_CONTACTS` exists on this branch's base, so this design
carries the scope on `KompaktSyncService` and adds the constant with SHP-1159's exact name and value.
See *Decisions* 1 for the reconciliation, which is a decision to take, not a merge to discover.

---

## Decisions

### 1. The service abstraction is an enum of Kompakt services, not upstream's `SyncDataType`

```kotlin
enum class KompaktSyncService(
    val dataType: SyncDataType,
    @ServiceType val serviceType: String,
    val scope: String
) {
    CALENDAR(SyncDataType.EVENTS, Service.TYPE_CALDAV, KompaktOAuthGoogle.SCOPE_CALENDAR),
    CONTACTS(SyncDataType.CONTACTS, Service.TYPE_CARDDAV, KompaktOAuthGoogle.SCOPE_CONTACTS)
}
```

Its value is **not** that it removes `when` branches — measured across the abandoned attempt, the
field-level payoff was small. Its value is that it is the **iteration axis**. `SyncDataType.entries`
includes `TASKS`, which is why every manual sync today enqueues a tasks worker that can never do
anything on this device. `KompaktSyncService.entries` is exactly the set the screen renders, the
receiver fans out over, and eligibility filters.

Three fields, and no more. No `collectionType` — its only consumers are inside `KompaktInitDefaults`,
where the selection policy is a `when (service)` anyway.

**`scope` is carried here rather than consumed from SHP-1159's `KompaktGrantedServices`, because that
object does not exist on this branch's base** — neither it nor `KompaktOAuthGoogle.SCOPE_CONTACTS` is on
`release/1.1.0`; both arrive with SHP-1159. A design that consumed it would not compile.

So `SCOPE_CONTACTS` is added to `KompaktOAuthGoogle`'s companion here, with the **same name and value**
SHP-1159 gives it, and *not* added to that class's requested `SCOPES` array — requesting the scope is
SHP-1159's decision, not this branch's. An identical constant on both branches merges cleanly.

When the branches meet there are briefly two homes for one mapping. The enum is the one to keep:
`KompaktGrantedServices.fromAuthState` returns a `Set<String>` of service types that every caller must
then match against something, which is what the enum already is. Reconciliation is to reimplement
`KompaktGrantedServices` over `KompaktSyncService.entries` or delete it — recorded in *Interaction with
SHP-1159* so it is a decision rather than a discovery.

### 2. Seven small components; no controller

The UI layer already has the shape this needs. `serviceSyncState(switch, syncing, lastSync, failed)`
combines four *independent* inputs and `syncStatus` deliberately ignores the switch, so a switched-off
service keeps the last-sync time it earned. This design supplies per-service sources for those inputs
and merges nothing.

The abandoned attempt reached a 289-line class with ten dependencies by giving one object the switch,
liveness, a four-level last-sync pipeline, run-outcome attribution, state assembly and six actions.
Its own handoff diagnosed the cause correctly: those are separate small things that were never given
names.

### 3. Contacts ships as a live toggle

Leaving it inert stops being defensible once SHP-1159 lands: real consent would sit behind a dead
switch. The enum makes the second instantiation structural rather than extra work.

What Contacts cannot do yet is sync anything useful — see *The line this design does not cross*.

### 4. `KompaktInitDefaults` becomes per-service

Not optional, for two reasons — one of which this design creates.

**Created here.** `KompaktServiceSwitch.setEnabled` marks defaults applied so the init routine cannot
flip a deliberate choice back on. `KEY_DEFAULTS_APPLIED` is one account-global key and `maybeApply`
short-circuits on `ALREADY_APPLIED` *before* it looks at any service, so with two services, switching
Calendar off would silently suppress Contacts defaults forever.

**Pre-existing.** `ensureApplied` resolves `Service.TYPE_CALDAV` and returns `NOT_READY` when it is
absent, and `KompaktSyncRequestReceiver` skips the whole account on `NOT_READY`. SHP-1159 AC 7/8 make a
Contacts-only account a legal state; such an account would never sync at all, and nothing would report
an error.

### 5. The last-sync *source* is owned here; the *format* stays with SHP-1155

No AC in either story needs a last-sync value directly — AC 7.5 holds because `logSyncTime` is never
reached on a cancelled run. But leaving the read alone would pin the Contacts row at *Not synced yet*
permanently, because today's read is `getLastSyncedFlow(primaryCollectionId)`: the **primary calendar
alone**, resolved by matching the account email against a collection URL, then filtered to `EVENTS` by
string. There is no primary address book, so nothing about that generalises.

It is also narrower than the question it is asked. It equals "when did this service last sync" only
because `KompaktInitDefaults` happens to select exactly one calendar; it diverges the moment a second
collection is selected.

So `KompaktServiceLastSync` answers the question per service and returns **epoch millis**.
`lastSyncFormatter` and the 12/24-hour and locale reactivity stay in the ViewModel, shared by both rows,
and the wording and format remain SHP-1155's to change.

### 6. Liveness moves to `AccountProgressUseCase`

Beyond the AC list, and justified by AC 7. Today's hand-rolled flow watches only the one-time unique
work for `EVENTS`, so a periodic or content-triggered sync never shows *Syncing now* — which makes
AC 7 unobservable exactly when it matters, since a scheduled run is the one a user is most likely to be
cancelling.

The use case already handles the trap that periodic work is never terminal: it queries `RUNNING` via
`commonTag` (both workers) and `ENQUEUED` via the one-time name only, so merging the periodic unique
name cannot pin the status on. For `CONTACTS` it also queries the address-book accounts, which is where
contacts sync actually runs.

**Both `AccountProgress.Active` and `AccountProgress.Pending` map to `KompaktSyncStatus.Syncing`.**
Stated explicitly because it is a regression otherwise: today's check treats RUNNING, ENQUEUED and
BLOCKED alike. Mapping only `Active` would leave a just-tapped sync showing nothing until the worker
starts — the worst moment to go blank on a screen that repaints slowly.

One consequence accepted: collection discovery also reads as *Syncing now*, because the use case folds
`isServiceRefreshing` in. Right after linking, that is honest.

---

## Components

Each new seam is interface plus adapter plus a Hilt `@Binds` module in one file — the `LoginValidator`
pattern already used in this module — so the adapter is the only new file importing the platform class.
Every component takes `(account, service)` parameters rather than being assisted-injected per service,
so there is no per-service instance to construct and `KompaktSyncRequestReceiver` can use the same
objects without a ViewModel.

### `sync/KompaktSyncService.kt`

The enum above, plus the one rule two components share:

```kotlin
internal fun KompaktSyncService.isConsented(authState: AuthState?): Boolean =
    authState?.scopeSet?.contains(scope) == true
```

Pure, takes the `AuthState` rather than fetching it, so both callers decide their own freshness and it
tests with no mocks.

A `null` `AuthState`, an unparseable one and one with no `scopeSet` all answer `false`. That is the
conservative direction: it can hide a grant the user actually has, and the user recovers by re-consenting;
the opposite default would enqueue a sync into a 403 and the D1 deletion chain.

The service-row lookup shared by the liveness flow and `KompaktServiceLastSync` is
`DavServiceRepository.getServiceFlow(accountName, serviceType)`. `ServiceDao.getByAccountAndTypeFlow`
is already general; the repository merely lacked a flow accessor for it, exposing only two
type-specific wrappers. A Kompakt-side `when` over those two would have carried no logic — it would
have re-implemented by dispatch what the query already does by parameter.

### `sync/KompaktServiceToggle.kt` — the persisted toggle

```kotlin
interface KompaktServiceToggle {
    fun isOn(account: Account, service: KompaktSyncService): Boolean
    fun observe(account: Account, service: KompaktSyncService): Flow<Boolean>
    suspend fun set(account: Account, service: KompaktSyncService, on: Boolean)
}

internal fun toggleOn(intervalSeconds: Long?): Boolean =
    intervalSeconds == KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS
```

Its only dependency is `KompaktAccountSettings`, from which it inherits both properties that matter.
`isOn` is a raw user-data read, cheap enough for any thread — though the screen no longer seeds itself
from one; see *The switch starts at `Resolving`*. `set` routes through
`KompaktAccountSettings.setSyncInterval`, which delegates to `AccountSettings.setSyncInterval` — and
that call is what performs `updateAutomaticSync`, enabling or cancelling the periodic worker and the
sync framework's content trigger. Those two halves *are* AC 3.1 and AC 6.1; a raw write would satisfy
neither.

The read is deliberately raw. `AccountSettings.getSyncInterval` substitutes a four-hour default for an
absent key and so cannot distinguish "never configured" from "configured to four hours".

**A boolean, not a tri-state.** An absent key is a real third condition — upstream schedules a
four-hourly worker for it — but nothing in this design branches on it differently from *off*, so naming
it would be a distinction no code reads. The window it describes is recorded under *Invariants*.

### `sync/KompaktSyncWork.kt` — one service's work

```kotlin
interface KompaktSyncWork {
    suspend fun enqueue(account: Account, service: KompaktSyncService, manual: Boolean = true): UUID?
    suspend fun cancel(account: Account, service: KompaktSyncService)
}
```

The only new file that touches `WorkManager`.

`enqueue` calls `SyncWorkerManager.enqueueOneTimeReturningId`, which is `private`. That method is the
**fork's own** addition (SHP-1046), not upstream's, so widening its visibility costs nothing at the next
rebase. Calling `enqueueOneTimeAllAuthorities` and picking one entry out of the returned map is what the
ViewModel does today and is precisely what AC 6.2 forbids.

`cancel` cancels the unique work named by `OneTimeSyncWorker.workerName(account, service.dataType)`.

Two methods, deliberately. The abandoned attempt's version of this seam also carried run-outcome parsing
and a discovery-transition flow; those belong to SHP-1150 and SHP-1155 and are what turned it into a
module.

### `sync/KompaktSyncEligibility.kt` — which services may enqueue

```kotlin
class KompaktSyncEligibility @Inject constructor(
    private val accountSettings: KompaktAccountSettings,
    private val toggle: KompaktServiceToggle,
    private val serviceRepository: DavServiceRepository
) {
    suspend fun enabledServices(account: Account): List<KompaktSyncService>
}
```

Three conditions, all read at call time: consented, switched on, and a `Service` row of that type
exists. **A question and nothing else** — it performs no writes, so it stays inside Invariant 3 without
an exception, and it tests against three cheap reads with no setup machinery.

The ordering rule it depends on — that defaults are applied before the toggle is read — is not enforced
here. It belongs to `KompaktStartSyncUseCase`, for the reason given there.

**Consent is in this filter, not only in the display.** The persisted interval and the granted scopes
can disagree — SHP-1159 applies a token granting only one of the two scopes, so a service can be left
switched on with its consent gone. Enqueuing then produces a 403, which is the head of the D1 chain:
`HomeSetRefresher` catches it, deletes the home set without rethrowing, the foreign key orphans the
collections, `CollectionsWithoutHomeSetRefresher` deletes those, and `Syncer.updateCollections` then
deletes the local store and its pending changes. This check is a guard in front of a deletion path, not
cosmetic gating.

**The service-row condition is the same kind of guard.** `Syncer.sync` calls `updateCollections`
unconditionally, and `getSyncEnabledCollections` yields an empty map when the service row is absent, at
which point `updateCollections` deletes every local collection with no database match — D2. AC 9 puts
that one tap away.

**A one-shot, not a flow.** Both consumers are edge-triggered actions rather than renders:
`KompaktLinkedAccountModel.syncNow()` runs inside a `launch` on a tap, and `KompaktSyncRequestReceiver`
runs inside `goAsync()` with no lifecycle and cannot hold a subscription at all. A `Flow` would make
both callers write `.first()`. The stronger reason is staleness direction: an observable value is read
as of its last emission, a suspend call as of the tap. For a gate in front of a deletion path, the
second is what is wanted. This is also the one decision the abandoned attempt recorded as settled —
availability resolved on demand, never as a flow, or a toggle tapped before Room answers silently fails.

Existing as one object rather than an inline filter at each site is the point: two enqueue sites
drifting apart is how AC 6.2 gets half-implemented.

### `sync/KompaktStartSyncUseCase.kt` — start a sync run for an account

```kotlin
class KompaktStartSyncUseCase @Inject constructor(
    private val initDefaults: KompaktInitDefaults,
    private val eligibility: KompaktSyncEligibility,
    private val syncWork: KompaktSyncWork
) {
    suspend operator fun invoke(
        account: Account,
        services: Collection<KompaktSyncService> = KompaktSyncService.entries,
        awaitDiscovery: Boolean = true
    ): Map<KompaktSyncService, UUID?>
}
```

Apply defaults, intersect the requested set with the eligible set, enqueue, return the ids. Three
dependencies, one method, no state — the `AccountProgressUseCase` / `CollectionSelectedUseCase` shape
already used in this module.

**This is where the defaults-before-toggle ordering lives, and the coupling is causal rather than
conventional.** On a fresh account `ensureApplied` at version 0 is what writes the interval that the
eligibility check then reads, so reading first makes the calendar app's first `REQUEST_SYNC` after
linking enqueue nothing. Both call sites need that order; a rule two call sites must remember is one
that will eventually be got wrong, and it is a bug class both earlier attempts hit.

It does **not** belong inside `KompaktSyncEligibility`. That class answers a question, and applying
defaults selects the primary calendar and writes a sync interval — configuration hidden inside something
every caller reads as a predicate. An earlier revision of this design put it there and had to rename the
method away from `enabledServices` to stop the name being a lie, and to carve an exception into
Invariant 3. Both were signals that the work was in the wrong place, not that the names and invariants
needed loosening.

**Every enqueue goes through here**, including the one AC 9 triggers, which is what makes Invariant 1
structural: there is no path to `KompaktSyncWork.enqueue` that has not been filtered.

`awaitDiscovery` exists because `KompaktSyncRequestReceiver` runs inside `goAsync()` and must not block;
it passes `false`, the screen takes the default.

### `ui/account/KompaktServiceSwitch.kt` — the switch position, and moving it

```kotlin
class KompaktServiceSwitch @Inject constructor(
    private val accountSettings: KompaktAccountSettings,
    private val toggle: KompaktServiceToggle,
    private val syncWork: KompaktSyncWork,
    private val initDefaults: KompaktInitDefaults
) {
    fun read(account: Account, service: KompaktSyncService): KompaktSyncSwitch
    fun observe(account: Account, service: KompaktSyncService): Flow<KompaktSyncSwitch>
    suspend fun setEnabled(account: Account, service: KompaktSyncService, on: Boolean)
}
```

Without this, the derivation and the ordering rules would be written inline in the ViewModel once per
service — two chances to write them differently. `observe` combines `accountSettings.observeAuthState`
with `toggle.observe`; `read` is the one-shot form, used only by the `ConsentMissing` guard, which needs
a value fresher than the rendered one.

`setEnabled` owns the ordering the acceptance criteria actually specify, and nothing else. It does
**not** trigger the sync for AC 9: that path raises the storage-full and no-connectivity sheets, so it
stays in the ViewModel and this component never learns what a dialog is.

**Guardrail for review:** if this class gains a fifth constructor dependency or its first
`MutableStateFlow`, it is turning into the controller this design exists to avoid.

### The switch starts at `Resolving`

`KompaktSyncSwitch` carries a fourth position, `Resolving`, which is where every service starts. The
screen seeds `switchOf`'s `stateIn` with it and `observe` replaces it once the stored settings have been
read, off the main thread via `flowOn`.

The alternative was a synchronous seed, and it is what an earlier revision did. It is rejected because
of what one read costs: the consent half deserializes the stored OAuth token, and the screen needs a
position twice per service — once for `switchOf`, once for `initialState` — so a synchronous seed parsed
that blob four times on the main thread before the first frame, on a display that repaints in about a
second. No amount of caching makes main-thread JSON the right call there; it only makes it cheaper.

**The accepted cost** is that a service whose sync is on renders off for the first frame and then
populates. That is a real repaint on e-ink, and it is the thing to watch: if it is reported as a visible
flip, the answer is to seed synchronously again and pay the parse. Device check 12 is written to catch
it. Weakening Invariant 2 to "the first frame is *honest*, not necessarily final" is a deliberate trade,
not an oversight.

Two consequences that keep it safe. `KompaktServiceSyncCell` renders `Resolving` exactly like `Off` and
**drops input while in it** — not via `SwitchMMD`'s `enabled`, which also greys the control, but by not
forwarding the callback — so a tap cannot act on a value the screen does not have yet. And the pure
`kompaktSyncSwitch(consented, on)` never returns `Resolving`: it is a seed, not a derivation, so the
rule that consent vetoes and the interval decides is unchanged.

`setServiceSync` re-reads the position rather than trusting the rendered one, because a tap can arrive
minutes after the frame; that read now happens inside the coroutine, so no path parses on the main
thread.

### `ui/account/KompaktServiceLastSync.kt` — when did this service last sync

```kotlin
class KompaktServiceLastSync @Inject constructor(
    private val serviceRepository: DavServiceRepository,
    private val syncStatsRepository: DavSyncStatsRepository
) {
    fun observe(account: Account, service: KompaktSyncService): Flow<Reported<Long?>>
}
```

The maximum `SyncStats.lastSync` over the collections **selected** for the service, filtered to its data
type. Epoch millis — formatting is a display concern shared by both rows and stays in the ViewModel.

It lives in `ui/account/` rather than `sync/` because `Reported` has to be applied at the source: Room's
first emission is asynchronous, so *not loaded yet* must stay distinguishable from *never synced*, and
wrapping a fabricated `null` afterwards would lose exactly that. The existing `syncing` flow carries the
same argument.

**Storage: one appended DAO query.** `CollectionDao` exposes no Flow for collections-by-service and
`SyncStatsDao` has no per-service maximum, so one is added:

```kotlin
// SyncStatsDao
@Query("SELECT MAX(syncstats.lastSync) FROM syncstats " +
       "INNER JOIN collection ON collection.id = syncstats.collectionId " +
       "WHERE collection.serviceId = :serviceId AND collection.sync " +
       "AND syncstats.dataType = :dataType")
fun lastSyncFlow(serviceId: Long, dataType: String): Flow<Long?>
```

plus a passthrough on `DavSyncStatsRepository`, because nothing outside a repository touches a DAO in
this codebase. A `JOIN` rather than a subquery so the invalidation set is unambiguous: Room re-emits on
a write to **either** table, which means both a completed sync and a `Collection.sync` flip recompute
the value, with no explicit trigger anywhere.

**Rejected: `AppDatabase.invalidationTracker.createFlow("collection")` as the trigger**, composing the
maximum in Kotlin. It touches no upstream file, which is the only thing in its favour, and it is worse
in three ways that matter more. The table name is a string nothing verifies, so renaming the table stops
the recompute with no compile error and no failing test. It uses the returned `Set<String>` purely as a
side channel. And it is a pattern used nowhere else here, so it reads as a workaround rather than as the
way this codebase talks to Room.

It also kept a defect the query removes. Composing the maximum requires `combine` over one flow per
selected collection, and `combine` over an **empty** list never emits — so a service with no selected
collections would sit at `Reported.Pending` forever, which is precisely the shipping Contacts
configuration. SQL `MAX` over zero rows returns `NULL`, so that case is ordinary behaviour rather than a
guard someone has to remember to write.

Two things the query keeps that the earlier attempt could not. `KompaktInitDefaults` need not be the
only writer of `Collection.sync` — that attempt required it *because its recompute triggers did not
observe the flag*, a rule nothing enforced. And `RefreshCollectionsWorker.existsFlow` stays out of this
file: it calls `WorkManager.getInstance` internally, so using it would either break Invariant 4 or force
a third method onto `KompaktSyncWork`, which is how that attempt's work seam began growing into a
module.

The cost is honest and bounded: `SyncStatsDao` goes from three methods to four. It is a small upstream
file that upstream rarely touches, unlike `CollectionDao` at forty-plus methods and actively changed —
which is also why the query lives here rather than there.

### Additions to existing files

**`settings/KompaktAccountSettings.kt`** — the seam can already write the auth state but not read it:

```kotlin
fun getAuthState(account: Account): AuthState?
fun observeAuthState(account: Account, emitInitial: Boolean = true): Flow<AuthState?>
```

Its `getDefaultsAppliedVersion` and `setDefaultsApplied` gain a `KompaktSyncService` parameter.

**`ui/account/KompaktServiceSyncState.kt`** — one pure function:

```kotlin
internal fun kompaktSyncSwitch(consented: Boolean, on: Boolean): KompaktSyncSwitch = when {
    !consented -> KompaktSyncSwitch.ConsentMissing
    on -> KompaktSyncSwitch.On
    else -> KompaktSyncSwitch.Off
}
```

Consent alone would report *On* for a scope granted during a re-auth where no service, no discovery and
no interval follow, so the interval decides between `On` and `Off` and consent only vetoes.

**`sync/worker/SyncWorkerManager.kt`** — one visibility keyword.

---

## Composition in the ViewModel

`KompaktLinkedAccountModel` is the composition root. Nothing mediates between the components, and the
per-service wiring is written once, not once per service.

```
switchOf(service)  ──┐   consent ∧ toggle          KompaktServiceSwitch
syncingOf(service) ──┤   liveness                  AccountProgressUseCase
lastSyncOf(service)──┼──▶ serviceSyncState(…) ──▶ KompaktServiceSyncState
failedOf(service)  ──┘   (existing pure fn)              │
                                                         ▼
        CALENDAR ─┐                          KompaktLinkedAccountState
        CONTACTS ─┼──▶ combine ────────────▶  (email, calendar, contacts,
        dialog   ─┤                            dialog, reauthPhase)
        reauthPhase┘
```

One private function per input:

```kotlin
// starts at Resolving; observe settles it, off the main thread
private fun switchOf(service: KompaktSyncService): StateFlow<KompaktSyncSwitch> =
    switches.observe(account, service)
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KompaktSyncSwitch.Resolving)

private fun syncingOf(service: KompaktSyncService): Flow<Reported<Boolean>> =
    accountProgress(account, serviceRepository.serviceFlow(account, service), listOf(service.dataType))
        .map<AccountProgress, Reported<Boolean>> { Reported.Value(it != AccountProgress.Idle) }
        .onStart { emit(Reported.Pending) }
        .distinctUntilChanged()

private fun lastSyncOf(service: KompaktSyncService): Flow<Reported<String?>> =
    combine(lastSync.observe(account, service), dateTimeFormat) { reported, format ->
        when (reported) {
            Reported.Pending -> Reported.Pending
            is Reported.Value -> Reported.Value(reported.value?.let { format.format(Instant.ofEpochMilli(it)) })
        }
    }

// Calendar only until SHP-1150 attributes failures per service
private fun failedOf(service: KompaktSyncService): Flow<Boolean> = when (service) {
    KompaktSyncService.CALENDAR -> _syncFailed
    KompaktSyncService.CONTACTS -> flowOf(false)
}

private fun serviceState(service: KompaktSyncService): Flow<KompaktServiceSyncState> =
    combine(switchOf(service), syncing.getValue(service), lastSyncOf(service), failedOf(service), ::serviceSyncState)
```

**Per-service state is held in maps keyed by the enum, never in per-service fields**, so a third
service would add no field at all:

```kotlin
private val syncing: Map<KompaktSyncService, StateFlow<Reported<Boolean>>> =
    KompaktSyncService.entries.associateWith {
        syncingOf(it).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Reported.Pending)
    }

private val serviceStates: Map<KompaktSyncService, Flow<KompaktServiceSyncState>> =
    KompaktSyncService.entries.associateWith { serviceState(it) }
```

`syncing` has to be memoised rather than called inline: the offline-while-syncing handler asks whether
Calendar is active, and so does `serviceState(CALENDAR)`. Calling the builder from both would construct
two `AccountProgressUseCase` chains and two sets of WorkManager queries for one question. Memoising also
makes "each `*Of(service)` builder runs exactly once per service" a property of the code rather than a
convention.

`failedOf` is the one input still keyed by service in a `when`, and the `when` is the point: it states
out loud that failure attribution is not yet per-service and names the ticket that makes it so. When
SHP-1150 lands it replaces one function body and nothing else moves.

### What leaves the ViewModel

`_calendarSwitch` and its optimistic write-then-roll-back, `readCalendarSwitch`, `calendarSwitchOf`, the
`observeSyncInterval` collector in `init`, `eventsSyncWorkInfos`, `isSyncActive()`, the `syncing`
StateFlow, the `primaryCollectionId` StateFlow and the `lastSyncEpoch` chain beneath it, the direct
`WorkManager` enqueue, and the `contactsState` constant.

Both of the ViewModel's `WorkManager.getInstance` calls for liveness and last-sync go with them. The
optimistic mirror disappears specifically because the account-settings seam announces its own writes —
that is the landed groundwork paying off, not a style change. `MutableStateFlow` count drops from six to
five, and the two that remain per-service (`_syncFailed`, `_trackedSync`) are exactly what SHP-1150 comes
to collect.

### Fields, counted

| | today | after |
|---|---|---|
| per-service | `_calendarSwitch`, `primaryCollectionId`, `lastSyncEpoch`, `lastSync`, `eventsSyncWorkInfos`, `syncing`, `syncingNow`, `calendarState`, `contactsState` — nine, all Calendar-shaped despite the naming | `syncing`, `serviceStates` — two maps |
| Calendar-only, SHP-1150's to generalise | `_syncFailed`, `_trackedSync`, `trackedSyncInfo` | unchanged |
| account-global | `email`, `dateTimeFormat`, `networkAvailable`, `_showNoInternet`, `_showOutOfStorage`, `needsReauth`, `_reauthPhase`, `dialog`, `state` | unchanged |
| **total** | **21** | **14** |

`_trackedSync` holds a single `UUID?`, so "which run did the user start" stays account-wide. That is
what SHP-1150 comes to collect; when it does, these become one more map or move into a component rather
than becoming three fields per service.

Constructor parameters go from fourteen to **sixteen**: `−kompaktAccountSettings`,
`−syncStatsRepository`, `−syncWorkerManager`, `+switches`, `+startSyncUseCase`, `+accountProgress`,
`+lastSync`, `+syncWork`. `eligibility` never enters the ViewModel — the use case holds it — and
`initDefaults` is retained only for the post-discovery apply in `init`, not for the sync path.
`syncWork` does stay, because the offline-while-syncing handler cancels a run the ViewModel started.

Net two more collaborators for seven fewer fields, and the ViewModel's only remaining `WorkManager`
touch is observing the tracked run by id: the liveness query and the last-sync recompute both leave.

---

## `KompaktInitDefaults`, per service

```kotlin
fun appliedVersion(account: Account, service: KompaktSyncService): Int
suspend fun markApplied(account: Account, service: KompaktSyncService)
suspend fun ensureApplied(account: Account, service: KompaktSyncService, awaitDiscovery: Boolean = true): Outcome
suspend fun maybeApply(account: Account, service: KompaktSyncService, serviceId: Long): Outcome
```

`ensureApplied` resolves `service.serviceType` instead of hardcoding CalDAV, so each service reaches
`NOT_READY` independently. `AUTO_SYNC_INTERVAL_SECONDS` stays one constant — AC 4 is 24 h for both.

### The marker migration

Existing accounts carry a single `kompakt_defaults_applied` value meaning *calendar defaults done*. The
legacy key therefore counts as the CALENDAR value and nothing else:

```kotlin
internal fun appliedVersionOf(perService: Int?, legacy: Int?, service: KompaktSyncService): Int
```

Pure, exhaustively tested, no mocks. Reading the legacy marker as a Contacts value would skip Contacts
setup on every account that already exists.

### Per-service policy

One exhaustive `when (service)` inside `maybeApply` — the one place service-specific behaviour
legitimately lives, and a compile error if a third service is ever added.

- **CALENDAR** — unchanged: select the primary calendar, and write the EVENTS interval.
- **CONTACTS** — write the CONTACTS interval, and **select no address books**.

**The interval is written only when nothing is stored** — `getSyncInterval(...) == null` — not when the
marker is 0. The distinction is the whole of the guard: a user who switches a service off before
discovery finishes has stored the manual sentinel, and a "first setup" test would overwrite it, turning
the service back on and re-arming its worker. Collection selection sits above this block and still runs
either way, so respecting the choice costs no setup.

This relies on the **seam's** raw getter, not `AccountSettings.getSyncInterval`, which substitutes the
four-hour default for an absent key and would make the condition never true. Raw, `null` means the key
was never written; `-1` is the user's explicit off.

Only two things write that key on this device — `maybeApply` itself and the toggle — so `null` really
does mean "no preference yet". `AccountSettingsMigration14` is a third writer in principle, and it reads
through upstream's getter, so it would store the four-hour default over an absent key; it cannot run
here, because Kompakt accounts are created at the current settings version. Were it ever to run, the
service would land on upstream's interval and read as **off**, since `toggleOn` compares against
`AUTO_SYNC_INTERVAL_SECONDS` exactly.

It also makes the method's older promise real rather than incidental: a `DEFAULTS_VERSION` bump
re-runs selection while leaving the interval alone, which "version 0" only achieved by accident.

---

## The line this design does not cross

Selecting an address book is what first creates a *local* address book on the device. From that moment
`Syncer.updateCollections` will delete it, and every pending local change in it, on any run where the
database set comes back empty — which a 403 from a narrowed scope produces via the D1 chain above. With
nothing selected there is nothing to delete, so the path stays inert.

Three existing behaviours turn a 403 or an empty collection set into deletion of the user's data. They
are recorded as D1, D2 and D3 in `2026-08-12-oauth-scope-denial-design.md` on the unmerged branch
`worktree-spec+oauth-scope-denial`, together with the device measurements behind them. D3 in particular
means a contacts sync failure is currently swallowed and reported as success, because
`AddressBookSyncer` wraps the whole per-address-book sync in a blanket catch that never touches the sync
result — so a contacts failure would not even be visible.

**The honest consequence of shipping Contacts now:** its toggle works, its consent is real, its interval
is scheduled, and a sync it triggers processes zero collections. That is a strictly better state than a
dead switch behind real consent, and it is inert with respect to data loss.

**Address-book selection needs its own ticket, and D1–D3 must be closed first.** That is the
prerequisite, not a nice-to-have — the first sync after a selection is exactly the case D2 describes.
All three were still open in this tree when this branch was written: `HomeSetRefresher` deletes a home
set on 403 without rethrowing, `Syncer.sync` calls `updateCollections` with no guard for an absent
service row, and `AddressBookSyncer` swallows every exception and still reports the sync complete.

**The ordering matters, and it is not the obvious one.** On this branch the Contacts toggle cannot be
turned on at all — no `carddav` scope is requested, so the switch reads `ConsentMissing` and enabling it
is a no-op. SHP-1159 changes that: consent becomes real, a CardDAV service row appears, and the toggle
starts working. From that moment until address-book selection lands, Contacts looks operable, schedules
a worker, and syncs **nothing** — silently, because D3 reports a swallowed failure as success. That
window is the thing to plan around: it is a product-visible gap opened by a ticket that is already in
review, not by this one.

---

## Behaviour

### Switching on

1. `KompaktServiceSwitch.setEnabled(account, service, true)` — persist the interval, then mark defaults
   applied for that service. Persist first, so the switch stays on regardless of what follows.
2. The ViewModel then runs the **same guarded path** the "Synchronize now" button uses: critically low
   storage raises the storage message and stops; no connectivity raises the connectivity message and
   stops.
3. Otherwise enqueue and track the returned run id (AC 9).

Sharing the button's guards literally is the point: switching on reports the same conditions the button
reports rather than enqueuing silently.

### Switching off

Order matters, and each step exists for a named AC. The codebase already contains this exact sequence in
`KompaktLinkedAccountModel`'s offline-while-syncing handler, which clears the tracked id before
cancelling for the same reason.

1. **The ViewModel clears the tracked run id first.** It owns that id, and a cancellation must not be
   attributed as a failure (AC 7.3, 7.4). Clearing *before* the cancel is the whole point.
   Only `EVENTS` runs are tracked today, so this step is vacuous for Contacts — AC 7.3 and 7.4 hold
   there because nothing can misattribute a run nobody is watching. It stops being vacuous when
   SHP-1150 tracks outcomes per service, which is why the step is written per service rather than for
   Calendar.
2. `KompaktServiceSwitch.setEnabled(account, service, false)`, which does two things in order:
   1. persist `null` → `AutomaticSyncManager` cancels the periodic worker and disables the
      content-triggered sync (AC 6.1). This also stops a *running* periodic sync, because the routing
      reaches `SyncWorkerManager.disablePeriodic`, which cancels that unique work.
   2. `KompaktSyncWork.cancel` → cancels the running or enqueued one-time worker (AC 7.1).

Partial data already written to the provider stays, because `SyncManager` saves each collection's sync
state as it goes (AC 7.1.1). The previous timestamp survives because `logSyncTime` is never reached
(AC 7.5).

### Manual-sync eligibility

Both enqueue sites change from "all authorities" to `KompaktStartSyncUseCase`:

- `KompaktLinkedAccountModel.syncNow()`
- `KompaktSyncRequestReceiver` — after its throttle check, `startSyncUseCase(account, awaitDiscovery = false)`

```kotlin
fun syncNow() {
    viewModelScope.launch(ioDispatcher) { startSync(KompaktSyncService.entries) }
}

private suspend fun startSync(requested: List<KompaktSyncService>) {
    if (KompaktStorage.isStorageLow(context)) { _showOutOfStorage.value = true; return }
    val accountSettings = accountSettingsFactory.create(account)
    if (!syncConditionsFactory.create(accountSettings).internetAvailable()) { _showNoInternet.value = true; return }
    _trackedSync.value = startSyncUseCase(account, requested)[KompaktSyncService.CALENDAR]
}
```

Having AC 9 enqueue its service directly would skip the gate at exactly the moment Invariant 1 matters
most: switching on a service whose `Service` row is absent is the D2 case, and AC 9 puts it one tap
away. Routing every request through the use case removes that path.

**Ordering choice, recorded so it does not look accidental:** the storage and connectivity guards run
*before* eligibility is known, so with every service off the user sees the storage or connectivity sheet
rather than nothing. Today's code has the same ordering, and SHP-1157 owns the "nothing enabled" dialog
that would otherwise take precedence.

A side effect worth having: this stops the stray `TASKS` worker that fires on every manual sync today.

**Accepted consequence:** with every service off, "Synchronize now" enqueues nothing and says nothing.
SHP-1157 AC 6 owns that dialog.

The full action surface:

```kotlin
fun setServiceSync(service: KompaktSyncService, enabled: Boolean) {
    viewModelScope.launch(ioDispatcher) {
        // a ConsentMissing row renders like Off, so its switch reports an enable — SHP-1151 owns it
        if (enabled && readSwitch(service) == KompaktSyncSwitch.ConsentMissing) return@launch
        // only Calendar runs are tracked, so only its toggle may clear the id (AC 7.3/7.4)
        if (!enabled && service == KompaktSyncService.CALENDAR) _trackedSync.value = null
        switches.setEnabled(account, service, enabled)     // persist, then cancel
        if (enabled) startSync(listOf(service))            // AC 9
    }
}
```

---

## Invariants

1. **No sync is enqueued for a service that is not consented, switched on, and backed by a `Service`
   row.** A data-loss guard, not hygiene — see `KompaktSyncEligibility`. Structural, not conventional:
   `KompaktStartSyncUseCase` is the only caller of `KompaktSyncWork.enqueue`, and it filters.
2. **A switch position is never derived from the Room service row** — only from the stored interval and
   auth state. The first frame is *honest* rather than final: it shows `Resolving`, which renders like
   off, until the stored values arrive. See *The switch starts at `Resolving`* for the trade.
3. **State resolution performs no writes**, with no exceptions. Losing consent leaves the persisted
   interval alone, and every component that answers a question — the toggle read, the switch derivation,
   eligibility, last-sync — is a pure read. The one component that writes on the way to an outcome,
   `KompaktStartSyncUseCase`, is named for the action it performs rather than the question it answers.
4. **No new file calls `AccountManager.get`, `WorkManager.getInstance`, or a worker static helper**,
   except `KompaktSyncWork`. Grep-checkable, and the reason every rule tests without Robolectric.
   `RefreshCollectionsWorker.existsFlow` counts, which is why `KompaktServiceLastSync` gets its
   recompute from a Room query's own invalidation rather than a discovery transition.

`KompaktInitDefaults` no longer needs to be the only writer of `Collection.sync`. The earlier attempt
required that, because its last-sync recompute triggers did not observe the flag; observing the
`collection` table removes the constraint.

Two things this design does *not* make invariant, recorded so they are not surprises:

- **An absent interval key is not inert.** `AccountRepository.createBlocking` calls
  `updateAutomaticSync` immediately after inserting the service row, and with the key absent
  `getSyncInterval` returns the four-hour default, so a four-hourly worker is scheduled in the window
  before `KompaktInitDefaults` writes the Kompakt interval. This is upstream behaviour, it is brief, and
  the ordering rule above is what keeps it from being observable. It is listed under *To verify on
  device*.
- **A cancelled sync still increments the unclassified-error count.** `SyncManager` rethrows
  `CancellationException` and `Syncer`'s catch has no arm for it. Every user-visible AC still holds — the
  work state is `CANCELLED`, `logSyncTime` is skipped, and the tracked id was cleared first — but
  SHP-1150 inherits this as its input when it starts attributing failures per service.

---

## Interaction with SHP-1159, which is in review concurrently

`origin/task/SHP-1159-link-calendar-contacts` touches the same files. Both branches must not land
without someone reconciling them; this design is written so that either order works.

| SHP-1159 | This design |
|---|---|
| adds `KompaktGrantedServices` | carries `scope` on `KompaktSyncService` instead, because neither that object nor `SCOPE_CONTACTS` exists on this base. **Reconcile by keeping the enum** and reimplementing or deleting `KompaktGrantedServices`; its `Set<String>` of service types is something every caller must match back against the enum anyway. |
| adds `SCOPE_CONTACTS` to `KompaktOAuthGoogle` **and to its requested `SCOPES` array** | adds the same constant with the same value, and **not** to the `SCOPES` array. Identical constant, clean merge; requesting the scope stays SHP-1159's decision |
| adds `KompaktInitDefaults.applyContactsSyncInterval` | **subsumes** it — an unversioned side door called from `KompaktLoginFinalizeModel` that does what `ensureApplied(account, CONTACTS)` does, minus the version guard. Resolution: delete it, call `ensureApplied`. |
| derives a `contactsSwitch` inline in `KompaktLinkedAccountModel` with raw `AccountManager` reads | **replaces** it with `KompaktServiceSwitch`, which is the same rule with a push channel |
| leaves the Contacts switch `onCheckedChange = { /* SHP-1151 */ }` | wires it |

The one that needs a human decision is `applyContactsSyncInterval`. Everything else is additive.

---

## Files

**New (7):** `sync/KompaktSyncService.kt`, `sync/KompaktServiceToggle.kt`, `sync/KompaktSyncWork.kt`,
`sync/KompaktSyncEligibility.kt`, `sync/KompaktStartSyncUseCase.kt`,
`ui/account/KompaktServiceSwitch.kt`, `ui/account/KompaktServiceLastSync.kt`.

**Changed (8):** `sync/KompaktInitDefaults.kt` (per service, plus the marker migration),
`settings/KompaktAccountSettings.kt` (auth-state reader, per-service defaults marker),
`ui/account/KompaktServiceSyncState.kt` (one pure function),
`ui/account/KompaktLinkedAccountModel.kt` (composition; drops the toggle mirror, the raw interval read
and the hand-rolled liveness; keeps storage, connectivity, re-auth phase, dialog precedence, unlink and
formatting), `ui/account/KompaktLinkedAccountScreen.kt` (`onToggleService(service, enabled)`, the
Contacts row live, per-service disable confirmation), `ui/KompaktSyncRequestReceiver.kt`,
`sync/worker/SyncWorkerManager.kt` (one visibility keyword), `docs/app-integration.md`.

**Unchanged:** `KompaktServiceSyncCell`, `KompaktLinkedAccountState`, `linkedAccountDialog`,
`KompaktLastSyncFormatter`, `CollectionDao`, `AppDatabase`.

`core/.../network/KompaktOAuthGoogle.kt` gains one companion constant, `SCOPE_CONTACTS`. It is a Kompakt
file, and the constant is not added to its requested `SCOPES` array.

**Four upstream edits, all pure appends, none changing existing behaviour:**

| File | Edit | Why a new file cannot express it |
|---|---|---|
| `sync/worker/SyncWorkerManager.kt` | drop one `private` keyword | the method is the fork's own addition (SHP-1046) |
| `db/SyncStatsDao.kt` | one `@Query` returning `Flow<Long?>` | Room accepts a query only on a DAO |
| `repository/DavSyncStatsRepository.kt` | one passthrough | nothing outside a repository touches a DAO here |
| `repository/DavServiceRepository.kt` | one accessor, `getServiceFlow(accountName, serviceType)` | the DAO's flow is already general; only the repository lacked a general accessor |

No schema change and no migration — the query is a `SELECT`, and `AppDatabase` already exposes
`syncStatsDao()`, so neither the database class nor a Room version is affected.

---

## Tests

In `core`. `app-ose` has no unit tests and a root `testOseDebugUnitTest` runs nothing.

**No Robolectric.** A unit test that needs it is not written; the behaviour it would have covered is
verified on a device instead. This is a deliberate constraint, and it decides what is testable here:
a plain JVM test sees `android.*` only as stubs, and `android.accounts.Account`'s constructor stores
nothing, so `name` and `type` read back as null. Anything whose subject touches an `Account` — the
toggle seam, eligibility, the start-sync use case, the switch writer, the last-sync composition —
therefore has no unit test.

What is covered is the pure rules, which is where the acceptance criteria actually live, and they test
with no mocks and no Android at all:

| Rule | Pins |
|---|---|
| `toggleOn(intervalSeconds)` | an absent key and the manual sentinel both read as off, so an unconfigured account is never reported as on |
| `KompaktSyncService.isConsented(authState)` | an unproven grant answers false, which is the guard in front of the 403 deletion chain |
| `kompaktSyncSwitch(consented, on)` | consent vetoes, the interval decides |
| `appliedVersionOf(perService, legacy, service)` | the stored-marker migration — the legacy key counts for Calendar and nothing else |
| `KompaktSyncService.entries` | two services, and `TASKS` is not one of them |

**What that leaves unverified by the suite, and where it moves to.** These are the ordering rules, and
they are exactly the ones that look correct in any order, so they need naming even though no test
holds them:

- `setEnabled(false)` persists **before** it cancels; the tracked id is cleared **before** either.
- `KompaktStartSyncUseCase` applies defaults **before** it reads eligibility.
- It is the only caller of `KompaktSyncWork.enqueue`, and no Kompakt screen or receiver reaches
  `SyncWorkerManager.enqueueOneTimeAllAuthorities`. `KompaktAccountsActivity`'s undeclared
  `Intent.ACTION_SYNC` handling did, and is **removed** rather than filtered: it synchronized every data
  type regardless of the toggles, no intent filter advertised it, and `REQUEST_SYNC` is the specified way
  for another app to ask. Upstream's own screens and the sync widget still call it and remain unreachable
  on this device (*Reachability on Kompakt*), so they are out of scope rather than exempt.
- `enabledServices` excludes a service that is switched on but has lost consent, and one with no
  `Service` row.
- Marking one service's defaults applied does not mark the other's.
- `KompaktServiceLastSync` emits `Reported.Pending` before its first value and `Reported.Value(null)`
  when there is no `Service` row.

Each is listed under *To verify on device*. The eligibility guard and the marker migration are the two
worth exercising first, because both are silent when wrong: the first deletes data, the second skips
setup on an account that already exists.

---

## Verification

```bash
./gradlew :app-ose:assembleDebug
./gradlew :core:lintDebug :app-ose:lintOseDebug
./gradlew :core:testDebugUnitTest --rerun-tasks
```

`--rerun-tasks` matters: an up-to-date `testDebugUnitTest` prints BUILD SUCCESSFUL without running
anything. Read the tally from `core/build/test-results/testDebugUnitTest/*.xml`, not the status line —
78 tests, 0 failures before this change. Never run the instrumented suite locally; CI runs it on the PR.

### To verify on device

Nothing below is provable from a build, and nothing in either earlier attempt, nor in the landed account
seam, has ever run on hardware.

1. Switching a service off during an active sync stops it, keeps the partial data, and leaves the
   previous last-sync time on the row.
2. **Contacts cancellation specifically.** Contacts sync runs under address-book sub-accounts, so
   whether cancelling the main account's one-time unique work actually stops it is unverified.
3. Switching on triggers a sync, and reports the storage and connectivity messages when they apply.
4. "Synchronize now" with everything off enqueues nothing.
5. An external `REQUEST_SYNC` with a service off enqueues only the others, and one immediately after
   linking still enqueues.
6. A background periodic sync shows *Syncing now* on the row, and a just-tapped one does too before the
   worker starts.
7. Whether a four-hourly periodic worker is observable before `KompaktInitDefaults` applies its own
   interval (`adb shell dumpsys jobscheduler`), and how long that window lasts.
8. Whether a Google token refresh ever returns a narrowed `scope`. If it does, the background writer in
   `HttpClientBuilder` — which calls `AccountSettings.updateAuthState` directly on a sync thread,
   bypassing the seam and announcing nothing — needs routing through `KompaktAccountSettings`. That is a
   one-line change; until it is measured, the one-shot eligibility read is what keeps the *action*
   correct even when the *display* is stale.
9. A Contacts-only account (granular consent) syncs at all — the case that is silently broken today.
10. The Calendar row's last-sync time is unchanged by the switch from the primary-calendar read to the
    selected-set read. They should agree today; if they do not, the selection is not what
    `KompaktInitDefaults` is assumed to have made.
11. The Contacts row settles on *Not synced yet* rather than sitting at *Resolving*, with no address
    book selected — the shipping configuration, and the one the last-sync query returns `NULL` for.
12. **How visible the switch settling from `Resolving` is.** A service that is on renders off for the
    first frame and then populates. If that reads as a flip rather than as loading, the answer is to seed
    synchronously again and accept the main-thread parse — see *The switch starts at `Resolving`*. This
    is the one deliberate repaint in the design and the only place it can be judged.

13. **A service switched off before its defaults have applied stays off.** Link fresh, switch Calendar
    off before discovery completes, then let discovery finish and re-enter the screen. The switch must
    stay off and no periodic worker may appear. This is the one the review caught, and it needs a fresh
    link — on an account whose defaults have already applied, the path is unreachable.

`docs/kompakt-testing.md` has the recipes.

### Already verified on device

On a Kompakt (`LD20240700365`) with a linked account, against the build from commit
`e65285586`. Recorded so the list above is not re-run wholesale.

- The last-sync query, executed verbatim through `sqlite3` against the live `services.db`: returns the
  Calendar timestamp shown in the UI, correctly excluding a second calendar with `sync = 0`, and
  returns `NULL` for a service with no rows — the empty case that needs no guard. It also re-emits
  after each sync with no explicit refresh, which is the join's invalidation working.
- `KompaktInitDefaults` selected exactly the primary calendar and left the other alone.
- *Synchronize* with Calendar on enqueues **one** worker, `dataType=EVENTS`. Before this change the
  same tap enqueued EVENTS, CONTACTS and TASKS.
- *Synchronize* with every service off enqueues **nothing** — the seventh no-op condition.
- Switching Calendar off logs `Disabling periodic worker … dataType=EVENTS` and cancels the job,
  scoped to that service.
- Switching Calendar on re-arms the periodic worker (`interval=900`, the debug value) **and** enqueues
  a manual run by itself — AC 9.
- A service switched off stays off across a force-stop and relaunch, with nothing re-arming it.

Not yet exercised: AC 7 proper (switching off *during* an active run — the calendar syncs too fast to
catch), anything Contacts-behavioural (no `carddav` scope on this branch), and the `version == 0`
window in check 3, which needs a fresh link.
