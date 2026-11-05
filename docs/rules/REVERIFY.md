Supersedes the cancelled run of 2026-11-05; all scenarios re-run clean.

# Re-verification — §6 acceptance regrade on OPPO Reno4 5G (run-and-report)

Status: **IN PROGRESS** — device-driver evidence pass. No code changes, no gradle, no commits.

Device: OPPO Reno4 5G (`192.168.50.20:37161`), Android 13 / SDK 33, ColorOS, 1080×2400.
APK `7f530f7` already installed (versionName 0.1.0, lastUpdateTime 2026-11-05 07:58:04).
Evidence: `reverse/runtime-reno/`.

Fixes under test: SCROLL_PAST on scrollable ancestors (forward-first); `tiktok_playback`
re-anchored on visible FYP chrome; single-action escape-priority gate + `selected` criterion;
enforcement-truth hub banner; arm-then-capture inspector.

*(Sections appended as each scenario completes.)*

## Wave A — YouTube + budget (YT-only)

Device: OPPO Reno4 5G, `192.168.50.20:37161`, Android 13 / ColorOS, 1080×2400. APK `7f530f7`,
YouTube logged in, Instagram/TikTok untouched (out of scope). All state changes via Free Social's UI.
Logs: `adb logcat -d -s FilterService`; screenshots `reverse/runtime-reno/waveA-NN-*.png`.

Pre-flight state change (recorded for honesty): the hub opened with `Schedule · On · 00:00–23:55`
(Thursday left **all-day locked** by the previous wave — `schedule_day_4_bedtime=0`,
`schedule_day_4_wake=1435`). That lock covers the whole test window, so for Wave A the Nightly-lock
switch was set **Off** (`waveA-01-schedule-off.png`, hub then reads `Schedule · Off`,
`waveA-01b-hub-schedoff.png`) and **restored On** at the end. The Thursday anomaly itself is left
exactly as found. Budget at start: limit 20 min, used 116 s (`settings.xml`).

### A1 — Shorts shelf scroll-past — **BLOCKED-ENGINE** (rule not exercised)

* Surface verified first: home feed, tabs `Início/Subscrições/Música/Em direto`, the Shorts shelf is
  present **at the top of the feed without scrolling** — a 2-column Shorts grid (header `Shorts` at
  `[192,299][353,365]`, chips whose descs end `– reproduzir Short`). `waveA-02-yt-home-a.png` (before).
* Scrolled the feed twice (vertical swipes, 3 s apart) and returned from a Shorts-player detour:
  still in `com.google.android.youtube` (`mCurrentFocus=…Shell$HomeActivity`), no launcher bounce —
  `waveA-04-yt-home-after-scroll.png` (after). The shelf moved with the feed; nothing else happened.
* **The rule never got a chance to fire.** Every scan on this surface reports an almost empty tree:

  ```
  scan com.google.android.youtube (YOUTUBE): 4 nodes, 1 with viewId, rules=2/8, matched=0, windows=1 [YouTube(0)=3/1 ids]
  com.google.android.youtube: 0 hit(s) but gate=NONE (budget=false, locked=false)
  ```

  There is **no** `ACTION_SCROLL_FORWARD` line and no `shorts_shelf` hit, so "scrolled past by the
  engine" cannot be shown. For contrast, `uiautomator dump` of the *same* window returns **137
  nodes** (including `browse_fragment_layout_coordinator_layout`, `results`, `youtube_logo`,
  `menu_item_0…`). The service capture stops at 3 nodes / 1 viewId, i.e. the engine is not
  traversing YT's view tree at all (X1: capture, not rule logic). `shorts_shelf`'s behaviour
  (forward-not-gesture, stay-in-app, no repeat) is therefore **unverified**, not passed.

### A2 — Shorts player in budget — **FAIL** (player is never paid)

* Reached via `am start -a android.intent.action.VIEW -d "https://www.youtube.com/shorts/"`.
* Surface checked before judging: Shorts tab selected in the bottom bar, vertical video with the
  `Gosto/Comentários/Guardar/Partilhar/Remix` rail and the channel row — this is the **Shorts
  player**, not the channel "Shorts" tab and not a live stream (`waveA-03-shorts-player-a.png`).
* Watched it for ~2.5 min (09:26:20 → 09:28:52, one tap inside the player, no navigation away).
* `budget_used_seconds` **did not move**: `116` before (`09:26:10`), `116` after (`09:28:52`).
  Expected: it increases while the player is up. Every scan during the watch:

  ```
  11-05 09:27:41.076  scan com.google.android.youtube (YOUTUBE): 4 nodes, 1 with viewId, rules=2/8, matched=0, windows=1 [YouTube(0)=3/1 ids]
  11-05 09:27:41.077  com.google.android.youtube: 0 hit(s) but gate=NONE (budget=false, locked=false)
  11-05 09:28:52.705  scan com.google.android.youtube (YOUTUBE): 4 nodes, 1 with viewId, rules=2/8, matched=0
  ```

  No `yt_player` (or any YT surface) hit is ever logged, so there is no "counting hit" and the
  stopwatch never starts — same root cause as A1: the capture is 4 nodes on every YT surface.
  Consequence for the combined budget: **YouTube can never consume budget on this device/build**.

### A3 — Budget exhaustion (1 min limit) — **BLOCKED-FIXTURE** (state unreachable)

Two hard stops, both in the app's own UI, before any escape behaviour could be observed:

1. **The allowance stepper cannot express 1 min.** `20 → 15 → 10 → 5 → 0 → 5` — 5-minute steps and
   no 1-minute stop (`waveA-05-budget-min-step.png`: the readout is `5 min of 5 min daily`, the
   smallest non-zero allowance the screen can express).
2. **YT cannot accrue, so "used ≥ limit" is unreachable through YouTube.** With limit 20 the used
   counter is stuck at `116 s` (A2); lowering the limit to 5 still leaves `116 s` of `300 s` used
   and nothing on the YT side ever increases it. Exhausting the budget through YT is impossible on
   this build, so "exactly ONE escape action (BACK), then stays out" is **unverified**, not passed.

