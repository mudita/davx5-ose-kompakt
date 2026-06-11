# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **davx5-ose-kompakt** — a fork of [DAVx⁵ OSE](https://github.com/bitfireAT/davx5-ose) (CalDAV/CardDAV/WebDAV sync for Android), customized for a Mudita e-ink AOSP device with a custom Material Design library. The upstream project is GPLv3.

## Build Commands

```bash
# Compile Kotlin only (fastest check during development)
./gradlew :app-ose:compileOseDebugKotlin
./gradlew :core:compileDebugKotlin

# Build debug APK
./gradlew assembleOseDebug

# Run unit tests
./gradlew testOseDebugUnitTest
./gradlew :core:testDebugUnitTest
./gradlew :synctools:testDebugUnitTest

# Run a single test class
./gradlew :core:testDebugUnitTest --tests "at.bitfire.davdroid.SomeTest"

# Lint
./gradlew lintOseDebug

# Install on connected device
./gradlew installOseDebug

# Instrumented tests (requires connected device or emulator)
./gradlew connectedOseDebugAndroidTest
```

## Module Structure

Three Gradle modules:

- **`core`** (`at.bitfire.davdroid`) — all business logic, database, sync adapters, and shared UI. This is an Android library that is NOT an application.
- **`app-ose`** (`com.davx5.ose` namespace, `at.bitfire.davdroid` applicationId) — thin application shell with OSE/Kompakt-specific Hilt bindings, flavor overrides, and the launcher activity.
- **`synctools`** (`at.bitfire.synctools`) — iCalendar/vCard serialization and Android content provider mapping (calendar, contacts, tasks). Comes from an upstream library.

`app-ose` depends on `core`; `core` depends on `synctools`.

## Kompakt Fork Conventions

This fork prefixes all Mudita-specific new files with **`Kompakt`**. The intent is to add files without modifying existing upstream files, making future upstream merges easier (new files never conflict; only edits to existing files do).

Pattern:
- `core/src/main/kotlin/at/bitfire/davdroid/ui/AccountsActivity.kt` → upstream, untouched
- `core/src/main/kotlin/at/bitfire/davdroid/ui/KompaktAccountsActivity.kt` → Kompakt replacement, sits next to the original

**Rule**: Kompakt UI files live in `core/` alongside their originals (same package, same directory). Kompakt flavor wiring (Hilt modules, factories) lives in `app-ose/src/ose/kotlin/com/davx5/ose/`.

The current Kompakt entry point bypasses onboarding entirely:
- `KompaktAccountsActivity` → `KompaktAccountsScreen` (shows "Link account" if no accounts, account cards if accounts exist)
- `KompaktIntroPageFactory` returns `emptyArray<IntroPage>()` — no intro is ever shown
- The `app-ose` manifest suppresses the core `AccountsActivity` launcher and registers `KompaktAccountsActivity` instead

## Architecture

### Dependency Injection (Hilt)

Core provides base `@Module` bindings. `app-ose` overrides them via its own modules in `com/davx5/ose/di/`:

| Module | Binds |
|--------|-------|
| `KompaktIntroPageFactoryModule` | `KompaktIntroPageFactory` as `IntroPageFactory` |
| `LoginTypesProviderModule` | Login flow types |
| `ColorSchemesModule` | Light/dark theme |
| `Cert4AndroidModule` | Certificate management |
| `AccountsDrawerHandlerModule` | Navigation drawer |
| `AppLicenseInfoProviderModule` | Overrides core's license info |

### Key Extension Points

- **`IntroPageFactory`** (`core/.../ui/intro/IntroPageFactory.kt`) — array of `IntroPage` objects to show on first run. Each page returns a `ShowPolicy` (`SHOW_ALWAYS`, `SHOW_ONLY_WITH_OTHERS`, `DONT_SHOW`). Kompakt returns an empty array.
- **`FlavorComposable`** — injectable composables rendered in `AccountsScreen` for flavor-specific UI additions.
- **`AccountsDrawerHandler`** — injectable that provides navigation drawer content.

### Navigation

Activity-based (no Jetpack Navigation graphs):
```
KompaktAccountsActivity → LoginActivity (add account)
                        → AccountActivity (per-account view)
                             → AccountSettingsActivity
                             → CreateCalendarActivity / CreateAddressBookActivity
```

### Sync Architecture

Android `SyncAdapter` services trigger sync. The flow:
1. `SyncAdapter` (core) → `SyncWorker` → fetches from CalDAV/CardDAV server via `dav4jvm`
2. `synctools` mapping layer transforms between iCalendar/vCard and Android content provider rows
3. Changes written to Calendar/Contacts/Tasks content providers

