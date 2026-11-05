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