Restoral done and verified the same way: `+` three times back to `20 min`
(`waveA-06-budget-restored-20.png`, hub `waveA-06b-hub-budget20.png` reads `19 min left`).
Note: the Budget screen has **no reset-usage control** — the readout only says "Resets at midnight";
`budget_used_seconds=116` is therefore left as found (carried over from the previous wave), while
`budget_limit_minutes=20` is restored.

### A4 — Enforcement banner (master switch only) — **PASS**

* Master `Filtering` switch OFF → pill reads **"Filtering off"** and the three app rows flip to
  `Filter · paused` — `waveA-07-hub-master-off.png`; engine agrees:
  `scan skipped: master switch is off`.
* Master switch ON → pill reads **"Filtering on"**, rows back to `Filtering` —
  `waveA-08-hub-master-on.png`.
* Accessibility services were never unbound (`Permissions · 4 of 4` throughout). One side effect
  worth recording: **each master toggle reconnects the a11y service** — the log shows a fresh
  `connected, rules=8, targets=…` right after both taps (09:30:57 and 09:31:15), i.e. the engine is
  rebound, not left running across the flip.

### A5 — Inspector arm-capture — **FAIL** (captures the navigation bar, not YouTube)

Arm/expiry mechanics themselves work; the **content** is wrong.

* Rule inspector → `YouTube` chip → `YT · shorts player` → `Dump screen` →
  banner **"Armed — open YT · shorts player or another target app within 60 s. The next screen is
  captured automatically."** (`waveA-09-inspector-armed.png`). Then `monkey … com.google.android.youtube`
  (`waveA-10-yt-home-after-arm.png`), back to Free Social (`waveA-11-inspector-dump-list.png`).
* A dump **was** written automatically — `com.google.android.youtube-20261105-093153-622.json`, listed
  in Saved dumps as **`4 nodes`**:

  ```
  09:31:53.635  armed dump com.google.android.youtube/"YT · shorts player": 4 nodes (1 with viewId,
                1 windows: YouTube(layer 0)=3 nodes/1 ids) in 20 ms -> …-093153-622.json
  ```

  The header carries the per-window stats as required (`"windows":[{"title":"YouTube",
  "className":"android.widget.FrameLayout","layer":0,"nodes":3,"viewIds":1}]`, `nodeCount 4`,
  `truncated false`) — but the three sampled nodes are **SystemUI chrome**, not YT:

  ```
  {"viewId":null,"className":"android.widget.FrameLayout","bounds":"[202,53][1234,2347]","visible":true}
  {"viewId":null,"className":"android.widget.LinearLayout","bounds":"[202,53][1234,2347]","visible":true}
  {"viewId":"android:id/navigationBarBackground","className":"android.view.View","bounds":"[202,2221][1234,2347]"}
  ```

  No `browse_fragment_layout_coordinator_layout`, no `results`, no `youtube_logo`, no Shorts ids: the
  armed capture took the **`android:id/navigationBarBackground` window** while labelling it
  "YouTube". This is the same 3-node window that every scan sees (A1/A2/A3), so the whole YT pass
  stands or falls on it. (The pre-existing 08:16 dump in Saved dumps is also `4 nodes`, and even the
  08:19 Instagram dump is `4 nodes` — the wrong-window capture is not YT-specific.)
* **Expiry: PASS.** Armed again at `09:32:47`, stayed in Free Social, no target app opened; at
  `09:34:03` the dumps directory still held exactly the same three files (newest `09:31`) and the
  "Armed —" banner was gone (`waveA-12-inspector-expired.png`). No dump after 60 s, as specified.

### Wave A result

| # | Scenario | Verdict |
|---|----------|---------|
| A1 | Shorts shelf scroll-past | BLOCKED-ENGINE — shelf present, `shorts_shelf` never fired (4-node capture) |
| A2 | Shorts player pays budget | FAIL — player up 2.5 min, `budget_used_seconds` stays 116 |
| A3 | 1-min budget exhaustion | BLOCKED-FIXTURE — stepper min 5 min, and YT cannot accrue at all |
| A4 | Enforcement banner | PASS — "Filtering off" ↔ "Filtering on" (+ a11y service rebinds on each flip) |
| A5 | Inspector arm-capture | FAIL content / PASS expiry — dump written in 60 s but it is the nav-bar window |

Single cross-cutting defect behind A1/A2/A3/A5: on this device the service's window capture returns a
**3-node `android:id/navigationBarBackground` tree** for the window it labels "YouTube" (identical
`4 nodes` in the pre-existing 08:16 YT dump and the 08:19 IG dump), while `uiautomator dump` of the
same moment returns 137 real YT nodes. Every YT surface is therefore invisible to the matcher
(`rules=2/8, matched=0` on home, Shorts shelf and Shorts player alike), so no YT rule — shelf, player
or escape gate — can be graded as working. Needs a capture fix before any further YT wave; re-running
these five scenarios without it will reproduce the same result.

Final state (verified, `waveA-13-final-hub.png`): hub on screen, `Filtering on`, three apps
`Filtering`, `19 min left` (limit 20 / used 116 s — no reset control exists), `Schedule · On ·
00:00–23:55` (Thursday all-day lock left exactly as found), `Permissions · 4 of 4`, both services
still bound. Schedule was temporarily Off for A1–A3 only (see pre-flight note) and restored On.

## X1 — ghost-tree rebind experiment

Bounded experiment (2026-11-05 09:31–09:44, same device): does a full a11y **disable → re-enable**
restore the capture tree, or is the 3-node root a vendor strip? No code, no gradle, no reboot.

Method note: the service-side count is read from its own scan line, which is the same
`getWindows()`/`getRoot()` result the Rule-Inspector dump writes (Wave A A5 already showed the
inspector dump is that 4-node tree). `x1-02` inspector screencap was dropped in favour of the live
scan line to stay inside the interaction budget; `x1-05` is the YT surface after the rebind.

### X1.1 baseline — service 4 nodes vs uiautomator 136 (`x1-01-yt-before.png`)

