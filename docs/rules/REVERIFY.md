Supersedes the cancelled run of 2026-11-05; all scenarios re-run clean.

# Re-verification — §6 acceptance regrade on OPPO Reno4 5G (run-and-report)

Status: **IN PROGRESS** — device-driver evidence pass. No code changes, no gradle, no commits.

Device: OPPO Reno4 5G (`LAN-IP-REDACTED:37161`), Android 13 / SDK 33, ColorOS, 1080×2400.
APK `7f530f7` already installed (versionName 0.1.0, lastUpdateTime 2026-11-05 07:58:04).
Evidence: `reverse/runtime-reno/`.

Fixes under test: SCROLL_PAST on scrollable ancestors (forward-first); `tiktok_playback`
re-anchored on visible FYP chrome; single-action escape-priority gate + `selected` criterion;
enforcement-truth hub banner; arm-then-capture inspector.

*(Sections appended as each scenario completes.)*

## Wave A — YouTube + budget (YT-only)

Device: OPPO Reno4 5G, `LAN-IP-REDACTED:37161`, Android 13 / ColorOS, 1080×2400. APK `7f530f7`,
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

Device: same OPPO Reno4 5G, `LAN-IP-REDACTED:37161`, Android 13 / ColorOS. Run 2026-11-05
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
`2026-11-05 22:30:00`. `adb devices` shows `LAN-IP-REDACTED:37161  device`. No reboot, no
`am force-stop`, no `settings put`, no code/gradle/git changes.

## Wave A retry — pt-PT content behaviors

Device: same OPPO Reno4 5G, `LAN-IP-REDACTED:37161`, Android 13 / ColorOS, pt-PT. APK built from
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
shows `LAN-IP-REDACTED:37161  device`. Budget limit restored to **20** (`budget_limit_minutes=20`)
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
`LAN-IP-REDACTED:37161 device`. Caveat: the hub/Budget readouts kept `15 min left` for this process
(the app and the service each hold an in-memory `SettingsRepository`; the persisted value is 0 and
the display corrects on the next process start). No reboot, no `settings put`, no second
`am force-stop`.

## Wave C — Instagram + TikTok

Device: same OPPO Reno4 5G, `LAN-IP-REDACTED:37161`, Android 13 / ColorOS, **pt-PT**, 3-button nav.
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

## Wave D — policy acceptance

Device: OPPO Reno4 5G, `LAN-IP-REDACTED:37161`, Android 13 / ColorOS, 1080×2400, pt-PT UI,
2–3 s pacing. APK `7f530f7` (unchanged); driver at `e6d7fce`. No app code changed, no commits,
**no reboot, no `am force-stop`**. Every run: `uv run tools/device/drive.py run <scenario>`
(exit 0 = PASS) with evidence under `reverse/runtime-reno/driver-<ts>/<scenario>/`.

Standing fixtures: budget limit/used driven through the app UI where the scenario needs a
zero-minute budget; prefs written only through `run-as` and recorded in each `steps.log`
(`FIXTURE prefs write …`). Log windows are device-clock stamps (`policy.log_stamp`) and action
lines are the `ACTION_RE` set (`neutralising`, `gesture completed/failed`, `ACTION_SCROLL_FORWARD`,
`action failed`); scans and gate decisions are *not* actions.

### D1 — `exhaustion_consistent` — **PASS** (3/3 escapes, identical shape)

* Budget driven to **0 min** via the UI stepper (`budget limit now 0 min (target 0)`), then three
  fresh IG Reels entries: `entry 1/2/3: escaped=True actions=1 clips_tab_selected=False
  foreground=com.instagram.android`, `outcomes=[True, True, True] total_actions=3 entries=3`.
* No allow-then-deny: every entry produced exactly one escape action and never left Instagram.
* Evidence: `driver-20261105-182815/exhaustion_consistent/` (`engine-entry-{1,2,3}.log`,
  `01..06-*.png`, `07-final-pass.png`). Budget restored to 20 min at the end.

### D2 — `direct_entry_pays` — **PASS** (gate=PAY, no escape)

* Budget before entry `{used: 0, limit: 20}`, after entering Reels `{used: 12, limit: 20}`
  (delta **12 s**); `gate=PAY` line present; app never bounced out of Reels.
* Evidence: `driver-20261105-183112/direct_entry_pays/` (`engine-window.log`, `05-final-pass.png`).

### D3 — `dm_carveout_structural` — **LOCALE-GAP** (not a policy verdict)

* TikTok FYP on this build is undumpable (`02/05-dump-failed.png`, `window-structure.txt`), so
  there is no semantic route into the DM inbox; 10 s on the FYP produced **24 engine lines,
  0 actions**. Carve-out stays unproven, not disproven.
* Evidence: `driver-20261105-181138/dm_carveout_structural/`.

### D4 — `passive_feed_untouched` — **PASS** (zero actions across 3 deliberate swipes)

* YT Home, window opened at `11-05T18:36:41`; three swipes inside `results` at 2.5 s spacing;
  **12 engine lines, 0 action lines**; foreground still `com.google.android.youtube`;
  `results` still visible after the window closed.
* Evidence: `driver-20261105-183632/passive_feed_untouched/` (`engine-window.log`,
  `01-yt-home-before.png` … `05-yt-still-home-clean.png`).

### D5 — `capture_pause_filter` — **PASS** (paused, not racing)

* Guided capture armed from *Surfaces & rules → Instagram → Home feed*; the app launched IG, the
  scenario swiped and tapped a `video_container` cell during the armed window.
* **26 engine lines, 0 action lines** for the whole guided window; finish line
  `guided capture com.instagram.android/feed_reel_cell: 9 nodes, verified=true,
  file=com.instagram.android-20261105-184025-156.json`; inspector banner
  `Capture complete — 9 nodes.`; dump header `"surface": "feed_reel_cell", "surfaceVerified": true,
  "nodeCount": 9, "grafted": 4`.
* Caveat recorded: tapping into the feed video cell changed the detected surface, so the session
  auto-stopped at ~6 s (18:40:19 → 18:40:25) per `docs/rules/CAPTURE.md` §6 instead of running the
  full 15 s. Zero actions still holds across the entire arm.
* First attempt `driver-20261105-183724` aborted before any capture: IG had resumed into a
  full-screen Reels player left by the previous wave, so `uiautomator dump` could not reach idle
  (`02-dump-failed.png`). Prep (back to IG Home feed) and re-run only — no scenario edits.
* Evidence: `driver-20261105-183953/capture_pause_filter/` (`engine-window.log`, `newest-dump.json`,
  `04-ig-during-capture-video.png`, `06-inspector-capture-complete.png`).

### D6 — `capture_match_list` — **PASS** (`ig_reels_tab` in the analysis)

* Guided capture on the IG Reels-tab surface: `guided capture com.instagram.android/reels_tab:
  10 nodes, verified=true, file=com.instagram.android-20261105-184350-119.json`; dump header
  `"surface": "reels_tab", "surfaceVerified": true, "nodeCount": 10, "grafted": 6`; **0 action
  lines** while armed (gate decisions only).
