#!/usr/bin/env bash
# Bump (or set) versionName in app/build.gradle.kts.
#
#   tools/release/bump_version.sh patch    # 0.4.0 -> 0.4.1
#   tools/release/bump_version.sh minor    # 0.4.0 -> 0.5.0
#   tools/release/bump_version.sh major    # 0.4.0 -> 1.0.0
#   tools/release/bump_version.sh 1.2.3    # set exactly
#
# Prints the resulting version on stdout. Safe to re-run: an explicit version
# equal to the current one leaves the file untouched and still prints it.
# Unknown bump values (or a malformed current version) exit non-zero.
set -euo pipefail

usage() {
  echo "usage: $0 <patch|minor|major|X.Y.Z>" >&2
  exit 2
}

die() {
  echo "error: $*" >&2
  exit 1
}

arg="${1:-}"
[ -n "$arg" ] || usage
[ $# -eq 1 ] || usage

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -n "${GRADLE_FILE:-}" ]; then
  gradle_file="$GRADLE_FILE"
elif [ -f "app/build.gradle.kts" ]; then
  gradle_file="app/build.gradle.kts"
else
  gradle_file="$script_root/app/build.gradle.kts"
fi
[ -f "$gradle_file" ] || die "gradle file not found: $gradle_file"

current="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$gradle_file" | head -n1)"
[ -n "$current" ] || die "no versionName = \"...\" line found in $gradle_file"

IFS=. read -r cur_major cur_minor cur_patch <<<"$current"
if ! [[ "$cur_major" =~ ^[0-9]+$ && "$cur_minor" =~ ^[0-9]+$ && "$cur_patch" =~ ^[0-9]+$ ]]; then
  die "current versionName '$current' is not X.Y.Z; cannot bump"
fi

case "$arg" in
  patch) new="$cur_major.$cur_minor.$((cur_patch + 1))" ;;
  minor) new="$cur_major.$((cur_minor + 1)).0" ;;
  major) new="$((cur_major + 1)).0.0" ;;
  *)
    case "$arg" in
      *[!0-9.]* | .* | *. | *..*) usage ;;
    esac
    [[ "$arg" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || usage
    new="$arg"
    ;;
esac

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
sed -E "s/^([[:space:]]*versionName[[:space:]]*=[[:space:]]*)\"[^\"]*\"(.*)$/\1\"${new}\"\2/" \
  "$gradle_file" >"$tmp"

grep -q "versionName = \"${new}\"" "$tmp" || die "failed to rewrite versionName in $gradle_file"

if cmp -s "$tmp" "$gradle_file"; then
  echo "unchanged: versionName already ${new} (${gradle_file})" >&2
else
  cat "$tmp" >"$gradle_file"
  echo "bumped: ${current} -> ${new} (${gradle_file})" >&2
fi

echo "$new"
