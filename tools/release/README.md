# Release & nightly delivery

Everything here runs on GitHub Actions; no local tooling beyond `./gradlew` is required.

## Workflows

- `.github/workflows/release.yml` — manual (`workflow_dispatch`). Inputs: `bump`
  (patch|minor|major, default patch) and `version` (explicit `X.Y.Z`, overrides bump).
  Runs `tools/release/bump_version.sh` against `app/build.gradle.kts`, commits
  `chore(release): vX.Y.Z` only when the file changed, creates the annotated tag
  `vX.Y.Z`, pushes commit+tag to `master`, then calls the reusable workflow.
- `.github/workflows/nightly.yml` — cron `0 3 * * *` + manual. Finds the newest
  `nightly-*` tag and skips (exit 0, no release) when `app/`, `build.gradle.kts`,
  `settings.gradle.kts`, `gradle/`, `gradlew`, `gradlew.bat` are unchanged since that
  commit. Never bumps `versionName`, never creates a `vX.Y.Z` tag.
- `.github/workflows/build-release.yml` — reusable (`workflow_call`): checkout, JDK 21,
  Android SDK guard, `./gradlew test assembleDebug`, `tools/release/package.sh`,
  `gh release create --generate-notes`.

## How to trigger

`Actions -> Release -> Run workflow` (bump or explicit version), or
`Actions -> Nightly -> Run workflow` for an on-demand nightly.

## Naming

- Release asset: `free-social-X.Y.Z-<sha>.zip` (apk + README.txt inside).
- Nightly asset: `free-social-nightly-YYYYMMDD-<sha>.zip`, tag
  `nightly-YYYYMMDD-<sha>`, marked **prerelease**.
- Versioned releases are full releases; nightlies are prereleases and their notes
  prepend `git log --oneline <last-nightly>..HEAD` to GitHub's generated notes.

Both zips come from `tools/release/package.sh` (the single packaging truth), so local
zips and CI artifacts are identical in layout.
