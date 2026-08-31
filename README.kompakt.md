# DAVx⁵ for Mudita Kompakt

This repository is Mudita's fork of [DAVx⁵ OSE](https://github.com/bitfireAT/davx5-ose) — the
CalDAV/CardDAV/WebDAV sync app for Android — adapted for the **Mudita Kompakt**, a de-googled
Android 12 (AOSP) e-ink device with no Google Mobile Services.

Upstream's own `README.md`, `CONTRIBUTING.md`, `SECURITY.md` and `SUPPORT.md` are left in place
unmodified and describe the upstream project, not this fork. **Start here instead.** The project
remains GPLv3.

**All Kompakt work lives on the `kompakt` branch** — `main` is a pristine mirror of upstream and
contains none of it. GitHub's default branch is `main`, so check where you are before you start.

## What the Kompakt build does

It ships under its own applicationId, separate from upstream's, and replaces DAVx⁵'s multi-account UI
with a single-account flow sized for e-ink:

- Link one Google account through an embedded WebView OAuth flow (the device has no Chrome or
  Custom Tabs), sync its calendars into `CalendarContract`, and re-authorize in place when the token
  expires.
- Expose that state to the other Kompakt apps — the calendar app can launch onboarding or re-auth,
  request a sync, read the auth state, and trigger logout. The contracts are in
  [`docs/app-integration.md`](docs/app-integration.md).

Upstream's own onboarding, account list and intro pages are still compiled in but are not the entry
point.

## Quick start

```bash
./gradlew :app-ose:assembleOseDebug
adb install -r -d app-ose/build/outputs/apk/ose/debug/davx-*-debug.apk
```

No credentials are needed to build. The toolchain and SDK levels are in
[`CLAUDE.md`](CLAUDE.md#device-and-build-envelope); what `-d` is for, when that glob stops matching, and
the signing and OAuth setup are in [`docs/local-config.md`](docs/local-config.md).

## Layout

Four modules — `core` (all business logic, database, sync and UI, including every Kompakt screen),
`app-ose` (a thin application shell), `synctools` (iCalendar/vCard serialization) and `frontitude`
(localized strings). Dependency edges and the build-only modules are in [`CLAUDE.md`](CLAUDE.md).

## Docs

| Doc | What it covers |
|---|---|
| [`docs/git-workflow.md`](docs/git-workflow.md) | Branches, merges, releases, versions, PR rules. `kompakt` — not `main` — is the production branch. |
| [`docs/upstream-maintenance.md`](docs/upstream-maintenance.md) | Remotes, the current upstream base tag, and the rebase-onto-a-newer-release procedure. |
| [`docs/ci-cd-release.md`](docs/ci-cd-release.md) | Workflows, tags, Nexus upload, `versionCode`, cutting a release. |
| [`docs/local-config.md`](docs/local-config.md) | Signing, OAuth clients, installing on a device, Frontitude strings. |
| [`docs/app-integration.md`](docs/app-integration.md) | The runtime contracts other Kompakt apps depend on. |
| [`docs/figma-workflow.md`](docs/figma-workflow.md) | Reading a Figma node, the post-change audit checklist, Frontitude keys. |
| [`docs/kompakt-testing.md`](docs/kompakt-testing.md) | Recipes for exercising the account and sync flows on a device. |
| [`CLAUDE.md`](CLAUDE.md) | The agent contract: the device envelope, conventions, traps, and what not to do. |