```
09:36:42.909 FilterService: scan com.google.android.youtube (YOUTUBE): 4 nodes, 1 with viewId,
             rules=2/8, matched=0, windows=1 [android.widget.FrameLayout(0)=3/1 ids]
09:36:50.904 FilterService: scan … 4 nodes, 1 with viewId … [YouTube(0)=3/1 ids]
uiautomator dump of the same home feed: 136 nodes
```

Grant timeline at that moment (`appops get dev.k3vinb5.freesocial`): `ACCESS_RESTRICTED_SETTINGS`
allowed **1 h 36 m ago** (≈08:01), `BIND_ACCESSIBILITY_SERVICE` allowed **52 m ago** (≈08:45) — the
current bind already post-dates the restricted-settings grant.

### X1.2 rebind — service toggled off then on, full-control dialog accepted

* Settings → Acessibilidade → **Content filtering** → switch `Ligado` → `Desligar Content filtering?`
  → **Parar**. `dumpsys accessibility` then lists only `…block.BlockService` (`x1-03-toggle-off.png`).
* Re-enabled; the **"Ver e controlar tela / Ver e realizar ações"** full-control dialog appeared
  (the restricted-settings grant path) and **Permitir** was accepted (`x1-04-toggle-on.png`).
* Post-rebind: `Enabled services: {…filter.FilterService, …block.BlockService}`,
  `Bound services: Service[label=Content filtering, capabilities=41, …]`.
  **41 = 32|8|1 → CAN_PERFORM_GESTURES | CAN_FILTER_KEY_EVENTS | CAN_RETRIEVE_WINDOW_CONTENT**, i.e.
  the framework is now serving this connection *with* content-retrieval capability. Process PID
  unchanged (6575) — the service object was re-bound, not respawned.

### X1.3 result after rebind — **unchanged**

```
09:39:46.138 FilterService: scan com.google.android.youtube (YOUTUBE): 4 nodes, 1 with viewId,
             rules=2/8, matched=0, windows=1 [YouTube(0)=3/1 ids]
09:40:07.938 (after two feed swipes) same: 4 nodes, 1 with viewId … [YouTube(0)=3/1 ids]
uiautomator dump of the same moment: 136 nodes   (`x1-05-yt-after-rebind.png`)
```

Before 4 nodes / 1 viewId (uiautomator 136) → after rebind 4 nodes / 1 viewId (uiautomator 136).

### X1 verdict — **BRANCH B**

Re-binding with content capability confirmed does **not** restore the tree, so the "bound before the
grant" hypothesis (A) is rejected — and the appop timestamps show the bind already post-dated the
grant. What remains: for this third-party service ColorOS hands back a 3-child `FrameLayout`
placeholder — the same 3/1 node set A5 dumped as the `navigationBarBackground` shell — labelled
`YouTube` or `android.widget.FrameLayout` as the *only* window, while shell `UiAutomation` (privileged path)
sees 136 real YT nodes. Vendor strip suspected; needs a stock-Android (Pixel) comparison before any
capture-side code change. A1 (Shorts shelf) stays **unverified**; no further defect work done.

### X1.4 final state — schedule restored (`x1-08-hub-final.png`)

Thursday was put back to **22:30 → 07:00** (picker dial: Bedtime 22:30, Wake 07:00); all seven days
now read `22:30 → 07:00`. Hub: `Filtering on`, `19 min left`, `Schedule · On · 22:30–07:00`,
`Permissions · 4 of 4`, both services bound. Budget untouched.

## Wave B — block mode + schedule flip + Doze

Device: same OPPO Reno4 5G, `192.168.50.20:37161`, Android 13 / ColorOS. Run 2026-11-05
09:46–… WET (Thursday). All state changes through Free Social's UI (`input tap`), 2–3 s pauses,
no reboot, no `am force-stop`. Evidence: `reverse/runtime-reno/waveB-NN-*.png`.
Scope note: this wave deliberately uses **whole-app block mode**, because the content tree is the
broken one (Wave A / X1) and every content-dependent scenario is out of scope here.
Logging note: `BlockService`/`BlockGate` log **nothing on a successful block** (only `Log.w` on a
crash path — see `block/BlockGate.kt`, `block/BlockService.kt`), so the block trace is the app's own
`WindowManager: Add to mViews: …[BlockActivity]` line plus `mCurrentFocus=…ui.BlockActivity`; the
`-s FilterService BlockService` excerpt is quoted verbatim below and shows only the FilterService
side of the same moment.

### B1 — Block mode happy path — **PASS**

* BEFORE: hub `waveB-00-start.png` — Instagram `Filtering`, `Filtering on`, `19 min left`,
  `Schedule · On · 22:30–07:00`, `Permissions · 4 of 4`. AppDetail (`waveB-01a-detail.png`) has
  `Filter` selected in the Mode segment (`Filter | Block | Allow`).
* Set **Block** (`waveB-01b-block-selected.png`, Block pill filled). Hub re-reads Instagram
  **`Blocked`** (red dot) — mode change really landed (`waveB-03b-hub-blocked.png`).
* `monkey -p com.instagram.android 1` → `mCurrentFocus=…dev.k3vinb5.freesocial/.ui.BlockActivity`
  after 4 s; screen is the block card **"Instagram is blocked" / "This app is blocked by Free
  Social." / "Go home"` (`waveB-01-block.png`).
* `Go home` → `mCurrentFocus=com.android.launcher/.Launcher` (launcher visible,
  `waveB-02-home.png`), i.e. it left Instagram rather than returning to it.
* Relaunch after exit → block screen **again** (`waveB-03-block-redetect.png`,
  `mCurrentFocus=…ui.BlockActivity`). Re-detection after exit works.
* Logs (block moment, `logcat -d -s FilterService BlockService`):
  ```
  11-05 09:47:55.075 6575 FilterService: com.instagram.android is not filtered (master off or monitor mode)
  ```
  same buffer, app PID (the actual block): `09:47:56.014 D WindowManager: Add to mViews: DecorView@e1cea32[BlockActivity]`
  → block card up 0.9 s after the launch. Re-detect: `09:49:16.355 … DecorView@fe16ac[BlockActivity]`.
