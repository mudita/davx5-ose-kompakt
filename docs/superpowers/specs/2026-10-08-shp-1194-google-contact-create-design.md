# Keep a new Google contact's identity — PUT, then learn the name (SHP-1208, epic SHP-1194)

Base branch: `release/1.1.0`. Spikes on `tech/SHP-1194-google-post-create-spike` (not for merge):
`0de2e3a03` creates with POST (rejected below), `ca0986280` keeps PUT and follows it with a GET (this design).

**Decision (2026-10-09):** store Google's name **and the ETag** after the GET, so the first sync downloads
nothing and the contact keeps its detail rows. The device copy may differ from Google's normalised copy
until the next change made on Google — accepted, because upstream DAVx5 already behaves this way after every
phone edit (see *Open*). Not chosen: leaving the ETag empty to force one re-download (aligns with Google at
creation, like events, but rewrites the detail rows once and drifts again after the next phone edit anyway).
Bulk upload (SHP-1217) is reconsidered separately.

## The one-line summary

DAVx5 creates a new contact with `PUT <collection>/<uuid>.vcf`, but Google renames the card to its own
id and never says so. The next listing therefore inserts a new raw contact and hard-deletes the uploaded
one. **Keep the PUT, then `GET <uuid>.vcf`:** Google still serves the renamed card there, and the card's
`UID` is its new name. Store that name, and the next listing matches the existing row — the contact keeps
its raw id, contact id and local-only columns.

## Contract change

None. Nothing in [`docs/app-integration.md`](../../app-integration.md) changes. What other apps observe
gets better: contact ids, `STARRED` and `SEND_TO_VOICEMAIL` stay stable across the first upload (device);
`CUSTOM_RINGTONE` and pinning live on the same raw contact row and should too (not tested). The lookup key
still changes once, from `r<rawId>-…` to `i<googleId>`, when the name is stored; old lookup links should keep
resolving through the unchanged raw id (not tested), and plain contact ids are unaffected.

## Background

