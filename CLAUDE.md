# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

**All Kompakt work lives on the `kompakt` branch. `main` is a pristine mirror of upstream and
contains none of this** — no Kompakt code, no `CLAUDE.md`, no `docs/`. If you are on `main`, you are
on the wrong branch; see [`docs/git-workflow.md`](docs/git-workflow.md).

## Overview

**davx5-ose-kompakt** is Mudita's fork of [DAVx⁵ OSE](https://github.com/bitfireAT/davx5-ose)
(CalDAV/CardDAV/WebDAV sync for Android), adapted for the Mudita Kompakt. Upstream is **GPLv3**, and
so is this fork. Kotlin, Jetpack Compose, Hilt, Room, WorkManager; tests are JUnit 4 + MockK +
Robolectric, plus instrumented tests on Gradle-managed devices.

The Kompakt build ships under **its own applicationId and its own account type**, separate from
upstream's, and replaces the multi-account UI with a single-account, e-ink-sized flow: link one
Google account through an embedded WebView OAuth flow, sync its calendars, re-authorize in place, and
expose that state to the other Kompakt apps
([`docs/app-integration.md`](docs/app-integration.md)).

## Device and build envelope

This app runs on **one specific device**, and every code and design decision has to hold inside that
envelope. Global Mudita rules live in the workspace `CLAUDE.md` one directory up and are not repeated
here — but every workspace rule marked **(overrides workspace)** below is replaced by what this
file says instead.

- **Custom OS** — KompaktOS, a de-googled **Android 12 (API 31)** AOSP build. **(overrides
  workspace)** the build keeps upstream's **`minSdk 24`**, not the workspace's 31+, so an API guard is
  needed far lower than on sibling apps; `targetSdk 36` and `compileSdk 37`
  (`CommonBuildConfigPlugin`). **(overrides workspace)** build with **JDK 21**, not JVM 17
  (`gradle/gradle-daemon-jvm.properties` pins `toolchainVersion=21`).
- **E-ink display** — the screen refreshes slowly and ghosts on fast or large-area redraws. Nothing
  may repaint continuously or animate to convey state; where progress has to be shown, it is MMD's
  own `CircularProgressIndicatorMMD`, which is drawn for this screen. Prefer a static layout that
  changes once over one that updates as data streams in.
- **No animations** — no transitions, enter/exit animations, shared-element transitions or ripples.
  Not one Kompakt composable imports an `androidx.compose.animation` API today, and nothing enforces
  that: the app theme does not null out window animations, so it holds only as long as it is written
  that way. Upstream's screens do animate — that is upstream's code on an unreachable path, not a
  precedent.
- **Monochrome, no dark mode** — `KompaktTheme` wraps MMD's `ThemeMMD` and never reads
  `isSystemInDarkTheme`. Upstream's `AppTheme` does follow the system setting and carries a dark
  colour scheme, but every screen that uses it is unreachable (*Reachability on Kompakt*). Don't add a
  night resource bucket or a second colour scheme for a Kompakt screen.
- **No Google Play Services** **(overrides workspace, partly)** — the device has no GMS stack, and no
  `play-services` or Firebase artifact appears anywhere in the build. A GMS call would compile and
  then fail at runtime. The app *does* talk to Google's **CalDAV and OAuth endpoints** over HTTPS
  (`dav4jvm`, AppAuth) — that is plain networking, not a Play Services dependency, and the distinction
  matters when reading the OAuth code. There is also no Chrome or Custom Tabs on the device, which is
  why authorization runs in `KompaktOAuthWebViewActivity`'s embedded WebView.
- **MMD is the design system** **(overrides workspace)** — `com.mudita:MMD`, *not* the workspace's
  `kompakt-ui`. Rules and the typography mapping are in *Design system & typography*.

Those SDK levels plus `sdk.dir` in `local.properties` are the whole toolchain. **No credentials are
needed to build** ([`docs/local-config.md`](docs/local-config.md)).

## Where each fact is documented

One fact, one home. When something below changes, change it *there* — and check nothing else restates
it, because nothing in this repo enforces agreement between two Markdown files.