* Mode was left on **Block** at the end of B1 on purpose — B2 needs the same reachable gate state
  (with mode `Filter` the schedule never blocks at all, see B2). Restored to `Filter` at the end of
  B2, verified on the hub.

### B2 — Schedule lock + boundary flip (block-mode variant) — **PASS** (reason flip)

* Gate note first, because it decides how this can be measured: `block/BlockGate.kt` gates 2–3
  return `Ignore` unless the app's mode is **Block**, and gate 5 then only *chooses the reason*
  (`SCHEDULE` → "Sleep mode is active — go to bed.", `APP_BLOCK` → "This app is blocked by Free
  Social."). So in Block mode the screen stays up on both sides of the boundary and the observable
  flip is the **copy**, not the presence of the screen; in Filter mode the schedule is never
  consulted at all, so "no block after wake" there proves nothing. Instagram was therefore left on
  **Block** (carried from B1) and the copy + the boundary alarm are the evidence pair.
* **Setup** (09:52–09:53, via UI only): Thursday `Bedtime 01:00`, `Wake 10:00`, Wind-down `0 min`,
  Morning lock `0 min` (both were already 0 on this device). Thursday window `01:00–10:00`,
  confirmed by the screen's own preview **"Locked 01:00–10:00 tonight"** (`waveB-06-sched-preview-locked.png`)
  and by a re-armed alarm `origWhen=2026-11-05 10:00:00.000` (`dumpsys alarm`).
* **BEFORE (inside the window, 09:53:59)** — `monkey -p com.instagram.android 1` →
  `mCurrentFocus=…ui.BlockActivity`, card reads **"Instagram is blocked" / "Sleep mode is active —
  go to bed."** (`waveB-05-schedule-lock.png`). Alarm still armed for `10:00:00`.
* **AFTER (past the boundary, 10:00:59)** — same launch, same mode: block screen is up again but
  the reason line is now **"This app is blocked by Free Social."** (`waveB-07-after-wake.png`), i.e.
  `scheduleLocked` went false at 10:00. The boundary alarm was consumed and re-armed:
  `origWhen=2026-11-06 22:30:00.000` (next start is Friday's bedtime, since Thursday's window was
  the 01:00→10:00 one). Logcat at that moment:
  ```
  10:00:59.419 FilterService: com.instagram.android is not filtered (master off or monitor mode)
  10:00:59.985 wm_create_activity: [... .ui.BlockActivity, ...]
  10:01:00.079 D WindowManager: Add to mViews: DecorView@ac1ba7c[BlockActivity]
  ```
  (again: `BlockService` prints nothing on a block — it only has `Log.w` on the crash path.)
* **Clip of the flip** (`waveB-flip.mp4`, 20 s): boundary replayed once more at **10:20** (Thu
  `01:00 → 10:20`, alarm `origWhen=10:20:00.000`, window `+36 s`); launch at `10:19:54.174` →
  sleep-mode copy, launch at `10:20:04.694` → app-block copy, both `wm_create_activity …BlockActivity`
  and both visible in the clip. Caveat: `screenrecord` is **permission-denied to the shell user** on
  this ColorOS build (`/system/bin/screenrecord: Permission denied`), so the mp4 is a 30-frame
  `screencap` timelapse (~1 fps wall clock) encoded at 1.5 fps — real frames, not a re-encode of a
  device recording.
* **Restore:** Thursday back to `22:30 → 07:00`, all seven days verified `22:30 → 07:00`
  (`waveB-09-schedule-restored.png`); Instagram mode back to **Filter**
  (`waveB-08-ig-filter-restored.png`), hub row `Filtering`.

### B3 — Doze soak — **PASS**

* `cmd deviceidle force-idle` → `mState=IDLE mLightState=OVERRIDE` (10:04:2x), wait 60 s
  (`waveB-11-during-idle.png`), re-read at 10:05:24: still `IDLE`.
* `dumpsys accessibility`: **both** services still `Bound services:{Content filtering,
  capabilities=41 …}` and `{Whole-app blocking, capabilities=0 …}`, `Enabled services` unchanged,
  `Crashed services:{}`; PID unchanged (6575). `dumpsys deviceidle` still `IDLE`, i.e. the services
  were not torn down by the idle transition.
* `logcat -d -s FilterService | tail -5` at 10:05:24 — last lines are ordinary rebinds from the
  preceding UI navigation, then silence across the soak (no crash, no `onServiceConnected failed`):
  ```
  10:03:03.114 FilterService: connected, rules=8, targets=com.instagram.android,...
  10:03:03.128 FilterService: dev.k3vinb5.freesocial is not a filtered app
  ```
  (No `FATAL EXCEPTION` / `AndroidRuntime` line for the package anywhere in the buffer.)
* `cmd deviceidle unforce` + `cmd battery reset` → `Light state: ACTIVE, deep state: ACTIVE`,
  `mState=ACTIVE`; services still bound (`waveB-12-after-doze.png`).

### B4 — Block-mode re-detection latency (500 ms dedupe) — **PASS (3/3)**

* Instagram on **Block** (`waveB-13-block-again.png`), `logcat -c`, then three
  `monkey -p com.instagram.android 1` launches 2 s apart. Three distinct block activities, no
  missed detection:
  ```
  10:06:37.404 I wm_create_activity: [0,209787430,2702,dev.k3vinb5.freesocial/.ui.BlockActivity,...]
  10:06:40.087 I wm_create_activity: [0,3307843,2702,dev.k3vinb5.freesocial/.ui.BlockActivity,...]
  10:06:42.791 I wm_create_activity: [0,69729836,2702,dev.k3vinb5.freesocial/.ui.BlockActivity,...]
  10:06:37.543 D WindowManager: Add to mViews: DecorView@edc24c1[BlockActivity]
  10:06:40.163 D WindowManager: Add to mViews: DecorView@b1c7637[BlockActivity]
  10:06:42.884 D WindowManager: Add to mViews: DecorView@848c21d[BlockActivity]
  ```
  `mCurrentFocus` was `…ui.BlockActivity` after each of the three launches (2.68 s / 2.70 s apart),
  block card drawn 0.11–0.14 s after the activity was created (`waveB-14-block-3rd.png`). The 500 ms
  per-package dedupe (`BlockGate.DEFAULT_DEDUPE_MILLIS`) did not swallow any of the 2 s-spaced
  launches, as designed.

### Wave B result

| # | Scenario | Verdict |
|---|----------|---------|
| B1 | Block mode happy path (block, Go home, re-detect) | PASS — block 0.9 s after launch, launcher after `Go home`, block again on relaunch |
| B2 | Schedule lock + boundary flip | PASS — `Sleep mode is active` (09:53) → `This app is blocked` (10:00), alarm 10:00 → 06 Nov 22:30; clip `waveB-flip.mp4` |
| B3 | Doze soak (60 s `force-idle`) | PASS — both services bound, `Crashed services:{}`, PID 6575 stable, restored to ACTIVE |
| B4 | Block re-detection latency | PASS 3/3 — three launches, three block activities, 0.11–0.14 s draw |

Final state (verified, `waveB-16-final-hub.png`): hub on screen, `Filtering on`, three apps
`Filtering`, `19 min left` (budget untouched — limit 20 min, used 116 s, the Budget screen has no
reset control), `Schedule · On · 22:30–07:00` with **all seven days** `22:30 → 07:00` (Thursday
re-checked after the edits, `waveB-09-schedule-restored.png`), Wind-down/Morning lock left at the
`0 min` they were found at, `Permissions · 4 of 4`, both services bound, next schedule alarm
`2026-11-05 22:30:00`. `adb devices` shows `192.168.50.20:37161  device`. No reboot, no
`am force-stop`, no `settings put`, no code/gradle/git changes.

## Wave A retry — pt-PT content behaviors

Device: same OPPO Reno4 5G, `192.168.50.20:37161`, Android 13 / ColorOS, pt-PT. APK built from
HEAD `620e74e` (sources 11:17–11:24, APK 11:25:09, `assembleDebug` UP-TO-DATE, reinstalled with
`install -r` at 11:29). Budget at start: limit 20 min, used 116 s, `budget_date=2026-11-05`.
No schedule lock in the test window (Thu 22:30→07:00, device clock 11:30 WET). Evidence
`reverse/runtime-reno/waveAr-NN-*.png`, logcat `-s FilterService`.
Scan trigger: scans are event-driven only (`FilterService.onAccessibilityEvent` → `requestScan`,
no timer); a static YT home emitted nothing for 2 min, so each scenario's scan is produced by the
navigation event itself, never by touching the feed.

### R1 — Shorts shelf scroll-past — **FAIL, still BLOCKED-ENGINE** (`grafted=0`)

* Surface verified first (`waveAr-01-shelf-before.png` + `uiautomator dump`): Shorts shelf at the
  top of the home feed with no scrolling — header `content-desc="Shorts"` and four chips,
  e.g. `content-desc="Goku Fought Jiren In 10x GRAVITY?!… – reproduzir Short"`,
  `"Mac mini M6: is 32GB enough to run local AI? – reproduzir Short"` (U+2013 en dash, as the rule
  expects). Exactly the strings `yt_shorts_shelf` matches on.
* Waited untouched, then produced the scan with the YT home launch event (11:34:56). The service
  still sees the ghost tree, and the graft recovers nothing:

  ```
  11-05 11:34:56.933 30243 FilterService: scan com.google.android.youtube (YOUTUBE): 4 nodes, 1 with viewId, rules=2/8, matched=0, grafted=0, windows=1 [YouTube(0)=3/1 ids], rootChildCount=2, idProbe=menu_item_0=1 app_bar_wrapper=0 results=2
  11-05 11:34:56.933 30243 FilterService: com.google.android.youtube: 0 hit(s) but gate=NONE (budget=false, locked=false)
  ```

  No `ACTION_SCROLL_FORWARD`, no `shorts_shelf` hit, shelf unmoved — `waveAr-02-shelf-after.png`
  (11:34, same feed, `Shorts` header + the same 2×2 grid, still `…Shell$HomeActivity`; no launcher
  bounce because nothing fired).
* Regression data for the report: `uiautomator dump` of the same moment = **135 nodes**, 1×
  `content-desc="Shorts"`, 4× `content-desc="… – reproduzir Short"`, real ids
  (`browse_fragment_layout_coordinator_layout`, `appbar_layout`, …). Service = 4 nodes.
* **Why the graft cannot help here** (code read, no change): `NodeTreeReader.textGrafts` resolves a
  rule's text/desc regex to literals (`RuleLookup.literalQueries("(?i)(^shorts$| - play short$|
  – reproduzir short$)")` → `shorts`, `- play short`, `– reproduzir short`) and calls
  `findAccessibilityNodeInfosByText` on the window root; the id-graft calls
  `findAccessibilityNodeInfosByViewId`. On this device the **id lookup works** (`idProbe=… results=2`
  finds 2 nodes from the same root) while the **text lookup returns nothing** — i.e. ColorOS prunes
  the id-less subtree that the text search traverses but services the view-id registry. `yt_shorts_shelf`
  declares **only** a `contentDescriptionRegex` (no viewId), so it has no id anchor to graft by and
  stays invisible. This is the precise remaining defect: text-anchored rules on this vendor need a
  different recovery path than `findAccessibilityNodeInfosByText`. The `grafted=1` noted in the D8
  work does not reproduce on YT home.