### Database

Room database in `core`. Key entities: `Service` (CalDAV/CardDAV service per account), `Collection` (calendar/addressbook), 
`SyncStats`. `AccountRepository` wraps Android `AccountManager` for DAVx5 accounts (type `"bitfire.at.davdroid"`).

### String Resources

Kompakt-specific strings go in `core/src/main/res/values/strings.xml` alongside upstream strings — this avoids R namespace issues 
since `KompaktAccountsScreen` is in the `core` module and uses `at.bitfire.davdroid.R`.

## SDK / Toolchain

- Java toolchain: JDK 21
- `compileSdk` 37, `minSdk` 24, `targetSdk` 36
- Kotlin 2.3.x, AGP 9.2.x, Hilt 2.59.x
- Compose BOM 2026.05.01

## MMD (Mudita Mindful Design)

The project uses `com.mudita:MMD` as the e-ink UI component library. **Before creating any new Kompakt composable, check if MMD already provides an equivalent.**

Components follow the naming convention `*MMD` (e.g. `TopAppBarMMD`, `ButtonMMD`, `TextMMD`). The full list of available components is in the `com.mudita.mmd.components.*` packages. Use them in place of their Material3 counterparts in all Kompakt screens.

`KompaktTypography900` / `KompaktTypography500` are project-defined typography objects (in `core/.../ui/KompaktTypography.kt`) that provide the `(NEW)` Figma type scale — these are larger than MMD's built-in `eInkTypography` and must be used for text that maps to `(NEW)` Figma styles.

## Figma

### MCP Figma Integration

Claude can read Figma design files directly via the `figma-developer-mcp` MCP server. Please configure it following the offical tutorial.

**Usage:**

Provide a Figma URL with `node-id` (use Dev Mode in Figma to get it). Claude will call `mcp__figma__get_figma_data` to read the 
design and implement the Compose screen to match.
```
Implement this screen: https://www.figma.com/design/<fileKey>/...?node-id=<id>&m=dev
```

### Figma Design Audit

**After every change based on a Figma design, run a full audit before marking the task done.**

Fetch the relevant node with `mcp__figma__get_figma_data` and verify each of the following:

| What to check | How |
|---|---|
| Typography | Every `Text` uses `KompaktTypography` — match Figma style name to the table above |
| Icon identity | Compare drawable name/size to Figma component name (e.g. `Style=default` vs `Style=tile`) |
| Icon placement | Check `position: absolute` vs in-flow; note `locationRelativeToParent` x/y |
| Borders | Check `strokeDashes` — solid vs dotted (`dashPathEffect`) vs dashed |
| Border radius | Verify `borderRadius` value in dp |
| Padding & spacing | Check `padding` and `gap` values on each frame |
| `Show X: true/false` props | Map each visible component property to the actual rendered node |
| Action icons in AppBar | Check `Buttons & Icons` children — `KompaktTopAppBar` `actionView` slot — **download the node as SVG** if it's IMAGE-SVG type (children hidden from API) |
| Dividers | Which items have separators (`Show bottom-separator: true/false`) |
| Default state | Confirm initial toggle/selection values |

**Go deeper on every layer.** When a node is `IMAGE-SVG` (flat/opaque to the API), use `mcp__figma__download_figma_images` to download it as SVG and inspect the raw path data — 
this reveals the actual icon shapes. Never assume two icons are the same just because they share a component set name.

Don't trust implementation — re-read the Figma node for every component that changed.

### String Resources from Figma

When creating string resources from Figma layers, check the layer name for a **Frontitude key**. Frontitude keys appear at the end of the layer name in the format `[key]`, e.g.:

```
"Link a Google account [calendar.empty_state.title]"
```

If a Frontitude key is present, use it verbatim as the `strings.xml` resource name (replacing `.` with `_` if needed for XML):

```xml
<string name="calendar_empty_state_title">Link a Google account</string>
```

If no Frontitude key is present, fall back to a descriptive `snake_case` name prefixed with `kompakt_`.

### Typography

Always use `KompaktTypography` styles — never hardcode font sizes. Map from Figma:

| Figma style | Compose style |
|---|---|
| `Title/Medium/900` | Handled internally by `KompaktTopAppBar` |
| `Label/Medium/900` | `KompaktTypography900.labelMedium` |
| `Label/Small/900` | `KompaktTypography900.labelSmall` |
| `Body/Small/500` | `KompaktTypography500.bodySmall` |
| `Body/Medium/500` | `KompaktTypography500.bodyMedium` |
| `Label/Medium/500` | `KompaktTypography500.labelMedium` |