| Topic | Owner |
|---|---|
| Device envelope, toolchain and SDK levels | this file, above |
| Conventions, traps, reachability, what not to do | this file |
| Design-system and typography rules | this file; the *procedure* is [`docs/figma-workflow.md`](docs/figma-workflow.md) |
| Signing, OAuth clients, on-device install, Frontitude pull | [`docs/local-config.md`](docs/local-config.md) |
| Branches, merges, PR rules, `versionName` and tags | [`docs/git-workflow.md`](docs/git-workflow.md) |
| Workflows, Nexus, `versionCode`, cutting a release | [`docs/ci-cd-release.md`](docs/ci-cd-release.md) |
| Remotes, upstream base tag, the rebase procedure | [`docs/upstream-maintenance.md`](docs/upstream-maintenance.md) |
| Cross-app intents, broadcasts, permissions, provider | [`docs/app-integration.md`](docs/app-integration.md) |
| On-device test recipes, sync intervals, forcing errors | [`docs/kompakt-testing.md`](docs/kompakt-testing.md) |

## Common commands

```bash
# Compile only (fastest check during development)
./gradlew :app-ose:compileOseDebugKotlin
./gradlew :core:compileDebugKotlin
./gradlew :synctools:compileDebugKotlin

# Build / install
./gradlew :app-ose:assembleOseDebug
./gradlew :app-ose:assembleOseQa                                        # see the qa trap below
adb install -r -d app-ose/build/outputs/apk/ose/debug/davx-*-debug.apk   # see local-config.md for
                                                                        # why -d, and when the glob breaks

# What a PR checks that you can reproduce locally (test-core.yml), in order
./gradlew :app-ose:assembleDebug
./gradlew :core:lintDebug :app-ose:lintOseDebug
./gradlew :core:testDebugUnitTest

# One test class
./gradlew :core:testDebugUnitTest --tests "*KompaktOAuthGoogleTest"

# synctools — a PR checks these as well, but only when it touches synctools/ or libs.versions.toml
# (a PR also runs emulator-backed tests in both modules — see Won't do)
./gradlew :synctools:lintDebug
./gradlew :synctools:testDebugUnitTest
```

There are **no unit tests in `app-ose`** — a root `testOseDebugUnitTest` has nothing to run. Put tests
in `core`.

## Module architecture

Four Gradle modules plus two build-only ones:

- **`core`** (`at.bitfire.davdroid`) — all business logic, database, sync, and shared UI, **including
  every Kompakt screen**. An Android library, not an application.
- **`app-ose`** (namespace `com.davx5.ose`, applicationId `at.bitfire.davdroid.mudita`) — thin
  application shell: launcher activity registration, flavor Hilt bindings, product version, deploy
  and `checkVersion` tasks.
- **`synctools`** (`at.bitfire.synctools`) — iCalendar/vCard serialization and content-provider
  mapping. From upstream.
- **`frontitude`** (`com.mudita.frontitude`) — the localized `strings.xml` for all Kompakt copy.
- **`build-logic`** — the `davx5.common-buildconfig` convention plugin, `AppVersion` and
  `KompaktAppVersion`.
- **`buildSrc`** — `KompaktDeployTask` and `bump_build_version.py`.

Edges: `app-ose → core → synctools`, `core → frontitude`. One product flavor, `ose`; build types
`debug`, `qa`, `release`.

## Key architectural notes

- **DI is Hilt.** `core` provides base `@Module` bindings; `app-ose` overrides them from
  `app-ose/src/ose/kotlin/com/davx5/ose/di/`. All six modules there are **upstream's**
  (`IntroPageFactoryModule`, `ColorSchemesModule`, `Cert4AndroidModule`, `AccountsDrawerHandlerModule`,
  `AppLicenseInfoProviderModule`, `LoginTypesProviderModule`); only the last carries a Kompakt edit —
  it binds `KompaktLoginTypesProvider` — and it is the worked example of "prefer the smallest possible
  edit" in *Fork conventions*.
- **Extension points this fork actually uses:** `LoginTypesProvider` (`KompaktLoginTypesProvider`),
  `AccountsDrawerHandler`, `FlavorComposable`. `IntroPageFactory` is bound too, but to *upstream's*
  `OseIntroPageFactory`, and nothing reaches it — see *Reachability on Kompakt*.
- **Entry point and navigation** are activity-based — no navigation graphs, no Compose navigation.
  `KompaktAccountsActivity` is the launcher. The screens it reaches, and the upstream screens it does
  not, are mapped in *Reachability on Kompakt*.
- **Sync:** Android `SyncAdapter` services → `SyncWorker`/`BaseSyncWorker` → CalDAV/CardDAV over
  `dav4jvm` → the `synctools` mapping layer → Calendar/Contacts/Tasks content providers.
  Kompakt-specific defaults (first-sync window, primary-calendar selection, auto-sync interval) live
  in `KompaktInitDefaults`; `AUTO_SYNC_INTERVAL_SECONDS` differs between debug and release
  ([`docs/kompakt-testing.md`](docs/kompakt-testing.md)).
