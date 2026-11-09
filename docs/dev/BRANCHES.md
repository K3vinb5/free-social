# Feature-branch workflow

Decided 2026-11-06 (grill session). This is how parallel agent work is isolated and
merged in this repo. It replaces the shared-checkout + file-ownership-contract model,
which produced three same-day collisions (workers seeing each other's half-written files).
Updated 2026-11-08: `master` is now GitHub-protected, so every change — bookkeeping
included — lands through a pull request.

## Model

- **One task = one git worktree + one branch.** Create with the OpenCode worktree tools
  (`worktree_create`), never by hand-copying the repo.
- The **main checkout stays on `master`** and is for orchestration only. A worker does
  every read and write inside its assigned worktree directory and never touches the main
  checkout.
- **Workers commit on their branch** (their own checks green first). The orchestrator
  reviews and merges.

## Naming

- Branch: `feature/<slug>` · `fix/<slug>` · `chore/<slug>` — lowercase, hyphenated.
- PR: one per task. Title matches the branch intent and carries the OpenProject id when
  a ticket exists (e.g. `fix(website): downloads table overflow (OP #141)`). Body = what
  changed, why, and the named checks with their results.

## Integration

- **PR flow on GitHub.** The light CI (`.github/workflows/ci.yml`) runs
  `./gradlew test assembleDebug` on every PR and on every push to `master`. That is the
  whole CI — the release pipeline is separate and stays as-is.
- **`master` is protected.** The GitHub ruleset **Protect master** (id `26818162`) enforces
  a pull request, a green `build-test` check, no force-push and no deletion, with an empty
  bypass list. There is no direct-push path left, for humans or workflows.
- **Review ladder.** The orchestrator reads every diff. Behavior, security, CI-pipeline and
  data changes additionally get an independent reviewer-agent verdict — exactly `PASS` or
  `FAIL`, recorded in the PR body. `FAIL` blocks the merge; max two correction rounds, then
  escalation to the user. Mechanical and docs-only diffs skip the reviewer.
- **Merge: squash into `master`**, then delete the branch and remove the worktree. The
  loser of a conflict race re-lands as a fresh PR.
- **Bookkeeping exception.** Release and nightly flows no longer push to `master`: their
  commits (`chore(release): vX.Y.Z`, `chore(site): publish …`) land through an auto-merged
  PR with green checks. Machine-generated bookkeeping needs no review — but it still needs
  the green check like everything else.

## Same-file conflicts (decision ladder)

When two tasks must touch the same files:

1. **Combine** them into one task/PR if the combined scope is still small.
2. Otherwise **serialize**: the second task waits until the first merges.
3. If parallel is unavoidable: the later branch **rebases at merge time and re-runs its
   checks**.
4. Race tie-break: **first merge wins**; the loser rebases and re-verifies.

## Standing device rule (unchanged)

Only one device-driving task at a time (serial pinned in the brief); code-only tasks run
no `adb` at all.

## Open questions (deferred)

- PR template file (the PR body convention above is enough for now).
