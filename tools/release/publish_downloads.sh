#!/usr/bin/env bash
# Publish a built artifact set to the static site.
#
#   tools/release/publish_downloads.sh <release|nightly> <version> <sha> <artifact-dir>
#
# Copies the APK and the packaging zip out of <artifact-dir> (any *.apk / *.zip)
# into website/downloads/ under their final public names and records them in
# website/downloads/versions.json. The download tables on the page are rendered
# from versions.json when the site is built (website/src/components/DownloadTables.tsx),
# so publishing a build is a data change, never an HTML edit.
#
#   release   -> free-social-<version>-<sha>.apk / .zip
#   nightly   -> nightly-<YYYYMMDD>-<sha>.apk / .zip   (UTC publish date)
#
# versions.json keeps the newest 10 stable and the newest 10 nightly entries,
# newest first. Files belonging to a pruned nightly are deleted from
# website/downloads/; stable artifacts are versioned and never deleted.
#
# Env:
#   PUBLISH_DATE=YYYY-MM-DD   publish date for a new entry (backfills, seeds).
#
# Re-running with the same arguments is a no-op: an entry that is already
# recorded keeps its date, so its filename and versions.json stay byte-identical.
# The "generated" stamp only moves when the data actually changed.
#
# Exit status: 0 published (or already current), 1 missing/unusable artifacts,
# 2 bad invocation.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: tools/release/publish_downloads.sh <release|nightly> <version> <sha> <artifact-dir>

  release            copy as free-social-<version>-<sha>.apk/.zip
  nightly            copy as nightly-<YYYYMMDD UTC>-<sha>.apk/.zip
  <artifact-dir>     holds the built *.apk and the packaging *.zip

Rewrites website/downloads/versions.json and prunes to the newest 10 per
channel. The site's Stable/Nightlies tables are rendered from that file at build
time, so the next site build (website/ -> bun run build) picks the entry up.
EOF
  exit 2
}

die() {
  echo "error: $*" >&2
  exit 1
}

[ $# -eq 4 ] || usage
mode="$1"
version="$2"
sha="$3"
artifact_dir="$4"

case "$mode" in
  release | nightly) ;;
  *)
    echo "error: mode must be 'release' or 'nightly', got '$mode'" >&2
    usage
    ;;
