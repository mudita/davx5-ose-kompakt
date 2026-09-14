# One dialog slot for the linked-account screen

**Status: unparked.** This was written to wait for **SHP-1149** and **SHP-1153**; both are now merged
into `release/1.1.0` (`[SHP-1149] Use the re-auth copy that does not say "calendar" (#57)`,
`[SHP-1153] Update unlink (#49)`). It is now written against **`task/SHP-1157-sync-off-dialog`** —
the release branch plus the sync-off dialog — which is what this branch is cut from. See *Base and
sequencing*.

## The one-line summary

Nine dialog flags and two derived conditions become one ranked slot, low storage stops being polled
on every resume, and "the auth dialog cannot be hidden" stops being a coincidence of composition
order and becomes a rule the compiler enforces.

## Contract change — read this first

**None.** No intent action, broadcast, permission, authority or provider column changes, and no
behaviour that [`docs/app-integration.md`](../../app-integration.md) promises a caller.

That document is, however, why one of the invariants below exists. It tells the calendar app that
after a cancelled re-auth "`needs_reauth` stays `1` and the account screen keeps showing the
'Account link error' dialog, so the caller's own prompt reappears correctly on return." The auth
dialog being *visible* is therefore a cross-app promise, not a preference. This design does not
change it; it makes it enforced rather than incidental.

[`docs/kompakt-testing.md`](../../kompakt-testing.md) **is** edited, in the same change — it owns the
"out of storage" description and its test recipe, and Decision 2 invalidates both.

## Scope

In:

| | |
|---|---|
| Collapse the raise/dismiss dialog flags into one `MutableStateFlow<KompaktLinkedAccountDialog?>` | the follow-up named in the SHP-1149 design |
| Rank at raise time, so collapsing cannot cost precedence | the regression that follow-up warned about |
| Delete `KompaktFlowCombine` and its test | a helper and a test that exist only to feed the dialog fan-in |
| Stop polling low storage on screen entry and on every `ON_RESUME` | the reported defect |
| Stop `showAccountLinkedDialog` composing alongside the slot, so exactly one sheet is on screen | closes the last unranked channel, without moving it into the model |
| Make "`AuthError` outranks everything" a compile error and a test to violate | today it is unenforced |

Out, and why:

| | |
|---|---|
| Stacking or queueing dialogs | explicitly not wanted, and wrong for e-ink — see Decision 5 |
| Moving `justLinked` into the ViewModel | it must outlive a model that is keyed by account name; Decision 3 keeps it hoisted |
| Any change to dialog copy, icons, buttons or Frontitude keys | not a copy change |
| Any change to which conditions raise a dialog, apart from storage | Decision 2 is the one behaviour change |
| A "Blocked" cell state for parked periodic work | rejected in the SHP-1149 design (its Decision 1); Decision 2 notes what that costs |

## The state this replaces

`KompaktLinkedAccountModel` holds **eleven** inputs to one `dialog` flow:

| Input | Kind | Raised by | Cleared by |
|---|---|---|---|
| `needsReauth` | derived | the sync worker / re-auth flow, via `KompaktAccountSettings` | a successful re-auth only |
| `showNewContactsConsent` | derived | contacts `ConsentMissing` && not yet offered | `setNewContactsConsentShown` |
| `_showOutOfStorage` | **both** | screen entry, every `ON_RESUME`, **and** `BlockedNoStorage` | `consumeDialog` — then re-raised on the next resume |
| `_showNoInternet` | raised | `BlockedNoNetwork`, `Interrupted(NoNetwork)` | `consumeDialog` |
| `_showSyncOff` | raised | `NoneEligible` | `consumeDialog` |
| `_syncFailed` | raised | `Failed(retry)` | `consumeDialog` |
| `_explainSyncFailure` | raised | alert-icon tap | `consumeDialog` |
| `_importServiceNow` | raised | returning from add-consent with the service now consented | `consumeDialog` |
| `_requestConsent` | raised | toggling on a `ConsentMissing` service | `consumeDialog` |
| `_confirmDisable` | raised | toggling a service off | `consumeDialog` |
| `_confirmUnlink` | raised | the app-bar logout tap (SHP-1153) | `consumeDialog` |

Plus one dialog that is **not** in that flow: `showAccountLinkedDialog`, hoisted as `justLinked` in
`KompaktAccountsScreen`. SHP-1153 removed the other out-of-band one (`showUnlinkDialog`), so
`justLinked` is the only survivor.

### What is wrong with it

1. **The fan-in grows with every ticket.** `KompaktFlowCombine` exists only because kotlinx's
   `combine` stops at five flows. Every ticket that added a dialog widened it: SHP-1151 → 7,
   SHP-1153 → 8, SHP-1152 → 9, SHP-1149 → 10, SHP-1157 → 11. **Five widenings of one helper across
   five tickets**, each replacing the previous overload rather than adding to it, and
   `KompaktLinkedAccountModel` is still its only importer.