* Expanding that dump in the inspector shows **Rules that match this dump** with
  `ig_reels_tab · reels_tab` (`05-analysis-match-list.png`), closing Wave 2/C2's no-match gap.
* Evidence: `driver-20261105-184245/capture_match_list/`.

### D7 — guided-capture notification (bonus) — **NOT RENDERED on ColorOS**

* Notification permission is granted (`POST_NOTIFICATIONS: granted=true`; hub *Permissions 4 of 4*)
  and the record is posted: `dumpsys notification` shows
  `pkg=dev.k3vinb5.freesocial … channel=node_capture … importance=2`,
  `android.title=String (Node capture)`, text `Surface left — capture stopped at N nodes`
  (`reverse/runtime-reno/waveD-notification/notification-dumpsys.txt`).
* It does not appear in the shade on this firmware: two fresh captures + shade expansions
  (`reverse/runtime-reno/waveD-notification/01-recording-notification{,-fast}.png`,
  `02-shade-scrolled.png`) show the
  system/Instagram/YouTube/OneDrive/Nextcloud rows but never the Free Social one, while the record
  stays live. The screenshot is also self-defeating: expanding the shade itself wins the window
  focus, so the session ends as `Surface left — capture stopped` (text captured above).
* Verdict: **bonus not obtainable**; the notification's existence/props are evidenced, its
  rendering is a ColorOS shade behavior, not app behavior.

### D8 — fixture writes, prefs cache, driver fixes

* Fixtures this wave: exhaustion set `budget_limit_minutes` 20→0 through the app stepper and back
  to 20; `policy.prefs_fixture` attempted `budget_used_seconds=0` / `budget_limit_minutes=20` /
  `budget_date=2026-11-05` through `run-as` (logged as `FIXTURE prefs write …`).
* **`policy.prefs_fixture` does not land on this device.** Observed: after the logged fixture the
  read-back still returned the old `used=397` and `settings.xml`'s mtime had not changed, i.e. the
  write silently no-op'd (the `sh -c "… | base64 -d > …"` argv is re-joined by `adb shell`, so the
  redirect/mv did not run under `run-as`). The final restore was therefore executed as one quoted
  run-as command (`cat > shared_prefs/settings.xml.new` on stdin, then `mv`) and verified: on disk
  `budget_used_seconds=0`, `budget_limit_minutes=20`, `budget_date=2026-11-05`
  (`reverse/runtime-reno/waveD-final/settings-final.xml`, `03-hub-final-restored.png`).
* **Prefs-cache caveat (recorded, not worked around):** an external prefs write lands on disk but
  the running process keeps its `SharedPreferences` map; only a process restart (explicitly *not*
  done) reloads it. Visible right now: disk says `used=0` (20 min left) while the live hub still
  reads **16 min left** from the cached value; the engine will keep using the cached budget until
  the next process start. The write is also not durable against the app itself: after the first
  successful restore (`used=0`), re-foregrounding IG to check it sat on Home let the app flush its
  cache back to `used=443`, so the final write below was made as the **last** device action.
* Driver bug fixes carried by `e6d7fce` (needed for these runs):
  1. wall guard read a nonexistent `Node.cls` → `Node.class_name` (the `EditText` password-field
     signal never matched before);
  2. `app_settings_read` ignored typed prefs (`<int>/<long>/<boolean>` carry `value=` attributes),
     so every budget read returned 0 — it now falls back to the element's `value`.

### Wave D final state

* Budget: on disk limit **20** / used **0** / date `2026-11-05`, written as the last action and
  re-read 15 s later with no UI interaction
  (`reverse/runtime-reno/waveD-final/settings-final.xml`); live engine still cached (hub shows
  16 min left — see D8).
* Schedule **untouched**: `schedule_enabled=true`, `schedule_day_4_wake=420`,
  `schedule_day_4_bedtime=1350`, `wind_down_minutes=0`, `morning_lock_minutes=0`; hub reads
  `Schedule · On · 22:30–07:00`.
* Apps left on their home screens (IG dump: `feed_tab selected="true"`, `clips_tab
  selected="false"`; YouTube Home; then launcher reached); Free Social hub brought to front last
  (`reverse/runtime-reno/waveD-final/05-hub-final-verified.png`), both services bound:
  `Enabled services:{{…filter.FilterService}, {…block.BlockService}}`.

## Final tally — direct-engagement acceptance (2026-11-05)

Note: the original §6 item list predates the direct-engagement redefinition
(user steer 2026-11-05). Passive-surface items are SUPERSEDED by policy
(retired rules), not failed. This tally keys on the behaviours the policy
defines, each with its evidence.

| Behaviour | Verdict | Evidence |
|---|---|---|
| Passive feed surfaces untouched (no auto-scroll) | PASS | driver-20261105-183632: 3 swipes, 12 engine lines, 0 actions |
| In-budget direct entry pays (watch + accrue) | PASS | driver-20261105 Wave D: gate=PAY, used 0→12 s, 0 escapes (D9 regression closed) |
| Exhausted entries escaped consistently (N taps = N escapes) | PASS | exhaustion_consistent: 3/3 TAP_HOME_TAB to Home, no allow-then-deny |
| Filter pauses during guided capture | PASS | driver-20261105-183953: 26 engine lines / 0 actions while armed |
| Guided capture surface-verified + analysis ("rules that match") | PASS | guide-07…12 (44 nodes, surface=home verified); driver-20261105-184245 (`ig_reels_tab · reels_tab` listed) |
| Rule heal via export/import (no new APK) | PASS | W2 smoke: "Rules imported — 8 active", engine cache dropped, live on next event |
| Locale-proof rules (language independent) | PASS (suite) | loader rejects text-only rules; enabled rules keyed on ids/classes; 215 tests green |
| Whole-app Block mode + re-detection | PASS | Wave B: block 0.9 s after launch, 3/3 re-detects |
| Nightly lock + exact-alarm boundary flip | PASS | Wave B: 0 s drift, re-armed; waveB-flip.mp4 |
| Doze survival | PASS | Wave B B3: services bound, PID stable, ACTIVE |
| Hub enforcement truth + Permissions 4/4 | PASS | items 11 / setup pass; banner live |
| TikTok DM carve-out (over-block check) | **PASS** (re-verified 2026-11-07) | Live 0-budget run on Reno4 / TikTok 47.6.3 / pt-PT: DM thread `ui_slot_input_layout_redesign`, no `player_view`; shared-video `DetailActivity` exposes `player_view` + `video_seek_bar` **and** the composer `udm` (Button, "Enviar mensagem para Carol…"), and the engine logs `2 hit(s) but gate=SKIP (budget=true)` on every scan — no `neutralising`, no flash (`reverse/runtime-reno/flash-dm/after-tt-dm-thread.xml`, `after-tt-dm-video.xml` 163 nodes, `after-tt-dm-logcat.txt`, screenshot `after-tt-dm-video.png`). Residual: the very first capture after a cold app-switch resume can be a partial tree (3–19 nodes) without `udm` and escapes once before the composer renders (observed 16:50:22 in the same slice; filed as a ticket). Steady state is SKIP. |
| Capture notification visibility | DEVICE QUIRK | ColorOS never renders the posted capture notification in the shade (record exists: channel=node_capture); opening the shade ends capture ("Surface left"). In-app step card is the visible guide on this device. |