### R2 — Shorts player in budget — **PASS** (player is now seen and paid)

* Reached via `am start -a android.intent.action.VIEW -d "https://www.youtube.com/shorts/"`. Surface
  checked before judging (`waveAr-03-shorts-player.png`): vertical video, right rail
  `Gosto/Comentários/Guardar/Partilhar/Remix`, `Subscrever` — the Shorts **player**, inside
  `…Shell$HomeActivity`.
* The id graft works on this surface: the scan recovers `reel_recycler`'s node
  (`grafted=2`, 6 nodes, 2 with ids) and `yt_shorts_player` matches every 10 s:

  ```
  11-05 11:40:52.087 FilterService: scan com.google.android.youtube (YOUTUBE): 6 nodes, 2 with viewId, rules=2/8, matched=1, grafted=2, windows=1 [YouTube(0)=3/1 ids], rootChildCount=2, idProbe=menu_item_0=0 app_bar_wrapper=0 results=0
  11-05 11:40:52.089 FilterService: com.google.android.youtube: 1 hit(s) but gate=NONE (budget=false, locked=false)
  ```

  `gate=NONE` with 1 hit is correct in-budget behaviour (`RuleGate.decide`: layer-2 surfaces stay
  NONE until the budget is spent), and the stopwatch still charges the surface.
