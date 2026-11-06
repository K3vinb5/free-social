# DevOps runbook — Free Social

Everything below was learned the hard way. Read it before touching CI, the website
container, DNS or the OpenProject board. This file is **sanitized on purpose**: no
host addresses, no device identifiers, no credential values. Env-var and secret
*names* only. Repo-wide rules live in `AGENTS.md`; packaging detail in
`tools/release/README.md`; the device driver in `tools/device/README.md`.

## 1. Release pipeline
```
release.yml (workflow_dispatch: bump patch|minor|major, or explicit X.Y.Z)
  └─ tools/release/bump_version.sh        # edits versionName in app/build.gradle.kts only
  └─ git commit "chore(release): vX.Y.Z"  # skipped when versionName is already right
  └─ annotated tag vX.Y.Z, push HEAD:master + tag
  └─ build-release.yml (workflow_call, secrets: inherit)
        ├─ verify signing secrets present (fails early, lists what is missing)
        ├─ ./gradlew test assembleRelease
        ├─ tools/release/package.sh -> dist/free-social-<ver>-<sha>.zip
        ├─ tools/release/changelog.sh <from> <HEAD> <tag>   # grouped "What's Changed"
        └─ gh release create --notes-file ... <zip> <apk>
```

- The tag step aborts (no partial release) when `vX.Y.Z` already exists.
- `nightly.yml` (cron `0 3 * * *` + manual) never bumps `versionName` and never
  creates a `vX.Y.Z` tag. It looks at the newest `nightly-*` tag and **exits 0
  without publishing** when `app/`, `build.gradle.kts`, `settings.gradle.kts`,
  `gradle/`, `gradlew` or `gradlew.bat` are unchanged since that commit. Tag/asset
  name: `nightly-YYYYMMDD-<sha>`, published as a **prerelease**.

**Gotcha (solved 2026-11-06):** a `workflow_call` job receives **no secrets**
unless the caller passes `secrets: inherit`. Without it, signing silently degrades
and the "verify signing secrets" step is what saves you — it fails the job before
a 20-minute build rather than shipping an unsigned APK. Both `release.yml` and
`nightly.yml` pass `secrets: inherit`; keep it that way when adding a caller.

## 2. Signing

- The keystore lives **outside the repo**, in `~/.config/free-social/`. It is never
  committed (`keystore.properties` and `*.jks` are gitignored — do not weaken that).
- GitHub repository secrets, all four required:
  `FREE_SOCIAL_KEYSTORE_B64`, `FREE_SOCIAL_KEYSTORE_PASSWORD`,
  `FREE_SOCIAL_KEY_ALIAS`, `FREE_SOCIAL_KEY_PASSWORD`.
  Set them with `gh secret set <NAME>` and pipe the value on stdin; never paste it
  into a shell command line, a ticket or a doc.
- CI decodes the keystore into the runner temp dir, so release artifacts are always
  **release-signed** — nightlies included. No debug APK ever reaches a release.
- Consequence for testers: a debug-signed install and a release-signed install have
  different signatures, so the phone must **uninstall the app first**
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` otherwise). That also wipes app data
  (settings, budgets, captured rules) — warn before doing it.

## 3. Packaging truth

`tools/release/package.sh <version> <sha> <outdir>` is the only packager. It copies
`app/build/outputs/apk/release/app-release.apk` into a staging dir as
`free-social-<version>-<sha>.apk`, writes a generated `README.txt` (install steps,
known rough edges) and zips the pair as `free-social-<version>-<sha>.zip`. Local
builds and CI artifacts are therefore byte-identical in layout. It needs a prior
`./gradlew assembleRelease` and fails loudly if the APK is missing.

## 4. Website

- `website/` is a **static site**: `index.html`, `styles.css`, `assets/`,
  `downloads/`. No build step, no bundler — edit and reload.
- `.github/workflows/website-image.yml` builds `website/Dockerfile`
  (`nginx:1.27-alpine`, content baked in) and pushes
  `ghcr.io/k3vinb5/free-social-website:latest` plus `:<short-sha>` to GHCR. It
  triggers only on `website/**` or the workflow file itself, on `master`.
- Deploy target is the container `freesocial-web` on the user's Docker server,
  behind nginx-proxy-manager; `website/nginx.conf` carries the server block
  (gzip, cache policy, security headers, `/healthz`). Roll a new image by pulling
  `:latest` and recreating the container (Portainer API / `docker compose up -d`).
- Because content is baked into the image, editing `website/` does **nothing**
  until the image is rebuilt and redeployed.

## 5. DNS

- Domains are managed through the Spaceship MCP server (`npx -y spaceship-mcp`);
  its API credentials (`SPACESHIP_API_KEY`, `SPACESHIP_API_SECRET`) are configured
  in `~/.config/opencode/opencode.jsonc` under `mcp`. Never echo or copy them.
- Hosting pattern: every subdomain is a **CNAME to `server.k3vinb5.dev`**; the
  reverse proxy on that box routes by hostname to the right container.
  `freesocial.k3vinb5.dev` is the live site; new subdomains follow
  `<name>` → CNAME `server.k3vinb5.dev`, then add a proxy host entry.

## 6. Issue tracking (OpenProject)

Board: project **Free Social** (`-p 4`). The filing protocol — verified-only
evidence, subject prefixes, scope rules, closing what you fix — is in `AGENTS.md`;
follow it exactly, because the board is the only cross-run memory.

- Credentials come from `~/.config/opencode/env/openproject.sh` (exports
  `OP_CLI_HOST`, `OP_CLI_TOKEN`). Source it, never print it.
- **API gotcha on this instance:** `_links` hrefs take **no trailing slash**
  (`/api/v3/statuses/12`, not `/api/v3/statuses/12/`). The `op` CLI skill's
  example with a trailing slash 422s here. Also: `op` has no `--status` flag, so
  status transitions go through a `PATCH` with `lockVersion` + `_links.status`.
- Wrap every `op`/`curl` call in `timeout 15`; status ids: 1 New · 7 In progress ·
  12 Closed.

## 7. Delivery policy

- Users get builds from **GitHub Releases** and the site's `website/downloads/`
  page only. Nextcloud as a distribution channel was **retired 2026-11-06**; do
  not re-add it, and do not introduce a second mirror.
- When a release ships, the site's download button should point at the current
  `free-social-<ver>-<sha>.zip` under `website/downloads/`.

## 8. Test device

- The phone is reached over USB (its serial comes from `adb devices`) or, as a
  fallback, wirelessly after `adb tcpip 5555`. No address is hardcoded anywhere:
  `tools/device/drive.py` resolves the target from `--device`, then
  `$FS_DEVICE_SERIAL`, then the first attached device — see
  `tools/device/README.md`.
- **Never `adb reboot` it.** It is the user's daily phone; a reboot drops the
  wireless transport and interrupts whatever they are doing.
- Full device facts (model, OS build, screen, locale, quirks) live in project
  memory **#267** — read that instead of re-deriving, and keep identifiers out of
  this repo.
