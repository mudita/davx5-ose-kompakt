# CI/CD & release

Two workflows in `.github/workflows/` build and ship the Kompakt app:

| Workflow | Trigger | Does |
|---|---|---|
| `kompakt-deployment-qa.yml` | `workflow_dispatch`; push to `release/**`, `feature/**`, `tech/**` | `assembleOseQa` → upload to Nexus with `tagPrefix=qa` |
| `kompakt-deployment.yml` | push of a tag `release.*` (also `qa.*`, `development.*`) | `checkVersion` → assemble the variant the prefix names → upload to Nexus |

Neither builds on `kompakt` or on `task/**` — pushing there produces no artifact. `release.*` is the
only prefix used for shipping builds; `qa.*` and `development.*` exist for ad-hoc ones.

## Inherited from upstream

`release.yml`, `test-core.yml` and `test-synctools.yml` came with the fork and are **not part of the
Kompakt release path**.

- `release.yml` fires only on a `v*` tag and builds a signed *upstream* release with bitfire's
  keystore secrets, which this fork does not have. **Never create a `v*` tag** — Kompakt releases are
  `release.x.y.z`.
- `test-core.yml` and `test-synctools.yml` do still run on pull requests, and are the PR gate
  (compile, lint, `core` unit tests, instrumented tests). Leave them alone.

`test-core.yml` and `release.yml` both call tasks on a module named `app`, which doesn't exist here;
Gradle resolves them to `:app-ose:` (see *Traps* in [`../CLAUDE.md`](../CLAUDE.md)). Don't "fix" them.

## Nexus upload

`uploadApkToNexus` is a `KompaktDeployTask` (`buildSrc/src/main/kotlin/tasks/KompaktDeployTask.kt`)
registered in `app-ose/build.gradle.kts`. It curls the newest APK from
`app-ose/build/outputs/apk/ose/<buildType>/` to:

```
<nexusUrl>/kompakt-davx/<tagPrefix>/<versionName>/<apk-name>
```

- The app-name segment is **`davx`** (`kompaktAppName`), so the repository path is `kompakt-davx` —
  not `davx5`, not `davdroid`. The APK is named `davx-<versionName>(<versionCode>)-<buildType>.apk`.
- `tagPrefix` doubles as the build-type directory, except `development` → `debug`.
- Credentials come from `-PnexusUrl` / `-PnexusUsername` / `-PnexusPassword`, fed in CI from the
  `ARTIFACTORY_URL`, `ARTIFACTORY_USERNAME`, `ARTIFACTORY_PASSWORD` secrets. The task fails fast if
  any is blank.
- The curl uses `--fail-with-body`, deliberately: plain `curl` exits 0 on an HTTP 403 and a Nexus
  permission problem would otherwise pass as a green build.
- There is **no changelog task** in this repo (sibling repos have `generateChangelog`); only the APK
  is uploaded.

## versionCode

`versionCode` is `VERSION_CODE` from the environment, falling back to `KompaktAppVersion.CODE` (`1`)
— so every local build is version code 1, and only CI produces real ones. Both Kompakt workflows
compute it with `buildSrc/src/main/kotlin/scripts/bump_build_version.py BUILD_NUMBER`, which
increments the repository's **GitHub Actions variable** `BUILD_NUMBER` through the REST API.

That script needs `GH_TOKEN` to be a **PAT** (classic `repo`, or fine-grained with
*Variables: read + write*). The built-in `GITHUB_TOKEN` cannot manage Actions variables and the bump
step 401s. The step also assigns with `VERSION_CODE=$(python3 …)` under `set -euo pipefail` rather
than `echo "$(…)"`, because `echo` would swallow the script's exit code.

Never hardcode a `versionCode`.

## Required repository secrets

| Secret | Used by |
|---|---|
| `ARTIFACTORY_URL`, `ARTIFACTORY_USERNAME`, `ARTIFACTORY_PASSWORD` | both Kompakt workflows |
| `GH_TOKEN` (PAT) | the `VERSION_CODE` bump in both Kompakt workflows |
| `gradle_encryption_key`, `gradle_buildcache_*` | upstream test workflows (absent → cache disabled, tests still run) |

## Cutting a release

1. On `release/x.y.z`, bump `KompaktAppVersion.NAME` off `-SNAPSHOT` to `x.y.z` in its own PR.
2. Merge `release/x.y.z → kompakt` (standard merge).
3. Tag the release commit on `kompakt` `release.x.y.z` and push the tag — that is the production
   build.
4. `checkVersion` fails the run if the tag and `KompaktAppVersion.NAME` disagree, so step 1 is not
   optional.

Version rules, tag prefixes and the `checkVersion` regex are in the *Versions* section of
[`git-workflow.md`](git-workflow.md).