Fixes landed this day (all committed): D9 gate root cause + direct-engagement
policy (6b69fc2); capture UX + heal (58f0693); locale-proof invariant (b1a5dbc);
driver + 2 driver bug fixes (e6d7fce). Known accepted gaps: hub stale budget
until process restart (dual cache; decisions always use live values); yt_shorts_shelf
UNPROOFED (retired by policy); TikTok DM carve-out live check (above).

Delivery: ~/Nextcloud/FreeSocial/free-social-0.2.0-e6d7fce.zip (APK + README).

### Addendum (2026-11-05 evening, D10 finding): the `exhaustion_consistent` PASS above
was PARTLY ARTIFACTUAL — uiautomator dumps cause an accessibility rebind, which
reloaded settings mid-test and made fixture writes appear live. True behavior at the
time was the stale-snapshot defect (D10). After the settings-propagation fix the
scenario must be re-run WITHOUT relying on dump-triggered rebinds (dump after asserts).

# 0.3.0 verification — Reno4 end-to-end (feature set at d6ef75c, 2026-11-06)

Device: OPPO Reno4 5G, `adb -s DEVICE-SERIAL-REDACTED` (USB; fallback `LAN-IP-REDACTED:5555`), Android 13 / SDK 33.
APK built from HEAD `d6ef75c` (`versionName` still `0.1.0` — see item 6: the release label is 0.3.0,
the manifest version is not bumped, so the staleness banner must be judged on versionName).
Evidence: `reverse/runtime-reno/verify-030/`.

*(Filled in as each item completes.)*

## Item 0 — the Block chip writes its mode on every tap — **PASS**

The wave's unresolved flag (two Block taps that left `app_mode_com.instagram.android` untouched)
does **not** reproduce when the tap is resolved from a fresh dump of the chip node.

