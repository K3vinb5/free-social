# Feature-branch workflow

Decided 2026-11-06 (grill session). This is how parallel agent work is isolated and
merged in this repo. It replaces the shared-checkout + file-ownership-contract model,
which produced three same-day collisions (workers seeing each other's half-written files).

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
- **Review:** the orchestrator reviews every PR. The `reviewer` subagent is mandatory for
  risky diffs — multi-file behavior changes, CI/release, security, data, concurrency — and
  must return exactly PASS or a failure list. Small mechanical diffs skip it.
- **Merge: squash into `master`**, then delete the branch and remove the worktree. The
  loser of a conflict race re-lands as a fresh PR.
- **Documented exception:** the release pipeline pushes directly to `master`
  (`chore(release): vX.Y.Z` version bump and `chore(site): publish …` commits). These
  bypass PRs by design — the pipeline must never 403 on its own bookkeeping. No branch
  protection on `master` until that exception has a bot bypass.

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

- Branch protection / required checks on `master` (needs a bypass for the release
  pipeline's direct pushes).
- PR template file (the PR body convention above is enough for now).
