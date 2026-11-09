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
| instant_settings_mode | FAIL | step 17: no node matches `<text='Instagram'>` (0 visible nodes) after returning to Free Social and swiping down 3× — the swipes opened the ColorOS notification shade over the app (02-failure.png shows the shade with the app panel ghosted behind), so the settings row was never reachable | reverse/runtime-reno/release-gate/20261108-195620/instant_settings_mode |
| passive_feed_untouched | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/passive_feed_untouched |
| yt_home_shelf_visibility | PASS | all assertions passed | reverse/runtime-reno/release-gate/20261108-195620/yt_home_shelf_visibility |

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