* `tools/device/scenarios/app_mode_chip_persistence.py` (PASS, 67.3 s, evidence
  `verify-030/scenario-blockchip2/`): three deliberate trials — each preceded by its own dump,
  followed by a prefs read-back and an Instagram launch, with `Filter` / `Allow` taps interleaved
  so the Block tap always changes the state (mirrors the wave's sequence).
* The Block text node is `clickable=False` at `[492,496][589,542]` inside the pill; the tap point
  `(540,519)` lies inside the chip's own bounds, so the hit target is correct — case **(b)**
  (missed chip) ruled out.
* trial 1 `filter → block`: pref `filter` → `block`, block screen **0.96 s**
* trial 2 `Filter` chip → `Block`: pref `filter` → `block`, block screen **0.76 s**
* trial 3 `Allow` chip → `Block`: pref `allow` → `block`, block screen **0.90 s**

Every tap wrote the pref *before* the launch and the very next launch was blocked, so neither case
**(a)** (state wiring) nor case **(c)** (settings propagation) reproduces. Screens before/after each
tap and the dumps: `scenario-blockchip2/01…09-*.png` / `*.xml`; engine excerpt per trial
(`matched`, `neutralising`, block latency): `scenario-blockchip2/logcat-FilterService-080435/080537/080555.txt`.
An earlier run (`scenario-blockchip/`) aborted in the *harness* after "Go home" (the app is
backgrounded there, so the next detail-screen lookup failed) — not a product failure; the scenario
now relaunches Free Social between trials. No code change needed; the scenario is kept as the
permanent regression guard.

## Item 1 — instant settings, no app restart — **PASS**

Headline behaviour: tapping a mode chip on the AppDetail screen is in force on the **next**
launch of the target app, with no Free Social process restart and no engine bounce.

Process continuity (proves "no restart"): `dev.k3vinb5.freesocial` PID **20107** started
2248 s before the check (`/proc/20107/stat` field 22 = 13932148 ticks, `/proc/uptime` =
141569 s, 07:58:47) — i.e. it has been up since the APK install at 07:21 and never restarted
during the whole sequence. Instagram PID **1361** started 3965 s before the check (06:52), so
the target app was *relaunched*, never killed/restarted
(`reverse/runtime-reno/verify-030/00-process-ids.txt`).

| Step (07:29–07:31) | Observed | Evidence |
|---|---|---|
| IG detail → **Block** chip → `am start` IG | `BlockActivity` displayed **1.03 s** after the tap (`Displayed …BlockActivity: +262ms`); topResumedActivity = `ui.BlockActivity` | `10-instant-block.png`, `10-instant-block.logcat.txt`, scenario-instant2/01 |
| → **Allow** chip → launch IG | IG `MainTabActivity` foreground; engine line `com.instagram.android is not filtered (master off or monitor mode)`; **zero** `neutralising` / `escape flash` | `12-instant-allow.png`, `12-instant-allow.logcat.txt` |
| → **Filter** chip → launch IG | IG foreground, no block screen; engine scanning (`scan com.instagram.android … matched=2`) | `14-filter-pay.logcat.txt`, `15-filter-reels-playing.png` |
| Budget variant: stepper 20→**5**, watch Reels | used counted 58 s → 301 s while watching; at **07:41:27.952** `ContentFlashActivity` STARTed and the Reels surface escaped to the IG feed — no user action, no restart | `16-budget-5.png`, `17-item1-watch-start.png`, `18-item1-escape.logcat.txt` |

Note (honesty): the 07:41 flash's own `FilterService` lines were lost when the main log
buffer wrapped (ColorOS `main` buffer is small and rotated before the excerpt could be
saved); the launch is quoted from the live `logcat -v time` read taken immediately after
(ActivityTaskManager 07:41:27.952 `START … ContentFlashActivity`). The flash mechanics at
exhausted budget are re-proven with a tight capture in Item 2.

Instant-settings regression guard: `tools/device/scenarios/instant_settings_mode.py`
(PASS, 54.3 s, evidence `scenario-instant2/`): block screen at **1.03 s** (budget 2.0 s),
Allow = "not filtered" + 0 actions, Filter = engine scanning + no block.

## Item 2 — escape flash — **PASS**

`tools/device/scenarios/flash_escape_visibility.py` (PASS, 33.2 s, evidence
`scenario-flash4/`), and the copy was also captured by hand at 07:46 (`scenario-flash/01`).

* Flash appears **0.80 s** after the Reels entry at exhausted budget; on screen **2.12 s**
  (2.2 s `FLASH_DURATION_MS`, ≤ 2.5 s budget), then auto-dismissed.
* Copy verified on a mid-show screencap: title **"Free Social blocked this"**, reason
  **"Reels & Shorts budget used up · resets at midnight"**, caption "You can watch again
  after the budget resets." (`scenario-flash4/01-flash-mid-show.png`).
* One flash per episode: a second entry issued the instant the flash was dismissed
  (< 3 s after the first) produced **1 `flash throttled`** line, **no** extra
  `escape flash` line and **no** second flash on screen.
* Detection note: `dumpsys activity` keeps a finished flash's task record for ~0.9 s;
  the scenario measures lifetime from `mCurrentFocus` to avoid that artifact (an earlier
  run read 2.94–3.19 s and was a measurement artifact, not a product regression).

## Item 3 — exhausted-budget escape sweep — **PASS**

Setup through the app UI: the allowance stepper was taken to its floor (`20 → 5 min`); used was
already **698 s** > 300 s, so the budget was exhausted on the spot (`50-item1-exhausted-5min.png`,
"0 min left today").

| entry | evidence | observed |
|---|---|---|
| IG **Reels tab** | `54`, `55-item1-reelstab-entry2.logcat.txt` | 08:26:54.138 `neutralising com.instagram.android/reels_tab via TAP_HOME_TAB`, then **one** `escape flash … reason=BUDGET`; 15 s watched, nothing else |
| IG **feed-cell → viewer** | `56`, `57`, `58`, `59-item1-feedcell.logcat.txt` | 08:30:58.475 `matched=1`, `neutralising …/clips_viewer via BACK`, **one** flash; the follow-up scan logged `duplicate action for the current surface episode` |
| **YT Shorts player** | `60`, `61`, `62-item1-yt-shorts.logcat.txt` | 08:32:08.271 `matched=1`, `neutralising …/shorts_player via BACK`, **one** flash; focus returned to YT `Shell$HomeActivity` |

No allow-then-deny alternation on any entry: all three matched and escaped on the first scan.

Honesty note: the first Reels-tab run (`51/52/53`) produced **two** flashes from one entry
(08:24:36.246 and 08:24:45.325) with a `connected, rules=8` a11y rebind between them. That rebind
was the tail of the `uiautomator dump` taken just before the tap (the known dump-triggered rebind)
and the re-bound engine re-matched the cached Reels tree; the clean repeat without a preceding dump
fires exactly once (08:26:54). Instrumentation artifact, but worth a dedicated no-dump test if the
pattern recurs.

## Item 4 — TikTok live: FYP pay + exhausted escape — **FAIL (as found)**

> **Update 2026-11-06:** the stale override this FAIL blames has since been cleared from the
> device. See *Re-verify after override clear* at the end of this file — the first attempt was
> blocked on a user-only ColorOS dialog, then resolved by installing the HEAD APK (which restarts
> the process and rebinds the services). Item 4 now **PASSES**: `matched=1` and a rising
> `budget_used_seconds`. The FAIL below stands as the original record.

Mid-range (limit 20 min, used 698 s): a TikTok FYP video was playing (`Para Ti` selected,
`40-item4-tiktok-fyp.png`) for ~2 min. Every scan reported `matched=0` and
`budget_used_seconds` stayed **698** — no `tiktok_playback` hit, no accrual:

```
08:10:53 / 08:11:04 / 08:14:30  I/FilterService: scan com.zhiliaoapp.musically (TIKTOK):
    64 nodes, 37 with viewId, rules=2/8, matched=0, grafted=3, windows=1 [TikTok(0)=60/34 ids]
    com.zhiliaoapp.musically: 0 hit(s) but gate=NONE (budget=false, locked=false)
```

Cause — the device is running a **stale user rule override**, not the shipped asset:
`Surfaces & rules → Rules in use` reads **`Your override (rules-override.json)`**
(`42-item4-rule-source.png`), and `files/rules-override.json` (written 2026-11-05 16:47, copied to
`43-item4-device-rules-override.json`) still holds the **pre-re-anchor** rule:

```json
"match": { "viewId": "com.zhiliaoapp.musically:id/player_view",
           "classNameRegex": "android\\.view\\.(TextureView|SurfaceView)|android\\.widget\\.VideoView" }
```

The shipped asset at `d6ef75c` dropped the class criterion (`app/src/main/assets/rules.json`); the
override still ANDs it, and this build exposes no video-surface node — the engine's own capture has
`player_view` **visible** at `[0,110][1080,2121]` as `android.widget.FrameLayout` and no
TextureView/SurfaceView/VideoView (`reverse/runtime-reno/anchor-audit/tt-b-midplay.json`), so the
rule can never match. `RuleOverride.select()` gives a *valid* override full precedence over the
asset (`filter/RuleOverride.kt`), and the file survives the APK update, so the 0.3.0 TikTok fix
never reaches this device.

Consequence at exhausted budget: entering the FYP did **not** escape — `08:32:44 matched=0`, video
still playing at `08:32:52` (`70-item3-tiktok-fyp-exhausted.png`, `71-item3-tiktok-exhausted.logcat.txt`).
Also logged during that scan: `E/ANR_LOG … Blocked msg … cost=3105 ms` on the service handler.

The re-keyed rule itself is right at unit level (`TikTokPlaybackRuleTest`, `LocaleProofRulesTest`);
the failure is device state (stale override), reported here rather than fixed (user data, outside
this wave's file scope). The recorded "photo post also matches by id" trade-off of the re-key is
moot while the override keeps the rule from matching at all.

## Item 5 — no-leak sweep at exhausted budget — **FAIL (TikTok only)**

> **Update 2026-11-06:** same addendum as Item 4 — override cleared, and the sweep **now PASSES**
> after the HEAD APK install: all three short-video surfaces escape at exhausted, TikTok included
> (*Re-verify after override clear*, end of file). The FAIL below stands as the original record.

One continuous window, 08:32:38–08:33:31 (~53 s), exhausted budget throughout, deliberate entries on
all three apps (`72-item3-sweep-ig-yt.logcat.txt`):

* **TikTok FYP — LEAK**: launched 08:32:38, scan 08:32:44 `matched=0`, video playing at 08:32:52
  (~14 s), no action line at all. Same stale-override cause as Item 4.
* **IG clips viewer**: escaped 08:33:17.7 (`neutralising …/clips_viewer via BACK` + flash); the
  next entry at 08:33:20.2 escaped again with `escape flash suppressed (one per 3000 ms)`.
* **YT Shorts**: escaped 08:33:26.4 (`matched=1`, `neutralising …/shorts_player via BACK` + flash).

No IG or YouTube short video played >2 s without an escape; TikTok played ~14 s with none.

## Item 6 — staleness banner — **PASS**

* Installed Instagram `versionName` **454.0.0.54.77** (`dumpsys package com.instagram.android`).
* AppDetail shows `Rules verified for Instagram 454.0.0.54` and **no** stale banner
  (`80-item6-appdetail-staleness.png`). The verdict compares the asset stamp
  `Instagram 454.0.0.54.77` with the installed string → segment-equal → **FRESH**, so the absence
  of the banner matches the verdict. (The displayed copy is the hand-written `rulesVersion` label
  and drops the `.77`; it is a label, not the compared stamp.)
* Flagged, not fixed: Free Social's own `versionName` is still **0.1.0** while the packaging labels
  say 0.3.0, so no build check can tell the 0.3.0 APK from an older one.

## Final state restore

* Budget: **limit 20 min**, used **698 s** of 1200 s — hub reads "9 min left"
  (`90-item5-final-hub.png`, `91-item5-final-state.txt`). The Budget screen has **no reset-usage
  control** (only "Resets at midnight"), so "used 0" is unreachable through the app UI; the counter
  is left exactly as the wave found it.
* Modes: IG `filter`, YT `filter`, TikTok unset → default `filter`; all three hub rows read
  "Filtering", IG detail shows the `Filter` chip selected.
* Apps: IG on its home feed, YT on `Shell$HomeActivity`, TikTok left — none parked in a player.
* Front: Free Social hub with `Filtering on`; both accessibility services bound —
  `dumpsys accessibility` Bound services = `Content filtering` (FilterService) + `Whole-app
  blocking` (BlockService).

## Re-verify after override clear — 2026-11-06 (verify-030, items 4 + 5)

Dated follow-up to the two FAILs above. Device `DEVICE-SERIAL-REDACTED` (USB), branch HEAD `e1bf545` during the
run, installed APK `lastUpdateTime 2026-11-06 07:21:17` (`versionName` still mislabelled `0.1.0`).
Goal: delete our own W2-import residue (`files/rules-override.json`), force a live engine reload
without a restart, and re-run items 4/5.

**Outcome of the first attempt (§1–§5 below): the override is gone, but the live reload the re-run
needed was blocked on a user-only ColorOS confirmation dialog, so items 4 and 5 were left
UNVERIFIED. Those paragraphs are kept verbatim as the record of what was tried.**

**Outcome of the unblock (addendum subsection 7): an `adb install -r` of the HEAD APK — which ends
the app process and makes the system rebind both accessibility services — cleared the cached
selection with no dialog, no toggle and no consent tap. `Rules in use` now reads `Built-in rules`,
and items 4 and 5 both pass: `matched=1` for `tiktok_playback` with a rising budget, and all three
short-video surfaces escape at an exhausted budget. The original FAILs above are untouched.**

### 1. Override cleared — **PASS**

`run-as dev.k3vinb5.freesocial ls -l files/` → `rules-override.json`, 11766 B, 2026-11-05 16:47 (the
same file `43-item4-device-rules-override.json` recorded). Content re-captured to
`92-override-before-rm.json` before deleting: 8 rules, and `tiktok_playback` still carries
`"viewId": "…/id/player_view"` **AND** `"classNameRegex": "android\\.view\\.(TextureView|SurfaceView)|android\\.widget\\.VideoView"`
— i.e. exactly the pre-re-key rule. Then `rm files/rules-override.json`; `ls -l files/` afterwards
shows only `profileInstalled`. Nothing else in `files/` or `shared_prefs/` was modified by the
deletion.

### 2. The deletion is not observed live — the dual cache, measured

`RuleCatalog` keeps `current`/`source` in process memory and `EngineBootstrap.deps` holds the
compiled `RuleSetMatcher`; `RuleCatalog.load()` only runs from `FilterService.loadEngine()`, i.e.
from `onServiceConnected` or from `RuleCatalog.RuleReload.wake()`. Nothing re-reads the directory.

Measured, in order:

* 08:48 `Surfaces & rules → Rules in use` still reads **`Your override (rules-override.json)`**
  with the override path beneath it (`94-rule-source-before-reload.png`).
* 08:49, minutes *after* the file was deleted and with the app freshly brought to the front, the same
  screen still reads **`Your override (rules-override.json)`**
  (`95-rule-source-still-override-after-file-rm.png`) — the file is gone from disk, the cached
  selection is not.
* The process has been alive since `07:21:18` (`ps -A -o PID,STIME`), i.e. since the APK install,
  so the cached selection is the one taken at that moment — the override.

So the engine is still running the 2 TikTok rules of the override, and the 0.3.0 asset fix cannot
reach it by deleting the file alone. This is memory #274's dual cache, now with direct evidence.

### 3. The reload gate — ColorOS confirmation dialog, stopped (BLOCKER — superseded by subsection 7)

The live reload needs the accessibility service to reconnect. Attempted in the order the brief
gives:

1. `settings put secure enabled_accessibility_services …` (idempotent write of the current value,
   purely to test writability) → `SecurityException: … requires:android.permission.WRITE_SECURE_SETTINGS`,
   exit 255, setting unchanged. Confirmed denied on this build, as recorded.
2. UI path: Settings → Acessibilidade → **Content filtering** → toggle ON at 08:51
   (`96-phase1-toggle-on-before.png`). Tapping it at 08:52 raised the ColorOS confirmation
   **“Desligar Content filtering? / Se você tocar em Parar, o Content filtering será interrompido.”**
   with `Cancelar` / `Parar` (`97a-phase1-colourOS-disable-confirm.png`, the dialog itself).
   Re-enabling afterwards is the step that grants the service device-wide control, so honouring
   this dialog means consenting on the user's behalf. **Stopped here and cancelled** — `Cancelar`
   tapped, no state changed: both services still `Ligado`, `enabled_accessibility_services`
   unchanged, `dumpsys accessibility | grep -c freesocial` = **2**, and both services still Bound
   (`97-phase1-colourOS-disable-confirm-cancelled.png`, toggle back to `Ligado`).
3. The in-app alternative does not exist on this install: `RuleCatalog.revertOverride()` is wired
   to a **“Use built-in rules”** button that ships only from `e1bf545` (HEAD). The installed APK's
   `resources.arsc` contains `Your override (%1$s)` and `Built-in rules` but **not**
   `Use built-in rules`, and the screen shows no such button above Export/Import
   (`95-rule-source-still-override-after-file-rm.png`). No source was changed to route around this.

`adb shell am kill` / a reinstall would reboot the process, which the run forbids, so no load path
remained. **The user has to tap the toggle (or restart the app) once; a single tap is all that is
missing.**

What the reload would apply is not in doubt: the installed APK's own `assets/rules.json` already
carries the fixed rule — 8 rules, `tiktok_playback` = `viewId …/player_view` +
`contentDescriptionRegex "…for you…|like video…"` with **no** `classNameRegex` — identical in shape to
`app/src/main/assets/rules.json` at HEAD. The asset is right; only the cached selection is
wrong.

### 4. Items 4 and 5 — not re-run at the time (superseded by subsection 7 — both now PASS)

No FYP scan was taken after the clear: with the stale override still selected in memory,
`matched=0` is a foregone conclusion and any evidence would restate the FAIL above under a new
name. Item 4 (mid-range pay + `budget_used_seconds` rising) and item 5 (exhausted escape + the
three-app sweep) stay **UNVERIFIED pending one reload**. When it is done, the check is unchanged:
`matched=1` for `tiktok_playback` on a playing video and a rising `budget_used_seconds`; the photo
post trade-off of the re-key then needs re-recording.

### 5. Phase 3 — the `run-as` + internal-write reset does **not** work (measured)

The reset recipe must be recorded as **invalid**, not as a recipe. Measured at 09:00–09:01:

* before: `shared_prefs/settings.xml` → `budget_used_seconds=698`, `budget_date=2026-11-06`,
  `budget_limit_minutes=20`; hub and Budget screen both read **9 min left** (`99-budget-before-reset.png`).
* wrote `budget_used_seconds=0` (date already today) through `run-as` (`cp` from
  `/data/local/tmp`, then `chmod 660`); disk verified `0`.
* immediately afterwards the Budget screen still read **9 min left** — navigating away and back
  (which builds a fresh `SettingsRepository`, since `SettingsRepository.from()` is not a singleton)
  still read 9 min, because the *process-wide* `SharedPreferences` map is the cache, not the file
  (`99b-budget-disk-zero-not-live.png`).
* then one internal write — the limit stepper `+` then `−` (the stepper moves **5 min per tap**,
  measured in 7.5 below) — and the disk **went back to
  `budget_used_seconds=698`**: `SettingsRepository` writes the whole in-memory map back, clobbering
  the external edit. Same conclusion as D8 above, now with the clobbering step captured.

Consequences: `used 0` cannot be made live on a running process (no reset control in the UI, a
restart is forbidden), and a disk-only `0` is not durable — the next app write of any setting or
any counted second restores `698+n`. Final device action was therefore the `run-as` write of
`budget_used_seconds=0` / `budget_date=2026-11-06` with `budget_limit_minutes=20`, re-read from disk
15 s later unchanged (`98-final-state.txt`, `98-final-prefs.xml`). **Disk says 0, the live cache
says 698 (9 min left)** — the divergence is real, expected from the above, and resolves at the next
process start. State dump before the write: `98-prefs-before.xml`.

### 6. Final state (2026-11-06 09:04 Z, `98-final-state.txt`)

* Front: Free Social hub, `Filtering on`, all three rows "Filtering", `99c-hub-before-final-write.png`.
* Budget: limit **20**, `budget_date 2026-11-06`, disk `used 0`; live engine and UI still 698
  (**9 min left**) — see §5.
* Modes: IG `filter`, YT `filter`, TikTok key unset → default `filter` (left as found, not written).
* `files/`: `profileInstalled` only — **no override**.
* Services: `enabled_accessibility_services` has both components;
  `dumpsys accessibility | grep -c freesocial` = **2**, both **Bound** (Content filtering +
  Whole-app blocking).
* Apps: IG/YT/TikTok were not launched this run (the device stayed on Free Social from 08:46 to
  09:04), so their last state is the one recorded in *Final state restore* above.
* No source file and no git commit — only `docs/rules/REVERIFY.md` and
  `reverse/runtime-reno/verify-030/9x-*` evidence.

### 7. The unblock — HEAD APK install, cache cleared, items 4 and 5 re-run (09:20–09:52)

The gate in subsection 3 was a *live process holding a cached selection*, not a device permission.
Ending that process is the documented way to get a fresh `RuleCatalog.load()`, and an
`adb install -r` does it: the system kills the app and then rebinds both accessibility services,
which is the `onServiceConnected` path. No toggle, no dialog, no consent tap was involved.
Evidence — all `reverse/runtime-reno/verify-030/1xx-*`.

**7.1 Phase 0 and install.** `run-as` write of `budget_used_seconds=0` / `budget_date=2026-11-06`
(the file already held those values from the previous run's final write; re-written and verified on
disk at 09:20:29, `101-prefs-before-phase0.xml`, `budget_limit_minutes=20` untouched) and then, with
no UI action in between, `./gradlew assembleDebug` → `BUILD SUCCESSFUL`, 37 tasks up to date,
`app-debug.apk` 30 074 281 B whose `resources.arsc` contains `Use built-in rules`;
`adb -s DEVICE-SERIAL-REDACTED install -r` 09:21:32 → 09:21:41, `Success`. Nothing was tapped after the write.

**7.2 What the install changed — no dialog, no toggle.**

* process: pid `20107` (STIME 07:21:19) → **`5586`** (STIME 09:21:41).
* `versionName` `0.1.0` → **`0.4.0`**; `lastUpdateTime 2026-11-06 09:21:40`.
* `dumpsys accessibility | grep -c freesocial` = **2** at 09:22:48 (checked once, no wait loop
  needed); both components still in `enabled_accessibility_services`, both **Bound** (Content
  filtering + Whole-app blocking). The system rebound them on its own; no ColorOS dialog appeared
  and none was answered.
* disk budget read back `used 0` after the fresh start, i.e. the phase-0 write survived.

**7.3 `Rules in use` reads the built-in asset — PASS.** Hub at 09:24 shows **20 min left**
(`103-hub-20-min-left.png`) — first proof the fresh process loaded `used 0`; the 698 s cache died
with pid `20107`. Surfaces & rules → `Rules in use` = **`Built-in rules`**
(`108-rules-in-use-builtin.png`, dump `107-rules-in-use.xml`). The
`Override file: /data/user/0/dev.k3vinb5.freesocial/files/rules-override.json` line beneath it is
the static `inspector_override_path` hint (`app/src/main/res/values/strings.xml:183`), not a
selection. No `Use built-in rules` button was shown and none was needed — it only renders while an
override is active. The list shows `tiktok_playback | TikTok · playback | Enabled | Last verified
against TikTok 47.6.3` (`109-rules-list-tiktok.png`), and at 09:48:51 the service logs
`connected, rules=8, targets=…` — the built-in 8-rule set, not the 2-rule override.

**7.4 Item 4 — TikTok FYP pay — PASS.** Before the run `budget_used_seconds=0` (disk + hub 20 min).
TikTok launched 09:33, `Para Ti` feed, a video post playing (`112-item4-tiktok-fyp-video.png`,
node dump `114-item4-fyp-nodes.xml`). Eleven scans 09:34:32 → 09:36:15, every one `matched=1`:

```
11-06 09:34:32.518 I/FilterService( 5586): scan com.zhiliaoapp.musically (TIKTOK): 66 nodes, 38 with viewId,
    rules=2/8, matched=1, grafted=2, windows=1 [TikTok(0)=63/36 ids], rootChildCount=2, idProbe=n/a
11-06 09:34:32.521 I/FilterService( 5586): com.zhiliaoapp.musically: 1 hit(s) but gate=PAY (budget=false, locked=false)
… (same pair every ~10 s) …
11-06 09:36:15.483 I/FilterService( 5586): com.zhiliaoapp.musically: 1 hit(s) but gate=PAY (budget=false, locked=false)
```

`budget_used_seconds`: **0 → 114** (`111-item4-logcat-early.txt`) → **252**
(`113-item4-prefs-after.txt`), and the hub then read **15 min left** (`116-hub-used-252.png`). The
counter reached **317** by the time the hub was brought forward (~09:37:45) — the extra 65 s is
TikTok still playing in the foreground while the dumps/screencaps were taken, and it stops on the
first scan that sees the un-filtered hub window (`scan()` → `stopBudget()`), so nothing accrued
outside the surface. Gate
`PAY` with `budget=false` is the intended middle state. The UI was Portuguese
("Gostar do vídeo…", "Partilhar vídeo") and still matched — the key is the locale-proof
`viewId …:id/player_view`, with `contentDescriptionRegex` only breaking ties
(`RuleMatcher.kt:70-105`).

*Photo-post trade-off:* not exercised this run — the FYP served a video post only, so nothing was
measured for a photo carousel. By construction the key is `player_view`, which a photo post also
renders into, so the "photo post also matches" trade-off of the re-key stands unrefuted but
**unmeasured on device**.

**7.5 Item 5 — exhausted escape on all three surfaces — PASS.** Stepper set the allowance 20 → 5
(note: the stepper moves in **5-minute** steps per tap, not 1 — this also corrects the
`20→21→20` wording in §5; `120-budget-limit5-final.xml`). With `used 317 s` > `300 s` the budget
was exhausted immediately (0 min left, `121-budget-limit5.png`), no extra watching needed.

| entry | scan | action | flash |
|---|---|---|---|
| TikTok FYP 09:47:05 | `matched=1`, 66 nodes | `neutralising com.zhiliaoapp.musically/playback via BACK` | `escape flash … reason=BUDGET` |
| IG reels 09:47:40 | `matched=2` (reels_tab + clips_viewer markers) | `neutralising com.instagram.android/reels_tab via TAP_HOME_TAB` | `escape flash … reason=BUDGET` |
| YT Shorts 09:49:09 | `matched=1`, 5 nodes | `neutralising com.google.android.youtube/shorts_player via BACK` | `escape flash … reason=BUDGET` |

Logs `122-item5-tiktok.logcat.txt`, `124-item5-ig-reels.logcat.txt`, `126-item5-yt-shorts.logcat.txt`;
screens `123-item5-tiktok-escape-{a,b,c}.png`, `125-item5-ig-reels-{a,b}.png`,
`128-item5-yt-shorts-{a,b}.png`. TikTok's `BACK` returned focus to Free Social within 0.2 s of the
scan, so no FYP video reached 2 s — the leak recorded under *Item 5* above is gone. IG was entered
via `instagram://reels_home`; the reels-tab rule fires first, and the same scan reports `matched=2`,
so the clips viewer was matched too and never rendered — the entry that was asked for (clips viewer)
is therefore blocked one gate earlier, not skipped. YT: the Shorts shelf tap opened
`shorts_player`, `BACK` escaped and took the app out to the launcher.

### 8. Final state after the re-verify (2026-11-06 09:52 Z, `137-final-state.txt`)

Restore order matters and was followed exactly: **first** the allowance back to 20 with the stepper
(3 taps × 5 min, `131-budget-limit20-restored.xml`), which writes the whole in-memory map
(`used 317`), **then** the `run-as` hygiene write of `budget_used_seconds=0` / `budget_date=2026-11-06`
(limit already 20) at 09:50:56 as the **last** write (`132-prefs-before-final-write.xml` →
`133-final-prefs.xml`), re-read 15 s later unchanged (`134-final-prefs-15s.xml`). No UI write after it.

* Front: Free Social hub, `Filtering on`, all three rows "Filtering", `136-final-hub.png`.
* Budget: limit **20**, `budget_date 2026-11-06`, disk `used 0`; hub still reads **15 min left**
  (live cache 317). **Expected, per §5**: a disk-only reset is invisible until the next process
  start (or midnight), and any write through the app re-clobbers the disk copy — so the durable
  "clean" state is exactly this one, taken as the last write.
* Modes: IG `filter`, YT `filter`, TikTok key unset → default `filter` (unchanged, never written).
* `files/`: `profileInstalled` only — **no override**.
* Services: `enabled_accessibility_services` has both components;
  `dumpsys accessibility | grep -c freesocial` = **2**, both **Bound**.
* Process: pid `5586`, `versionName 0.4.0`, `lastUpdateTime 09:21:40`.
* No source file changed and no git commit — only this document and
  `reverse/runtime-reno/verify-030/1xx-*` evidence.

# D12 + D13 after-fix re-verification — Reno4 (branch `fix/flash-and-dm-carveout`, 2026-11-07)

Device: OPPO Reno4 5G (USB, serial in `FS_DEVICE_SERIAL`; never committed), Android 13 / SDK 33,
1080×2400, pt-PT. APK built from this branch (`./gradlew test assembleDebug`, 276 tests green).
Apps: Instagram **454.0.0.54.77**, TikTok **47.6.3**. Budget set through Free Social's own stepper
(0 min, `budget_used_seconds` > 0 ⇒ exhausted); the two retired passive rules stay disabled and
were not touched. Evidence: `reverse/runtime-reno/flash-dm/after-*` (gitignored).

## D12 — one flash per escape episode — PASS

Four consecutive 0-budget Reels entries, each a fresh episode (dismiss the flash, re-enter):

```
ep0: FLASH appeared +1.83s (entry->flash 1.71s)  screenshot=after-rapid-ep0.png
ep1: FLASH appeared +4.44s (entry->flash 1.07s)  screenshot=after-rapid-ep1.png
ep2: FLASH appeared +6.69s (entry->flash 0.80s)  screenshot=after-rapid-ep2.png
ep3: FLASH appeared +8.75s (entry->flash 0.78s)  screenshot=after-rapid-ep3.png
escapes=4 flash_log=4 suppressed=0
```

Engine lines (`after-rapid-service.log`): `neutralising com.instagram.android/reels_tab via
TAP_HOME_TAB` ×4, `escape flash com.instagram.android/reels_tab reason=BUDGET` ×4, **zero**
`escape flash suppressed`. Screenshots show the copy on screen well inside the 2.2 s visible window
(the D12 timing fix arms the window on first focus; the old build showed ~0.65 s). BEFORE for
contrast: `before-rapid-transcript.txt` = 4 escapes / 2 flashes / 1 suppressed.

## D13 — IG DM carve-out, thread and DM-opened reel — PASS

* Thread: `after-ig-dm-thread.xml` has `row_thread_composer_edittext` (`android.widget.EditText`,
  pt-PT hint "Enviar mensagem…"); engine logs `1 hit(s) but gate=SKIP` (`after-ig-dm-logcat.txt`).
* Reel opened from the thread (`com.instagram.modal.TransparentModalActivity`):
  `after-ig-dm-reel.xml` has `clips_viewer_view_pager` **and**
  `reply_bar_edittext` (`android.widget.AutoCompleteTextView`, inside
  `reel_viewer_message_composer`) and **no** `row_thread_composer_edittext`; the engine logs
  `2 hit(s) but gate=SKIP (budget=true)` on every scan, with no `neutralising` and no flash.
  Screenshot is black/empty: IG Direct is FLAG_SECURE (recorded in RULES.md).
* Negative control (pre-fix dump `10-ig-reels-viewer-inbudget.xml`): the ordinary viewer has the
  pager and no `reply_bar_*`, so the new SKIP rule cannot shield a non-DM viewer; `RuleGateTest`
  asserts ESCAPE for that tree at 0 budget.

## D13 — TikTok DM carve-out — PASS (steady state), one residual first-capture race

* Thread: `after-tt-dm-thread.xml` has `ui_slot_input_layout_redesign` and no `player_view`.
* Shared video opened from the thread (`DetailActivity`): `after-tt-dm-video.xml` (163 nodes) has
  `player_view` [0,110][1080,2121], `video_seek_bar` and the composer `udm` (Button, pt-PT
  "Enviar mensagem para Carol…"); engine logs `2 hit(s) but gate=SKIP (budget=true)` on every scan
  (`after-tt-dm-logcat.txt`, screenshot `after-tt-dm-video.png`), no `neutralising`, no flash.
* **Residual, not fixed here:** the first capture after a cold app-switch resume can be a partial
  tree (3–19 nodes) that exposes `player_view` before TikTok renders `udm`; one scan then sees
  `matched=1` and escapes + flashes once (`16:50:22` in `after-tt-dm-logcat-full.txt`). Steady
  state is SKIP. Filed as OpenProject #146 (Free Social); a fix needs a capture-settle/dedupe change outside
  this branch's file scope.

## "Use built-in rules" — PASS (2026-11-07, first device proof)

`RuleCatalog.revertOverride()` + its button shipped in `e1bf545` but had never been exercised on a
device: subsection 3 above found the button absent from the installed APK, and 7.3 only noted it
"was not shown and none was needed". This run opened an override deliberately, then reverted it with
the button (**closing the open item**).

Device: Reno4 5G, app 0.4.1, pt-PT, override active from an import in the same session.

* **Dialog**: title *Use built-in rules*, body *Your imported rules will be removed. Free Social
  will use its built-in rules.*, `Cancel` / `Use built-in rules`
  (`reverse/runtime-reno/heal/driver/01-revert-dialog.png`).
* **Confirm** → banner *Your imported rules were removed — built-in rules are active.*,
  `files/` back to `profileInstalled` only (`rules-override.json` gone), `Rules in use` reads
  **`Built-in rules`**, the button disappears, `RuleCatalog: engine cache dropped — the override
  applies on the next event` (`driver/01-reverted.png`, `driver/01-reverted-source.png`,
  `driver/02-ab2-reverted.png`).
* **Live A/B, same PID, no restart** — the Reels tab scanned by `FilterService`:

  | state | engine line |
  | --- | --- |
  | override (built-in with `ig_clips_viewer` disabled) | `rules=3/9, matched=1` |
  | after **Use built-in rules** | `rules=4/9, matched=2` |

  (`driver/01-ab2-disabled.png`, `driver/03-ab2-reverted.png`)

## Heal-from-dump marathon — 2026-11-07

Full pass of `docs/rules/CAPTURE.md` on the device, no APK rebuild and no process restart between
the steps. Two kinds of evidence exist and they are **not** interchangeable — the scripted scenario
reads the `GuidedCapture` and `RuleCatalog` tags only, so a scenario run proves the inspector
analysis and the engine-cache drop, never a `matched=` count:

* **Inspector analysis**: the *Rules that match this dump* block for a fixed dump file.
* **Engine log**: `FilterService` `scan … matched=N` lines, which only exist in the **manual** run
  of this day. They are transcribed in `reverse/runtime-reno/heal/logcat-heal-chain.txt` (the
  logcat ring had rotated by the time the run was written up).

Scripted: `uv run tools/device/drive.py run heal_from_dump` →
**PASS** (63 steps, 351.1 s, `reverse/runtime-reno/driver-20261107-205244/heal_from_dump/`).

| step | result | Inspector-analysis proof (scenario run, reproducible) | engine-log proof (manual run, once) |
| --- | --- | --- | --- |
| 1 capture | PASS | `GuidedCapture … com.instagram.android/reels_tab: 9 nodes, verified=true`; IG Home `feed_reel_cell`, YT Home `home` likewise verified | — (capture is logged by `GuidedCapture`, not the engine) |
| 2 analyse | PASS | the dump lists `ig_reels_tab · reels_tab` (+ `ig_clips_viewer`) with `viewId`/`desc`/`class`/`selected`; the Home dump lists none | — |
| 3 break | PASS | `clips_tab` → `clips_tab_gone` imported: `engine cache dropped`; the same dump file **loses** `ig_reels_tab` (`06-break-analysis-empty.png`) | `rules=4/9, matched=1` on the Reels tab with the broken anchor (19:28:02) |
| 4 heal | PASS | corrected anchor imported: `engine cache dropped`; the same dump **regains** `ig_reels_tab` (`07-heal-analysis-match.png`) | `rules=4/9, matched=2` after the heal (19:30:45), same PID 24053 |
| 5 revert | PASS | dialog → confirm → banner, `files/` holds only `profileInstalled`, `Rules in use = Built-in rules`, button gone (`08-reverted.png`) | separate A/B with **`ig_clips_viewer` disabled**: `rules=3/9, matched=1` → `rules=4/9, matched=2` |

`matched=1` vs `matched=2` for steps 3–4 is a comparison across two moments, not one continuous
capture: the pre-break Reels scan was not recorded, so the `2` side is the post-heal measurement on
the same screen. The revert A/B moves a **different** rule (`ig_clips_viewer`), so its `1 → 2` must
not be read as part of the break/heal chain.

Three defects found and fixed on the same branch (details in
`reverse/runtime-reno/heal/REPORT.md`, `tools/device/scenarios/heal_from_dump.py`,
`RuleInspectorHealTest`):

* **A** `RuleInspectorScreen.kt:319-322` — Export wrote `filesDir/rules-override.json` with a bare
  `writeText`, so the screen still read *Built-in rules* and hid *Use built-in rules* while a file
  that would be loaded at the next start was on disk. Fixed by publishing the document through
  `RuleCatalog.applyOverride` and refreshing the screen; the decision now lives in the pure
  `exportEffect(...)` seam (adopt ⇒ refresh + picker + *exported*, reject ⇒ banner only).
* **B** `RuleInspectorScreen.kt:329` — the import picker was launched via
  `ActivityResultContracts.OpenDocument`, whose base type is the wildcard; ColorOS DocumentsUI then
  lists no `application/json` row at all, so the exported document could not be imported. Fixed by
  launching `ACTION_OPEN_DOCUMENT` with base type `application/json` and no `EXTRA_MIME_TYPES`
  (measured: `am start -a OPEN_DOCUMENT -t application/json` lists the file immediately).
* **C** `RuleInspectorScreen.kt:219` — the three app chips split the row into equal thirds and
  "Instagram" wrapped to "Instagra / m"; the chip now takes a compact padding/label style.

A fourth defect was caught by review after the first PR: a **rejected** Export still opened the
save picker, and the picker callback then replaced the failure banner with *Rules exported to …* —
a failed adoption reported as success. Fixed in the same seam (`ExportEffect.openPicker` is false
unless the document was adopted) and pinned by `RuleInspectorHealTest`.

Device limitation reconfirmed, not a regression: the accessibility tree handed to a third-party
service on this build is decor-only (D8), so dumps are graft views — IG Home 4 walked + 5 grafted
nodes while `uiautomator dump` of the same screen has 112.

## Restored state

`budget_limit_minutes=20`, `schedule_enabled=true` with `420/1350` untouched, `filtering_enabled=true`,
both accessibility components enabled and bound; phone left on the launcher.
