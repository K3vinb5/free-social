#!/usr/bin/env bash
# Tests for tools/release/publish_downloads.sh.
#
# The script's job is data only: copy the artifacts and rewrite
# website/downloads/versions.json. The page renders its tables from that file at
# build time, so a publish must never touch HTML. These tests pin that contract,
# the idempotency guarantee, nightly pruning and the exit codes.
#
#   tools/release/publish_downloads.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fails=0
pass() { printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; fails=$((fails + 1)); }

# A throwaway repo root: the script derives its paths from its own location, so a
# copy of it under <tmp>/repo/tools/release is enough.
root="$tmp/repo"
mkdir -p "$root/tools/release" "$root/website/downloads"
cp "$here/publish_downloads.sh" "$root/tools/release/"
script="$root/tools/release/publish_downloads.sh"
json="$root/website/downloads/versions.json"
# A file the script must leave alone (it is the Vite shell now, not publish output).
printf '<div id="root">__APP_HTML__</div>\n' > "$root/website/index.html"
index_before="$(cksum < "$root/website/index.html")"

artifacts() { # artifacts <dir> <zip-name> [apk-bytes]
  mkdir -p "$1"
  head -c "${3:-1024}" /dev/zero > "$1/app-release.apk"
  head -c 2048 /dev/zero > "$1/$2"
}

# ---------------------------------------------------------------- exit codes
set +e
bash "$script" release 1.0.0 abc >/dev/null 2>&1
[ "$?" -eq 2 ] && pass "wrong argument count exits 2" || fail "wrong argument count should exit 2"
bash "$script" release 1.0.0 abc "$tmp/rel" extra >/dev/null 2>&1
[ "$?" -eq 2 ] && pass "extra arguments exit 2 (arity pinned at 4)" || fail "extra arguments should exit 2"
bash "$script" beta 1.0.0 abc "$tmp" >/dev/null 2>&1
[ "$?" -eq 2 ] && pass "unknown mode exits 2" || fail "unknown mode should exit 2"
bash "$script" release 1.0.0 abc "$tmp/nope" >/dev/null 2>&1
[ "$?" -eq 1 ] && pass "missing artifact dir exits 1" || fail "missing artifact dir should exit 1"
set -e
mkdir -p "$tmp/empty"
set +e
bash "$script" release 1.0.0 abc "$tmp/empty" >/dev/null 2>&1
[ "$?" -eq 1 ] && pass "missing artifacts exit 1" || fail "missing artifacts should exit 1"
set -e

# ------------------------------------------------------------ release publish
artifacts "$tmp/rel" free-social-1.0.0-abc1234.zip
out="$(PUBLISH_DATE=2026-01-02 bash "$script" release 1.0.0 abc1234 "$tmp/rel")"
grep -q "versions.json: 1 stable, 0 nightly (updated)" <<<"$out" \
  && pass "release publish records one stable entry" \
  || fail "release publish did not report the new entry: $out"

[ -f "$root/website/downloads/free-social-1.0.0-abc1234.apk" ] \
  && pass "release APK copied to its public name" \
  || fail "release APK was not copied"
[ -f "$root/website/downloads/free-social-1.0.0-abc1234.zip" ] \
  && pass "release zip copied to its public name" \
  || fail "release zip was not copied"

python3 - "$json" <<'PY' && pass "versions.json entry shape" || fail "versions.json entry shape"
import json, sys
doc = json.load(open(sys.argv[1]))
e = doc["stable"][0]
assert doc["nightlies"] == [], doc
assert e["version"] == "1.0.0" and e["sha"] == "abc1234" and e["date"] == "2026-01-02", e
assert e["apk"] == "downloads/free-social-1.0.0-abc1234.apk", e
assert e["zip"] == "downloads/free-social-1.0.0-abc1234.zip", e
assert e["bytes_apk"] == 1024 and e["bytes_zip"] == 2048, e
PY

[ "$index_before" = "$(cksum < "$root/website/index.html")" ] \
  && pass "publish leaves index.html untouched" \
  || fail "publish rewrote index.html"

# -------------------------------------------------------------- idempotency
before="$(cksum < "$json")"
PUBLISH_DATE=2030-12-31 bash "$script" release 1.0.0 abc1234 "$tmp/rel" >/dev/null
[ "$before" = "$(cksum < "$json")" ] \
  && pass "re-running a recorded release is byte-identical (date kept)" \
  || fail "re-running a recorded release changed versions.json"

# ------------------------------------------------------------- nightly flow
artifacts "$tmp/night" free-social-nightly-20260103-def5678.zip
out="$(PUBLISH_DATE=2026-01-03 bash "$script" nightly 0.0.0 def5678 "$tmp/night")"
grep -q "1 stable, 1 nightly (updated)" <<<"$out" \
  && pass "nightly publish records one nightly entry" \
  || fail "nightly publish reported: $out"
[ -f "$root/website/downloads/nightly-20260103-def5678.apk" ] \
  && pass "nightly files use the UTC publish date, not versionName" \
  || fail "nightly file naming is wrong"

# 11 nightlies -> the oldest is pruned, its files deleted.
for day in 04 05 06 07 08 09 10 11 12 13 14; do
  artifacts "$tmp/nIGHT$day" "free-social-nightly-202601${day}-s${day}.zip"
  PUBLISH_DATE="2026-01-${day}" bash "$script" nightly 0.0.0 "s${day}" "$tmp/nIGHT$day" >/dev/null
done
count="$(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))['nightlies']))" "$json")"
[ "$count" = "10" ] && pass "nightlies are pruned to the newest 10" || fail "expected 10 nightlies, got $count"
[ ! -f "$root/website/downloads/nightly-20260103-def5678.apk" ] \
  && pass "pruned nightly files are deleted" \
  || fail "pruned nightly files were left behind"
[ -f "$root/website/downloads/free-social-1.0.0-abc1234.apk" ] \
  && pass "stable artifacts are never pruned" \
  || fail "stable artifact was deleted"

# 13 stable entries total -> JSON keeps the newest 10, files all stay.
for i in $(seq 1 12); do
  d="$(printf '%02d' "$i")"
  artifacts "$tmp/rel$i" "free-social-2.0.$i-s$i.zip"
  PUBLISH_DATE="2026-02-$d" bash "$script" release "2.0.$i" "s$i" "$tmp/rel$i" >/dev/null
done
count="$(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))['stable']))" "$json")"
[ "$count" = "10" ] && pass "stable entries are capped at the newest 10" || fail "expected 10 stable entries, got $count"
newest="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['stable'][0]['version'])" "$json")"
[ "$newest" = "2.0.12" ] && pass "stable entries are newest-first" || fail "newest stable entry is $newest"
[ -f "$root/website/downloads/free-social-2.0.1-s1.apk" ] \
  && pass "capped stable files are kept on disk" \
  || fail "capped stable file was deleted"

[ "$index_before" = "$(cksum < "$root/website/index.html")" ] \
  && pass "index.html still untouched after every publish" \
  || fail "index.html was modified by a publish"

echo
if [ "$fails" -ne 0 ]; then
  echo "$fails test(s) failed"
  exit 1
fi
echo "all publish_downloads tests passed"