* Budget measured read-only via `run-as … cat shared_prefs/settings.xml`:
  `budget_used_seconds` **116** (11:35:40) → **212** (11:37:24) after ~100 s of playback, then
  **254** (11:41:19) → **300** (11:42:03) during R3. Player playback is paid ~1 s/s.

### R3 — budget exhaustion at the 5-min limit — **PASS with a deviation** (2 BACKs, not 1)

* **Fixture deviation:** the stepper is 5-min steps and floors at **0 min**, not 5 — 15 taps on `−`
  from 20 min landed on `0 min daily` / `0 min left today` (`waveAr-05-budget.png` is 20 min,
  `waveAr-06-budget-5min.png` is the 5 min acceptance value). Set to exactly **5 min**
  (`budget_limit_minutes=5`, `budget_used_seconds=219` at 11:39, 81 s left) via the Budget screen.
* Watched the Shorts player from 11:40:49 (`waveAr-07-exhaust-start.png`); `used` 219 → 246 → 254
  → **300** at 11:42:03. First neutralise at the moment the budget hit 0:

  ```
  11-05 11:42:03.535 FilterService: neutralising com.google.android.youtube/shorts_player via BACK
  11-05 11:42:04.006 FilterService: com.google.android.youtube/shorts_player: action suppressed by throttle/quota
  11-05 11:42:04.815 FilterService: neutralising com.google.android.youtube/shorts_player via BACK
  11-05 11:42:06.147 FilterService: neutralising com.google.android.youtube/shorts_player via BACK
  ```

  Three neutralise lines: one throttle-suppressed, **two BACK actions actually performed 1.3 s
  apart**. The brief wants exactly one. Effect is still correct: `waveAr-08-exhaust-after.png` is the
  YT home feed (Início, no player), still in `com.google.android.youtube` (no launcher bounce).
* **Stays out, no re-entry loop:** 25 s after the last BACK the FilterService log is quiet (no
  further `neutralising`, no player re-scan) and `budget_used_seconds` stays 300 (the stopwatch
  stopped). Open follow-up: the second BACK is harmless here but would exit the app on a surface
  one level from the root.
* Limit restored to **20** (`waveAr-09-budget-restored.png`); `budget_used_seconds` stays 300 —
  the Budget screen has no reset control.

### R4 — Inspector content capture (arm → capture) — **FAIL content / PASS expiry**

* Rule inspector → YouTube chip (`waveAr-11-inspector-ytchip.png`, amber dot, `YT · shorts player`)
  → arm with the orange `↓` FAB: `Armed — open YT · shorts player or another target app within
  60 s` (`waveAr-12-armed.png`) → YT home (`…Shell$HomeActivity`) → back to Free Social in 12 s.
* A new dump **was** written (`waveAr-13-inspector-captured.png`,
  `com.google.android.youtube-20261105-114607-383.json`) but its header is the ghost tree, not
  real content:

  ```
  "windows": [{"title": "YouTube", "className": "android.widget.FrameLayout", "layer": 0,
               "packageName": "com.google.android.youtube", "nodes": 3, "viewIds": 1}],
  "nodeCount": 4, "nodesWithViewId": 1, "truncated": false
  ```

  All node lines are null-id / null-text (`[0,0][1080,2400]` root + FrameLayout + 1) — **no**
  `com.google.android.youtube:id/…`, no pt-PT strings to sample. Service log for the same capture:
  `armed dump com.google.android.youtube/"YT · shorts player": 4 nodes (1 with viewId, 1 windows:
  YouTube(layer 0)=3 nodes/1 ids) in 39 ms`.
* **Cause (code read, no change):** `FilterService.captureArmedDump` calls
  `NodeTreeReader.captureAll(windows, appPackage)` **without** `viewIdLookups`/`textLookups`, so the
  inspector dump never runs the D8 graft layers the scan path now runs (R2 got `grafted=2` on the
  same package moments earlier). The dump seam needs the same lookup lists as `scan()`.
* **60 s expiry — PASS:** armed at 11:47:20, waited 66 s without switching (`waveAr-14-armed-expiry.png`):
  dumps dir stayed at 4 files (`ls … /files/dumps | wc -l` 4 → 4), no dump written, no
  "expired" line needed because the target app never produced a window-state event.

### Wave A retry result

| # | Scenario | Verdict |
|---|----------|---------|
| R1 | Shorts shelf scroll-past | **FAIL** — shelf on screen (135 uiautomator nodes, `Shorts` + 4× `– reproduzir Short`), service `4 nodes, matched=0, grafted=0` |
| R2 | Shorts player pays budget | **PASS** — `matched=1, grafted=2`, used 116 → 212 over ~100 s |
| R3 | Exhaustion at 5-min limit | **PASS with deviation** — BACK escape at used≥300, stays out; 2 BACKs executed, not 1; stepper floors at 0, steps 5 |
| R4 | Inspector arm-capture | **FAIL content / PASS expiry** — dump written but 4-node ghost header; 60 s expiry writes nothing |

Net: the D8 graft **works wherever a rule declares a viewId** (YT Shorts player: `reel_recycler`,
`grafted=2`) and does **not** work for text/desc-anchored rules on this vendor
(`yt_shorts_shelf` declares only `contentDescriptionRegex`; `findAccessibilityNodeInfosByText`
returns nothing from the ghost root while `findAccessibilityNodeInfosByViewId` on the same root
returns nodes — `idProbe=… results=2`). R1 stays BLOCKED-ENGINE for a *different, narrower* reason
than Wave A, and R4 is a seam bug (armed dump skips the lookup layers).