2. **Adding one dialog touches seven places** — the field, the combine (and possibly a new overload
   plus its test), `linkedAccountDialog`'s parameter list, its `when`, the second hand-written
   argument list in `initialState()`, `consumeDialog`, and the screen's `when`. `initialState()` is
   a silent second call site of the same function; nothing keeps the two in step.

3. **`consumeDialog()` clears nine flags to dismiss one sheet.** This is safe today only because
   raises happen to be mutually exclusive in practice.

4. **A raise that loses is kept, not dropped.** The flags are independent latches, so a flag raised
   while something outranks it lingers and surfaces later, out of context.

5. **`_showOutOfStorage` is a condition and an acknowledgement in one Boolean** — the general failure
   mode set out in Invariant 3, and the direct cause of the reported defect.

6. **Two synchronous `KompaktStorage.isStorageLow(context)` calls on the main thread per screen
   entry** — once in `_showOutOfStorage`'s initializer, once in `initialState()`. Each is a `StatFs`
   plus two `Settings.Global` reads. The model calls the object statically even though SHP-1149 added
   `KompaktStorageAvailability` as the injectable seam for exactly this.

7. **Nothing enforces one sheet at a time.** `showAccountLinkedDialog` is a plain `if` outside the
   precedence function, so it can compose alongside any member of the ranked set.

8. **A dialog's payload can end up outside the dialog, in two places.** `ImportServiceNow` is a
   `data object` whose service lives in `_importServiceNow` and is flattened to a Boolean on the way
   into the combine (`_importServiceNow.map { it != null }`); `importServiceNow()` reads the field
   back to learn what to sync. `ConfirmDisable` *does* carry its service in the dialog type, but
   `confirmDisable()` still reads `_confirmDisable.value` rather than the rendered value — the same
   shape. Both are only possible because the flag and the dialog are separate things; a slot that
   holds the dialog cannot drift from it.

## Decisions

### 1. One raised slot, and everything is raised

All nine flags — and the two conditions that are derived today — become one field, written only
through `raise()` and cleared only through `dismiss()`:

```kotlin
private val _raised = MutableStateFlow<KompaktLinkedAccountDialog?>(null)

private fun raise(dialog: KompaktLinkedAccountDialog) {
    _raised.update { held -> if (held == null || rank(dialog) <= rank(held)) dialog else held }
}

fun dismiss() = _raised.update { held -> if (held?.dismissible == false) held else null }
```

`dialog` stops being a `combine` at all — it **is** `_raised`. `linkedAccountDialog()` is deleted,
`initialState()` passes `dialog = null`, `state` drops to four inputs (kotlinx's own `combine`
covers that), and `KompaktFlowCombine` and its test go with them.

**`<=`, not `<`.** Five members carry a payload. Under a strict `<`, re-raising the same kind with a
newer payload is a no-op: a held `SyncFailed({CALENDAR})` would survive a newer
`SyncFailed({CONTACTS})` and **Try again** would retry the wrong services. Today each field is
assigned outright, so latest-wins at equal rank is what *preserves* current behaviour rather than
changing it.

`rank` is an exhaustive `when` over the sealed interface returning an `Int`, and it is the **only**
statement of order in the app:

```kotlin
internal fun rank(dialog: KompaktLinkedAccountDialog): Int = when (dialog) {
    AuthError -> 0
    SyncOff -> 1
    OutOfStorage -> 2
    NoInternet -> 3
    is SyncFailed -> 4
    is ExplainSyncFailure -> 5
    is ImportServiceNow -> 6
    is RequestConsent -> 7
    NewContactsConsent -> 8
    is ConfirmDisable -> 9
    ConfirmUnlink -> 10
}
```

Because the `when` is exhaustive over a sealed interface, **adding a dialog without ranking it is a
compile error.** That is the enforcement the current `when`-chain of Booleans cannot give: a new flag
appended to `linkedAccountDialog` silently lands last, and nothing says so.

The order is today's, unchanged. Every member is one the ViewModel can actually raise — the "Account
linked" sheet is deliberately **not** here, see Decision 3.

`ImportServiceNow` becomes `data class ImportServiceNow(val service: KompaktSyncService)`, closing
point 8: the slot holds the dialog, so the service travels with it and `importServiceNow()` stops
reading a separate field to find out what it is syncing.

**`dismissible` defaults to true and is overridden only where it is false:**

```kotlin
sealed interface KompaktLinkedAccountDialog {
    val dismissible: Boolean get() = true
    …
    data object AuthError : KompaktLinkedAccountDialog { override val dismissible = false }
}
```

