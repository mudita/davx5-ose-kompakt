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

Room database in `core`. Key entities: `Service` (CalDAV/CardDAV service per account), `Collection` (calendar/addressbook), `SyncStats`. `AccountRepository` wraps Android `AccountManager` for DAVx5 accounts (type `"bitfire.at.davdroid"`).

### String Resources

Kompakt-specific strings go in `core/src/main/res/values/strings.xml` alongside upstream strings — this avoids R namespace issues since `KompaktAccountsScreen` is in the `core` module and uses `at.bitfire.davdroid.R`.

## SDK / Toolchain

- Java toolchain: JDK 21
- `compileSdk` 37, `minSdk` 24, `targetSdk` 36
- Kotlin 2.3.x, AGP 9.2.x, Hilt 2.59.x
- Compose BOM 2026.05.01