Final state (verified, `waveAr-15-final-hub.png`): hub on screen, `Filtering on`, three apps
`Filtering`, `Schedule · On · 22:30–07:00`, `Permissions · 4 of 4`, both services bound
(`dumpsys accessibility`: 2 `Service[label=…]`, Content filtering `capabilities=41`), `adb devices`
shows `192.168.50.20:37161  device`. Budget limit restored to **20** (`budget_limit_minutes=20`)
but `budget_used_seconds=300` — there is **no reset control**, the day resets at midnight; the hub
and Budget screen read **`17 min left of 20`** while 300 s used implies ~15, i.e. the displayed
remainder lags the persisted counter (noted, not chased — out of scope for this wave).
Schedule untouched, no reboot, no `am force-stop`, no `settings put`, no code/gradle/git changes.

## Wave A gaps — fixes + recheck

Fixes for the three retry gaps (details in `DEFECT-DIAGNOSIS.md` → *D8 round 3*, rule provenance in
`RULES.md` → *Capture anchors*). Device re-run on the same Reno4, same pt-PT locale, no reboot, no
`settings put`. Final APK built 13:15 (`assembleDebug` after the tests), installed with `install -r`
(lastUpdateTime `2026-11-05 13:15:15`, newer than HEAD `620e74e` at 11:27).

Setup: `budget_used_seconds` reset to **0** in `settings.xml` (verified), then the one allowed
`am force-stop` (the process caches prefs). That unbinds both accessibility services on ColorOS, so
both were re-enabled **by taps** (Settings → Accessibility → Content filtering → *Permitir*;
Whole-app blocking → *Permitir*) and `dumpsys accessibility` showed
`Bound services:{Content filtering capabilities=41 …, Whole-app blocking …}` before any scenario.
Hub after the reinstall: `Filtering on`, three apps `Filtering`, `Permissions · 4 of 4`.

### R1 — Shorts shelf on pt-PT — **PASS** (path (b))

Landing page: YT Home with the shelf on screen (`uiautomator`: `Shorts` header + 4 cards ending
`– reproduzir Short`). Scan line and action on the 12:26 build (the final build only narrowed which
grafts descend, `results` still does):

```
12:29:00.781 scan com.google.android.youtube: 44 nodes, 4 with viewId, rules=2/8, matched=1,
            grafted=40, windows=1 [YouTube(0)=3/1 ids], rootChildCount=2,
            idProbe=menu_item_0=1 app_bar_wrapper=0 results=2
12:29:00.801 neutralising com.google.android.youtube/shorts_shelf via SCROLL_PAST
            (ACTION_SCROLL_FORWARD Bounds(left=0, top=398, right=1080, bottom=2124))
```

`results` is the scrollable container the gesture is framed on (not the 58 px header). Hands-off:
the shelf moved off the top of the feed, the next scans reported `matched=0`, and the app stayed
`…Shell$HomeActivity` (`waveAg-01-before.png`, `waveAg-03-shelf-after.png`). The final build was
re-checked at 13:21 (`56 nodes … grafted=52`, `SCROLL_PAST … ACTION_SCROLL_FORWARD`), shelf gone in
the `uiautomator` dump (`shorts-header 0, chips 0`), no launcher bounce. A 10 s diagnostic rate
limit hides some `scan` lines; actions are never rate-limited, so the `neutralising` lines are the
authority.

Residual (pre-existing, not one of the three gaps): when the shelf match arrives through a *text*
graft with no captured ancestor, `SCROLL_PAST` falls back to the whole window rect
(`Bounds(0,0,1080,2400)`) instead of `results`. It still scrolls the feed and the engine settles
(`matched=0` after), but the frame is less precise than the id-graft path above.

### R3 — budget exhaustion — **PASS** (exactly one BACK)

Limit set to **5 min** on the Budget screen, `budget_used_seconds` accrued **0 → 300** while the
Shorts player was up (12:36:31 → 12:41, ~1 s/s), so `used ≥ limit`. One settled in-budget visit,
logcat captured continuously to a file (the tag buffer rotates fast under YouTube logging):

```
12:51:09.257 scan com.google.android.youtube: 6 nodes, 2 with viewId, rules=2/8, matched=1, grafted=2
12:51:09.264 neutralising com.google.android.youtube/shorts_player via BACK
12:51:11.412 neutralising com.google.android.youtube/shorts_shelf via SCROLL_PAST …   ← now on Home
```

One BACK only; the next scan 0.7 s later was suppressed by the post-escape hold, the screen was the
YT Home feed (`results` present, `reel_recycler` absent, `waveAg-05-r3-after.png`), and the log
stayed free of further `shorts_player` actions for the remaining ~47 s of the window. An earlier
attempt fired its one BACK during the launch transition (12:49:55); same single-action count, but
the player reappeared, so it is not counted as the acceptance observation. Limit restored to **20**
via the Budget screen.

### R4 — Inspector arm-capture — **PASS content** (dump now runs the lookups)

Arm → YT · shorts player → launch YouTube → back. First event was skipped by the new
target-window guard (`armed dump: waiting for com.google.android.youtube (active window is
dev.k3vinb5.freesocial)`); a brief ghost capture was retried and the written dump is:

```
13:16:31.474 armed dump com.google.android.youtube/"YT · shorts player": 9 nodes
             (2 with viewId, 5 grafted, 1 windows: YouTube(layer 0)=3 nodes/1 ids)
header: {"nodeCount": 9, "nodesWithViewId": 2, "grafted": 5, "truncated": false}
node samples:
  com.google.android.youtube:id/results | RecyclerView | [0,290][1080,2052] | grafted=true
  ViewGroup | content-desc "Quer fazer uma pausa?" | grafted=true
  ViewGroup | content-desc "Um lembrete rápido para fazer uma pausa do ecrã" | grafted=true
```

Real ids, pt-PT strings and per-node `grafted` markers are in the JSON — the id/text lookup lists
are the same ones the scan uses. (The captured screen was YouTube's break reminder, hence the pt
strings instead of a feed; a second arm on a settled Home yields the same grafted `results` subtree
that the 44–56-node scans show.)