esac
[[ "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || die "invalid version '$version'"
[[ "$sha" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || die "invalid sha '$sha'"

command -v python3 >/dev/null 2>&1 || die "python3 is required"

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
site="$script_root/website"
dl="$site/downloads"
[ -d "$site" ] || die "missing $site"
[ -d "$artifact_dir" ] || die "artifact directory not found: $artifact_dir"
mkdir -p "$dl"
artifact_dir="$(cd "$artifact_dir" && pwd)"

shopt -s nullglob
apks=("$artifact_dir"/*.apk)
zips=("$artifact_dir"/*.zip)
shopt -u nullglob

[ "${#apks[@]}" -gt 0 ] || die "no .apk in $artifact_dir (looked for *.apk)"
[ "${#zips[@]}" -gt 0 ] || die "no .zip in $artifact_dir (looked for *.zip)"

pick() { # pick <kind> <preferred basename> <candidate...>
  local kind="$1" preferred="$2" c
  shift 2
  if [ "$#" -eq 1 ]; then
    printf '%s' "$1"
    return 0
  fi
  if [ -n "$preferred" ]; then
    for c in "$@"; do
      if [ "$(basename "$c")" = "$preferred" ]; then
        printf '%s' "$c"
        return 0
      fi
    done
  fi
  echo "error: ambiguous $kind in $artifact_dir, expected one:" >&2
  printf '  %s\n' "$@" >&2
  return 1
}

apk_src="$(pick .apk app-release.apk "${apks[@]}")" || exit 1
zip_src="$(pick .zip free-social-"${version}"-"${sha}".zip "${zips[@]}")" || exit 1

# An entry that is already recorded keeps its date, so a re-run overwrites the
# same two files instead of inventing a new nightly date.
existing_date="$(python3 - "$dl/versions.json" "$mode" "$version" "$sha" <<'PY'
import json
import sys

path, mode, version, sha = sys.argv[1:5]
try:
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
except (OSError, ValueError):
    sys.exit(0)
for entry in doc.get("stable" if mode == "release" else "nightlies") or []:
    if entry.get("sha") != sha:
        continue
    if mode == "release" and entry.get("version") != version:
        continue
    print(entry.get("date") or "")
    break
PY
)"

if [ -n "$existing_date" ]; then
  publish_date="$existing_date"
elif [ -n "${PUBLISH_DATE:-}" ]; then
  [[ "$PUBLISH_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "PUBLISH_DATE must be YYYY-MM-DD"
  publish_date="$PUBLISH_DATE"
else
  publish_date="$(date -u +%Y-%m-%d)"
fi

if [ "$mode" = "release" ]; then
  base="free-social-${version}-${sha}"
else
  base="nightly-$(printf '%s' "$publish_date" | tr -d '-')-${sha}"
fi

apk_dst="$dl/$base.apk"
zip_dst="$dl/$base.zip"
[ "$apk_src" -ef "$apk_dst" ] || cp -f "$apk_src" "$apk_dst"
[ "$zip_src" -ef "$zip_dst" ] || cp -f "$zip_src" "$zip_dst"

python3 - "$dl" "$mode" "$version" "$sha" "$publish_date" "$apk_dst" "$zip_dst" <<'PY'
import datetime
import json
import os
import re
import sys

dl, mode, version, sha, date, apk_path, zip_path = sys.argv[1:8]
json_path = os.path.join(dl, "versions.json")
key = "stable" if mode == "release" else "nightlies"

try:
    with open(json_path, encoding="utf-8") as fh:
        doc = json.load(fh)
except FileNotFoundError:
    doc = {}
except ValueError as exc:
    sys.exit("error: %s is not valid JSON: %s" % (json_path, exc))


def clean(entry):
    return {
        "version": entry.get("version"),
        "sha": entry.get("sha"),
        "date": entry.get("date"),
        "apk": entry.get("apk"),
        "zip": entry.get("zip"),
        "bytes_apk": int(entry.get("bytes_apk") or 0),
        "bytes_zip": int(entry.get("bytes_zip") or 0),
    }


old_stable = [clean(e) for e in doc.get("stable") or []]
old_nightlies = [clean(e) for e in doc.get("nightlies") or []]
stable = list(old_stable)
nightlies = list(old_nightlies)
target = stable if key == "stable" else nightlies


def is_same(entry, new):
    if entry["sha"] != new["sha"]:
        return False
    return key != "release" or entry["version"] == new["version"]


new_entry = {
    "version": version,
    "sha": sha,
    "date": date,
    "apk": "downloads/" + os.path.basename(apk_path),
    "zip": "downloads/" + os.path.basename(zip_path),
    "bytes_apk": os.path.getsize(apk_path),
    "bytes_zip": os.path.getsize(zip_path),
}

# Newest first. A brand-new entry goes to the top of its date group; an entry
# that is already recorded is replaced in place, so re-running with the same
# arguments cannot reshuffle a date it shares with its neighbours.
same = [i for i, e in enumerate(target) if is_same(e, new_entry)]
if same:
    target[same[0]] = new_entry
else:
    target[:] = [new_entry] + target
    target[:] = sorted(target, key=lambda e: e.get("date") or "", reverse=True)

dropped = target[10:]
target[:] = target[:10]

removed = []
if key == "nightlies":
    safe = re.compile(r"^nightly-[0-9]{8}-[0-9A-Za-z._-]+\.(?:apk|zip)$")
    for entry in dropped:
        for field in ("apk", "zip"):
            name = os.path.basename((entry.get(field) or "").strip())
            if not safe.match(name):
                continue
            path = os.path.join(dl, name)
            if os.path.isfile(path):
                os.remove(path)
                removed.append(name)

out = {"stable": stable, "nightlies": nightlies}
unchanged = old_stable == stable and old_nightlies == nightlies
body = {"generated": doc.get("generated") if unchanged else None}
if not body["generated"]:
    body["generated"] = datetime.datetime.now(datetime.timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )
body.update(out)

serialized = json.dumps(body, indent=2, ensure_ascii=False) + "\n"
previous = None
if os.path.isfile(json_path):
    with open(json_path, encoding="utf-8") as fh:
        previous = fh.read()
changed = serialized != previous
if changed:
    with open(json_path, "w", encoding="utf-8") as fh:
        fh.write(serialized)


def human(size):
    if size >= 10**9:
        return "%.1f GB" % (size / 10**9)
    if size >= 10**6:
        return "%.1f MB" % (size / 10**6)
    if size >= 10**3:
        return "%.0f kB" % (size / 10**3)
    return "%d B" % size


apk_name = os.path.basename(new_entry["apk"])
zip_name = os.path.basename(new_entry["zip"])
print("published %s (%s) and %s (%s)"
      % (apk_name, human(new_entry["bytes_apk"]),
         zip_name, human(new_entry["bytes_zip"])))
print("versions.json: %d stable, %d nightly%s (%s)"
      % (len(stable), len(nightlies),
         (", pruned " + ", ".join(removed)) if removed else "",
         "updated" if changed else "unchanged"))
print("site tables are rendered from versions.json by the next site build")
PY
