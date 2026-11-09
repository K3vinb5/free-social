#!/usr/bin/env bash
# Tests for tools/release/release-gate.sh — the parts that need no device.
#
# The regression these pin: the gate used to exit 0 when every scenario was skipped
# (`PASS=0 FAIL=0 SKIP=1`), i.e. a green gate that exercised nothing. Skips are
# documented and allowed; an *empty* gate is not.
#
#   tools/release/release-gate.test.sh
#
# `dm_carveout_structural` is the only entry in SKIPS, so `--only` on it is a real
# all-skip pass through the script with no adb, no uv and no device involved.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fails=0
pass() { printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; fails=$((fails + 1)); }

# A throwaway repo root: the script derives its paths from its own location, so a copy
# under <tmp>/repo/tools/release is enough (and keeps evidence out of the real repo).
root="$tmp/repo"
mkdir -p "$root/tools/release"
cp "$here/release-gate.sh" "$root/tools/release/"
script="$root/tools/release/release-gate.sh"

run() { # run <args...> -> sets rc, out, err
    set +e
    out="$(bash "$script" "$@" 2>"$tmp/err.txt")"
    rc=$?
    set -e
    err="$(cat "$tmp/err.txt")"
}

# ------------------------------------------------- all-skip must not be green
run --only dm_carveout_structural
[ "$rc" -ne 0 ] && pass "all-skip run exits non-zero (rc=$rc)" || fail "all-skip run must exit non-zero (rc=0)"
[ "$rc" -eq 1 ] && pass "all-skip run exits exactly 1 (rc=$rc)" || fail "all-skip run should exit 1, got $rc"
case "$err" in
    *"no scenario ran"*) pass "all-skip run explains itself on stderr" ;;
    *) fail "all-skip run should print 'no scenario ran' (stderr: ${err:0:120})" ;;
esac
case "$out" in
    *"PASS=0 FAIL=0 SKIP=1"*) pass "all-skip run reports PASS=0 FAIL=0 SKIP=1" ;;
    *) fail "all-skip table/summary wrong: ${out: -120}" ;;
esac
case "$out" in
    *"| dm_carveout_structural | SKIP |"*) pass "all-skip run still writes the SKIP row" ;;
    *) fail "SKIP row missing from the table" ;;
esac

# ---------------------------------------------------------- usage / arguments
run --nonsense
[ "$rc" -eq 2 ] && pass "unknown argument exits 2 (rc=$rc)" || fail "unknown argument should exit 2, got $rc"
case "$err" in
    *usage*) pass "unknown argument prints usage" ;;
    *) fail "unknown argument should print usage" ;;
esac

echo
if [ "$fails" -eq 0 ]; then
    echo "release-gate.test.sh: all checks passed"
    exit 0
fi
echo "release-gate.test.sh: $fails check(s) FAILED"
exit 1
