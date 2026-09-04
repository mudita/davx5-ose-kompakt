# Local configuration

Toolchain and SDK levels are in [`../CLAUDE.md`](../CLAUDE.md#device-and-build-envelope). This page is
everything you have to set up or know beyond installing them.

## Prerequisites

`local.properties` needs only `sdk.dir`, which Android Studio writes for you.

**No credentials are required to build**, unlike most sibling Kompakt repos: every dependency
resolves from the three public repositories in `settings.gradle.kts` (mavenCentral, google, jitpack)
— `com.mudita:MMD` is published to mavenCentral. Do not copy the sibling repos'
`mudita_repo_username` / `mudita_repo_password` setup here; nothing reads it. Nexus credentials are
needed only by the `uploadApkToNexus` deploy task, and only in CI (see
[`ci-cd-release.md`](ci-cd-release.md)).

## Signing

All three build types — `debug`, `qa`, `release` — are signed with the **checked-in
`debug.keystore`** (`app-ose/build.gradle.kts`: `qa` inherits `release`, and `release` uses the
`debug` signing config):

| Field | Value |
|---|---|
| store | `debug.keystore` in the repo root |
| alias | `system-debug` |
| store / key password | `android` |

That alias is the **AOSP platform testkey**, shared by every Kompakt app, which is why builds from
this repo can replace the app on a device. AGP's auto-generated debug key and upstream's `bitfire`
key both produce a different fingerprint and break Google sign-in — never swap the signing config.

## Google OAuth clients

Google binds an Android OAuth client to a *package name + SHA-1 certificate fingerprint* pair, so the
client ID depends on which key signed the build. `KompaktGoogleOAuthClients`
(`core/.../network/KompaktGoogleOAuthClients.kt`) reads the running app's signature at runtime and
picks the matching client ID. When nothing matches it falls back to `FALLBACK_CLIENT_ID` — a
separate constant that happens to hold the same ID as entry 0 today, so reordering `clients` would
not change the fallback.

- Index `0` is the `system-debug` / platform-testkey fingerprint
  (`27:19:6E:…:3D:FA`) — what local and shipped builds use.
- `applicationId` must stay `at.bitfire.davdroid.mudita`, or Google rejects the OAuth redirect.
- To add a signing key: get its SHA-1, register an OAuth client for
  `at.bitfire.davdroid.mudita` + that SHA-1 in Google Cloud Console, then add a `SignatureClient`
  entry.
- The Cloud project is **unverified**, so requesting a sensitive scope that has not been granted yet
  shows Google's "This app hasn't been verified" interstitial (passable via *Advanced → Open*).
  Clearing that is a console/verification task, not a code change.

That `applicationId` is also a **separate package** from upstream's `at.bitfire.davdroid`, so a stock
DAVx⁵ can be installed alongside; provider authorities are derived from the package ID for the same
reason.

## Installing on a device

```bash
./gradlew :app-ose:assembleOseDebug
adb install -r -d app-ose/build/outputs/apk/ose/debug/davx-*-debug.apk
```

The APK is named `davx-<versionName>(<versionCode>)-<buildType>.apk` — parentheses included, e.g.
`davx-1.0.0(1)-debug.apk`. The glob above only works while that directory holds one APK; after a
version bump it matches several and `adb install` errors. Name the file, or clean the directory
first.

`-d` is required: the shipped app on the device is a **privileged system app** whose `versionCode`
is in the hundreds of millions (upstream derives it from the release version) while a local build is
`1`, so this is a downgrade. It still installs over
the system app because the ROM signed it with this same `debug.keystore`, and the DAVx⁵ account plus
its synced data survive — which is what makes it possible to exercise re-auth against the real OAuth
client without relinking. Restore the device afterwards by installing an unmodified build.

## Frontitude strings

Kompakt copy lives in the `frontitude` module and is **owned by Frontitude** — hand edits are
overwritten by the next pull. To add a string, pull rather than write it. From `frontitude/`, with the
CLI installed and authenticated (config in `frontitude/frontituderc.json`):

```bash
cd frontitude
find src/main/res -name strings.xml -delete   # start from a clean state
frontitude pull --has-key --include-translations
python3 move_frontitude_files.py              # or ./gradlew :frontitude:moveFrontitudeFiles
```

The pull writes a flat `values/strings.xml` plus per-locale `values/strings-<locale>.xml`; the script
moves each into its `values-<locale>/` directory. Then use the key via `RFrontitude.string.<key>`.

The source is the Frontitude **Settings** project, which carries the copy for every settings-related
screen on Kompakt — so a pull brings back far more keys than this app uses, and that set keeps
growing as other Kompakt settings work lands. The script therefore filters before it moves: any line
matching nothing in `frontitude/keys_to_filter.py` is dropped, and only the keys listed there
survive. The Settings app's own `resources` module filters the same project the same way.

**Adding a string therefore takes two edits**: the `RFrontitude.string.<key>` reference, and the key
in `keys_to_filter.py`. Miss the second and the next pull quietly drops the string again, breaking
the build at that reference.

An entry matches anywhere in the line, so a key that is the prefix of another key keeps that one too
— `common_label_sun` alone also keeps `common_label_sunday`. Such entries carry the closing quote
(`'common_label_sun"'`) to pin them to a single key.

`common_label_*` is a namespace shared across the Kompakt apps and each app pulls its own project, so
a word this app needs may already exist, translated, in another project — the calendar app's
"Calendar", the dialer's "Phone", "Messages (SMS)", "Camera". Point design at that copy and ask for
the key in **Settings**; this app pulls one project and adding a second is not the fix.

If the string genuinely doesn't exist in Frontitude yet, hard-code it at the call site with a
`// TODO:` so it can be added and replaced later.

Test recipes for the account/sync flows — auto-sync intervals, forcing sync errors — are in
[`kompakt-testing.md`](kompakt-testing.md).