It is deliberately *not* abstract. Making it so would force every member to answer, but ten
`override val dismissible = true` blocks to mark one exception is a poor trade, and it is the wrong
direction to be strict in: the default is the safe one, so a member that forgets is merely
dismissible, never a permanent mask. The dangerous direction — a new member made non-dismissible
without the clearer Invariant 4 obliges — is caught by the test that asserts `AuthError` is the only
`false`, and `rank`'s exhaustive `when` is what forces the new member into that test's list first.
`rank` stays abstract-equivalent because it has no safe default: every dialog needs a position, and a
wrong one is silent.

`AuthError` is the only `false` today. No call site can reach its dismissal anyway — its sheet passes
`onDismissRequest = {}` with `shouldDismissOnBackPress` and `shouldDismissOnClickOutside` both
`false` — so this is defence in depth rather than the mechanism. But it is what lets the auth dialog
live in the same field as everything else without becoming clearable by a tap on an unrelated sheet.

**The two derived conditions become raises, from collectors in `init` — but they re-assert.** This is
the one place where a single slot is not enough on its own, and getting it wrong silently loses a
dialog.

`SyncFailed`, `NoInternet`, `ConfirmDisable` and the rest are **events**: they happened once, and a
missed one is correctly dropped. `AuthError` and `NewContactsConsent` are **conditions**: they are
true *right now*, and while they hold and nothing outranks them, the dialog belongs on screen. A
purely edge-triggered raise conflates the two and drops a condition permanently.

The case that proves it — enter the screen needing re-auth **and** owing the Contacts consent offer.
`AuthError` (rank 0) wins, correctly, whichever collector fires first. `NewContactsConsent` (rank 9)
is dropped. The user re-authorizes; `needs_reauth` clears; the slot empties — and with edge-triggered
raises the consent offer never returns, because its condition never *changed*: it was true
throughout, and `setNewContactsConsentShown` was never written since the sheet was never shown. Today
the derived combine re-asserts it the moment `authError` goes false, so this would be a regression.

It is easy to miss because the common path hides it: a re-auth requests every scope, so Contacts
consent is usually granted by the re-auth itself and the condition goes false legitimately. The
regression bites only when the user re-authorizes and declines Contacts *again* — precisely the user
the offer exists for.

So the slot holds two things, not one: the raised event, and the **set of conditions currently
true**. What it shows is the lowest-ranked of the union. A condition's collector only reports
true/false into that set, so nothing needs re-raising — an outranked condition keeps its place and
surfaces the moment the union's winner changes. Both sources emit their current value when collection
starts (`emitInitial = true`), so a fresh ViewModel after process death asserts without a separate
seed.

Dismissal takes whatever is showing out of *both*, so a condition can be retired on the tap where
that is the right answer — see the Contacts offer below, where it is not.

The first frame therefore carries no dialog and the auth sheet lands a moment later. That is
deliberate and cheap here: `state.isLoading` already withholds the service cells and the bottom bar
until the switches resolve, so the frame it lands on is the header alone, not content that then gets
covered. It also removes one of the two blocking `AccountManager` reads at construction — the
survivor is `_reauthPhase`'s initializer, which still needs a synchronous answer for the
`ACTION_REAUTH` launch path.

**Why not keep the two derived, which is what the first draft of this design did?** Because the split
is what made *point 4* survive. With `authError` and `newContactsConsent` outside the slot, `raise()`
can only compare against the slot, so a raise that loses to one of *them* is kept rather than
dropped — and resurfaces when the condition clears. Concretely: tap the logout icon
(`_raised = ConfirmUnlink`), a background sync writes `needs_reauth`, the auth sheet correctly takes
over, the user re-authorizes — and the "Remove account?" sheet appears on a freshly re-authorized
account, with `accountRepository.delete` behind its confirm button. One field removes the category
that bug lives in, so Invariant 5 holds by construction instead of by a policy that has to be right.

**The Contacts offer keeps answering to its flag, and that is a decision, not an oversight.**
`newContactsConsentShown()` writes the flag on `ioDispatcher` inside a `try/catch` that swallows the
exception, and the sheet goes away when the write lands and the condition turns false. The slot could
retire it on the tap instead — `dismiss()` takes the winner out of the conditions too — but that
would let the screen say "answered" while storage still says "owed", and the offer would then return
on the next entry as though the dismissal had not worked. Tracking the flag keeps the two honest.

The cost is bounded and accepted: if that write throws, the offer stays up for the life of the
ViewModel, masking `ConfirmDisable` and `ConfirmUnlink` until the user leaves the screen. It takes an
`AccountManager` write failure to reach, and an app that cannot write account settings has a larger
problem than this sheet.

**A raise that loses is dropped, not deferred.** That is the no-stacking policy made structural, and
with everything in one field it removes point 4 rather than preserving it.

