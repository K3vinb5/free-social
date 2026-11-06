# Release & nightly delivery

Everything here runs on GitHub Actions; no local tooling beyond `./gradlew` and
`python3` (stdlib only) is required.

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
  Android SDK guard, secret-guarded release signing (keystore decoded from GitHub
  secrets to the runner temp dir), `./gradlew test assembleRelease`,
  `tools/release/package.sh`, `gh release create --notes-file` with the body from
  `tools/release/changelog.sh` (grouped sections + compare link).

## How to trigger

`Actions -> Release -> Run workflow` (bump or explicit version), or
`Actions -> Nightly -> Run workflow` for an on-demand nightly.

## Naming

- Release zip: `free-social-X.Y.Z-<sha>.zip` (apk + README.txt inside) — attached to the
  GitHub release, and the same file the site serves as its secondary `.zip` link.
- Release APK: `app-release.apk` on the GitHub release; served from the site as
  `free-social-X.Y.Z-<sha>.apk`.
- Nightly asset: `free-social-nightly-YYYYMMDD-<sha>.zip`, tag
  `nightly-YYYYMMDD-<sha>`, marked **prerelease**.
- Versioned releases are full releases; nightlies are prereleases. Both get their
  notes from `tools/release/changelog.sh`; a nightly's range starts at the previous
  `nightly-*` tag (or the last release when none exists).

Both zips come from `tools/release/package.sh` (the single packaging truth), so local
zips and CI artifacts are identical in layout.

## Publishing the site downloads

```bash
tools/release/publish_downloads.sh <release|nightly> <version> <sha> <artifact-dir>
```

`<artifact-dir>` is any directory holding one `*.apk` (the signed release build) and one
`*.zip` (the `package.sh` output) — a `gh release download` target or a CI artifact
directory works as is. The script:

- copies the APK and the zip into `website/downloads/` as
  `free-social-<version>-<sha>.apk` / `.zip` for `release`, and
  `nightly-<YYYYMMDD UTC>-<sha>.apk` / `.zip` for `nightly`;
- rewrites `website/downloads/versions.json` —
  `{"generated","stable":[…],"nightlies":[…]}`, newest first, each entry
  `{version,sha,date,apk,zip,bytes_apk,bytes_zip}` with site-root-relative hrefs;
- keeps the newest 10 stable and the newest 10 nightly entries, and **deletes the files**
  of a nightly that falls out of the window (stable artifacts are versioned, never deleted);
- regenerates the Stable and Nightlies tables between the `AUTOGEN:downloads:start/end`
  markers in `website/index.html` — direct APK and ZIP links, no redirect.

`PUBLISH_DATE=YYYY-MM-DD` overrides the publish date for a new entry (backfills). Re-running
with the same arguments is a no-op: a recorded entry keeps its date, so the filenames,
`versions.json` and the generated tables stay byte-identical. Missing artifacts exit 1 with a
message; a bad invocation exits 2.

The tables in `website/index.html` and `versions.json` are build output — edit the
script, not them.

## Deploying the site container

```bash
tools/release/deploy-site.sh --check   # read-only
tools/release/deploy-site.sh           # deploy $IMAGE into $CONTAINER
```

`--check` prints the docker endpoint's `/_ping` result plus the current container's
name, image, `State.Status` and `State.Health.Status`, and mutates nothing — run it
first on any new host. Without a flag the script deploys.

Env contract (`--check` needs only the first two):

| Variable | | Meaning |
| --- | --- | --- |
| `PORTAINER_URL` | required | Portainer base URL, e.g. `https://portainer.example.tld` |
| `PORTAINER_TOKEN` | required | API key, sent as `X-API-Key` |
| `PORTAINER_ENDPOINT` | optional | docker endpoint id, default `4` |
| `IMAGE` | required to deploy | tagged reference, e.g. `ghcr.io/k3vinb5/free-social-website:1e7b5a1` |
| `CONTAINER` | optional | container name, default `freesocial-web` |
| `REGISTRY_TOKEN` | optional | password for the pull, defaults to `$GITHUB_TOKEN` (user `github`) |

Docker is reached through Portainer's proxy at
`$PORTAINER_URL/api/endpoints/<id>/docker/<path>`, so no docker socket is needed on
the machine running the script. `curl -k` is required because Portainer serves a
self-signed cert there; the API key is written to a `0600` curl config file and passed
with `-K`, so it never appears in `argv` or `ps`. Needs `jq` and `curl`.

Deploy flow: `/_ping` the endpoint → pull `IMAGE` (with registry auth when a token is
given) → snapshot the running container (network mode, restart policy) → stop it →
rename it to `<container>-prev` → create and start the replacement with the same
network aliases → wait up to `HEALTH_TIMEOUT` (60s) for the image `HEALTHCHECK` to
report `healthy` (an image without one is accepted once it is `running`). Any failure
after the rename starts `<container>-prev` again, puts the original name back and exits
1; success deletes `<container>-prev`. A leftover `-prev` from an earlier failed run is
removed first. `HTTP_TIMEOUT` (60s) and `PULL_TIMEOUT` (300s) are also overridable.

In CI this is the `deploy-site` job of `build-release.yml`, which runs `--check` and
then the deploy against the image that `website-image.yml` pushed for the same commit.

