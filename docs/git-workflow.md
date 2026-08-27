# Git workflow

The Mudita GitHub Flow variant: no `develop` branch, `release/x.y.z` branches replace it, and every
merge into the production branch is a release.

**`kompakt` is this repository's production branch.** Because the repo is a fork of
[DAVx⁵ OSE](https://github.com/bitfireAT/davx5-ose), `main` is reserved for a pristine mirror of
upstream and carries no Kompakt code at all; everything a single-app repo would do on `main` happens
on `kompakt` here. Upstream tracking and the rebase procedure are in
[`upstream-maintenance.md`](upstream-maintenance.md).

## Branches

| Branch | Cut from | Merges to | Purpose |
|---|---|---|---|
| `main` | `upstream/main` | — | **Pristine upstream mirror.** Fast-forward only, never any Kompakt commit. |
| `kompakt` | an upstream release tag | — | Production state of the Kompakt app. Each merge = a release. |
| `release/x.y.z` | `kompakt` | `kompakt` (via tag) | Code for regression + RC builds; regression fixes merge directly here. |
| `feature/<name>` | `release/x.y.z` | its `release` | Groups the tasks for one feature (multiple allowed per release). |
| `tech/<name>` | `release/x.y.z` | its `release` | Technical work (lib bumps, refactor, CI/CD). Treated like a feature. |
| `task/<JIRA-ID>-short-desc` | its `feature` or `release` | back to where it was cut from | One JIRA task. |

`SHP-` is the current JIRA board; other Mudita prefixes (`MOK-`, `CM-`) are equally valid.
`task/NO-JIRA-<desc>` is the accepted form for non-ticket work.

`kompakt` is force-push territory — it is rebased onto each new upstream release tag — so it is the
one branch whose history is expected to change. Do not build long-lived branches directly on it.

## Standard flow

1. Cut `task/<JIRA-ID>-desc` from its `feature` (team feature work) or from `release` (independent
   change).
2. `task → feature` — **squash and merge**.
3. Feature complete → `feature → release` — **standard merge**.
4. RC builds come from `release`; regression bugs are fixed by tasks merged directly to `release`.
5. `release → kompakt` — **standard merge**.
6. Tag the resulting commit on `kompakt` `release.x.y.z` — that tag is the production build. Full
   procedure: [`ci-cd-release.md`](ci-cd-release.md).

Keep long-running branches current via `merge release → feature`.

`kompakt` is not a build trigger, so work only produces a QA APK once it sits on a `release/**`,
`feature/**` or `tech/**` branch — one more reason not to merge a task straight into `kompakt`.

## Hotfixes

A hotfix is just a new release: cut `release/x.y.z+1` from `kompakt`, fix via tasks merged into it,
run regression, merge to `kompakt`, tag `release.x.y.z+1`.

## Merge methods

- `task/*` → **squash and merge** (which is where the trailing `(#NN)` on most subjects comes from).
- Everything else (`feature/* → release/*`, `release/* → kompakt`) → **standard merge, no squash**.
- `main` only ever moves by `--ff-only` from `upstream/main`.

There is no version-suffix-per-feature-branch convention in this repo: `KompaktAppVersion.NAME`
carries only `x.y.z`, `x.y.z-SNAPSHOT` or `x.y.z-rcN`, so nothing has to be stripped on merge.

## Builds

Full CI reference — every workflow, trigger and Nexus path — is in
[`ci-cd-release.md`](ci-cd-release.md). The part that matters when branching:

Any **push** to `release/**`, `feature/**` or `tech/**` builds a QA APK to Nexus — a merge and a
direct push count the same. The `release.x.y.z` tag builds production. Two things are specific to this
repo:

| Event | What runs |
|---|---|
| **PR opened / updated**, any base | `test-core.yml` — compile `app-ose:assembleDebug`, then lint (`core:lintDebug`, `app-ose:lintOseDebug`), `core` unit tests and `core` instrumented tests. It has no branch or path filter, so it runs on every PR. `test-synctools.yml` adds its own tests **only** when the PR touches `synctools/**` or `gradle/libs.versions.toml`. Neither is a **required** check — a red run does not block the merge button. |
| `release → kompakt` | **nothing** — `kompakt` is not a build trigger, and neither is `task/**` |


## Versions (`KompaktAppVersion` in `build-logic/src/main/kotlin/davx5/buildlogic/KompaktAppVersion.kt`)

The Kompakt product version is deliberately **separate** from upstream's `AppVersion.kt` in the same
package, which tracks the DAVx⁵ OSE release `kompakt` is rebased on. Do not conflate them; do not
edit `AppVersion.kt`.

- `KompaktAppVersion.NAME` is the `versionName`. Development on a release: `x.y.z-SNAPSHOT`; release
  candidates `x.y.z-rcN`; production `x.y.z`.
- `KompaktAppVersion.CODE` is only a local fallback (`1`). CI takes `versionCode` from the
  `VERSION_CODE` env var, produced by `buildSrc/src/main/kotlin/scripts/bump_build_version.py`.
  Never hardcode a real version code.
- **Git tags exist for builds that ship** — `release.x.y.z` (also `qa.*` and `development.*` for
  ad-hoc builds). RCs are not tagged; an RC is a version bump on `release/*`, built from the branch
  push.
- A version bump is its own commit and its own PR, because `checkVersion` compares the tag to
  `versionName` and fails the release build on a mismatch.

`checkVersion` (registered in `app-ose/build.gradle.kts`) reads `GITHUB_REF` and matches
`(release|development|qa)\.(\d+\.\d+\.\d+(-\w*)?)`, then compares the captured version to
`versionName`. Three consequences:

- The prefix must be one of those three words.
- **Exactly three numeric segments** — a four-segment version like `1.2.0.1` (used in
  `KompaktOS-phone`) does not work here.
- The optional suffix is `-\w*`, so `-rc1` **and `-SNAPSHOT` both pass**. `checkVersion` will not
  stop you tagging a SNAPSHOT as a release; bump off SNAPSHOT yourself before tagging.

## Pull requests (mandatory for every merge)

**GitHub's default base for a new PR here is `main`** — the upstream mirror, which is never a valid
target. Retarget every PR to `kompakt` or a `release/*` branch; a PR left on the default base would
put Kompakt commits into the mirror.

- Minimum **2 approvals** — a team convention, *not* enforced. As of 2026-08-27 the only protection
  in the repo is a `main-protect` ruleset blocking deletion and force-push on `refs/heads/main`:
  nothing is enforced on `kompakt`, `release/*`, `feature/*` or `tech/*`, no reviews or status checks
  are required, and all three merge buttons are enabled everywhere — so every rule above is on you.
  Re-check with `gh api repos/mudita/davx5-ose-kompakt/rulesets` and
  `gh api repos/mudita/davx5-ose-kompakt`.
- Title / commit subject: `[<JIRA-ID>] Imperative summary` — e.g.
  `[SHP-1070] Select primary calendar before auto-sync on entry`; `[NO-JIRA]` for minor non-ticket
  changes. Avoid vague verbs (fix/update/changes).
- Multiple tickets go inside the brackets, comma-separated: `[SHP-1076, SHP-1087] …`.
- Upstream's own PR template (`.github/pull_request_template.md`) is written for bitfire's project,
  not this fork — it does not apply to Kompakt PRs.

## Branch hygiene

- Keep feature branches short-lived; merge often.
- **Delete `task/*` and `feature/*` yourself after merge** — "delete branch on merge" is *off* in
  this repo, so nothing cleans them up. `release/*` is kept through regression, then archived or
  deleted; `main` cannot be deleted (the `main-protect` ruleset blocks it).
- Local worktree branches used for exploration (`.claude/worktrees/*`) are not part of the flow and
  should never be pushed.