- **Database:** Room, in `core`. Key entities `Service` (one per CalDAV/CardDAV service),
  `Collection`, `SyncStats`. `AccountRepository` wraps Android `AccountManager`; the account type is
  `bitfire.at.davdroid.mudita` (`@string/account_type`, `translatable="false"`).
- **Two version objects sit side by side** in `build-logic/src/main/kotlin/davx5/buildlogic/`:
  upstream's `AppVersion` and our `KompaktAppVersion`. `app-ose` can import them because it applies
  the `davx5.common-buildconfig` plugin, whose classpath includes `build-logic` — that is how the
  product version is wired without editing an upstream file.
- **[`docs/app-integration.md`](docs/app-integration.md) is a published cross-app API, and the only
  record of it.** Every intent action, broadcast, permission, provider authority and provider column
  it lists is consumed by another app on the device (today, the Kompakt calendar app) over a runtime
  contract — there is no compile-time link, so breaking one breaks the *other* app, silently, at
  runtime. Read it before touching anything it names.

## Traps and non-obvious conventions

- **`app:` in a Gradle command means `:app-ose:`.** Upstream's workflows call `app:lintOseDebug` and
  `app:clean`; Gradle abbreviates project paths and `app` unambiguously prefixes `app-ose`, so they
  resolve. There is no second application module.
- **`doc/` and `docs/` both exist.** `doc/` is upstream's (RFCs, slides); `docs/` is ours. One
  character apart — check which one you are writing into.
- **All of upstream's UI is compiled in and none of it is reachable by a user** — not guessable from
  the code, which looks fully wired. Check *Reachability on Kompakt* before touching a screen.
