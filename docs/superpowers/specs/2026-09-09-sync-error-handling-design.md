# Errors in settings — generic sync error handling (SHP-1149)

Base branch: `task/SHP-1155-sync-fix-on-enable`, not `release/1.1.0`. That branch adds
`KompaktAccountProgressUseCase` — the liveness query this design reuses as its "already syncing" gate —
and renames `rescheduleFromNow` to `delayFirstRun`, removing the immediate periodic run that made a
manual sync collide with a scheduled one. Both are load-bearing here. `docs/git-workflow.md` does not
describe task-on-task branches, so the PR base needs retargeting once SHP-1155 merges.

Related, and read first if you are touching the same area:
[`2026-09-02-per-service-sync-toggle-design.md`](2026-09-02-per-service-sync-toggle-design.md), which
names this work and leaves the seams it fills.

## The one-line summary

A sync outcome becomes a **persisted, per-service, classified fact** written once by the worker, so a
failure survives process death and is visible for periodic runs — and the screen stops conflating
"this service is failing" with "show a dialog now".

## Contract change — read this first

**There is one, and the calendar app needs a coordinated update.**

`docs/app-integration.md` currently promises, under `REQUEST_SYNC` → *Notes / limitations*:

> Only one sync per account + data type runs at a time; if a sync is already running, the request is
> coalesced/queued (it won't run a second concurrent sync).

SHP-1157 AC 8 requires that a second job **is not created** while one is in progress, and the gate that
enforces it lives in `KompaktStartSyncUseCase` — which `KompaktSyncRequestUseCase`, the `REQUEST_SYNC`
path, also calls. So *coalesced/queued* becomes **dropped**: a cross-app request that lands while a run
for that data type is already `RUNNING`, `ENQUEUED` or `BLOCKED` enqueues nothing.

**And a second one, from Decision 9.** `REQUEST_SYNC` used to enqueue while offline and let the run wait
on its `NetworkType.CONNECTED` constraint. It is now **dropped**: connectivity and storage are checked
before enqueueing, for every caller.

- **What the other app must know:** a request may now be a complete no-op — because the service is
  mid-sync (including mid-*periodic*-sync, which the caller cannot observe), or because the device is
  offline or critically low on storage. Nothing is queued for later, so there is no request that runs
  when conditions recover; the caller re-sends instead. The broadcast is still fire-and-forget with no
  result, so none of this is detectable. The existing 15-minute throttle is untouched, and it keys on
  the last *successful* sync, so a dropped request does not consume the window.
- **`docs/app-integration.md` is updated in the same change** — condition 6 is rewritten, condition 7
  loses the "not configured" case (Decision 9 sets a service up on demand), an eighth condition is
  added, and *Notes / limitations* gains the never-deferred bullet.

Scoping the gate to the in-app path only (a defaulted `gateWhileRunning` flag, mirroring the existing
`awaitDiscovery`) was considered and **rejected**: two callers with different concurrency semantics is
the kind of divergence that produces the bug SHP-1157 AC 8 exists to fix.

Nothing else in that document changes. In particular `needs_reauth` stays a **single account-level
flag**: Google CalDAV and CardDAV share one OAuth token, so a 401 is necessarily account-wide, and a
token that is valid but unauthorised for a resource returns **403**, which lands in `numHttpExceptions`
and never touches the flag. The provider keeps its `account_name / account_type / needs_reauth` shape.
No new intent, broadcast, permission, authority or column.

## Scope

In:

| | |
|---|---|
| Persist a classified sync outcome per service, for one-time **and** periodic runs | new Room table |
| Classify `SyncResult` into a small user-meaningful vocabulary | new pure classifier |
| Make the cell's failure state per-service and durable | `failedOf(CONTACTS)` stops being `flowOf(false)` |
| Turn the alert-icon tap into "explain", not "clear" | SHP-1149 AC 2 |
| Aggregate a two-service manual sync into **one** modal | SHP-1149 AC 12, SHP-1157 AC 3 |
| **Try again** retries only the services that failed; dismissing stops erasing the row's error | *Behaviour → Try again* |
| Do not create a second job while one is in progress | SHP-1157 AC 8 |
| Generalise the re-auth flag writer beyond `EVENTS`, and guard its *clear* | closes the Contacts-only blind spot |
| Stop `AddressBookSyncer` reporting success after a preamble failure | narrow, real |

Out, and why:

| | |
|---|---|
| A "Waiting for network / storage" cell state | Decision 1 — considered and rejected |
| Any change to constraints, or to what the pre-flight guards and the mid-sync cancel *do* | same, in the UI. Both **move** — the guards into `KompaktStartSyncUseCase` (Decision 9), the cancel into `KompaktSyncAttempt` — and the cancel's **scope** becomes per-service (Decision 6). Decision 9 does change *who* the guards apply to: every caller, so a cross-app request while offline is dropped rather than parked |
| Collapsing the dialog flags in `KompaktLinkedAccountModel` | Follow-ups — a structural refactor that should not ride along with a behaviour change |
| 403-from-dropped-scope classified as an auth problem, and the collection-deletion chain behind it | SHP-1180. SHP-1149 AC 3 covers the errors *this* design handles, none of which delete data |
| `RefreshCollectionsWorker` (discovery) failures | no `SyncResult`; invisible today and left so |
| SHP-1157's own "nothing enabled" dialog (AC 6) | that story owns it — but this design must hand it a distinguishable result (Decision 7) |
| Dialogs 1.2, 1.5, 1.6 of SHP-1149 AC 1 (Offline+, "Couldn't connect to Google", "Couldn't set up your account") | Offline+ is unshipped; the other two belong to the login/detect flow, not sync |

## What already holds, and what does not

Holds, and is relied on:

- `SyncResult` is a complete structured tally by the time `syncer()` returns, and both worker classes
  reach that point — `doWork` and `doSyncWork` are defined on `BaseSyncWorker`, which
  `OneTimeSyncWorker` and `PeriodicSyncWorker` both extend.
- The precedent already runs in production: the existing `setReauthNeeded` block writes from inside
  `doSyncWork`, which is why a *background* auth failure reaches the UI today while every other
  background failure vanishes. This design generalises a proven mechanism rather than inventing one.
- `KompaktAccountProgressUseCase` (base branch) answers "is this service syncing" from an injected
  `WorkManager` over `commonTag`, counting `RUNNING` for either worker class plus a one-time run in any
  of `BLOCKED`/`ENQUEUED`/`RUNNING`. That is SHP-1157 AC 8's predicate, and it is already unit-tested.
- `syncstats` keeps answering "when did this service last succeed". Nothing in the last-sync pipeline
  moves, so the `REQUEST_SYNC` throttle — which reads `getLastSyncTime(dataType)` — is unaffected, and
  SHP-1149 AC 11 holds unchanged.

Does not hold, and is why this exists:

- **`WorkInfo` cannot carry a periodic outcome.** `WorkerWrapper.handleResult` routes *both* `Success`
  and `Failure` for a periodic `WorkSpec` to `resetPeriodic()`, which returns the state to `ENQUEUED`
  and never calls `setOutput`. Verified in work-runtime 2.11.2.
- **Finished work is pruned after one day**, by `CleanupCallback.onOpen` on every process start
  (`PRUNE_THRESHOLD_MILLIS`), and nothing sets `keepResultsForAtLeast`.
- **No failure is persisted anywhere.** `logSyncTime` sits under `if (!syncResult.hasError())`, so a
  failed run does not even record that an attempt happened. The single exception is
  `kompakt_needs_reauth`, written for `EVENTS` only.
- **The UI reads errors by regex over a `toString()`** — `Regex("numAuthExceptions=(\\d+)")` against
  `outputData.getString("syncresult")`, whose producer is `syncResult.toString()`.
- **`_syncFailed` means two things at once** — the cell's failure state *and* the modal trigger — which
  is why `consumeDialog` must clear it, and therefore why tapping the alert icon erases the error
  instead of explaining it.

---

## Decisions

### 1. No "Blocked" state. No-internet and low-storage behaviour is unchanged.

Considered at length and rejected. Recorded because the reasoning answers "why does the current code
cancel instead of letting the sync fail", and because it is the decision most likely to be revisited by
someone who has not seen the analysis. SHP-1149 AC 14 independently **requires** the pre-flight
behaviour this keeps.

Four independent mechanisms keep a network or storage condition from ever becoming a result:

1. `NetworkType.CONNECTED` on every one-time request, manual included. `buildPeriodic` uses `UNMETERED`
   when `getSyncWifiOnly()` is set and `CONNECTED` otherwise — nothing reachable on Kompakt sets it, so
   it is `CONNECTED` in practice. `setRequiresStorageNotLow(true)` on periodic and on non-manual
   one-time, but **not** on manual. An unmet constraint parks the work in `ENQUEUED` indefinitely.
2. Upstream's `BaseSyncWorker` returns **`Result.success()`** when a dispatched non-manual worker finds
   no internet — a run that did nothing, indistinguishable from one that worked.
3. A pre-flight checks storage then internet and returns before enqueuing.
4. A watch cancels a tracked run after a 2 s grace, clearing tracking **first** "so the cancel isn't
   reported as a result".

Mechanisms 3 and 4 both live in `KompaktLinkedAccountModel` today. Mechanism 3 moves into
`KompaktStartSyncUseCase` (Decision 9) and mechanism 4 into `KompaktSyncAttempt`, in both cases
**without changing what the user sees**. Two scopes do change: mechanism 4's, from calendar-only to
every service the attempt started (Decision 6); and mechanism 3's, from the screen to every caller
(Decision 9), which is what stops mechanism 1 parking a request nothing will ever end.

Mechanism 4 exists because of mechanism 1: a manual run parked on `NetworkType.CONNECTED` never
terminates, and with a vocabulary of `{Syncing, Synced, Failed, NeverSynced}` a parked run has no honest
rendering — "Syncing" would leave `CircularProgressIndicatorMMD` spinning indefinitely on a display that
must not repaint continuously. **The cancel was the correct trade for the vocabulary available.**

Adding the missing word was designed and then dropped, because it buys one case:

| Case | Fixed by this design | Would need "Blocked" |
|---|---|---|
| Online, periodic sync failing for days — row shows a stale `Last sync ✓` | yes | — |
| Contacts failures never reaching the cell | yes | — |
| No way to learn *why* it failed | yes | — |
| Manual tap while offline | works today (AC 14) | — |
| Mid-sync network loss | works today, once made per-service | — |
| Offline for days, periodic parked | no | yes |
| A parked *one-time* run reading as a sync in progress | Decision 9, for requested syncs | for a non-manual run interrupted mid-flight |

The last row was missed when this was first written, and it is the severe one: a resting periodic worker
is not counted as syncing, so its symptom is a stale `✓`, but a parked **one-time** run *is* counted by
`KompaktAccountProgressUseCase` (the unique work name is one of its tags), so the row spins on a display
that must not repaint continuously — the outcome this decision's own argument calls unacceptable. So
"the one case it uniquely fixes is the one where the user already knows the answer" does not hold for
that row: the user opened the app precisely because something looks stuck.

Decision 9 closes it for every sync that goes through a *request* — nothing is enqueued that cannot run.

**A content change made while offline is not a second source, which was checked on a device rather than
reasoned.** The sync framework attaches the connectivity requirement to its *own* job, above us: with the
device offline and a pending `upload=true` calendar sync queued (`CONNECTIVITY` unsatisfied),
`SyncAdapterImpl` is never invoked, no `OneTimeSyncWorker` exists in any non-terminal state, and both
rows render their last-sync time with no spinner. Content observation is also disabled whenever the
interval is null, so a switched-off service produces nothing either way.

**What does remain, reproduced on a device:** a *non-manual* one-time run — content change, upload or
push — that is already executing when the network drops. Cutting wifi and mobile data mid-run left a
`OneTimeSyncWorker` for `EVENTS` at `state = ENQUEUED`, `run_attempt_count = 1`, `stop_reason = 7`
(`STOP_REASON_CONSTRAINT_CONNECTIVITY`) — still carrying its one-time tag, so counted as syncing, with
the Calendar row showing the spinner and "Synchronizing…" for four minutes while nothing ran. The offline
watch does not cover it, because that watch only follows runs an attempt started. A periodic worker
interrupted the same way is harmless: at rest it carries no one-time tag. Restoring wifi resumed the run,
which succeeded and wrote its outcome (`EVENTS`, `succeeded = 1`, `trigger = AUTOMATIC`) — so nothing is
lost, and the defect is only the claim.

**Its fix is `WorkInfo.getStopReason()`, and it belongs to SHP-1155's file, not here.** A queued one-time
run should count as syncing only while `stopReason == STOP_REASON_NOT_STOPPED`: a fresh enqueue and a
`Result.retry()` both read `NOT_STOPPED` and stay counted, which keeps the no-flicker property
`KompaktAccountProgressUseCase` was built for, while a constraint-stopped run drops out. The reason also
resets to `NOT_STOPPED` when the run finally executes, so the predicate self-heals on the same event that
makes the spinner honest again — both halves observed (`7` while parked, `-256` after the successful
run).

For the rest, the one case "Blocked" uniquely fixes is the one where the user already knows the answer. Against that:
constraint changes, dependence on `nextScheduleTimeMillis` and `stopReason` semantics, and a new state
on a slow display.

**A trap this decision protects.** Cancellation must never be propagated to periodic work.
`CancelWorkRunnable` calls `workSpecDao.setCancelledState(id)`, and `CANCELLED.isFinished == true` — a
cancelled periodic worker **never runs again**, silently, until something re-enqueues it. Today
`KompaktSyncWorkImpl.cancel` targets `OneTimeSyncWorker.workerName(...)` only, and it must stay there
when it becomes per-service. `disablePeriodic` is the only legitimate way to stop periodic work.

For the record, had "Blocked" been built: `WorkInfo.getConstraints()` reports *declared* requirements,
never their satisfaction. Overdue is derivable (`ENQUEUED && nextScheduleTimeMillis <= now`, with
backoff already folded into that value), but the *reason* is not — **for a run that never started**. That
qualifier was missing and matters: a run that *was* stopped carries `WorkInfo.getStopReason()`, which
names the constraint, and that is the only case reachable here (see the parked-run row above). Don't read
the sentence above as "WorkManager never tells us why".

### 2. The store is a new Room table, mirroring `syncstats`

```kotlin
@Entity(tableName = "kompakt_sync_outcome",
    primaryKeys = ["serviceId", "dataType"],
    foreignKeys = [ForeignKey(
        childColumns = ["serviceId"], entity = Service::class,
        parentColumns = ["id"], onDelete = ForeignKey.CASCADE
    )]
)
data class KompaktSyncOutcome(
    val serviceId: Long,
    val dataType: String,
    val at: Long,
    val succeeded: Boolean,
    val cause: String?,
    val trigger: String,
    val detail: String?
)
```

`cause` holds a `KompaktSyncFailure.name` and is null on success; an absent or unparseable value renders
as `Unknown`. `trigger` is `MANUAL` or `AUTOMATIC`, from the existing `INPUT_MANUAL` input — no new
plumbing. **`trigger` and `detail` are written but unread today**, kept as diagnostics; nothing in the
copy varies by them.

**The composite key is defence in depth; the write filter is what is operative.** `TASKS` also resolves
to `Service.TYPE_CALDAV`, so `serviceId` alone would be ambiguous — but Decision 4's filter means a
`TASKS` outcome is never written, so the collision cannot occur. Both rules are kept, and neither should
be removed on the strength of the other.

**Successes are recorded, not just failures.** Otherwise "is it currently failed?" means comparing
`failure.at` against `syncstats.lastSync` across two tables, which breaks precisely when a failing run
writes no `syncstats` row. Each table answers one question: `syncstats` → *when did it last succeed*
(the "Last sync — …" text, unchanged); `kompakt_sync_outcome` → *how did the last attempt end*.

**Cleanup is structural.** `AccountRepository.delete` calls `serviceRepository.deleteByAccount`, so the
cascade fires on unlink and on the cross-app `LOGOUT` broadcast with no code of ours. A rename is
`UPDATE service SET accountName=…`, which keeps `service.id`, so an outcome survives it.

**Migration.** `AppDatabase` gains `KompaktSyncOutcome::class` in `entities`, an
`abstract fun kompaktSyncOutcomeDao()` accessor, `version = 20`, and `AutoMigration(from = 19, to = 20)`
— **no spec class**, since Room generates the `CREATE TABLE` for a new entity. Commit the
generated `core/schemas/…/20.json` and add a concrete `AutoMigration20Test : DatabaseMigrationTest(20)`,
matching `AutoMigration16Test` / `AutoMigration18Test`.

`(serviceId, dataType)` is the primary key itself; there is no synthetic row id. `@Insert(onConflict =
REPLACE)` therefore conflicts on the key that carries the constraint, so the upsert asks nothing of the
caller and a row read back can be re-inserted unchanged. Room's missing-index diagnostic accepts the
primary key's implicit index as covering the FK, so no separate `Index` is declared.

**This departs from `SyncStats`, deliberately.** Upstream's table pairs
`@PrimaryKey(autoGenerate = true) val id: Long` with a unique index on `(collectionId, dataType)`, which
obliges every writer to pass `id = 0` so that Room binds NULL and the conflict falls through to the index
— `DavSyncStatsRepository.logSyncTime` is that pattern, and round-tripping a read row through it would
replace by row id instead. That is an upstream file and stays as it is; ours is new, so it states the
constraint as the key and needs none of the ceremony. Expect the two to read differently side by side.

Last-only, no history.

**Known hazard, accepted rather than designed around.** `version = 20` is also upstream's next number.
A future rebase onto an upstream release that ships schema 20 collides on `AppDatabase.kt`, `20.json` and
`AutoMigration20Test.kt` — the last by both file and class name, since it deliberately follows upstream's
`AutoMigration16Test` / `18Test` sequence rather than taking a `Kompakt` prefix — and the failure mode of a botched resolution is `fallbackToDestructiveMigration(dropAllTables
= true)` — which wipes the database and removes every account. No rebase is currently planned; when one
is, this must be resolved deliberately. A separate `KompaktDatabase` avoids the collision entirely at
the cost of the free cascade and an explicit delete on unlink and `LOGOUT`; it is the fallback if the
collision ever bites.

Note also that **`AppDatabase.kt` carries no fork edits today** — it is byte-identical to the upstream
mirror. This is its first, which is part of the hazard above.

### 3. Classification happens in the worker, from `SyncResult`

`SyncResult` is the only place a run's outcome is complete, and it reaches `doSyncWork` intact. The
classifier is a pure function over it — no `Context`, no throwable, JVM-testable without Robolectric:

```kotlin
enum class KompaktSyncFailure { AuthExpired, ClockSkew, DeviceError, ServerProblem, NetworkProblem, Unknown }
```

Precedence, ordered by what the user can act on:

| Cause | From |
|---|---|
| `AuthExpired` | `numAuthExceptions` |
| `ClockSkew` | `numClockSkewErrors` |
| `DeviceError` | `contentProviderError`, `localStorageError`, `numDeadObjectExceptions` |
| `ServerProblem` | `numHttpExceptions`, `numServiceUnavailableExceptions` |
| `NetworkProblem` | `numIoExceptions` |
| `Unknown` | `numUnclassifiedErrors`, and anything else with `hasError()` |

Rejected alternatives: classifying at the throwable in `SyncManager.handleException` is richer but
per-*collection* while the UI is per-*service*, is a deeper upstream edit, and structurally cannot see
the cases handled above it (the 503 delay, the `DeadObjectException` rethrow, the `SSLHandshakeException`
suppression) or below it (`contentProviderError` in `Syncer.invoke`). Structured `WorkInfo` output is
dead on arrival for periodic work.

The regex and all `outputData` parsing leave the UI either way.

### 4. One writer, three terminal branches, guarded by `isStopped`

The write goes in `doSyncWork`, in the **terminal branches only** — not beside the existing
`setReauthNeeded` block, which sits before the verdict and therefore also fires on retries.

| Path | Result | Writes |
|---|---|---|
| `runningSyncs` dedupe | `success()` *(early, in `doWork`)* | nothing — the winner writes |
| `InvalidAccountException` raised early in `doWork` | `failure()` *(early)* | nothing |
| non-manual, `internetAvailable()` false | `success()` *(early)* | nothing |
| non-manual, `wifiConditionsMet()` false | `success()` *(early)* | nothing |
| no tasks provider | `failure()` | nothing — `TASKS` is not a Kompakt service |
| soft error, `runAttemptCount` 0–4 | `retry()` | nothing — not terminal |
| soft error, `runAttemptCount` 5 | `failure(TOO_MANY_RETRIES)` | `Failed(cause)` |
| hard error | `failure(output)` | `Failed(cause)` |
| clean run | `success(output)` | `Success` |
| any of the above three with `isStopped` | as above | **nothing** |

(`runAttemptCount` is 0-based on the first execution, so `MAX_RUN_ATTEMPTS = 5` means five `retry()`
returns and a failure on the sixth run.)

Two properties come free from the existing control flow: all four early returns happen in `doWork`
**before** `doSyncWork`, so a run that aborted on no-internet can never overwrite a real `Failed` with a
bogus `Success`; and retries do not flash an error, which matters where a flash costs a repaint.

**The `isStopped` guard — corrected rationale.** An earlier draft argued the guard was required because
a cancellation would land in `Syncer.invoke`'s `else` arm as `numUnclassifiedErrors`. **That path is not
reachable**: every syncer calls `runBlocking { syncManager.performSync() }`, and `runBlocking` starts a
job with no parent, so cancelling the worker's job never propagates into the sync; `Syncer.invoke` and
the `syncer()` call are both non-suspend, so there is no cancellable suspension point between them
either. A stopped worker runs to completion and returns a real verdict — most likely `NetworkProblem`
when the offline collector cancelled it.

The guard is still right, for a plainer reason: **the outcome records a completed attempt, and an
interrupted attempt did not end.** `ListenableWorker.isStopped` is set by WorkManager independently of
coroutine cancellation, so it is true and readable at the write point. It is never read today —
`BaseSyncWorker` only *logs* `stopReason` in its `finally`. `NonCancellable` must **not** be used: it
would force through exactly the write the guard exists to prevent.

Writes are filtered to dataTypes that map to a `KompaktSyncService` — `EVENTS` and `CONTACTS`. A
`KompaktSyncService.fromDataType(dataType)` helper joins `fromRequestName`.

`KompaktServiceSyncOutcome.record(account, service, …)` resolves the `Service` row itself and
**no-ops when it is absent**, with the insert wrapped in a logged try/catch. This matters because
`Syncer.invoke` catches `InvalidAccountException` *inside* its try — so an account unlinked mid-run
yields a clean `SyncResult` after `serviceRepository.deleteByAccount` has already cascaded, and an
unguarded insert would throw `SQLiteConstraintException` out of `doSyncWork`.

Nothing ever clears a row: it is replaced by the next terminal outcome, or cascade-deleted. There is no
"dismiss" that mutates the store.

**Accepted limitation: three paths write `Success` having done nothing.** `Syncer.invoke` returns with a
clean `SyncResult` when the runtime permission is missing (`SecurityException` from
`acquireContentProvider`), when `prepare(provider)` returns false, and when no collection is
sync-enabled. All reach the clean-run branch and write `Success`, clearing a genuine ⚠. Accepted rather
than fixed: the first two are only reachable by deliberate `adb` manipulation on a Kompakt, and a
service with no selected collection would itself mean `KompaktInitDefaults` failed to apply — a bug to
fix at that source, not to paper over here. Detecting them would need a "this run touched something"
signal out of upstream's `Syncer`, since all three are plain `return`s that mutate nothing.

### 5. `needs_reauth` stays one account-level flag; its writer widens, its *clear* is guarded

```kotlin
if (service != null)                                    // EVENTS or CONTACTS, not TASKS
    when {
        syncResult.numAuthExceptions > 0     -> setReauthNeeded(account, true)
        !isStopped && !syncResult.hasError() -> setReauthNeeded(account, false)
    }
```

Two changes, and the asymmetry is the point.

**Widened past `EVENTS`** — otherwise a **Contacts-only** account, which SHP-1159 makes legal, never
runs an `EVENTS` sync, so a revoked token is never detected and no re-auth prompt appears. Clearing from
either service is sound: one token means a clean run by either proves it good.

**Only the clear is guarded.** Setting *latches evidence* — a 401 was observed, and that stays true
whether or not the worker was later stopped; guarding it would discard a genuine detection from the only
durable auth signal we have. Clearing *asserts a completed clean sync*, which a stopped run has not
performed. Today it clears anyway, which is how a revoked token's prompt can silently vanish on a
network drop. Retries are unaffected: `hasError()` is true on a soft-error retry, so the clear arm
cannot fire.

`isStopped` does **not** help with the zero-work runs of Decision 4 — those are not stopped, they
complete cleanly and still clear the flag. That is part of the same accepted limitation.

### 6. The cell reads the store; the modal watches the runs it started

`_syncFailed` currently means both "this service is failing" and "raise a dialog", which is why
`consumeDialog` clears it and why tapping the alert icon erases the error. Splitting the two is the fix,
and it is SHP-1149 AC 2:

| | Source | Lifetime |
|---|---|---|
| **Cell** ⚠ and cause | the persisted outcome row | until the next terminal outcome replaces it |
| **Modal** | the runs *this screen* created | this screen session only |

`consumeDialog` then dismisses only the modal; the row keeps its ⚠. The tap opens the cause sheet — a
pure read that mutates nothing. The row clears when a sync actually succeeds.

**The modal awaits every tracked run and stays generic** (SHP-1149 AC 12). Today's `SyncFailed` copy is
already service- and cause-agnostic, so no tie-break is needed when the two services fail with different
causes — the modal never names one. Per-service detail lives in the cell's tap.

```
all tracked runs terminal
  ├─ needs_reauth set → raise nothing; AuthError already outranks SyncFailed
  ├─ any other failure → the existing SyncFailed modal, carrying the failed set for Try again
  └─ otherwise         → nothing
```

**The auth discriminator is the account-level `needs_reauth` flag**, read once *after* every tracked run
is terminal — not during, since both workers write it concurrently. It is authoritative: in the rare
window where one service's clean run clears a flag the other's 401 just set, a stored `AuthExpired`
renders the **generic** cause copy rather than offering a re-link the account does not need. In practice
that window is unreachable from the cell anyway, because the `AuthError` sheet is non-cancelable
(`shouldDismissOnBackPress = false`, `shouldDismissOnClickOutside = false`, `onDismissRequest = {}`), so
while the flag is set no cell is tappable.

**The mid-sync cancel becomes per-service, and moves into `KompaktSyncAttempt`.** Its trigger is
unchanged, but `KompaktSyncWorkImpl.cancel` is hardcoded to `CALENDAR` today while attempts are now
per-service — and `buildOneTime` sets `NetworkType.CONNECTED` on manual requests, so a manual contacts
run parks identically. Left alone, both toggles on and a network drop would cancel calendar and leave the
contacts cell spinning indefinitely: the exact e-ink state Decision 1's argument exists to prevent. The
watch cancels **every service the current attempt has in flight**, still by one-time work name only
(Decision 1's trap), and the attempt resolves as `Interrupted(NoNetwork)`.

Where a network drop leaves things, since it is the case most easily got wrong: WorkManager stops the
running worker (`ConstraintsNotMet` → `WorkerWrapper.interrupt`), sets `isStopped`, and `resetWorkerStatus`
returns the WorkSpec to **`ENQUEUED`** with `stopReason` recorded — the returned `Result` is discarded.
The sync itself keeps running, because `runBlocking` has no parent job, and reaches our write point with
`isStopped` true, so **nothing is written**. The watch then cancels the one-time work, which is terminal:
it will *not* resume when the network returns and the user must tap again. The **periodic** work is
untouched and resumes by itself — which is what satisfies SHP-1149 AC 5. With the screen closed there is
no watch at all, so the parked one-time run simply resumes and writes its outcome normally.

Rejected alternative: an "awaiting an outcome newer than T" marker instead of tracked ids. It attributes
a periodic run's outcome to a user tap, and needs a lifecycle for the cases where no outcome is written.
Decision 7 removes the problem it was solving at the source.

### 7. Do not start a sync while one is in progress

SHP-1157 AC 8, per service (AC 9/10). The gate lives in `KompaktStartSyncUseCase` and applies to every
caller, including the cross-app receiver — see *Contract change*. The predicate is
`KompaktAccountProgressUseCase(account, dataType).first()`, already written and tested on the base
branch. No signature changes: `KompaktSyncWork.enqueue` already returns `UUID?` and
`KompaktStartSyncUseCase` already returns `Map<KompaktSyncService, UUID?>`, so a gated service yields
`null` — one more reason for a null the callers already handle.

Layering note: `KompaktAccountProgressUseCase` sits in `ui.account` while `KompaktStartSyncUseCase` is in
`sync`. The class injects only `WorkManager` and has no UI dependency, but the package edge is worth
seeing; moving it to `sync` is a reasonable tidy-up if the reviewer prefers.

**The result must distinguish "nothing eligible" from "already syncing"** — they need opposite handling.
Nothing eligible is SHP-1157 AC 6, which owns a dialog; already syncing is AC 8, which must stay silent.
Collapsing them into one `NothingStarted` would force SHP-1157 to re-derive eligibility, which is the
duplication `KompaktSyncEligibility` exists to prevent.

*Superseded in part by Decision 9:* the `Map<KompaktSyncService, UUID?>` return and the `null`-means-gated
convention are gone — the gate still lives at the choke point, and the result is a sealed
`KompaktSyncStartResult`. **The layering note is not resolved:** `KompaktStartSyncUseCase` still imports
`at.bitfire.davdroid.ui.account.KompaktAccountProgressUseCase`. Moving that class into `sync` remains the
tidy-up, and it belongs to SHP-1155, which owns the file.

Residual, accepted, **and the opposite way round from what this originally said**: a periodic run can
start between the check and our worker starting, in which case ours skips via `runningSyncs` and returns
`success()` from `doWork` *before* `doSyncWork`, writing no outcome. `awaitFinished` then sees SUCCEEDED
and the verdict reads whatever row is already there — so a service with a stored failure reports `Failed`
again after a run that did nothing wrong, and the modal **speaks when it should be silent** rather than
staying silent when it should speak. The **cell** is unaffected, because the store is written by whichever
worker really ran. Bounding the read by a timestamp was considered and dropped: a caller joining an
in-flight drain would then miss outcomes written before it arrived, costing the shared-aggregate property
to fix a race one scheduler tick wide. The main source of that race — the toggle firing an immediate
periodic run — is removed by the base branch.

### 8. `AddressBookSyncer` mirrors `Syncer.invoke`'s arms

Narrower than it first appears, and the rationale is corrected deliberately: `syncAddressBook`'s `try`
wraps `performSync()`, but `performSync` classifies HTTP, auth, IO and local-storage errors into
`syncResult` itself before the outer catch sees them. Contacts failures of the common kinds **do** reach
`SyncResult` today.

What the blanket `catch (e: Exception)` swallows is the preamble — the group-method change, the
`AccountManager` writes, the `provider.delete` calls — plus the three exceptions `performSync` rethrows.
So the hole is "a preamble failure reports success", not "contacts can never fail". The blocker for
contacts was always `failedOf(CONTACTS) = flowOf(false)`, which Decision 6 fixes.

The edit gives the catch the arms `Syncer.invoke` already has: `DeadObjectException` as a soft error,
`InvalidAccountException` logged and uncounted, everything else `numUnclassifiedErrors++`.


### 9. Every precondition lives in `KompaktStartSyncUseCase`, in one ordered sequence

Added in review. Decisions 1 and 7 left the preconditions split: storage and network in
`KompaktSyncAttempt`, eligibility and the already-syncing gate in `KompaktStartSyncUseCase`. Two problems
came out of that split.

**The environment answered before anything asked whether there was work.** With both services switched
off and the device offline, tapping Synchronize reported "No internet connection" — an answer to a
question the user had not asked, which sends them to check a connection that changes nothing. Any empty
eligible set does it: consent revoked, or a retry set naming a service switched off since the modal
appeared.

**And a parked run reads as a sync in progress.** `buildOneTime` tags every request with the unique work
name, and `KompaktAccountProgressUseCase` counts a one-time run in `ENQUEUED` as syncing — so a request
enqueued while offline spins `CircularProgressIndicatorMMD` until connectivity returns, surviving process
death, with no watch to end it (the offline watch lives inside an attempt, and a cross-app request has
none). That is Decision 1's missing table row.

So the sequence is one function, and **nothing is enqueued that cannot run**:

```
1. consented && (switchedOn || defaults not applied)   none -> NoneEligible
2. storage.isLow()                                          -> NoStorage
3. network.isAvailable()                                    -> NoNetwork
4. ensure the Service row (discovery; a failure drops that service)
5. ensure the defaults are applied
6. consented && switchedOn                             none -> NoneEligible
7. already-syncing gate                                all  -> AlreadySyncing
8. enqueue                                                  -> Started(runs)
```

**Why the order cannot simply put eligibility first.** The three facts are circular: the authoritative
switch needs defaults applied, applying defaults needs a `Service` row and possibly discovery, and both
need the network. So connectivity necessarily precedes the authoritative answer. Step 1 is therefore what
can be ruled out from local state alone — and for an already-configured account (steps 4 and 5 both
short-circuit) it *is* the authoritative answer, which is what fixes the switched-off case. The order is
deliberately state-dependent: configured accounts get eligibility first, unconfigured ones get the guards
first, because for those the network genuinely is the blocking fact.

**Why step 1 needs the `defaults not applied` escape.** `KompaktServiceToggle.isOn` reads the stored
interval, and `KompaktInitDefaults.maybeApply` is what writes it — explicitly-off and never-defaulted are
both `null`, and only `KompaktInitDefaults.isApplied` separates them. Without the escape, a just-linked
account tapping "Sync now" before the screen's init collector wins the race would report `NoneEligible`
and never sync. Step 6 is the same predicate after materialization, where the escape is vacuous.

**Consent is checked in both, and that is not redundant.** It was briefly moved to step 6 on the grounds
that the switch implies consent — true only at the moment of switching on. Consent can be revoked
afterwards (a re-auth dropping a scope, or revocation at Google) with the switch left on, so it is an
independent fact; and being a pure `AuthState` read it is the cheapest and most stable thing in the
sequence, so it belongs at the front.

**Eligibility gets granular, and loses the row check.** `enabledServices` becomes `consented(account)`
and `switchedOn(account)` — one settings read each, neither `suspend` any more, and no
`DavServiceRepository`. Composing them is the use case's job, so `undecided` is a named local rather than
a clause hidden inside a predicate whose meaning changes with the call site. The row requirement returns
as a *configuration outcome* — `KompaktServiceProvisioning.ensureRow` returning true is what proves a row
exists — which also closes a hole the plain removal would have opened: a service switched on in an
earlier session whose row has since gone would otherwise be enqueued to sync nothing.

`KompaktServiceProvisioning` lifts `ensureServiceRow`/`discoverService` out of
`KompaktLinkedAccountModel`, so the toggle path and the sync path share one implementation. Discovery is a
network round-trip, which is the other reason step 4 cannot precede step 3.

**What `KompaktSyncAttempt` keeps:** the ids, the drain, the offline watch and the verdict. It maps
`KompaktSyncStartResult` onto `KompaktAttemptResult` and no longer holds a guard of its own. The watch stays
because it is tied to the screen's lifetime; giving a background caller one would be wrong.

**Rejected: a flag to skip the guards for background callers.** That is Decision 7's rejected
`gateWhileRunning` in a new costume — two callers with different concurrency semantics. Dropping a
cross-app request while offline loses freshness-on-reconnect, and that is the right trade here: the
periodic worker still covers the data, the throttle keys on the last *successful* sync so the window is
not consumed, and the alternative is a phantom spinner on an e-ink display.

---

## Components

New files, all in `core`:

| File | Responsibility |
|---|---|
| `db/KompaktSyncOutcome.kt` | the entity |
| `db/KompaktSyncOutcomeDao.kt` | upsert, `get(serviceId, dataType)`, `observe(serviceId, dataType)` |
| `repository/KompaktSyncOutcomeRepository.kt` | the DAO behind ids only — `record`, `get`, `observe` keyed by `serviceId` + `dataType`, exactly as `DavSyncStatsRepository` is |
| `sync/KompaktServiceSyncOutcome.kt` | the one place that resolves an account to its service row: `record`, `get`, and `observe` as `Reported` |
| `sync/KompaktSyncFailure.kt` | the cause vocabulary |
| `sync/KompaktSyncOutcomeClassifier.kt` | pure `SyncResult -> KompaktSyncFailure?` |
| `sync/KompaktSyncAttempt.kt` | await every run created, watch for the network going, aggregate |
| `ui/account/KompaktSyncFailureSheet.kt` | the cause sheet, built on **`KompaktModalSheet`** |
| `sync/KompaktNetworkAvailability.kt` | "is there internet", one-shot **and** observed — wraps upstream's `SyncConditions` so the pre-flight and the offline watch share one predicate. The observed flow is **seeded**, because `registerNetworkCallback` reports `onAvailable` only for networks that already match and `onLost` only for ones it matched itself: with nothing connected it reports nothing at all, and a collector waiting for `false` would wait for the life of the flow |
| `sync/KompaktStorageAvailability.kt` | "is storage low" as an injectable seam over the existing `KompaktStorage` object, so the pre-flight is testable |
| `sync/KompaktServiceProvisioning.kt` | Decision 9 — `ensureRow`, lifted out of `KompaktLinkedAccountModel` so the toggle path and the sync path share it |

`KompaktStartSyncUseCase`'s result, from Decision 9 — the `Map<KompaktSyncService, UUID?>` whose nulls
meant "gated" by convention is gone:

```kotlin
sealed interface KompaktSyncStartResult {
    data object NoStorage : KompaktSyncStartResult
    data object NoNetwork : KompaktSyncStartResult
    data object NoneEligible : KompaktSyncStartResult      // no consent, or switched off
    data object AlreadySyncing : KompaktSyncStartResult    // everything eligible had a run in flight
    data class Started(val runs: Map<KompaktSyncService, UUID>) : KompaktSyncStartResult
}
```

Upstream files edited:

| File | Edit | Fork status |
|---|---|---|
| `db/AppDatabase.kt` | entity, DAO accessor, `version = 20`, one `AutoMigration` | **first fork edit** |
| `sync/worker/BaseSyncWorker.kt` | the outcome write; widen and guard the re-auth block | already fork-edited |
| `sync/AddressBookSyncer.kt` | the catch arms | **first fork edit** |

Kompakt files edited: `sync/KompaktStartSyncUseCase.kt` (every precondition, sealed
`KompaktSyncStartResult`), `sync/KompaktSyncEligibility.kt` (granular `consented` / `switchedOn`, no row
check), `sync/KompaktInitDefaults.kt` (`isApplied`), `sync/KompaktSyncRequestUseCase.kt`,
`sync/KompaktSyncService.kt`
(`fromDataType`), `sync/KompaktSyncWork.kt` (per-service cancel already exists; no signature change),
`ui/account/KompaktLinkedAccountModel.kt`, `ui/account/KompaktServiceSyncState.kt`,
`ui/account/KompaktServiceSyncCell.kt`, `ui/account/KompaktLinkedAccountScreen.kt`,
`util/KompaktFlowCombine.kt`. Plus `docs/app-integration.md`.

**What leaves `KompaktLinkedAccountModel`**, following the predecessor spec's own accounting:
`_syncFailed`'s cell duty, `_trackedSync`, `trackedSyncInfo`, `hadAuthError()`, the result collector, the
offline-cancel collector, the private `networkAvailable` `callbackFlow`, `refreshStorageState`'s role in
the sync path, both pre-flight checks, and — from Decision 9 — `ensureServiceRow` and `discoverService`
with the four dependencies that existed only for them. `syncNow()` and `setServiceSync` become one `when` over
`KompaktAttemptResult`. `_syncFailed` survives only as the modal flag feeding `linkedAccountDialog`.

Two small edits worth naming because they are easy to miss:

- **`onFailureClick` must carry a service.** It is `() -> Unit` today, wired to `model::consumeDialog`
  and passed identically to both cells. It becomes `(KompaktSyncService) -> Unit`, each cell passing its
  own constant. This is the edit that makes AC 2 actually per-service.
- **`KompaktFlowCombine` needs an 8-arity overload.** The `dialog` combine already uses the custom
  7-arity `combine` (kotlinx stops at 5), and the cause sheet is an eighth raised-and-dismissed dialog.
  `KompaktFlowCombineTest` exists to extend. The deferred dialog-flag collapse (*Follow-ups*) is what
  would otherwise have avoided this.

### `KompaktSyncAttempt`

**The component owns the whole attempt** — the guards before it, the runs during it, and the verdict —
so that "why did nothing sync" has exactly one source. Its entire public surface is one function:

```kotlin
class KompaktSyncAttempt @Inject constructor(
    private val startSync: KompaktStartSyncUseCase,        // every precondition; Decision 9
    private val outcomes: KompaktSyncOutcomeRepository,
    private val accountSettings: KompaktAccountSettings,   // needs_reauth
    private val network: KompaktNetworkAvailability,       // the watch only; Decision 9
    private val syncWork: KompaktSyncWork,
    private val workManager: WorkManager
) {
    /** Starts what it can, then waits for every run this component has started to finish. */
    suspend fun run(account: Account, services: List<KompaktSyncService>): KompaktAttemptResult
}

sealed interface KompaktAttemptResult {
    data object BlockedNoStorage : KompaktAttemptResult    // mapped from KompaktSyncStartResult, AC 14
    data object BlockedNoNetwork : KompaktAttemptResult    // mapped from KompaktSyncStartResult, AC 14
    data object NoneEligible : KompaktAttemptResult        // SHP-1157 AC 6 owns the dialog
    data object AlreadySyncing : KompaktAttemptResult      // nothing to add; SHP-1157 AC 8
    data object Succeeded : KompaktAttemptResult
    data object AuthFailed : KompaktAttemptResult          // needs_reauth set; AuthError outranks
    data class Failed(val retry: Set<KompaktSyncService>) : KompaktAttemptResult
    data class Interrupted(val reason: KompaktInterruption) : KompaktAttemptResult
}

enum class KompaktInterruption { NoNetwork }
```

`KompaktLinkedAccountModel.syncNow()` becomes a dispatcher — one `when`, no collectors, no tracking
fields — and every "why nothing synced" answer comes from the same return value.

**The unit is the set of runs we have started, not a per-call attempt.** `run()` adds whatever it can
start to one shared set and then waits for that set to drain. A second call while the first is waiting
is never refused: it contributes its runs to the same set and both callers await the same drain. Setting
`_syncFailed` twice is idempotent, so that is one modal.

**They can still disagree on an interruption**, because each call owns its own watch and its own
`interrupted` flag: one caller's watch can fire, clear the set and cancel the work, while the other's
`watch.cancel()` beats its own grace period — so the first resolves as `Interrupted` and the second as
`Succeeded` over cancelled runs that contribute no failure. The user still sees one `NoInternet` sheet,
so this is a property the design leans on harder than the screen does.

This is what makes the awkward cases disappear rather than needing rules:

- **No refusal, so no dead button.** Calendar fails fast while contacts is still going; the user taps
  calendar's alert and hits Try again. The retry joins the set, and the modal appears once everything has
  settled — reporting the final state, which is closer to SHP-1149 AC 12's *"displayed once the results
  of both synchronizations are executed"* than two attempts each speaking for half the screen.
- **No two modals.** There is one drain event, so there is one raise.
- **No overlap bookkeeping.** If a requested service already has a run in flight, Decision 7's gate
  returns `null` and nothing is added; the caller simply awaits whatever is already in the set.

`AlreadySyncing` therefore means exactly *"we added nothing, because every requested service was already
syncing — by a periodic run, or by a run of ours"*, and there is nothing to await.

**Liveness is derived, never bookkept.** The set holds only the ids we started; whether an id is still in
flight is read from WorkManager at the point of asking. That matters because `run()` executes in
`viewModelScope`: if the user leaves mid-sync the await is cancelled, and any set maintained by the
caller's own bookkeeping would be left stale, wedging every later call. Deriving it means a stale entry
self-heals — its `WorkInfo` is terminal, or `null` because it was pruned or replaced by
`APPEND_OR_REPLACE` — and the component needs no background watcher, no scope of its own, and no
lifecycle. An id is finished when its `WorkInfo.state.isFinished` **or** its `WorkInfo` is `null`.
`Result.retry()` returns a run to `ENQUEUED`, so retries correctly do not count as finished.

**Why the offline watch lives here rather than in the model.** It was the only consumer of an exposed
in-flight set, and the only reason the model had to honour "clear tracking *before* cancelling, so the
cancel is not reported as a failure". Inside, the component knows it initiated the cancel, so that
ordering stops being a cross-object protocol and becomes how the function is written. Two consequences:

- **Toggle-off needs no API at all.** A run cancelled by switching a service off is already covered by
  the `CANCELLED` rule — terminal, contributes no failure. The only reason to publish a `stopTracking`
  was to tell *our* cancel apart from someone else's, and we no longer have to ask.
- **The cancel is per-service for free.** The watch cancels every id in the set that is still
  non-terminal — which is what fixes the contacts row spinning forever (Decision 6).

`run()` is called in `viewModelScope`, so leaving the screen cancels the await, the watch dies, and
**nothing is cancelled** — the parked run simply resumes when connectivity returns. That is SHP-1157
AC 12.

**Pre-flight** *(moved again by Decision 9)*: the guards live in `KompaktStartSyncUseCase`, and the
attempt maps `NoStorage` / `NoNetwork` onto `BlockedNoStorage` / `BlockedNoNetwork`. AC 14 behaviour is
unchanged — neither enqueues anything and neither writes an outcome.

**One seam, two deliberately different predicates.** `KompaktNetworkAvailability` gathers both existing
checks behind one injectable — which is what makes the attempt testable — but keeps each as it is today:

```kotlin
class KompaktNetworkAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val syncConditionsFactory: SyncConditions.Factory
) {
    /** One-shot — the pre-flight answer (AC 14). Upstream's check, unchanged. */
    @WorkerThread fun isAvailable(account: Account): Boolean

    /** The model's existing callbackFlow over validated networks, unchanged. */
    fun observe(): Flow<Boolean>
}
```

They **do** disagree in one case, and that is accepted rather than fixed:
`SyncConditions.internetAvailable()` requires `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`
**and** honours `accountSettings.getIgnoreVpns()`, while the watch requires only the two capabilities.
With `ignoreVpns` set and a VPN as the only transport, the pre-flight refuses to start while the watch
still reports online.

Unifying them was tried and reverted. Deriving each emission from `isAvailable` would put an
`AccountSettings.Factory.create` — a `@WorkerThread` AccountManager read that may throw — on **every
connectivity callback**, to correct a case no reachable Kompakt UI can even produce, since nothing sets
`syncWifiOnly` or `ignoreVpns`. The watch must stay cheap and non-throwing; the pre-flight must stay
upstream's answer. `internetAvailable()` is `internal`, so a `core` file may call it with no upstream
edit, and it blocks, so the attempt runs on the IO dispatcher as `syncNow()` already does.

`KompaktStorageAvailability` gets the same treatment for the same reason. `KompaktStorage` is an object
whose `isStorageLow(context)` takes a `Context`; wrapping it in a one-method injectable seam is what
makes `BlockedNoStorage` unit-testable, which is the point of moving the guards in at all. The existing
object stays as the implementation.

**Interruption.** If the offline watch cancels anything in the set, the attempt resolves as
`Interrupted(NoNetwork)`, whatever the other services did. The caller maps the *reason* to the
`NoInternet` sheet and **raises no failure modal**: with the network gone, a sibling failure is almost
certainly `NetworkProblem` from the same event, and one event should produce one dialog. The watch
itself raises nothing — it cancels and reports, and the reason is what names the sheet. The **cells are unaffected**: a service that completed and wrote
`Failed(NetworkProblem)` before the drop keeps its ⚠ and still explains itself when tapped. `Interrupted`
carries a reason so later causes — Offline+, say — need no new result case.

**The drain, and the two ways it waits a long time.** A manual one-time request carries only
`NetworkType.CONNECTED` (`buildOneTime` skips `setRequiresStorageNotLow` for manual), so loss of
connectivity is one way to park a run — and that is what the offline watch resolves, by cancelling into a
terminal `CANCELLED`.

**It is not the only one, which an earlier draft of this section got wrong.** A soft error returns
`Result.retry()` *without* writing an outcome (Decision 4's table), and `buildOneTime` sets
`BackoffPolicy.EXPONENTIAL` from `DEFAULT_BACKOFF_DELAY_MILLIS` with `MAX_RUN_ATTEMPTS = 5`, so a
retrying run stays non-terminal for roughly fifteen minutes — with the network up, so the offline watch
correctly does nothing. The drain waits, and the cell shows `Syncing` throughout, because a retrying run
is `ENQUEUED` with `stopReason == NOT_STOPPED`. Honest, since it *is* retrying, but long, and no timeout
bounds it. Accepted rather than fixed: a timeout would have to choose between abandoning a run about to
succeed and reporting a failure the store does not hold.

`WorkManager` is injected (`KompaktWorkManagerModule` provides it `@Singleton`), never
`WorkManager.getInstance` — so the component is JVM-testable with `mockk<WorkManager>()` returning a
replay `SharedFlow`, exactly as `KompaktAccountProgressUseCaseTest` already does. With the guards and the
offline watch inside, **the whole of this is unit-testable for the first time** — today's offline-cancel
is a ViewModel `init` collector, which is why nothing covers it.

## Behaviour

The pre-flight guards short-circuit before any of this — one sheet for no-internet or low storage, both
services, nothing enqueued, nothing written (SHP-1149 AC 14). What the user sees is unchanged; the guards
live in `KompaktStartSyncUseCase` and surface as `NoStorage` / `NoNetwork`, which `KompaktSyncAttempt`
maps to `BlockedNoStorage` / `BlockedNoNetwork` and `syncNow()` maps to the same two flags it sets today.
What *is* new (Decision 9) is that a request with nothing eligible answers **before** the guards, so a
switched-off account is not told to check its connection.

**Cell states.** `KompaktSyncStatus.Failed` gains a cause, and the outcome is read as
`Reported<KompaktSyncOutcome?>` so "not yet loaded" stays distinct from "no outcome" — without it the
first frame paints ✓ from `syncstats` and then repaints to the alert icon, which is the repaint
`KompaktServiceLastSync` and `syncingOf` were built to avoid. The shape transfers verbatim from
`KompaktServiceLastSync`: resolve the service row, `flatMapLatest` to the DAO flow, `onStart` a
`Pending`.

| switch | status | leading | subtitle | tap |
|---|---|---|---|---|
| `On` | `Syncing` | `CircularProgressIndicatorMMD` | "Synchronizing…" | — |
| `On` | `Synced(t)` | `ic_kompakt_success` | "Last sync - %s" | — |
| `On` | `NeverSynced` | `ic_kompakt_alert` | "Not synced yet" | **none** — intended |
| `On` | `Failed(t, cause)` | `ic_kompakt_alert` | "Last sync - %s", or "Not synced yet" | opens the cause sheet |
| `Off` / `ConsentMissing` | any | `ic_kompakt_sync_off` | "Sync is OFF" | — |

`NeverSynced` deliberately shares the alert glyph with `Failed`; the difference is that only `Failed` is
clickable. Two orderings follow from the existing `syncStatus` function and are stated so they are not
surprises: `Syncing` is tested before `failed`, so the alert icon is replaced by the spinner for the
duration of every run and returns after; and a switched-off row keeps hiding the status it still holds.

**Absent row semantics.** No outcome row means **not failed**, and is therefore indistinguishable from
"the last attempt succeeded". That is deliberate and has one visible consequence: immediately after
upgrading, an account that has been failing keeps its stale ✓ until the next sync lands, at which point
the mechanism starts working. Verification steps 2 and 3 must therefore run *after* at least one
post-upgrade sync.

**Copy.** Every cause renders from existing keys:

| Cause | Renders as |
|---|---|
| `AuthExpired`, while `needs_reauth` is set | the existing `AuthError` sheet — action **Link account** |
| `NetworkProblem` | `common_label_nointernetconnection` / `common_error_body_opensettingstocheck` |
| `ServerProblem`, `DeviceError`, `ClockSkew`, `Unknown`, and `AuthExpired` with the flag clear | the generic `…_accountsyncfailed` / `…_wecouldntsyncronizewithyyour` |

Three of six causes are therefore indistinguishable to the user. The vocabulary still earns its place:
`AuthExpired` routes to a different action, `NetworkProblem` gets specific copy, the stored `cause` and
`detail` are diagnostic, and adding copy later is a resource lookup that changes neither the classifier,
the store, nor the tests.

**One copy defect to fix by Frontitude refresh, not by hand.**
`calendar_accountsync_error_dialog_body_linkyouraccountagaintocontinue` currently reads *"Link your
account again to continue synchronizing with Google **calendar**"*. Design has changed it to *"…with
Google"*, which is what a Contacts-only account — the case Decision 5 exists to fix — needs. Keys may
have changed in the same pull, so the refresh happens before this ships.

### Try again

| | Raised by | Retries | Buttons |
|---|---|---|---|
| `SyncFailed` modal | automatically, once every tracked run has finished and at least one failed | only the services that **failed** in that attempt | **Try again**, **Cancel** |
| cause sheet | tapping one service's alert icon | that **one** service | **Try again**, **Cancel** |

The modal therefore carries data: `KompaktLinkedAccountDialog.SyncFailed` becomes
`data class SyncFailed(val retry: Set<KompaktSyncService>)`, and `linkedAccountDialog`'s
`syncFailed: Boolean` becomes `Set<KompaktSyncService>?` — the same shape `requestConsent` and
`confirmDisable` already use, so precedence ordering and the existing test style carry over.

The cause sheet is a new `KompaktLinkedAccountDialog.ExplainSyncFailure(service, cause)`, ranked **below**
`SyncFailed`, so a modal raised while the sheet is open replaces it rather than stacking two
`ModalBottomSheetMMD`s. Verb-first, like `RequestConsent` and `ConfirmDisable`: those and this one are
raised because the user asked for them, where `SyncFailed` and its neighbours are conditions the app
raises on its own — and it keeps the two failure dialogs from being one letter apart.

Rules, both surfaces:

1. **A retry is an ordinary sync request** — it goes through Decision 9's whole sequence, so eligibility,
   both guards, configuration and the gate all apply in that order. Retrying while offline raises the
   `NoInternet` sheet rather than silently doing nothing; retrying a service switched off in the
   meantime reports `NoneEligible` instead of blaming the network.
2. **It retries only what failed**, from the set the dialog carries. Today's `onSyncNow()` re-syncs
   everything, costing a second worker and a second set of repaints for fresh data — and a redundant
   *successful* manual run also pushes the periodic schedule back a full interval.
3. **The dialog dismisses first, then the sync starts.** Progress belongs in the cell, which already
   shows it; a modal holding a `CircularProgressIndicatorMMD` would repaint continuously.
4. **A repeat failure raises the modal again.** No backoff: the user is in control and **Cancel** is
   always there. A retry launched from the cause sheet raises the account-level modal, not the sheet.
5. **Cancel no longer erases the failure.** Today `consumeDialog()` clears `_syncFailed`, so dismissing
   also clears the row. With the cell reading the store, dismissing dismisses only the dialog.
6. **`AlreadySyncing` is silent** — the cell flips to `Syncing`, which is the feedback, since
   `KompaktAccountProgressUseCase` counts the running worker. **`NoneEligible`** is SHP-1157 AC 6's
   dialog. **`Interrupted`** raises the `NoInternet` sheet, from its reason — and deliberately not the
   failure modal, which would be a second dialog for one event. A retry is never refused — it joins the
   set of runs being awaited, so its result arrives with the rest.
7. **A retry writes an outcome like any other run**, replacing the row it is retrying.

## Invariants

1. **The store has one writer class, `BaseSyncWorker`, at three terminal call sites** — funnelled
   through a single private helper so the grep is one symbol.
2. **No outcome is written for a run that did nothing** — the early returns and any run where
   `isStopped`. The three clean-`SyncResult` zero-work paths of Decision 4 are a *stated exception*.
3. **The UI never parses `WorkInfo.outputData`.** The regex is deleted, not moved.
4. **The cell's failure state comes only from the store; the modal's *trigger* comes only from the runs
   the screen started.** The modal may read the store and the re-auth flag for its *content*.
5. **Cancellation is never propagated to periodic work.** One-time work names only, per service.
6. **`docs/app-integration.md` is updated in this change, and only in the places named in *Contract
   change*.** Any further edit means the design has drifted.
7. **Every precondition is asked in one place, in one order** (Decision 9). `KompaktSyncAttempt` maps
   `KompaktSyncStartResult` and holds no guard of its own; no caller re-implements one, and none opts out.
8. **Nothing is enqueued that cannot run** (Decision 9). A request whose constraints are unmet is
   refused, not parked — a parked run reads as a sync in progress with nothing to end it.

## The line this design does not cross

SHP-1149 AC 3 — "synchronization failures do not delete or modify previously synchronized data" — holds
for every error this design handles: none of them delete anything. The known chain that *does* delete on
a 403 from a dropped scope (`HomeSetRefresher` removing a home set without rethrowing, then
`Syncer.updateCollections` deleting local collections against an empty database set) is reachable only
via a re-auth that drops an already-granted scope, and belongs to **SHP-1180** along with the rest of
that analysis. A 403 therefore classifies as `ServerProblem` here, not `AuthExpired`.

## Tests

JVM, in `core`:

- `KompaktSyncOutcomeClassifierTest` — table-driven over every `SyncResult` field and every precedence
  pair, including that an all-zero result classifies as no failure.
- `KompaktSyncAttemptTest` — the component's whole surface, now that it has one: both pre-flight blocks;
  both services failing, one failing, auth-vs-generic via the flag; a gated service; `NoneEligible` vs
  `AlreadySyncing`; a second call joining the set mid-drain, so both callers get the same aggregate and
  the modal is raised once; a stale id whose `WorkInfo` is terminal or `null` not wedging the drain; a
  `CANCELLED` run resolving the drain
  resolving a wedged run; and the retry set — an attempt with one success and one failure retries **only**
  the failed one. Plus the offline watch, **which nothing covers today**: the network flow going false
  cancels every in-flight service after the grace and returns `Interrupted(NoNetwork)`, and does so even
  when a sibling service has already written a failure. `mockk<WorkManager>()` with replay `SharedFlow`s,
  fake `KompaktNetworkAvailability` and `KompaktStorageAvailability` seams — which is what makes the two
  `Blocked` cases assertable at all — and `UnconfinedTestDispatcher`, since a standard test dispatcher
  never runs `backgroundScope`.
- `KompaktStartSyncUseCaseTest` — the whole precondition sequence (Decision 9): both guards; a
  switched-off and a consent-revoked account each ruled out **before** the guards are consulted; an
  unconfigured service not mistaken for a switched-off one; a service with no discoverable row left
  alone; configuration ordered before the second switch read; `AlreadySyncing` distinct from
  `NoneEligible`.
- `KompaktSyncEligibilityTest` — `consented` and `switchedOn` reported independently, since consent can
  be revoked with the switch left on.
- `KompaktSyncOutcomeRepositoryTest` — the mapping and the per-service observation, with a mocked DAO and
  `DavServiceRepository`; `KompaktServiceLastSyncTest`'s shape. Includes the absent-service-row no-op.
- `KompaktServiceSyncStateTest` extended for `Failed(cause)` and the `Reported` pending value;
  `KompaktLinkedAccountStateTest` for `SyncFailed(retry)` and the new `SyncFailure` rank;
  `KompaktFlowCombineTest` for the 8-arity overload.

Instrumented, on the PR only: `AutoMigration20Test : DatabaseMigrationTest(20)`. No DAO test — REPLACE
resolving on the primary key is SQLite behaviour a mock cannot assert, and the JVM test covers the half
that is ours: that `record` writes the values it was handed.

No test is written for a cancellation producing `numUnclassifiedErrors`: that path is unreachable
(Decision 4).

## Verification

Compile: `./gradlew :core:compileDebugKotlin`, then `:core:testDebugUnitTest` and
`:core:lintDebug :app-ose:lintOseDebug`.

On a real Kompakt — this touches sync and cross-app paths, so a compile is not evidence. Steps 2 and 3
require at least one sync *after* upgrading (see *Absent row semantics*).

1. Force a server failure; the row shows ⚠ and the tap explains it — for **contacts** as well as calendar.
2. Kill the app after a failed sync, relaunch: the row still shows ⚠ (today it shows a stale ✓).
3. Let a **periodic** run fail with the app closed; the row shows ⚠ on next open — the case `WorkInfo`
   cannot express.
4. Tap the alert icon: it opens the sheet and does **not** clear the state.
5. Manual sync, both services failing: exactly **one** modal, after both finish; both cells show ⚠ as
   they finish.
6. Drop the network mid-sync with both services running: the "No internet" sheet, **both** cells stop
   spinning, and **no** generic failure in either row.
7. Tap Synchronize while a sync is running: no second run (`dumpsys jobscheduler`); the existing one
   continues.
8. Revoke the token on a **Contacts-only** account: the re-auth prompt appears, and its copy does not
   name calendar.
9. Unlink, re-link the same account: no stale failure survives.
10. Calendar succeeding, contacts failing: **Try again** enqueues only the **contacts** worker.
11. Dismiss the failure modal with **Cancel**: the row **keeps** its ⚠.
12. Interrupt a contacts sync and retry it (SHP-1149 AC 13): no duplicate contacts. Reported working;
    this step is regression cover, not new behaviour.

Added by Decision 9:

13. Switch both services off and go offline, then tap Synchronize: **nothing**, not the "No internet"
    sheet. (SHP-1157 AC 6 turns that into its own dialog.)
14. Send a cross-app `REQUEST_SYNC` while offline: nothing is enqueued, and no row spins. Confirmed on a
    Kompakt for the content-change path — with the device offline and a pending `upload=true` sync
    queued in the sync framework, no `OneTimeSyncWorker` existed in any non-terminal state and both rows
    showed their last sync.
15. Link a fresh account and tap "Sync now" from the modal before the screen's init collector has
    applied defaults: it syncs, rather than reporting nothing eligible. This is the case step 1's
    `defaults not applied` escape exists for, and the hardest of these to land.
16. Toggle a consented service that has no `Service` row (a re-auth grants every scope): the row is
    discovered and created, the periodic worker is armed, and one sync runs. Offline, the tap is a
    no-op with only a log — a known gap, see *Follow-ups*.

## Follow-ups, not in this change

- **Collapse the dialog flags in `KompaktLinkedAccountModel`.** `_showNoInternet`, `_syncFailed`,
  `_requestConsent` and `_confirmDisable` are all raised by an action and cleared by dismissal, so they
  can become one `MutableStateFlow<KompaktLinkedAccountDialog?>` — and the 8-arity `combine` this change
  adds would no longer be needed. `authError` and `newContactsConsent` must stay derived; and
  `_showOutOfStorage` does double duty as a polled condition *and* an acknowledgement, so it needs
  splitting rather than collapsing. The regression to design against: one field loses precedence
  *between* raised dialogs, so a sync failure could replace a confirmation the user is looking at —
  fixable with raise-time ranking.
- **The `version = 20` rebase collision** (Decision 2), to be resolved when a rebase is planned.
- **`RefreshCollectionsWorker` failures** — discovery has no `SyncResult` and its failures are invisible.
- **The three zero-work `Success` paths** (Decision 4), if the permission case ever becomes reachable
  without `adb`.
- **Toggling a rowless service on while offline fails silently.** `KompaktServiceProvisioning.ensureRow`
  needs discovery, so offline it throws, `setServiceSync` returns early with a log, and the switch
  re-renders off with no sheet — where tapping Synchronize in the same state now says "No internet".
  The toggle flow's own copy belongs to SHP-1157/1159, not here.
- **The row-before-interval ordering lives in the caller.** `KompaktServiceSwitch.setEnabled` writes the
  interval, and `AutomaticSyncManager.updateAutomaticSync` arms the periodic worker only for a service
  that already has a row — so `ensureRow` has to run first, and today `KompaktLinkedAccountModel` is
  what knows that. Moving the guard into `setEnabled` would put the constraint next to the write that
  depends on it; it needs `setEnabled` to be able to report failure.