Google's rename is known and, reportedly, deliberate: Evert Pot's email to Google
([gist](https://gist.github.com/evert/b1cef035890701973fd9)) — *"When a new card gets uploaded on a arbitrary
url, you change the url to a new location … and the UID: property also gets filled with this value"* — and
his workaround is the one this design uses: PUT, GET the same URL, take the new name from the UID. Google's
own docs ([CardDAV](https://developers.google.com/people/carddav)) describe creating with POST instead.
DAVx5 does not officially support Google ([tested-with](https://www.davx5.com/tested-with/google)).

## What was established (device, 2026-10-08)

Setup: Kompakt, MuditaOS K 2.0.0-a21, DAVx5 1.1.0-SNAPSHOT, Google test account. Every claim marked
*device* was observed in DAVx5's logs or ContactsProvider on the device. Rows marked *spike* were measured
with the spike, which ran each GET straight after its PUT; the implementation runs the GETs after the whole
upload phase, so their timing and overlap figures do not carry over. The last row is the implementation.

| Fact | Source |
|---|---|
| `PUT <uuid>.vcf` with `If-None-Match: *` → `201`, `etag`, **no `Location`**. Next listing: `Creating local contact` for the renamed card, then `Removed 1 local resources`; raw id, contact id and lookup key change; `STARRED`/`SEND_TO_VOICEMAIL` reset to 0 | device |
| **`GET <uuid>.vcf` right after the PUT → `200`**, the card with `UID:<googleId>` and an `etag`. With `<googleId>` stored as the name and that ETag, the same-sync listing reports *has not been changed on server*, nothing is created or removed, `starred=1` survives | device, spike |
| A later local edit uploads as `PUT <googleId>` with `If-Match`; the listing matches the new ETag. A local delete sends `DELETE <googleId>` (11 of 11) | device, spike |
| 10 new contacts in one sync: 10 × PUT, 10 × GET `200` in **189–245 ms** each, 10 distinct names, no swap, whole sync ≈ 6 s | device, spike |
| A simple card is ~156–230 bytes of vCard; headers ~0.6 KB | device |
| **200 realistic contacts** (1–3 phones, emails, postal addresses, birthdays with and without year, organisation/title, multi-line notes with Polish characters and an emoji, nicknames, URLs), imported by the Contacts app and moved into the DAVx5 account in one go: 200 × GET `200` in 172–376 ms (median 226 ms), bodies 215–467 chars (61 KB total); **200 distinct UIDs, each equal to the name the listing reported** (*has not been changed on server* for all 200); no `Creating local contact`, `Removed 0 local`; raw ids 531–730 unchanged; no duplicates | device, spike |
| In that bulk run Google answered one of the parallel PUTs with **`502`**; upstream cancelled the whole upload phase (4 PUTs interrupted mid-flight) and **no retry ran for over 10 minutes**. One forced sync then uploaded the remaining 194 (PUT + GET) in 83 s. The 4 interrupted PUTs were retried to the same names and produced no duplicates. This stall is upstream behaviour, independent of this design, and matters for SHP-1217 | device, spike |
| Google normalises some fields; with this design the device keeps what it sent until the next download: year-less birthday `--MM-DD` on the device vs `1604-MM-DD` on Google (23 cards); website type *homepage* vs Google's `item1.URL` + `X-ABLabel:Other` (22 cards); Google marks a number `PREF` on every card, the device only where it was set (66 single-number cards differ). Names, notes (incl. emoji), organisation, title, emails, addresses and nicknames came back unchanged | device |
| The Contacts app's vCard import drops `PHOTO`, so a contact created on the device carries no photo — photos only arrive from Google; the GET never carried one in these tests | device |
| Lost PUT response (PUT reached Google, local row put back to *no name, dirty*): the retry `PUT <uuid>.vcf` (same name — DAVx5 persists the UID before the first PUT) succeeds without a `412` and **updates the same card**. No duplicate | device |
| POST to the collection → `201` + `Location: …/<googleId>`; no swap — but a lost POST response duplicates the contact (two cards, two rows) | device |
| Google rewrites `UID` to its id on POST and on PUT; ETag is not unique (8 distinct ETags across 10 contacts) | device |
| A group vCard (PUT) is renamed the same way | device |
| Collection URL: `https://apidata.googleusercontent.com/carddav/v1/principals/<email>/lists/default/` | device |
| Upstream `v4.5.20-ose`: ktor client; per-resource `uploadDirty(local, capabilities, forceAsNew)` is final, `uploadDirty(capabilities)` stays `open` | code |
| Kompakt never creates contact groups; group method is always `GROUP_VCARDS` | code |
| Implementation (build of this branch): a new starred contact → `Google renamed <uuid>.vcf to <id>; kept contact <n>`, *has not been changed on server*, `Removed 0`, raw id and `starred` kept; a contact put back to *clean, still `<uuid>.vcf`* (as an interrupted sync leaves it) → named by the next sync without an upload, no swap; an edit uploads to `<id>` with `If-Match`; 10 new contacts in one sync → 10 renamed and kept, nothing created or removed; a GET forced to fail → contact reset, sync failed before the listing, the worker retried 30 s later (soft error), re-PUT the same `<uuid>.vcf`, named it, no duplicate; deletes reach Google under the new names. Once a `429` from Google hit upstream's sync-state request after our GET had named the contact; the next sync listed it as *not changed* | device |

## Decisions

### 1. PUT + GET, not POST

| | Normal upload | Lost response | Code |
|---|---|---|---|
| Today (PUT) | swap | swap, no duplicate | — |
| POST + duplicate guard | no swap | no duplicate (with `X-KOMPAKT-UID` + pending flag) | POST call, pending flag, adoption in the listing |
| **PUT + GET** | **no swap** | **no duplicate** — upstream's retry-safe PUT is untouched | one GET after a successful create |

POST is Google's documented create but is not retry-safe, so it needs a guard that is most of the work.
PUT + GET keeps upstream's upload untouched and adds one request. It relies on two undocumented Google
behaviours (decision 4), and every failure of them degrades to today's swap, never to data loss.

### 2. Scope: Google contacts still named after their upload

Predicate on the collection, not the account or build: the host is `apidata.googleusercontent.com` (what
Kompakt discovers) or `www.googleapis.com`, and the path starts with `/carddav/`.
Cheap, per collection, never fires against MockWebServer tests on localhost, survives a future non-Google
login type.

A contact is **waiting for its name** while its server name (`FILENAME`) still ends in `.vcf` and it is not
deleted. Upstream names every new upload `<uuid>.vcf`, and Google's names never end in `.vcf`, so the name
itself is the state — no extra column (`SYNC1`–`SYNC4` are all taken: UID, ETag, the Android 7 hash, flags).
That covers new contacts and also contacts left waiting by an earlier sync that was cancelled, stopped by
WorkManager or killed between the PUT and the GET. Edits, deletes, groups and non-Google collections are
unchanged. `forceAsNew` re-creates after a `404`/`410` keep their Google name, so they are not waiting and fall
back to today's swap; they are rare (a card deleted on Google while edited locally).

### 3. One hook, around the upload phase

In `ContactsSyncManager.uploadDirty()` — already overridden upstream, and still `open` in 4.5.20 as
`uploadDirty(capabilities)` — `super.uploadDirty()` runs inside `KompaktGoogleContactName.keepNamesAround`.
After it, whether it succeeded or failed (a `502` on one PUT cancels the rest of the phase), every waiting
contact is resolved (decisions 4–5). After a cancellation nothing is done; the contacts stay waiting and the
next sync resolves them.

Upstream clears `DIRTY` inside `super.uploadDirty()` (`SyncManager.onSuccessfulUpload`, private and shared
with calendars and tasks), so by the time the hook runs every uploaded contact is already clean. The hook
stays outside that code on purpose; decision 5 restores a contact's dirty state when it has to.

### 4. Resolve the name from the card's UID — defensively

New `core/.../sync/KompaktGoogleContactName.kt`:

- `GET <collection>/<uploadedName>` through dav4jvm's `DavResource.get` (`Accept: text/vcard`), so
  redirects and status mapping match the rest of the sync.
- Parse the body with the existing vCard parser, not string matching; take `UID`.
- Accept the UID as the new name only if it is non-blank, a single path segment (no `/`), does not end in
  `.vcf`, and is not the uploaded name's base (`<uid>.vcf` equal to the uploaded name means Google did not
  rename the card — then the uploaded name is already right and nothing is written).
- On success write **only** `FILENAME` and `ETAG` (from the GET's `ETag` header) on the raw contact — never
  `DIRTY`, so an edit made between the PUT and the GET (up to the rest of the upload phase) still uploads.
- A failure is either **temporary** — no response or an I/O error, `5xx`, `429`, `401` — and handled by
  decision 5, or **permanent** — `404`/`410` or another `4xx`, a body that does not parse, a UID that is
  missing or unusable — and leaves the row as upstream left it.

Why a permanent failure or a wrong answer is safe: the next listing compares names. If the stored name is
not a card on Google, the listing behaves exactly as today (insert the real card, remove the row) — the swap,
nothing worse.

### 5. A temporary GET failure is retried like a failed PUT

A failed PUT leaves the contact dirty and fails the sync before the listing, so the next sync uploads it
again. A failed GET would otherwise come after upstream has already cleared `DIRTY`, and the listing in the
same sync would swap the contact. So, after all GETs of the phase:

1. every contact whose GET failed temporarily is put back to *never uploaded*: `FILENAME = null`,
   `ETAG = null`, `DIRTY = 1`. Its UID stays, so the next upload uses the same `<uuid>.vcf`;
2. the sync then fails before the listing: the first temporary GET error is rethrown — or, when the upload
   phase itself failed, its own error, as upstream would (an error while naming is attached to it as
   suppressed, never replaces it). Upstream classifies it exactly as a failed PUT: an I/O error or `503` is a
   soft error, which `BaseSyncWorker` retries with exponential back-off (up to 5 attempts; `503` waits for
   `Retry-After`); `500`, `502`, `429` are hard HTTP errors and `401` an auth error leading to
   re-authorisation, none retried by the worker — the contact then waits for the next periodic sync, a local
   change or a manual sync. Either way the attempt shows as a failed contacts sync, as a failed PUT does.

The next sync PUTs the contact again and repeats the GET. Re-uploading to the same `<uuid>.vcf` is safe on
Google: in the lost-response test (device) the second `PUT <uuid>.vcf` with `If-None-Match: *` succeeded and
updated the card the first one had created — no `412`, no duplicate.

Failing the sync is what makes the reset safe. If the listing ran with the contact dirty again, it would see
Google's renamed card as new and insert it next to the contact, which the listing never deletes while dirty.

The two Google behaviours this relies on, both observed on the device and in Evert Pot's report, neither
documented by Google: the uploaded URL keeps serving the renamed card, and its `UID` equals the new name.

### 6. Next rebase onto 4.5.20+

The hook moves to the still-open `uploadDirty(capabilities)`; `KompaktGoogleContactName` switches to
`ktor.DavResource.get`. Small; record it in [`docs/upstream-maintenance.md`](../../upstream-maintenance.md).

## Cost

One GET per **newly created** Google contact, once — not per card and not per sync. About 1 KB and
~0.2–0.25 s each on Wi-Fi (device). It replaces the download the swap triggers today (the renamed card is
currently fetched by the listing, batched 10 per request), so data volume is about the same; the request
count for the create phase roughly doubles. A bulk move of 500 contacts: 500 PUT + 500 GET instead of
500 PUT + ~50 batched downloads.

Measured against a PUT-only baseline with the **spike** (same 200 cards, same procedure, original build vs
spike, device):

| Phase | PUT-only (today) | PUT + GET (spike) |
|---|---|---|
| Upload phase, per contact | 0.38 s (65 in 25.0 s), 0.40 s (139 in 55.0 s) | 0.37–0.38 s (132 in 48.6 s), 0.40 s (194 in 77 s) |
| GETs | — | 159–507 ms each (median ≈ 200 ms), overlapping the parallel uploads |
| Listing after the upload, 200 new cards | **21.4 s** — 20 batched downloads, 200 inserts, 200 deletes (the swap) | **2.8 s** — every card *not changed*, nothing downloaded |

In the spike the GETs overlapped the uploads and added no measurable time. The implementation runs them in
parallel after the upload phase instead, so it adds roughly one GET round trip per batch of parallel
requests to the phase — not re-measured for 200 contacts. The listing saving (~19 s per 200 contacts)
holds either way, because the swap's downloads, inserts and deletes disappear. Both runs hit Google `502`
within the first 25–53 s of the burst (after 65 and 132 uploads) — the upstream stall in *Open* applies to
both equally.

## Tests

- **JVM (`core/src/test`, MockWebServer, `OAuthInterceptorTest` pattern)** for the GET: `200` with a UID →
  name + ETag; a card Google did not rename (`UID` = the uploaded name's base) → permanent; a UID that is the
  uploaded name, ends in `.vcf`, contains `/`, is blank or missing → permanent; `404`, `410`, other `4xx`,
  unparsable body → permanent; `500`, `502`, `503`, `429`, `401`, no connection → temporary; the predicate
  (Google CardDAV, Google CalDAV, localhost, other host).
- **JVM (Robolectric)** for the hook: waiting contacts are selected by a `.vcf` name and not deleted; names are
  stored after a failed upload phase too; nothing happens after a cancellation as upstream reports it (a
  wrapped error from a cancelled job); a temporary failure resets the contact (`FILENAME`, `ETAG` null,
  `DIRTY` 1) and fails the sync with that error, after the other contacts are named; a failed upload phase
  rethrows its own error, with a naming error attached as suppressed; a provider write error fails the sync;
  a permanent failure leaves the row alone and does not fail the sync.
- **Device:** a contact keeps its raw id, contact id and `starred` across the first sync, an edit and a burst
  of new contacts; a GET forced to fail is retried on the next sync without a duplicate; a contact left
  waiting (still `<uuid>.vcf`) is named by the next sync.

## Negative effects and how they are contained

| Risk | Containment |
|---|---|
| Google stops serving the uploaded URL, or stops putting the name in `UID` | decision 4: the row keeps the uploaded name and the next listing swaps — today's behaviour. Fallback plan: the POST design (spike `0de2e3a03`, with its duplicate guard) |
| Google stops renaming altogether | the GET finds the card under its uploaded name, nothing is written and the listing matches — no swap. But those contacts stay named `.vcf`, so every sync GETs each of them again; acceptable as a transition, revisit if it is ever observed |
| Sync cancelled, worker stopped or process killed between PUT and GET | the contact stays named `<uuid>.vcf` (waiting) and the next sync resolves it before its listing (decision 2) |
| Extra request per new contact | measured ~0.2 s / ~1 KB; replaces an existing download (see *Cost*) |
| Upstream edit → rebase conflicts | one hook in an already-overridden method; logic in a new `Kompakt*` file; decision 6 |
| Local edit between PUT and GET | on success only `FILENAME` and `ETAG` are written; on a reset the contact is dirty anyway, so the edit goes out with the re-upload |
| Lost GET response, Google down | decision 5: reset and retried on the next sync. While it keeps failing, downloads from Google wait — as they do while a PUT keeps failing; upstream has no cap for a PUT either |
| Google answers `412` to the re-upload of `<uuid>.vcf` one day | upstream ignores a `412` and keeps the contact dirty; the listing would then insert Google's card next to it, and the contact would stay dirty — a lasting duplicate. Not seen: the re-upload succeeded on the device |
| Contact deleted on the phone after a reset, before the next sync | a reset contact counts as never uploaded, so upstream purges it locally without a `DELETE`; the card stays on Google and the next listing brings it back (the resurrection in *Open*, now also reachable through a temporary GET failure) |
| Contacts already swapped before the fix | nothing to repair; local-only columns are already gone |

## Open

- **Device copy vs Google copy.** The fix keeps what the device sent instead of the swap's accidental
  re-download, so normalised fields differ until the next change on Google (see the device table: year-less
  birthdays, website label, `PREF`). Upstream already behaves this way after every *edit* PUT. Options: accept
  it (Contacts must render both `--MM-DD` and `1604-MM-DD` as year-less anyway — SHP-1197), or apply the GET's
  card to the row, which rewrites data rows and changes their ids (the editor trade-off). Decide with SHP-1197.
- **A deleted contact can come back (upstream).** Three PUTs cut off by the `502` reached Google, but the
  device never recorded them; the local rows were then deleted before a retry. DAVx5 treats a never-uploaded
  row as local-only and just purges it, so the cards stayed on Google and the next listing downloaded them
  as new contacts (device). Same with or without this design; a lost response followed by a user delete
  resurrects the contact.
- **Bulk upload stalls on one 5xx (upstream, SHP-1217).** One `502` among parallel PUTs aborted the upload
  phase and nothing retried for 10+ minutes. Not caused by this design, but a bulk move will hit it; SHP-1217
  needs a retry/back-off decision.
- **Groups.** Google renames group vCards too (device). Kompakt never creates groups, so they are out of
  scope; extend to `LocalGroup` only with a story that creates groups and a check of memberships.
- **Contacts editor.** An editor open across the *old* swap lost its edits silently (device). With this
  fix the raw contact survives, so the editor meets an ordinary concurrent update — not verified on device.
- **Unexplained vanish.** One contact inserted while a sync was running was deleted before upload. Not
  reproduced in 8 attempts; unrelated to this design, worth its own ticket if seen again.