- **Kompakt strings are in `:frontitude`, not in `core`.** All Kompakt copy is `frontitude`'s
  `values*/strings.xml`, used as `import com.mudita.frontitude.R as RFrontitude`. `core`'s own
  `strings.xml` holds no `kompakt*` entries. This app pulls exactly **one** Frontitude project, named
  **"Settings"** (`frontitude/frontituderc.json`). In `keys_to_filter.py`, a key that is the prefix of
  another needs the closing quote ([`docs/local-config.md`](docs/local-config.md#frontitude-strings)).
- **The OAuth client ID is chosen at runtime from the signing certificate**
  (`KompaktGoogleOAuthClients`). A build signed with an unregistered key silently gets
  `FALLBACK_CLIENT_ID` and sign-in fails at Google, not in our code
  ([`docs/local-config.md`](docs/local-config.md)).
- **`qa` is a minified build** (`initWith(release)`), so anything reflective that works in `debug` can
  still break there.
- **`versionCode` is 1 in every local build** — only CI produces real ones
  ([`docs/ci-cd-release.md`](docs/ci-cd-release.md)).

## Fork conventions

The rule that keeps upstream upgrades tractable: **add files, don't edit upstream files.** New files
never conflict on a rebase; edits to upstream files conflict on every single one.

- Every Kompakt-specific file is prefixed **`Kompakt`** and sits next to the upstream file it
  replaces, in the same package: `core/.../ui/AccountsActivity.kt` stays untouched, and
  `core/.../ui/KompaktAccountsActivity.kt` sits beside it. Inventory:
  `find core/src app-ose/src -name 'Kompakt*'`.
- Kompakt UI and logic live in **`core/`**. Flavor wiring belongs in
  **`app-ose/src/ose/kotlin/com/davx5/ose/`**, though today there is no `Kompakt*` file there at all —
  the one binding this fork needed was a one-line edit inside upstream's `LoginTypesProviderModule`.
- When upstream behaviour must change and a new file cannot express it, prefer the smallest possible
  edit — an override, a manifest `tools:node` directive, a `resValue` — over editing upstream logic.
- Upstream repo furniture is left as-is: `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md`,
  `LICENSE`, `AUTHORS`, `.github/`, `doc/`, `fastlane/`. Kompakt equivalents are `README.kompakt.md`
  and `docs/`.

Branch roles, the current upstream base tag and the rebase procedure:
[`docs/upstream-maintenance.md`](docs/upstream-maintenance.md).

## Design system & typography

**MMD (Mudita Mindful Design)** — `com.mudita:MMD`, *not* the workspace's `kompakt-ui` — is the e-ink
component library. Before writing any new Kompakt composable, check whether MMD already provides it.
Components are named `*MMD` (`TopAppBarMMD`, `ButtonMMD`, `TextMMD`, `ModalBottomSheetMMD`,
`CircularProgressIndicatorMMD`, …) in `com.mudita.mmd.components.*`; use them in place of their
Material3 counterparts.

`KompaktTypography900` / `KompaktTypography500` (`core/.../ui/KompaktTypography.kt`) provide the
`(NEW)` Figma type scale — larger than MMD's built-in `eInkTypography` — and must be used for text
that maps to a `(NEW)` Figma style:

| Figma style | Compose style |
|---|---|
| `Title/Medium/900` | `KompaktTypography900.titleMedium` — apply it to the `title` slot you pass `KompaktTopAppBar`; the bar itself styles nothing |
| `Label/Large/900` | `KompaktTypography900.labelLarge` |
| `Label/Medium/900` | `KompaktTypography900.labelMedium` |
| `Label/Small/900` | `KompaktTypography900.labelSmall` |
| `Body/Small/500` | `KompaktTypography500.bodySmall` |
| `Body/Medium/500` | `KompaktTypography500.bodyMedium` |
| `Label/Small/500` | `KompaktTypography500.labelSmall` |
| `Label/Medium/500` | `KompaktTypography500.labelMedium` |

Reading a design, the post-change audit checklist and the Frontitude-key convention:
[`docs/figma-workflow.md`](docs/figma-workflow.md).

## Won't do

- **Don't push to any remote.** Commit locally and report the branch name; the user owns every push.
- **Don't edit an upstream file when a new `Kompakt*` file would do**, and don't reformat, re-comment
  or "tidy" upstream code you happen to be reading. Every such line is a conflict on the next rebase.
- **Don't `git merge upstream` into `kompakt`**, and don't commit Kompakt code to `main`.
- **Don't create a `v*` tag** — it triggers upstream's `release.yml`
  ([`docs/ci-cd-release.md`](docs/ci-cd-release.md)). Kompakt releases are `release.x.y.z`.
- **Don't edit `AppVersion.kt`** — it is upstream's and tracks the rebase base. **Don't change
  `KompaktAppVersion` as part of a feature change** either; version bumps are their own commit and
  their own PR ([`docs/git-workflow.md`](docs/git-workflow.md)).
- **Don't touch the signing config or the `applicationId`.** The registered Google OAuth client is
  bound to that exact package-plus-certificate pair, so changing either breaks sign-in and on-device
  installs ([`docs/local-config.md`](docs/local-config.md)).
- **Don't hand-edit `frontitude` `strings.xml`** — Frontitude owns that copy and overwrites hand
  edits on the next pull. Pull instead
  ([`docs/local-config.md`](docs/local-config.md#frontitude-strings)).
- **Don't change anything `docs/app-integration.md` names** — an intent action, broadcast, permission,
  authority, provider column, or the behaviour a caller is promised. **When a change to it genuinely
  is required, say so explicitly and up front**: which contract changes, what the other app has to
  do, and that the other app needs a coordinated update. Never make such a change quietly, never
  leave it out of the summary, and update the doc in the same change.
- **Don't delete upstream code because it looks dead**, and don't start work on an upstream screen
  without checking *Reachability on Kompakt* first. A Kompakt user cannot reach upstream's UI at all,
  so a change there ships nothing visible — say that up front rather than in review.
- **Don't reach for a Material3 component when MMD has an equivalent**, and don't hardcode font sizes
  or dimensions where a `KompaktTypography` style applies.
- **Don't extract one-off dimension constants.** Don't hoist a `.dp`/`.sp` literal into a named
  `private val` when it's used only once (or a couple of times) in a single component — inline the
  literal at the call site instead. Only extract a named constant when the value is genuinely
  repeated across multiple call sites and a single source of truth helps.
- **Don't add comments — doc or inline — unless the comment carries what the code cannot.** No
  Javadoc/KDoc on new code unless explicitly asked. Before writing any comment, try to make the code
  say it instead — rename the symbol, extract a method, simplify the structure; a comment is the last
  resort, and one that restates the line below it is worse than none. Never write one that only
  parses with full feature context — a reader arriving cold must be able to act on it. Never
  reference a JIRA ticket, PR or branch from source, since neither is reachable from a checkout and
  both go stale as soon as the tracker moves. A comment that clears all of that explains *why*, never
  *what*. The existing `Kompakt*` files are comment-heavy; that is history, not the target.
- **Don't add a dependency without asking first.** It must be GMS-free and GPLv3-compatible.
- **Don't run the instrumented tests locally.** CI runs them on the PR (`core` always, `synctools`
  when that module changes); locally they boot a Gradle-managed emulator image and take many minutes.
  If a change genuinely needs device coverage, use a real Kompakt
  ([`docs/kompakt-testing.md`](docs/kompakt-testing.md)).
- **Don't claim a change works because it compiled.** Say what was run and what was only read —
  especially for sync, OAuth and cross-app paths, which need a device.
- **Don't cite line numbers** in these docs or in commit messages. Point at a class, task, constant or
  literal string; those stay valid and are greppable.
- **Don't restate a fact that already has a home.** Link to its owner in *Where each fact is
  documented* instead; two copies drift, and the stale one is the one someone acts on.

## Git workflow

Full process — branches, merges, versions, PR rules — in
[`docs/git-workflow.md`](docs/git-workflow.md). The three things that go wrong without it:

- **Branch names carry meaning:** `task/<JIRA-ID>-short-desc` (or `task/NO-JIRA-<desc>`),
  `feature/<name>`, `tech/<name>`, `release/x.y.z` — all cut from `kompakt`, never worked on directly.
- **Commit / PR title:** `[<JIRA-ID>] Imperative summary`, e.g.
  `[SHP-1070] Select primary calendar before auto-sync on entry`; `[NO-JIRA]` for minor non-ticket
  work. Avoid vague verbs.
- **GitHub defaults a new PR's base to `main`**, the upstream mirror, which is never a valid target.
  Retarget to `kompakt` or a `release/*` branch.

## Reachability on Kompakt

Upstream's entire UI is still compiled in; this fork only replaces the **entry point**. So "the code
exists and looks wired" says nothing about whether a user can get to it. Derived by enumerating the
merged manifest and every launch site.

### The Kompakt flow — the launcher path

`KompaktAccountsActivity` (launcher, exported) → `KompaktAccountsScreen` → `KompaktLinkAccountScreen`
with no account, `KompaktLinkedAccountScreen` with one. Linking: `KompaktLoginActivity` →
`KompaktLoginScreen` / `KompaktGoogleLogin` → `KompaktOAuthWebViewActivity` →
`KompaktDetectResourcesPage` → `KompaktLoginFinalizeModel`. Re-auth: `KompaktReauthScreen` /
`KompaktReauthModel`. Errors and dialogs: `KompaktMessageSheet`, `KompaktModalSheet`. Behind the UI:
the upstream sync engine (`SyncWorker` / `BaseSyncWorker`) plus `KompaktInitDefaults`.

Both CalDAV and CardDAV are part of this flow. Both scopes are requested, Google offers each on its own
consent checkbox, and every step from linking to the linked-account screen is per-service. Refusing a
service's consent leaves that service with no `Service` row, so it is absent rather than present and
failing — a service the user declined syncs nothing instead of erroring.

### From other apps — by design

The deliberate entry points are `Kompakt`-prefixed and specified in
[`docs/app-integration.md`](docs/app-integration.md): `KompaktAccountsActivity`'s `ACTION_ONBOARDING`
and `ACTION_REAUTH`, `KompaktSyncRequestReceiver`, `KompaktLogoutRequestReceiver` and
`KompaktAuthStateProvider`. Consumed by the calendar app, and covered by the public-API rule in
*Won't do*.

### Upstream's UI has no user route on this device

Upstream's own screens — `AccountsActivity` and its whole tree (the drawer, `AboutActivity`,
`AppSettingsActivity`, `TasksActivity`, `PushSettingsActivity`, `DebugInfoActivity`,
`AccountActivity` and the collection screens, `IntroActivity` and its pages, `WebdavMountsActivity`) —
are compiled, wired and correct, and a Kompakt user cannot get to any of them:

- The **launcher surfaces no shortcuts and no widgets** (`KompaktOS-Launcher` has no
  `ShortcutManager` / `LauncherApps` / `AppWidgetHost` code), so the "Sync all accounts" dynamic
  shortcut and the two sync-button widget providers are registered but never displayed.
- **Upstream notifications are posted but not shown on the device**, so their content intents — which
  are what would otherwise open `AccountSettingsActivity`, `PermissionsActivity`,
  `DebugInfoActivity`, `PushSettingsActivity` and friends — are never tappable.

Two consequences worth keeping in mind. It is **not dead code**: `AccountsActivity`,
`AccountActivity`, `AppSettingsActivity` and upstream `LoginActivity` are all `exported="true"`, so
another app or `adb shell am start` still opens them, and `DavDocumentsProvider` is an exported
documents provider. And an edit to any of these screens **ships nothing a user can see** — say so
before starting the work.

Genuinely inert for environmental reasons: the **jtx / OpenTasks / tasks.org sync adapters** need one
of those apps installed, and **UnifiedPush** needs a distributor app.

**Don't delete any of it** — it is upstream code that must survive the next rebase.