### 2. Low storage is an answer to a blocked action, never a poll

`refreshStorageState()`, the `LifecycleEventEffect(ON_RESUME)` that calls it, and the constructor
seed are all deleted. `OutOfStorage` is raised **only** from `KompaktAttemptResult.BlockedNoStorage`
— exactly how `NoInternet` already works, and nobody has reported that one.

**Why it is there today.** Commit `73dcdeef4` ("Storage full error handling", no JIRA) modelled
storage on the re-auth flag and said so in the KDoc: *"Like the re-auth flag this is a persistent
condition surfaced immediately on screen entry and re-checked on resume."* At the time `needsReauth`
genuinely needed a resume re-read — `reloadNeedsReauth()` sat in the same `ON_RESUME` block, because
there was no observed flow for it. Both halves of that analogy have since broken: `needsReauth`
became `observeReauthNeeded` and its resume re-read was deleted, and SHP-1149 moved storage into the
pre-flight so `BlockedNoStorage` answers the question when it is actually asked.
`refreshStorageState()` is the leftover twin of a call that no longer exists.

**Why it reads as a bug.** `consumeDialog()` sets the flag false, so a dismissal is recorded nowhere;
the next `ON_RESUME` sets it straight back from `isStorageLow()`. `ON_RESUME` fires on every return
from the consent and re-auth activities, so dismissing the sheet and then granting consent brings it
back, and a successful re-auth on a low-storage device lands the user on a storage sheet they never
asked for. On a display that ghosts, each of those is a full-area repaint.

**What this costs, stated plainly.** With `setRequiresStorageNotLow(true)`, periodic work parks while
storage is low; the row then shows a stale last-sync tick and the passive sheet is today the only
hint that anything is wrong. The SHP-1149 design already considered and rejected a "Blocked" cell
state for the identical case with the network (its Decision 1), and KompaktOS posts its own
low-storage notification from the same `DeviceStorageMonitorService` threshold — so the app is
repeating a warning the system already gave. **If product wants the hint kept**, the designed
variant is to raise once on the `false → true` edge of a low-storage episode and reset the
acknowledgement when storage recovers — never on a plain resume. That is a strictly smaller change
than today and satisfies the complaint either way; it is not the recommendation because nothing
asked for the passive warning in the first place.

Two consequences fall out: the main-thread `StatFs` reads of point 6 disappear, and the model stops
touching `KompaktStorage` statically, leaving `KompaktStorageAvailability` as the one seam — which is
what makes the dialog behaviour testable without a real `Context`.

`docs/kompakt-testing.md` is rewritten in the same commit, and in **four** places, not the two the
first draft scoped:

- the "the UI message is persistent/live … seeded on screen entry and re-checked on `ON_RESUME`"
  paragraph;
- both halves of the recipe — "open the linked-account screen → the message appears immediately"
  **and** "delete the file → on next resume the message clears";
- "Manual **Sync now** is pre-checked in `KompaktLinkedAccountModel.syncNow()`", which is *already*
  stale: SHP-1149 moved that pre-check into `KompaktStartSyncUseCase`;
- *Why no extra "storage watcher" of our own*, which says the one first-party check kept is the
  synchronous one "used for the live UI message and the manual-sync pre-check" — false in both halves
  afterwards.

Its *Sync error handling — what shows when* section is separately stale from SHP-1149 (it still names
`SyncConditions.internetAvailable()`, `AccountSettings.KEY_NEEDS_REAUTH` and `armed`). Not this
change's doing, and not fixed here — but it is the section a reader lands on first, so it is worth a
follow-up rather than a silent pass.

### 3. The slot outranks the screen's own sheet; `justLinked` stays where it is

`justLinked` cannot move into the ViewModel, and the reason is sharper than "the model is keyed by
`account.name`" — that alone would only argue for the un-keyed `AccountsViewModel` sitting in the
same composable. The real reasons are that it is set **before the account it belongs to exists**, in
the login launcher's result callback, and that `rememberSaveable` carries it through process death.
It stays in `KompaktAccountsScreen`.

Worth stating because a verification step depends on it: `justLinked` is also cleared on `ON_PAUSE`,
so it deliberately does *not* survive backgrounding. That is what makes "the sheet appears exactly
once" true.

It also stays a separate `if`, and it stays **out** of `KompaktLinkedAccountDialog`. The only thing
that changes is that it no longer composes alongside the slot:

```kotlin
when (val dialog = state.dialog) { … }                  // the ViewModel's slot, unchanged

// The screen's own sheet, and only when the ViewModel has nothing to say.
if (state.dialog == null && showAccountLinkedDialog) { … }
```

