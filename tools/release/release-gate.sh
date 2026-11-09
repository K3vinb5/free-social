#!/usr/bin/env bash
# On-device release gate: run EVERY scenario in tools/device/scenarios/, print one
# aggregated PASS/FAIL/SKIP table, and exit non-zero if anything FAILed — or if nothing
# ran (a run of only documented skips proves nothing).
#
#   tools/release/release-gate.sh                  # the gate (a release needs this)
#   tools/release/release-gate.sh --only <name>    # debug one scenario, never a gate run
#
# Device: $FS_DEVICE_SERIAL, else the first authorised device (drive.py's contract).
# One evidence directory per scenario; the table and gate.log are written next to them.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 2

ONLY=""
if [[ "${1:-}" == "--only" ]]; then
    ONLY="${2:?--only needs a scenario name}"
elif [[ $# -gt 0 ]]; then
    echo "usage: $0 [--only <scenario>]" >&2
    exit 2
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
EVIDENCE="reverse/runtime-reno/release-gate/$STAMP"
TABLE="$EVIDENCE/table.md"
LOG="$EVIDENCE/gate.log"
mkdir -p "$EVIDENCE" || exit 2

# Scenarios that genuinely cannot run unattended. Format: "scenario|reason".
# Anything in scenarios/ that is not listed here is run. Adding a scenario therefore
# forces a decision: it runs, or it is skipped here with a written reason.
SKIPS=(
    "dm_carveout_structural|needs the TikTok DM inbox; the FYP is undumpable while a video plays, so no unattended route into a chat exists"
)

skip_reason() {
    local entry
    for entry in "${SKIPS[@]}"; do
        if [[ "${entry%%|*}" == "$1" ]]; then
            echo "${entry#*|}"
            return 0
        fi
    done
    return 1
}

if [[ -n "$ONLY" ]]; then
    SCENARIOS="$ONLY"
else
    SCENARIOS="$(uv run tools/device/drive.py list)"
    if [[ -z "$SCENARIOS" ]]; then
        echo "FAIL: no scenarios found under tools/device/scenarios/" >&2
        exit 2
    fi
fi

# A full gate run starts from a known phone state; --only is a debug re-run and does not
# reset anything, so it can reproduce the state a fixing session left behind.
if [[ -z "$ONLY" ]]; then
    echo "PREFLIGHT"
    if ! uv run tools/device/drive.py preflight --evidence-dir "$EVIDENCE/preflight" \
            >> "$LOG" 2>&1 < /dev/null; then
        echo "FAIL: device preflight failed — see $LOG" >&2
        exit 2
    fi
fi

printf '| scenario | result | reason | evidence |\n|---|---|---|---|\n' > "$TABLE"
row() { printf '| %s | %s | %s | %s |\n' "$1" "$2" "${3//|/\\|}" "$4" >> "$TABLE"; }

pass=0
fail=0
skip=0
while IFS= read -r scenario; do
    [[ -n "$scenario" ]] || continue
    dir="$EVIDENCE/$scenario"

    if reason="$(skip_reason "$scenario")"; then
        skip=$((skip + 1))
        row "$scenario" "SKIP" "$reason" "-"
        echo "SKIP $scenario — $reason"
        continue
    fi

    echo "RUN  $scenario"
    # `</dev/null`: the driver must not inherit the scenario list on stdin, or the first
    # scenario's adb/uv child eats every remaining name and the loop ends after one row.
    uv run tools/device/drive.py run "$scenario" --evidence-dir "$dir" >> "$LOG" 2>&1 < /dev/null
    rc=$?

    status="$(sed -n 's/^status: //p' "$dir/summary.txt" 2>/dev/null | head -1)"
    reason="$(sed -n 's/^message: //p' "$dir/summary.txt" 2>/dev/null | head -1)"
    if [[ $rc -eq 0 && "$status" == "PASS" ]]; then
        pass=$((pass + 1))
        row "$scenario" "PASS" "${reason:-ok}" "$dir"
        echo "PASS $scenario"
    else
        fail=$((fail + 1))
        row "$scenario" "FAIL" "${reason:-driver exited $rc with no summary}${status:+ [$status]}" "$dir"
        echo "FAIL $scenario — ${reason:-driver exited $rc with no summary}"
    fi
done <<< "$SCENARIOS"

echo
cat "$TABLE"
echo
echo "PASS=$pass FAIL=$fail SKIP=$skip  evidence=$EVIDENCE"

# A gate that exercised nothing must not go green: documented skips are allowed, but a run
# where every scenario was skipped (or the list came back empty) proves nothing.
if [[ $pass -eq 0 ]]; then
    echo "FAIL: no scenario ran (PASS=0 FAIL=$fail SKIP=$skip) — refusing to pass an empty gate" >&2
    exit 1
fi
[[ $fail -eq 0 ]] || exit 1
