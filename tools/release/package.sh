#!/usr/bin/env bash
# Single packaging truth for Free Social release builds.
#
#   tools/release/package.sh <version> <sha> <outdir>
#
# Produces <outdir>/free-social-<version>-<sha>.zip containing exactly:
#   free-social-<version>-<sha>.apk   (copy of app/build/outputs/apk/release/app-release.apk,
#                                      a signed release build — never the debug APK)
#   README.txt                        (install steps, generated from the template below)
#
# Run ./gradlew assembleRelease (with signing configured) first. Prints the zip
# path on stdout.
set -euo pipefail

usage() {
  echo "usage: $0 <version> <sha> <outdir>" >&2
  exit 2
}

die() {
  echo "error: $*" >&2
  exit 1
}

[ $# -eq 3 ] || usage
version="$1"
sha="$2"
outdir="$3"

[[ "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || die "invalid version '$version'"
[[ "$sha" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || die "invalid sha '$sha'"

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
apk="$script_root/app/build/outputs/apk/release/app-release.apk"
[ -f "$apk" ] || die "apk not found: $apk (run ./gradlew assembleRelease first)"

base="free-social-${version}-${sha}"
mkdir -p "$outdir"
outdir="$(cd "$outdir" && pwd)"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

cp "$apk" "$stage/$base.apk"

build_date="$(date -u +%Y-%m-%d)"

cat >"$stage/README.txt" <<EOF
Free Social ${version} (build ${sha}, ${build_date})

This APK is a signed release build (not a debug build).

INSTALL (3 steps)
1. Extract the .apk from this zip and tap it. Allow install from this source.
2. FIRST RUN: the setup wizard leads you through:
   - "Allow restricted settings" (Android 13+): Free Social app-info menu ->
     Allow restricted settings. REQUIRED or the accessibility toggle is dead.
   - Enable "Content filtering" accessibility service.
   - Optional "Whole-app blocking" service, overlay + battery exemption.
3. Grant any remaining permissions the Permissions screen shows (live status).

WHAT'S NEW
See the release notes on GitHub for this build's changelog.

KNOWN ROUGH EDGES
- Per-app modes explain themselves on the mode screen ("takes effect when you open
  the app") — switching modes has no visible effect inside Free Social, that part is
  expected: the effect shows in the target app.
- TikTok DM carve-out: structurally matched, live check still pending.
- ColorOS: the capture notification may not render in the shade (in-app step card
  is the guide there); hub budget can read stale until app restart (decisions always
  use live values).

Report anything that leaks a reel or over-blocks in the repo's issue tracker.
EOF

zip_path="$outdir/$base.zip"
rm -f "$zip_path"
(cd "$stage" && zip -X -q "$zip_path" "$base.apk" README.txt)

echo "$zip_path"
