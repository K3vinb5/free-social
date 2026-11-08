# Agent instructions — Free Social

## OpenProject filing protocol

Board: https://open-prj.k3vinb5.dev — project **Free Social 🎯** (`-p 4`, identifier `free-social`).
Every agent working in this repo SHOULD file issues for findings that are **outside its
assigned file scope** or that it cannot fix this run. The board is the cross-run memory;
a defect that only lives in a return note gets lost.

```bash
. ~/.config/opencode/env/openproject.sh    # exports OP_CLI_HOST + OP_CLI_TOKEN
# Never print, echo, commit or paste $OP_CLI_TOKEN anywhere.
timeout 15 op work-package create "Bug: <one line>" -p 4 --type Task --description "<body>"
```

Rules — a ticket that breaks these is noise and will be closed unread:

1. **Verified only.** File what you actually observed: a log line, a screenshot, a
   `file:line`, a failing command. Every body must contain the evidence path
   (`reverse/...`, `docs/...`, `app/src/...:123`, a logcat excerpt path). No
   speculation, no "might", no second-hand claims.
2. **No duplicates of your brief.** If the finding is inside your brief's scope, fix it
   or report it in your return format — do not ticket it. Tickets are for discoveries
   beyond scope, deferred work, and defects that need a different run to fix.
3. **Subject prefix = kind:** `Bug:`, `Task:`, `Chore:`, `Decision:`. Keep the board scannable.
4. **Close what you fix.** If your run also fixes a filed item, transition it yourself
   (recipe below) — never leave a fixed bug open.
5. **No secrets or personal data** in tickets. Device LAN IPs and USB serials are
   private-repo content: reference the evidence file instead of pasting values.
6. **Body under ~15 lines.** The evidence file holds the detail; the ticket holds the claim.

Status flow (`op` has no `--status` flag — use API v3; ids on this instance:
`1` New · `7` In progress · `12` Closed):

```bash
LV=$(timeout 15 curl -sS -u "apikey:$OP_CLI_TOKEN" "$OP_CLI_HOST/api/v3/work_packages/<ID>" | jq .lockVersion)
timeout 15 curl -sS -X PATCH -H 'Content-Type: application/json' -u "apikey:$OP_CLI_TOKEN" \
  "$OP_CLI_HOST/api/v3/work_packages/<ID>" \
  -d '{"lockVersion":'"$LV"',"_links":{"status":{"href":"/api/v3/statuses/12"}}}'
```

Link-href shape on THIS instance (measured 2026-11-06): `_links` hrefs take **no** trailing
slash — `/api/v3/statuses/7` works, `/api/v3/statuses/7/` 422s. Same for membership
payloads (`/api/v3/projects/4`, `/api/v3/users/4`, `/api/v3/roles/11`).

Wrap every `op`/`curl` call in `timeout 15`. `op login`/`op logout` are interactive —
never run them. After any mutation, re-inspect and report the resulting state.

## Release gate (standing rule)

A release is cut only after `tools/release/release-gate.sh` passes on a device. The gate
runs every `tools/device/scenarios/` scenario and must exit 0; a FAIL (including a
`WALL`) blocks the release until it is fixed — never lower the bar to ship.

## Keep the docs in sync

- **README mirrors the product.** When a run changes a screen, feature, permission flow
  or visible string that `README.md` (or `website/index.html`) describes or screenshots,
  update those docs in the same pass — stale screenshots and stale copy are defects.
- **DevOps/infra learnings** (CI quirks, registry/deploy steps, DNS/MCP setup, API gotchas)
  go in `docs/dev/DEVOPS.md` so the next agent doesn't rediscover them. Never write
  secrets, tokens, device LAN IPs or USB serials into any doc — reference the memory id
  or an evidence file instead.

## Feature branches (see docs/dev/BRANCHES.md)

Parallel tasks each get their own git worktree + branch (`feature/<slug>`, `fix/<slug>`,
`chore/<slug>`). Do all your work inside the assigned worktree directory — never edit the
main checkout. Commit on your branch, open one PR per task (title carries the OpenProject
id when one exists), and let the light CI (`.github/workflows/ci.yml`, tests + build) run.
Never push to `master` — the only exception is the release pipeline's own bump/site
bookkeeping commits. Overlapping-file tasks follow the combine → serialize → rebase ladder
in `docs/dev/BRANCHES.md`.