One rule — **the model's slot outranks the screen's own sheet** — and still exactly one sheet on
screen. `justLinked` behaves as a condition rather than an event: it latches until dismissed or
`ON_PAUSE`, so if the slot is occupied the sheet is deferred, not lost, and appears when the slot
frees.

**Why not fold it into `rank`**, which an earlier draft did. Ranking it would put a member into
`KompaktLinkedAccountDialog` that the ViewModel can never produce, so `state.dialog`'s type would
admit a value its only producer cannot emit — a standing puzzle for the next reader, and one this
design would be adding deliberately. The fold buys precedence *between* the two, which only matters
if they can be true at once, and in practice they cannot: `KompaktLoginFinalizeModel` calls
`suppressNewContactsConsent()` during linking, so a freshly linked account does not owe the Contacts
offer; a freshly linked account does not need re-auth; and nothing else raises without a user action
on a screen the sheet is covering. The one residual path is that suppression's own `catch`, which
accepts "worst case the prompt shows once for a freshly linked account" — and there both sheets still
appear, one after the other, whichever order is chosen. A type-level lie is a poor price for
ordering a pair that is nearly unreachable and harmless when reached.

The ordering rule still needs a test, because "the slot wins" is the part the auth requirement rests
on.

### 4. "Nothing hides auth-required" becomes a rule, not a coincidence

Today the guarantee rests on three things that hold, but not by design: `AuthError` being the first
branch of `linkedAccountDialog`; the modal sheet covering the app bar, so the out-of-band sheets
cannot be *opened* over it; and `KompaktAccountsScreen`'s `switchedFromAccount` loading state — added
for an unrelated flash (SHP-555) — unmounting the screen during the one window in which `justLinked`
could flip while an auth error is up.

Two caveats on that list, because the first draft overstated it. The precedence *is* written down, in
`KompaktSyncFailureSheet`'s KDoc ("the non-cancelable 'Account link error' sheet is already up and
**outranks every other dialog**"), in `consumeDialog()`'s comment and in `startSync()`'s `AuthFailed`
comment — so it is documented, just untested. And the scrim claim **cannot be verified from this
repo**: `ModalBottomSheetMMD` is a binary MMD artifact with no source here, and `KompaktModalSheet`'s
own KDoc says "transparent scrim". Whether it intercepts touches is unknown, which is exactly why the
guarantee should not rest on it.

After Decisions 1 and 3 it is structural instead: every dialog lives in one field, reaches the screen
through one slot ranked by `rank`, and `AuthError` is rank 0 with `dismissible = false`. Adding a
member without ranking it does not compile, and `rank` is what forces it into the tables below. Two
tests hold the rest:

- an exhaustive table asserting `AuthError` wins against every other member. Enumerate the members
  with an exhaustive `when` **inside the test**, not `KClass.sealedSubclasses` — that needs
  `kotlin-reflect`, which is declared nowhere in `libs.versions.toml` or `core/build.gradle.kts` and
  reaches the test classpath only transitively through MockK. `CLAUDE.md` forbids adding a dependency
  without asking, and the `when` is compiler-enforced rather than reflective, which is stronger
  anyway;
- `dismiss()` leaves a held `AuthError` in place.

