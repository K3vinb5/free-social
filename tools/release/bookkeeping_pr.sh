#!/usr/bin/env bash
# Land a workflow-authored commit on a protected base branch through a PR.
#
#   tools/release/bookkeeping_pr.sh --branch <name> --title <title> --body <markdown>
#                                   [--base master] [--timeout <seconds>]
#
# `master` is protected (ruleset "Protect master": pull request required, required
# status check `build-test`, no force-push, no deletion, zero bypass actors), so a
# workflow can no longer `git push origin HEAD:master`. This script is the single
# way the release pipeline writes to master:
#
#   1. push HEAD to refs/heads/<branch> — a throwaway branch, never master
#   2. open a PR against <base> (reused when one is already open for the branch)
#   3. `gh workflow run ci.yml --ref <branch>`. Mandatory: a pull request created
#      with GITHUB_TOKEN does not start `pull_request` workflows (GitHub suppresses
#      events authored by the token), so `build-test` would never be reported and
#      the required check would wait forever. workflow_dispatch is the one event
#      the token may still originate; its run reports `build-test` against the
#      same head sha the PR is waiting on.
#   4. wait for `build-test` on the PR head sha, bounded by <timeout> (default 1200s)
#   5. `gh pr merge --squash --delete-branch`; one rebase + re-check + retry if the
#      base moved under us
#   6. print `pr_url=` and `merged_sha=` on stdout (squash creates a new sha)
#
# Failure is loud and always names the PR URL: a release that half-landed with a
# dangling PR is worse than a red run. Nothing here bypasses the required check —
# only a green `build-test` on the pushed sha is merged.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: bookkeeping_pr.sh --branch <name> --title <title> --body <markdown>
                         [--base <branch>] [--timeout <seconds>] [--poll <seconds>]

  --branch   throwaway branch to push to (required, must differ from --base)
  --title    PR title, also the squash commit subject (required)
  --body     PR body in markdown (required)
  --base     PR target branch (default: master)
  --timeout  seconds to wait for the build-test check (default: 1200)
  --poll     seconds between check polls (default: 15)

env: GH_TOKEN (or an authenticated gh), GITHUB_REPOSITORY
stdout: pr_url=<url> and merged_sha=<sha>
EOF
  exit 2
}

branch=""
title=""
body=""
base="master"
timeout=1200
poll=15

while [ $# -gt 0 ]; do
  case "$1" in
    --branch)  branch="${2:-}"; shift 2 ;;
    --title)   title="${2:-}"; shift 2 ;;
    --body)    body="${2:-}"; shift 2 ;;
    --base)    base="${2:-}"; shift 2 ;;
    --timeout) timeout="${2:-}"; shift 2 ;;
    --poll)    poll="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "error: unknown argument '$1'" >&2; usage ;;
  esac
done

[ -n "$branch" ] && [ -n "$title" ] && [ -n "$body" ] || usage
[[ "$branch" != "$base" ]] || { echo "error: refusing a pull request from '$base' onto itself" >&2; exit 2; }
[[ "$timeout" =~ ^[0-9]+$ ]] || { echo "error: --timeout must be seconds" >&2; exit 2; }
[[ "$poll" =~ ^[0-9]+$ ]] && [ "$poll" -gt 0 ] || { echo "error: --poll must be a positive integer" >&2; exit 2; }

command -v gh >/dev/null 2>&1 || { echo "error: gh is required" >&2; exit 1; }
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

log() { printf '%s\n' "$*" >&2; }
head_sha() { git rev-parse HEAD; }

pr_url=""
pr_number=""
fail() { # fail <message>
  log ""
  log "::error::$1"
  if [ -n "$pr_url" ]; then
    log "pull request: ${pr_url}"
  fi
  exit 1
}

# sd <sha> -> success | failure:<conclusion> | pending | missing
check_state() {
  local sha="$1" out last status conclusion
  out="$(gh api "repos/${GITHUB_REPOSITORY}/commits/${sha}/check-runs?per_page=100" \
    --jq '.check_runs[] | select(.name=="build-test") | "\(.status) \(.conclusion)"' 2>/dev/null || true)"
  [ -n "$out" ] || { echo missing; return 0; }
  last="$(printf '%s\n' "$out" | tail -n1)"
  read -r status conclusion <<<"$last"
  if [ "$status" != "completed" ]; then
    echo pending
  elif [ "$conclusion" = "success" ] || [ "$conclusion" = "neutral" ] || [ "$conclusion" = "skipped" ]; then
    echo success
  else
    echo "failure:${conclusion}"
  fi
}

wait_for_check() {
  local deadline state
  deadline=$(( $(date +%s) + timeout ))
  while :; do
    state="$(check_state "$(head_sha)")"
    case "$state" in
      success)
        log "build-test passed on $(head_sha)"
        return 0
        ;;
      failure:*)
        fail "build-test failed (${state#failure:}) on $(head_sha) — not merging" ;;
    esac
    if [ "$(date +%s)" -ge "$deadline" ]; then
      fail "timed out after ${timeout}s waiting for the build-test check (last state: ${state})"
    fi
    log "build-test on $(head_sha): ${state}; polling every ${poll}s (bounded ${timeout}s)"
    sleep "$poll"
  done
}

log "pushing $(head_sha) to refs/heads/${branch}"
git push --force-with-lease origin "HEAD:refs/heads/${branch}"

pr_url="$(gh pr list --head "$branch" --base "$base" --state open --json url --jq '.[0].url // ""')"
if [ -z "$pr_url" ]; then
  pr_url="$(gh pr create --base "$base" --head "$branch" --title "$title" --body "$body")"
  log "opened pull request: ${pr_url}"
else
  gh pr edit "$pr_url" --title "$title" --body "$body" >/dev/null
  log "reusing open pull request: ${pr_url}"
fi
pr_number="${pr_url##*/}"
[[ "$pr_number" =~ ^[0-9]+$ ]] || fail "could not derive the pull request number from '${pr_url}'"

log "dispatching ci.yml on ${branch}"
gh workflow run ci.yml --ref "$branch" \
  || fail "could not dispatch ci.yml on ${branch}; build-test would never be reported for the PR"

wait_for_check

merged=false
for attempt in 1 2; do
  if gh pr merge "$pr_number" --squash --delete-branch --subject "$title"; then
    merged=true
    break
  fi
  [ "$attempt" -eq 1 ] || break
  log "merge attempt ${attempt} failed; rebasing onto origin/${base} and retrying"
  git fetch origin "$base"
  if ! git rebase "origin/${base}"; then
    git rebase --abort >/dev/null 2>&1 || true
    fail "could not rebase ${branch} onto origin/${base}"
  fi
  git push --force-with-lease origin "HEAD:refs/heads/${branch}"
  gh workflow run ci.yml --ref "$branch" \
    || fail "could not dispatch ci.yml on ${branch} after the rebase"
  wait_for_check
done
[ "$merged" = "true" ] || fail "the pull request did not merge after two attempts"

merged_sha="$(gh pr view "$pr_number" --json mergeCommit --jq '.mergeCommit.oid // ""')"
if [ -z "$merged_sha" ]; then
  log "mergeCommit was empty; falling back to origin/${base}"
  git fetch origin "$base" >/dev/null 2>&1 || true
  merged_sha="$(git rev-parse "origin/${base}")"
fi
[ -n "$merged_sha" ] || fail "the pull request merged but the squash commit sha is unknown"

log "merged ${pr_url} as ${merged_sha}"
printf 'pr_url=%s\nmerged_sha=%s\n' "$pr_url" "$merged_sha"
