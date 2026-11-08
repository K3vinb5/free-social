#!/usr/bin/env bash
# Tests for tools/release/bookkeeping_pr.sh — the one path a workflow uses to
# land a commit on protected master.
#
#   bash tools/release/bookkeeping_pr.test.sh
#
# `gh` is replaced by a stub on PATH; git runs for real against a throwaway bare
# "origin" in a temp dir, so the push branch/PR/check/merge sequence is exercised
# end to end without touching GitHub.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${here}/bookkeeping_pr.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

pass=0
fail=0
ok() { echo "ok - $1"; pass=$((pass + 1)); }
no() { echo "not ok - $1"; fail=$((fail + 1)); }

# --- fixtures ---------------------------------------------------------------
stub="${tmp}/bin"
mkdir -p "$stub"
cat > "${stub}/gh" <<'STUB'
#!/usr/bin/env bash
# Canned gh: logs argv, answers the handful of calls bookkeeping_pr.sh makes.
printf '%s\n' "$*" >> "${GH_LOG:?}"
mode="${GH_STUB_MODE:-green}"
case "$1 $2" in
  "pr list") echo "" ;;
  "pr create") echo "https://github.com/o/r/pull/42" ;;
  "pr edit" | "workflow run" | "pr merge") : ;;
  "pr view") echo "squashsha1234567890" ;;
  "api "*)  # commits/<sha>/check-runs
    case "$mode" in
      green) echo "completed success" ;;
      fail) echo "completed failure" ;;
      pending) echo "in_progress " ;;
    esac
    ;;
  *) echo "stub gh: unhandled $*" >&2; exit 1 ;;
esac
STUB
chmod +x "${stub}/gh"

make_repo() { # make_repo <dir>
  local dir="$1"
  git init -q --bare "${dir}/origin.git"
  git init -q "${dir}/work"
  (
    cd "${dir}/work"
    git config user.email t@example.com
    git config user.name Test
    git checkout -q -b master
    echo one > file.txt
    git add file.txt
    git commit -qm "initial"
    git remote add origin "${dir}/origin.git"
    git push -q origin master
  )
}

run_case() { # run_case <mode> <timeout> <poll> -> sets out/rc
  set +e
  out="$(
    cd "${work}/work"
    PATH="${stub}:${PATH}" \
      GH_LOG="${GH_LOG}" \
      GH_STUB_MODE="$1" \
      GITHUB_REPOSITORY=o/r \
      bash "$script" --branch chore/test-branch --title "chore(site): publish 1.2.3" \
        --body "test body" --base master --timeout "$2" --poll "$3" 2>&1
  )"
  rc=$?
  set -e
}

# --- case 1: green check, squash merge --------------------------------------
work="${tmp}/case1"
make_repo "$work"
(
  cd "${work}/work"
  echo two > site.txt
  git add site.txt
  git commit -qm "chore(site): publish 1.2.3"
)
GH_LOG="${tmp}/gh1.log"; : > "$GH_LOG"
run_case green 60 1
if [ "$rc" -eq 0 ] \
  && grep -q '^merged_sha=squashsha1234567890$' <<<"$out" \
  && grep -q '^pr_url=https://github.com/o/r/pull/42$' <<<"$out"; then
  ok "green check: prints the PR url and the squash commit sha"
else
  no "green check: exit=$rc out=$out"
fi
if git -C "${work}/origin.git" rev-parse -q --verify refs/heads/chore/test-branch >/dev/null; then
  ok "green check: the commit was pushed to the throwaway branch, not master"
else
  no "green check: branch chore/test-branch missing on origin"
fi
if grep -q 'workflow run ci.yml --ref chore/test-branch' "$GH_LOG"; then
  ok "green check: ci.yml is dispatched on the branch (GITHUB_TOKEN PRs do not trigger pull_request)"
else
  no "green check: ci.yml was not dispatched"
fi
if grep -q 'pr merge 42 --squash --delete-branch' "$GH_LOG"; then
  ok "green check: merged with --squash --delete-branch"
else
  no "green check: merge call missing or wrong"
fi
if [ "$(git -C "${work}/origin.git" rev-list --count master)" = "1" ]; then
  ok "green check: the script itself never pushes to master (the PR does that server-side)"
else
  no "green check: origin/master gained commits outside the PR merge"
fi

# --- case 2: red check -> loud failure, nothing merged ----------------------
work="${tmp}/case2"
make_repo "$work"
(
  cd "${work}/work"
  echo two > site.txt
  git add site.txt
  git commit -qm "chore(site): publish 1.2.3"
)
GH_LOG="${tmp}/gh2.log"; : > "$GH_LOG"
run_case fail 60 1
if [ "$rc" -eq 1 ] && grep -q 'build-test failed' <<<"$out" && grep -q 'pull/42' <<<"$out"; then
  ok "red check: fails loudly and names the PR"
else
  no "red check: exit=$rc out=$out"
fi
if grep -q 'pr merge' "$GH_LOG"; then
  no "red check: a merge was attempted anyway"
else
  ok "red check: no merge attempted"
fi

# --- case 3: pending check -> bounded timeout failure ----------------------
work="${tmp}/case3"
make_repo "$work"
(
  cd "${work}/work"
  echo two > site.txt
  git add site.txt
  git commit -qm "chore(site): publish 1.2.3"
)
GH_LOG="${tmp}/gh3.log"; : > "$GH_LOG"
run_case pending 2 1
if [ "$rc" -eq 1 ] && grep -q 'timed out after 2s' <<<"$out" && grep -q 'pull/42' <<<"$out"; then
  ok "pending check: bounded wait fails with the PR url"
else
  no "pending check: exit=$rc out=$out"
fi

# --- case 4: refuses to target the base branch itself ----------------------
set +e
out="$(GITHUB_REPOSITORY=o/r PATH="${stub}:${PATH}" GH_LOG=/dev/null bash "$script" \
  --branch master --title t --body b 2>&1)"
rc=$?
set -e
if [ "$rc" -eq 2 ] && grep -q 'onto itself' <<<"$out"; then
  ok "guard: refuses a pull request from master onto master"
else
  no "guard: exit=$rc out=$out"
fi

echo
echo "${pass} passed, ${fail} failed"
[ "$fail" -eq 0 ]
