# Release gate — full on-device run

Run: `bash tools/release/release-gate.sh` (all scenarios, preflight READY) on 2026-11-08.
Gate summary: **PASS=10 FAIL=1 SKIP=1**, exit code **1** (the FAIL blocks a release).
Evidence root: `reverse/runtime-reno/release-gate/20261108-195620/` (per-scenario dirs plus
`table.md` and `gate.log`).

| scenario | result | reason | evidence dir |
|---|---|---|---|
| app_mode_chip_persistence | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/app_mode_chip_persistence |
| capture_match_list | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/capture_match_list |
| capture_pause_filter | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/capture_pause_filter |
| direct_entry_pays | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/direct_entry_pays |
| dm_carveout_structural | SKIP | needs the TikTok DM inbox; the FYP is undumpable while a video plays, so no unattended route into a chat exists (skipped by the gate, so no evidence dir) | — |
| exhaustion_consistent | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/exhaustion_consistent |
| flash_escape_visibility | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/flash_escape_visibility |
| heal_from_dump | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/heal_from_dump |
| ig_reels_entry | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/ig_reels_entry |
| instant_settings_mode | PASS | all assertions passed on re-run (35 steps, 81.5 s; block screen 1.96 s of the 2.0 s budget after the driver gesture fix) | reverse/runtime-reno/release-gate/20261109-021320/instant_settings_mode |
| passive_feed_untouched | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/passive_feed_untouched |
| yt_home_shelf_visibility | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/yt_home_shelf_visibility |

## Re-run 2026-11-09 — `instant_settings_mode`

The 2026-11-08 FAIL above was a **driver gesture bug, not a product defect**.
`policy.to_hub` scrolls the hub with `swipe(direction="down")` and no container, and the
driver used the raw screen rect, so the gesture started at y=240 of 2400 — inside the
ColorOS shade-pull band. The shade landed over Free Social (the failure dump held 175
`com.android.systemui` nodes and zero app nodes), so the "Instagram" row was never
reachable. The product's instant-settings path was already device-proven in that run:
the block screen appeared 0.81 s after the chip tap with the process alive.

Fix: no-container swipes are confined to `device.content_rect` (screen minus the top 15 %
and bottom 10 %), and `policy.to_hub` dismisses a shade that appears anyway and retries the
scroll once. Re-run after the fix (`drive.py preflight` → READY, then
`drive.py run instant_settings_mode`): **PASS**, 35 steps, 81.5 s, block screen 1.96 s of
the 2.0 s budget.

Effect on the gate: this row is now PASS, so the 2026-11-08 summary at the top of this file
(10 PASS / 1 FAIL / 1 SKIP, exit 1) is history for that run only; with this row the gate has
no failing scenario left. Every other row above is the 2026-11-08 result, unchanged.

## Device end state

Restored by a post-run `drive.py preflight` (evidence `20261108-195620/final-preflight`),
device on stock OPPO Reno4 5G, pt-PT, ColorOS:

- app modes: **Filter** (Instagram, YouTube; TikTok default) — `app_mode_*` all `filter`
- budget: **used=0 / limit=20 min**, date 2026-11-08, filtering_enabled=true
- accessibility: **FilterService + BlockService both bound**
- rules in use: **Built-in** (no `rules-override.json` in `files/`)
- nothing armed: no rule-inspector capture pending (only `notif_requested`), no schedule lock
- launcher in front: `com.android.launcher/.Launcher`

## Negative controls (gate fails when it should)

The gate's non-zero-on-failure path is proven by two earlier runs in this same evidence tree:

- `20261108-113629` — bogus scenario name `not_a_scenario` → FAIL, driver exit 1, gate exit 1.
- `20261108-113634` — deliberate `zz_negative_control` → FAIL ("deliberate negative control"),
  gate exit 1.

So this run's exit 1 is a real FAIL, not a silent pass.
