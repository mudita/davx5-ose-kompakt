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

## Rules

- **Never `git merge upstream` into `kompakt`.** It would end the linear history that makes the next
  rebase tractable.
- **Never commit Kompakt code to `main`.**
- Keep the conflict surface small — new files prefixed `Kompakt` rather than edits to upstream files,
  and upstream's repo furniture left untouched. Both rules, with the file list, are in *Fork
  conventions* in [`../CLAUDE.md`](../CLAUDE.md).