**Both screen-level suppressions are named in Invariant 7 and both get a test.** One correction to
the first draft: `ReauthPhase`'s blanking is *not* what
[`docs/app-integration.md`](../../app-integration.md)'s "at most once per request" note describes —
that note is about the launch guard (`savedInstanceState == null && intent.action == ACTION_REAUTH`,
plus `onReauthLaunchStarted()`'s `PENDING_LAUNCH → AWAITING_RESULT`). The blanking is real and
deliberate but has no written home, so this document is it.

### 5. Alternatives rejected

| Alternative | Why not |
|---|---|
| A queue or stack of pending dialogs | Not wanted, and wrong for this device: a queue is N sequential full-area repaints on a screen that ghosts. It also preserves the out-of-context resurrection that Decision 1 removes. |
| **Keep `authError` and `newContactsConsent` derived, outside the slot** | This was the first draft, and it is the reason to record. It looks safer — "cannot be dismissed" is structural absence rather than a `dismissible` flag — but it keeps a category of dialog that `raise()` cannot compare against, so a raise that loses to a *derived* condition is kept and resurfaces when that condition clears. That is the ConfirmUnlink-after-re-auth bug in Decision 1. Putting dismissibility on the type recovers the safety it was protecting, and one field removes the category the bug needs. |
| Fix storage only, leave the flags | Cheapest, but leaves the fan-in widening once per ticket, leaves `consumeDialog` clearing nine fields, and leaves the auth guarantee unenforced — which is the part with a cross-app contract behind it. |
| Rank `AccountLinked` alongside the model's dialogs | Buys precedence between two dialogs that are nearly never both true, and pays for it by putting a member in `KompaktLinkedAccountDialog` that the ViewModel cannot produce. Decision 3. |
| **Push `justLinked` into the ViewModel from composition** (a `LaunchedEffect` into a setter, so the model can rank it) | Possible, but it *requires* the rejected row above rather than avoiding it — to be ranked it must be a `KompaktLinkedAccountDialog`. It also gives the model a flag whose rules it cannot reproduce: set before the account exists (the login callback fires while `KompaktAccountsScreen` still renders its blank box, so there is no model yet), persisted by `rememberSaveable`, and cleared on `ON_PAUSE` — which a ViewModel never observes, and which is what makes the sheet appear exactly once. |

## Components

| File | Change |
|---|---|
| `ui/account/KompaktLinkedAccountState.kt` | `ImportServiceNow` gains its service; `rank()` and `dismissible` added; `linkedAccountDialog()` **deleted**. No new member — the "Account linked" sheet stays out of this type |
| `ui/account/KompaktLinkedAccountModel.kt` | eleven inputs → `_raised` + `raise()`/`dismiss()`; two condition collectors in `init`; storage poll and `refreshStorageState()` deleted; `KompaktStorage` import gone; `dialog` is `_raised`, no combine |
| `ui/account/KompaktLinkedAccountScreen.kt` | `ON_RESUME` effect deleted; `showAccountLinkedDialog`'s `if` gains a `state.dialog == null` guard; `ImportServiceNow` becomes an `is` branch; two action lambdas take a service |
| `util/KompaktFlowCombine.kt` + its test | deleted |
| `docs/kompakt-testing.md` | four passages rewritten for Decision 2 |

`consumeDialog()` is renamed `dismiss()` — it now clears one field, and the old name describes the
shotgun it stops being. `KompaktLinkedAccountActions.onConsumeDialog` follows.

Three mechanical consequences the first draft missed, each of which would otherwise surface as a
compile error or a silent mis-port:

- **`confirmDisable()` has the same flaw as `importServiceNow()`.** It reads `_confirmDisable.value`
  to learn which service to switch off. Point 8 named only `ImportServiceNow`; there are two. Both
  are fixed the same way — the payload comes from the screen, as `onRetry(dialog.retry)` already
  does — so `onConfirmDisable` and `onImportServiceNow` change from `() -> Unit` to
  `(KompaktSyncService) -> Unit`. That also keeps the model from reading its own slot, which is the
  pattern the *Follow-ups* entry complains about for `explainSyncFailure()`.
- **The `RequestConsent` / `ConfirmDisable` smart casts survive, because of Decision 3.** Both read
  `state.dialog.service` — the property, not the `when` subject. An earlier draft folded the "Account
  linked" sheet into the subject, which would have broken both; keeping the subject as `state.dialog`
  leaves them alone. Worth knowing if anyone reintroduces the fold.
- **The existing `linkedAccountDialog` assertions are positional.** `KompaktLinkedAccountStateTest`
  calls it as `linkedAccountDialog(false, false, false, null, null, true, null)`. Deleting the
  function forces every one of them to be rewritten against `rank` / `raise`, and a mis-port of a
  positional call is silent. Port them deliberately rather than mechanically.

## Behaviour

| Situation | Today | After |
|---|---|---|
| Open the screen with storage low | "Your storage is full" sheet, unprompted | nothing |
| Return to the screen (consent, re-auth, task switch) with storage low | the sheet again, every time | nothing |
| Tap **Synchronize** with storage low | the sheet | the sheet — unchanged |
| **Toggle a service on** with storage low | the sheet | the sheet — unchanged, and easy to forget: `startSync` has four callers, so Synchronize is not the only trigger |
| **Anything ranked below `OutOfStorage` on a low-storage device** | masked — the sheet is re-seeded on every resume, so `SyncFailed`, `NoInternet`, consent, `ConfirmDisable` and `ConfirmUnlink` are all unreachable until it is dismissed | reachable. A large improvement Decision 2 was not claiming credit for, and the one QA needs to look for |
| Dismiss a sheet | nine flags cleared | the slot cleared |
| An **event** raised while a higher one is showing | kept; surfaces later out of context | dropped |
| A **condition** that holds while a higher one is showing | re-appears when the higher one clears | unchanged — conditions re-assert (Invariant 3) |
| **A pending confirmation when a sync verdict lands** | the confirmation survives and reappears after the verdict is dismissed | the verdict outranks it and the confirmation is gone; the user's tap did nothing and must be repeated |
| **"Account linked" together with another sheet** | both compose at once | one sheet; the model's dialog shows first, and "Account linked" follows when the slot empties. Nearly unreachable — a fresh account owes no consent offer and needs no re-auth |
| Auth error arrives while another sheet is up | the ranked set swaps correctly; the out-of-band sheet does not | one slot, always the auth sheet |
| Everything else | | unchanged |

Decision 2 is the only *intended* behaviour change, but it is not the only visible one — the four
bold rows above fall out of Decisions 1 and 3, and the first draft wrongly claimed there were none.
Three are improvements; the pending-confirmation row is a real, if small, regression, and it is the
price of "dropped, not deferred". It is accepted because the alternative is the resurrection bug, and
because a destroyed confirmation costs one tap while a resurrected one can delete an account.

## Invariants

1. **`rank` is the only statement of dialog order**, exhaustive over `KompaktLinkedAccountDialog`,
   and every member is one the ViewModel can raise. Its call sites are `raise()` and the condition
   collectors. The screen adds exactly one ordering rule of its own — the slot outranks the "Account
   linked" sheet — and that is the only ordering written outside `rank`.
2. **`AuthError` is rank 0, and `dismissible = false` keeps any dismissal from clearing it.** Note
   what this does *not* claim: `needs_reauth` has three clearing writers — `KompaktReauthModel` on a
   successful re-auth, `KompaktAddConsentModel`, and `BaseSyncWorker` on **any** clean sync
   (`!isStopped && !syncResult.hasError()`). The invariant is that no *dismissal path* clears it, not
   that only re-auth does. `docs/app-integration.md` promises a caller the sheet stays up while the
   flag is set.
3. **A condition re-asserts; an event does not.** A condition (`AuthError`, `NewContactsConsent`) is
   raised whenever it holds and nothing of lower rank occupies the slot. An event is raised once and,
   if outranked, dropped. Mixing them up is how a dialog disappears for the life of a ViewModel —
   see Decision 1.
4. **A non-dismissible dialog must have a clearer that is not the user.** `AuthError` has the falling
   edge of `observeReauthNeeded`. Without one, `dismissible = false` is a permanent mask.
5. **Exactly one sheet is composed at a time.** The `when` renders one value from the slot, and the
   screen's own sheet is guarded on the slot being empty. Today both compose at once, which is what
   makes this an invariant rather than an observation.
6. **A losing event is dropped, never queued**, and because every dialog now lives in one field there
   is no category of raise that can be deferred behind something outside it.
7. **Two paths suppress a dialog without ranking it, and both are deliberate.** `ReauthPhase`
   blanks the whole screen while the OAuth activity launches, released by the launcher result; and
   `KompaktAccountsScreen`'s loading branch (`accounts == null || (justLinked && account == null) ||
   (switchedFromAccount != null && account?.name == switchedFromAccount)`) unmounts the screen and
   every sheet with it, released by the accounts flow catching up or by `ON_PAUSE`. Both need a test;
   neither has one today.

## Tests

JVM, in `core` (there are no unit tests in `app-ose`).

**The slot has to be extractable, or none of this is testable.** `raise()`, `dismiss()` and the
condition collectors are the only genuinely new mechanism here, and putting them on the ViewModel
puts them out of reach: there is no `KompaktLinkedAccountModelTest` today, and constructing that
model means sixteen dependencies plus an `init` block that launches per-service collectors on
`viewModelScope` and calls `RefreshCollectionsWorker.existsFlow(context, …)`. The only precedent,
`KompaktAddConsentModelTest`, is Robolectric with six mocks against a model that does no `init` work.

So the slot is an `internal` holder next to `rank` — the raise policy, the dismissal guard and the
re-assertion rule as plain functions over `(held, incoming)` — and the ViewModel owns an instance of
it. That is what makes the list below pure-function tests rather than a new Robolectric harness, and
it is also what makes Invariant 1's call-site claim honest.

- `KompaktLinkedAccountStateTest` — the existing precedence cases ported to `rank`. They are
  **positional** calls into `linkedAccountDialog` today, so port them one at a time.
- The `AuthError`-wins table, exhaustive over the sealed interface via an in-test `when` (not
  `sealedSubclasses` — see Decision 4).
- Raise policy: a higher-ranked raise replaces a held lower one; a lower-ranked raise over a held
  higher one is dropped; an **equal**-ranked raise replaces, so a newer `SyncFailed` payload wins.
- Dismissal: `dismiss()` clears the slot; a held `AuthError` survives it.
- Conditions re-assert: with the consent condition true throughout, raising and then clearing
  `AuthError` leaves `NewContactsConsent` showing. **This is the test for the case Decision 1
  describes**, and the one a purely edge-triggered implementation fails.
- The screen's one ordering rule: with `showAccountLinkedDialog` true, a non-null `state.dialog`
  renders and the "Account linked" sheet does not; with `state.dialog` null it does. This is a
  composable-level rule, so it is the one item here that needs a Compose UI test or a careful reading
  rather than a pure function — say which, rather than leaving it implied.
- `ImportServiceNow` and `ConfirmDisable` carry their service, so the confirm action acts on the
  service the sheet named rather than on a separate field.
- `KompaktFlowCombineTest` deleted with its subject.

**What these do not cover**, and should be said rather than discovered: they exercise the slot, not
the wiring. That the model calls `raise()` on each `KompaktAttemptResult`, that the collectors are
actually started in `init`, and that the screen honours the slot-wins guard are all unverified by any
JVM test here, and are what the device steps below are for.

No instrumented test. Nothing here touches the database, a provider or a worker.

## Verification

`./gradlew :core:compileDebugKotlin`, then `:core:testDebugUnitTest` and
`:core:lintDebug :app-ose:lintOseDebug`.

On a real Kompakt — this changes what a user sees, so a compile is not evidence. Filling storage
below the threshold is the recipe in [`docs/kompakt-testing.md`](../../kompakt-testing.md).

1. Storage low, open the linked-account screen: **no** sheet.
2. Storage low, leave and return (task switch, and via the consent flow): still no sheet.
3. Storage low, tap **Synchronize**: the "Your storage is full" sheet, and no sync starts.
4. Dismiss it, tap **Synchronize** again: the sheet again — a dismissal is not a permanent mute.
5. Free space, tap **Synchronize**: it syncs.
6. Revoke the token, then cancel the re-auth: the "Account link error" sheet is showing on return,
   and the logout icon behind it cannot open a second sheet — the `app-integration.md` promise.
7. Trigger `ACTION_REAUTH` from the calendar app and back out of the OAuth screen: the auth sheet is
   what the user lands on.
8. Tap the logout icon, then let a background sync write `needs_reauth`: the auth sheet replaces the
   unlink confirmation rather than appearing behind it.
9. Link a fresh account: the "Account linked" sheet appears exactly once, and **Sync now** on it
   still starts a sync.
10. Grant a service's consent and come back: the "import now" sheet still appears — it is raised by
    the return, not by the resume hook Decision 2 deletes — and confirming it syncs **that** service.
    Worth checking with storage low as well, which is the one state where the two used to collide.
11. **The condition re-assertion case.** On an account that is owed the Contacts consent offer,
    revoke the token so it also needs re-auth, then open the screen: the auth sheet shows and the
    consent offer does not. Re-authorize, and at the consent step **decline Contacts again**. The
    consent offer must appear as soon as the auth sheet clears. An edge-triggered implementation
    passes every other step here and fails this one, and it fails silently.
12. Tap the logout icon while a sync is still running, then let the sync fail: the failure sheet
    replaces the unlink confirmation and the confirmation does **not** come back. That is the
    accepted regression in *Behaviour*; confirm it costs one tap and nothing else.

## Base and sequencing

The two blockers are gone. **SHP-1149** and **SHP-1153** are both merged into `release/1.1.0`, and
that merge resolved the `KompaktFlowCombine` question by itself: the file is now a **single
eleven-argument overload** rather than the tenth this document originally predicted, because
`ImportServiceNow` arrived alongside. Nothing is left to wait for.

This branch is cut from **`task/SHP-1157-sync-off-dialog`**, not from `release/1.1.0`, because
SHP-1157 adds `_showSyncOff` — the eleventh input and the `SyncOff` rank — and building on the
release branch would mean writing the collapse twice. The consequence is ordinary: **SHP-1157 has to
merge first**, and if it is reworked this branch rebases onto it again.

Three commits, titled `[NO-JIRA] …` per `docs/git-workflow.md`, so the behaviour change stays
isolated and reviewable on its own:

1. Collapse every dialog into the ranked slot, with the condition collectors, and give
   `ImportServiceNow` and `ConfirmDisable` their service — no *intended* behaviour change, though see
   the bold rows in *Behaviour*.
2. The storage rule, with its `docs/kompakt-testing.md` edits — the intended behaviour change.
3. The `justLinked` fold and the invariant tests.

One departure to acknowledge rather than paper over. The SHP-1149 design deferred this work with the
reason *"a structural refactor that should not ride along with a behaviour change"*. This design
bundles both onto one branch, isolated as commit 2 of 3. That is a defensible reading of the same
rule — the commits are separable and separately revertable — but it is a departure from the stated
reason, not an endorsement by it.

## Follow-ups, not in this change

- **`explainSyncFailure()` reads the model's own `state.value`** to find the stored cause. It works,
  but it makes the model a consumer of its own derived state; the cause could come from
  `outcomeSource` directly.
- **`KompaktMessageSheet`'s dismiss label on the storage sheet is "Cancel"**, which reads oddly for a
  sheet with nothing to cancel. Copy belongs to Frontitude, so it needs a key, not an edit.