### Final state

`budget_used_seconds=0`, `budget_limit_minutes=20`, `filtering_enabled=true`,
`budget_date=2026-11-05` (verified by `cat shared_prefs/settings.xml`); hub on screen
`Filtering on`, three apps `Filtering`, `Schedule · On · 22:30–07:00`, `Permissions · 4 of 4`
(`waveAg-06-final-hub.png`); both accessibility services bound; `adb devices` shows
`192.168.50.20:37161 device`. Caveat: the hub/Budget readouts kept `15 min left` for this process
(the app and the service each hold an in-memory `SettingsRepository`; the persisted value is 0 and
the display corrects on the next process start). No reboot, no `settings put`, no second
`am force-stop`.

## Wave C — Instagram + TikTok

Device: same OPPO Reno4 5G, `192.168.50.20:37161`, Android 13 / ColorOS, **pt-PT**, 3-button nav.
APK `479bec3` installed (no rebuild, no reinstall). Run 2026-11-05 14:17–… WET (Thursday; schedule
`22:30–07:00`, so no lock in the window). Start budget: limit 20 min, `budget_used_seconds=0`.
Both apps signed in. Evidence `reverse/runtime-reno/waveC-NN-*.png`, logcat `-s FilterService`,
uiautomator dumps kept in `/tmp/opencode/waveC/` (see per-scenario XML names).
Note: `logcat -c` precedes each scenario so the excerpt belongs to that scenario only.

### C1 — IG feed reel cells — **FAIL** (rule matches, SCROLL_PAST scrolls nothing, re-fires)

* Surface first: IG home feed on `InstagramMainActivity`, story tray + photo post (`SIC Notícias`,
  carousel 2/8) and, after one user swipe, a genuine video post
  `content-desc="antonio_vilaca_pacheco publicou um video 15 de outubro"` with
  `com.instagram.android:id/video_container` + `video_states` at `[0,983][1080,2124]`
  (`uiautomator`, 96 nodes; `waveC-01-ig-feed-reel-before.png`). The rule's anchor is present and
  visibly playing, so the surface is right.
* Scan on that surface finds it through the ID graft:
  ```
  14:18:46.965 scan com.instagram.android (INSTAGRAM): 8 nodes, 5 with viewId, rules=4/8,
              matched=1, grafted=3, windows=1 [Instagram(0)=4/2 ids], rootChildCount=3
  14:18:46.979 neutralising com.instagram.android/feed_reel_cell via SCROLL_PAST
              (gesture dispatched, awaiting completion on Bounds(left=0, top=0, right=1080, bottom=2400))
  14:18:47.224 gesture completed com.instagram.android/feed_reel_cell
  ```
* **But nothing scrolls.** The frame is the whole window `[0,0][1080,2400]`, and this device has
  **3-button** navigation: the nav strip is `[202,2221][1234,2347]`, so a drag that starts at
  `bottom=2400` begins *inside the system nav bar* and the app never sees it. Control test the same
  minute: `input swipe 540 2390 540 800` → the reel cell stays at `[0,909][1080,2124]` (identical
  before/after); `input swipe 540 1500 540 1100` → feed moves. So the geometry, not the rule, is
  why it is inert. The nearest-scrollable-ancestor framing (D1) did not engage: the 8-node capture
  carries no IG feed container id, so `scrollPast` fell through to the largest-ancestor rect.
* **Repeats instead of going quiet:** 5 dispatch lines for the *same* cell in 96 s
  (`14:18:35`, `14:18:46`, `14:19:33`, `14:19:34`, `14:20:11`), each followed by
  `action suppressed by throttle/quota or post-escape hold` for its immediate re-scan — the 1200 ms
  debounce is visible, but the cell never leaves, so the rule keeps trying. Photo posts above it
  were never acted on (`matched=1` is always `feed_reel_cell`), and the app never bounced to the
  launcher (`mCurrentFocus=com.instagram.android/…InstagramMainActivity` throughout).
* Evidence: `waveC-01-ig-feed-reel-before.png`, `waveC-02-ig-feed-reel-after.png`,
  `waveC-03-ig-feed-reel-after2.png`, `waveC-01-ig-reel-stuck.mp4` (18×1 fps screencap timelapse:
  the cell does not move across the engine's dispatch).
* Verdict: **FAIL** — matched + SCROLL_PAST dispatch proven, removal not achieved, no quiet state.

### C2 — IG Reels tab escape + `selected` criterion — **PASS** (escape), **PASS** (quiet on Profile)

* Tap `clips_tab` centre (`324,2141`; `content-desc="Reels"`, bounds `[216,2124][432,2158]` in the
  dump) → Reels opens, the very next scan matches **two** rules and the escape runs 116 ms later:
  ```
  14:26:10.597 scan com.instagram.android (INSTAGRAM): 10 nodes, 6 with viewId, rules=4/8,
              matched=2, grafted=6, windows=1 [Instagram(0)=4/2 ids]
  14:26:10.713 neutralising com.instagram.android/reels_tab via TAP_HOME_TAB
  ```
  The `+1 s` screencap is already the **Home feed** (Home tab filled/selected,
  `waveC-04-ig-reels-tapped-1s.png`), and 3 s later still Home
  (`waveC-05-ig-after-reels-tap.png`). Still in `com.instagram.android` the whole time, so the
  resolver used the **known id `feed_tab`** path, not `GLOBAL_ACTION_HOME` — if it had fallen back
  the launcher would be focused. **Log gap:** the build prints only `via TAP_HOME_TAB`; there is no
  line naming the resolution path (`FilterService.report`), so the target is inferred from the
  screen, not stated.
* **`selected` criterion:** tapped `profile_tab` (`972,2141`), dump confirms
  `profile_tab selected="true"`, `feed_tab selected="false"`, `clips_tab selected="false"`
  (`waveC-06-ig-profile.png`, `/tmp/opencode/waveC/wc2-profile.xml`), `logcat -c` then 7 s
  untouched → **zero FilterService lines**. Merely showing the tab bar on Profile does not fire.
