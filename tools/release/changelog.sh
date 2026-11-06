#!/usr/bin/env bash
# Markdown release notes for a Free Social build.
#
#   tools/release/changelog.sh <from-ref> <to-ref> <tag>
#
# Emits a "## What's Changed" body on stdout, grouped by commit type, followed
# by a Full Changelog compare link. This repo commits straight to master with
# no pull requests, so `gh release create --generate-notes` (which is PR-based)
# produces an empty body; this script reads `git log` instead.
#
#   <from-ref>  start of the range; may be empty. When empty or unknown it is
#               derived with `git describe --tags --abbrev=0 <to-ref>^`.
#               When no previous tag exists the compare link is omitted.
#   <to-ref>    end of the range (e.g. the built commit, or HEAD).
#   <tag>       tag being published; used in the compare link.
set -euo pipefail

usage() {
  echo "usage: $0 <from-ref> <to-ref> <tag>" >&2
  exit 2
}

[ $# -eq 3 ] || usage

from_ref="$1"
to_ref="$2"
tag="$3"

REPO_URL="https://github.com/K3vinb5/free-social"

if [ -z "$to_ref" ]; then
  to_ref="HEAD"
fi
git rev-parse --verify --quiet "${to_ref}^{commit}" >/dev/null \
  || { echo "error: unknown ref '${to_ref}'" >&2; exit 1; }

if [ -n "$from_ref" ] && ! git rev-parse --verify --quiet "${from_ref}^{commit}" >/dev/null; then
  echo "warning: '${from_ref}' is not a known ref; deriving the previous tag" >&2
  from_ref=""
fi

if [ -z "$from_ref" ]; then
  from_ref="$(git describe --tags --abbrev=0 "${to_ref}^" 2>/dev/null || true)"
fi

if [ -n "$from_ref" ]; then
  range="${from_ref}..${to_ref}"
else
  # No previous tag: describe everything reachable from <to-ref>.
  range="${to_ref}"
fi

section_of() {
  local subject="${1,,}"
  case "$subject" in
    feat*) echo "features" ;;
    fix*) echo "fixes" ;;
    docs*) echo "documentation" ;;
    ci* | chore* | test* | refactor* | perf* | build*) echo "internal" ;;
    *) echo "other" ;;
  esac
}

declare -A items=()
declare -a order=(features fixes documentation internal other)
declare -A header=(
  [features]="### ✨ Features"
  [fixes]="### 🐛 Fixes"
  [documentation]="### 📚 Documentation"
  [internal]="### 🔧 Internal"
  [other]="### 🧹 Other"
)

while IFS=$'\t' read -r sha subject; do
  [ -n "${sha:-}" ] || continue
  # Strip trailing whitespace (and stray CR) but keep the whole subject,
  # including any "prefix(scope): detail" colons.
  subject="$(printf '%s' "${subject%$'\r'}" | sed -e 's/[[:space:]]*$//')"
  [ -n "$subject" ] || continue
  case "$subject" in
    Merge\ * | "Merge pull request"*) continue ;;
  esac
  sec="$(section_of "$subject")"
  items[$sec]+="- ${subject} (${sha})"$'\n'
done < <(git log --no-merges --format='%h%x09%s' "$range")

echo "## What's Changed"
echo

for sec in "${order[@]}"; do
  if [ -n "${items[$sec]:-}" ]; then
    echo "${header[$sec]}"
    echo
    printf '%s' "${items[$sec]}"
    echo
  fi
done

if [ -n "$from_ref" ]; then
  echo "**Full Changelog**: ${REPO_URL}/compare/${from_ref}...${tag}"
fi
