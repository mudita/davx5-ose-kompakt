# Upstream maintenance

This is a fork of [DAVx⁵ OSE](https://github.com/bitfireAT/davx5-ose) (GPLv3). The fork strategy is
**rebase, not merge**: all Kompakt work sits as a linear stack of commits on top of an upstream
*release tag*, so upgrading to a newer upstream release is a rebase of that stack.

## Remotes

| Remote | URL | Role |
|---|---|---|
| `origin` | `git@github.com:mudita/davx5-ose-kompakt.git` | our fork; the only remote we push to |
| `upstream` | `https://github.com/bitfireAT/davx5-ose.git` | read-only source of upstream commits and tags |

`upstream` is **not** part of a fresh clone — add it before any upgrade work:

```bash
git remote add upstream https://github.com/bitfireAT/davx5-ose.git
git fetch upstream --tags
```

## Keeping `main` in sync

Branch roles are in [`git-workflow.md`](git-workflow.md); the one that matters here is that **`main`
is a pristine mirror of `upstream/main`** and moves only by fast-forward:

  ```bash
  git fetch upstream --tags
  git checkout main && git merge --ff-only upstream/main
  git push origin main
  ```

## Current Kompakt base: `v4.5.15-ose`

⚠️ **Update this heading whenever you rebase onto a newer tag** — it is the `<OLD-tag>` argument for
the next upgrade, and it is the only record of the base.

It has to be recorded by hand because **you cannot derive it from git**: upstream tags its releases
on release branches that `upstream/main` does not contain, so `v4.5.15-ose`'s commits are not
ancestors of `main`. `git merge-base main kompakt` therefore points at a *pre-release* commit
(`4.5.15-beta.1`), not at the base tag, and `git log main..kompakt` includes upstream's own
`4.5.15-rc.1` / `4.5.15` version bumps alongside our work.

The heading is the record; `AppVersion.kt` on `kompakt` — upstream's version object — is the
cross-check. (Our own product version lives beside it in `KompaktAppVersion.kt` and is unrelated.)

## Upgrading to a newer upstream release

```bash
git fetch upstream --tags
git branch kompakt-backup kompakt              # safety net — kompakt is the only branch with our work
git rebase --onto <NEW-tag> v4.5.15-ose kompakt   # replace v4.5.15-ose with the current base above
```

Then, per conflict resolution round:

```bash
./gradlew :synctools:compileDebugKotlin :core:compileDebugKotlin :app-ose:compileOseDebugKotlin
```

and test on device before force-pushing:

```bash
git push --force-with-lease origin kompakt
```

Finally, update the *Current Kompakt base* heading above to `<NEW-tag>`.

**Check for open `release/*` branches first.** They are cut from `kompakt`, so a force-pushed
`kompakt` leaves them on an orphaned base: either wait for the release to land, or rebase every open
`release/*` onto the new `kompakt` and force-push those too.

Pick `<NEW-tag>` from upstream's release tags (`v4.5.16-ose`, …), not from `upstream/main`'s tip —
`main` runs ahead of the last release, and rebasing onto a moving branch defeats the point of a fixed
base. Note the base tag is not in a fresh clone either: `git fetch upstream --tags` above is what
brings it in.

## Known rebase hazards

- **`ContactsSyncManager.uploadDirty()`** wraps `super.uploadDirty()` in
  `KompaktGoogleContactName.keepNamesAround`, which stops Google's rename of new contacts from replacing them
  ([design](superpowers/specs/2026-10-08-shp-1194-google-contact-create-design.md)). From `v4.5.20-ose` the
  method is `uploadDirty(capabilities)` (still `open`) and the HTTP client is ktor: keep the wrapper around the
  new `super` call and port `lookUpServerName` to dav4jvm's ktor `DavResource.get`.
- **`ContactReader`, `ContactWriter`, `ImBuilder`, `ImHandler`, `PhoneHandler`, `EmailHandler`,
  `StructuredPostalHandler`, `WebsiteHandler`** (synctools) fix general vCard losses: an IM's
  `X-SERVICE-TYPE` is kept as its custom protocol and written back; an empty `X-ABLabel` ("no type") on a
  phone, email, address or website is kept as label `""` in both directions
  (`findAndRemoveLabel(…, keepEmpty = true)`, the handlers' `TYPE_CUSTOM, null ->` branch); a labelled
  address is written with Apple's `X-ABADR`, which the reader no longer retains; an `X-SIP` keeps its type
  and label. Each edit carries a `Kompakt:` comment and is covered by the module's tests;
  `EmailHandlerTest.testTypeCustom_NoLabel` was changed to expect `""`
  ([design](superpowers/specs/2026-10-14-shp-1198-preserve-google-fields-design.md)).
- **`ContactsSyncManager.processCard()` and `generateUpload()`** wrap `ContactReader.fromVCard` and
  `ContactWriter` in `KompaktGoogleVCard.read` / `write`, which apply Google-only vCard forms (yearless
  custom dates, SIP as `X-SIP`). Keep both wraps around whatever replaces those calls. `write` edits
  `ContactWriter.vCard` after construction, so it relies on `ContactWriter` building the vCard in its
  constructor and `writeVCard` not rebuilding it; if that changes, the Google forms silently stop being
  applied (`KompaktGoogleVCardTest` fails). Same design.
- **`PhoneBuilder`, `EmailBuilder`, `RawContactBuilder.insertDataRows`, `AndroidContact.update`** (synctools)
  fix how a downloaded `PREF` meets the device's default. Upstream writes `PREF` as `IS_PRIMARY` **and**
  `IS_SUPER_PRIMARY`; Google puts `PREF` on every card, so every download made Google's pick the person's
  default. The builders now write `IS_PRIMARY` only, on the most preferred value (lowest `PREF`), and never
  write the `IS_SUPER_PRIMARY` key — not even as 0, which makes ContactsProvider clear it. `update()` reads
  the raw contact's super-primary phone and email (with the provider's `Phone.NORMALIZED_NUMBER`) before
  deleting the rows, and `RawContactBuilder.insertDataRows` marks the re-inserted row that holds the same value
  (`findSameValue`: normalised number, or a national number Google now sends in international form, which
  equals the old row's E.164; case-insensitive email — never `PhoneNumberUtils.compare`, which matches on the
  last digits and could move the default to another number). A later choice on the server wins: when the card prefers
  another value while the old one is still on it and the raw contact is not dirty (`update()` reads `DIRTY`
  before the batch clears it), super-primary moves to the card's preferred value; when the raw contact is
  dirty or the old value is gone, it is dropped and the picker asks; nothing is uploaded back. A choice made on the device reaches the server through the normal dirty-contact upload, which
  runs before a sync's download. Each edit carries a `Kompakt:` comment; `RawContactBuilderTest`,
  `AndroidContactTest` (instrumented) and the builder tests cover it
  ([design](superpowers/specs/2026-10-15-shp-1203-default-number-design.md)). Worth proposing to
  bitfireAT/synctools: AOSP's own vCard importer maps `PREF` to `IS_PRIMARY` only.
- **`AndroidContact` is `@Deprecated("Replaced by AndroidAddressBook Entity CRUD")` upstream, but the switch is
  unfinished:** as of `v4.5.20-ose`, `AndroidAddressBook` gained raw-contact CRUD (`addRawContact(Entity)`,
  `updateRawContactRows`) that covers only the raw contact row, and `LocalContact.update(data)` still rewrites the
  data rows through `AndroidContact.update`. When a later release moves that rewrite, carry the super-primary
  preservation above into the new path, or every download drops the device's default again (the instrumented
  `AndroidContactTest` super-primary cases fail).

## Rules

- **Never `git merge upstream` into `kompakt`.** It would end the linear history that makes the next
  rebase tractable.
- **Never commit Kompakt code to `main`.**
- Keep the conflict surface small — new files prefixed `Kompakt` rather than edits to upstream files,
  and upstream's repo furniture left untouched. Both rules, with the file list, are in *Fork
  conventions* in [`../CLAUDE.md`](../CLAUDE.md).
