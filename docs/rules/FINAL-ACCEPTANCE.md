# Final acceptance — real device, run-and-report

Status: **evidence pass, 2026-11-05 01:07–01:40** on the physical Pixel 9a. No code changes.
Build `cbaedb1` (`fix(filter): runtime-verified defects …`). Every claim below has a screenshot
and/or a logcat line in `reverse/runtime-verify2/`; the raw service log is
`reverse/runtime-verify2/logcat-excerpt.txt`.

> **Section note.** `docs/SPEC.md` at `cbaedb1` has **no §8** — it ends at §6. The acceptance
> checklist is §6 "Verification plan (acceptance)", whose **8 numbered items** are graded in the
> table below (that is the "§8" of the task brief). Everything else in this file is the
> re-verification of the fixed scenarios plus the first-ever BlockService pass.

## Environment / method

| | |
| --- | --- |
| Device | Pixel 9a `56191JEBF26582`, Android 17, 1080×2424 |
| Build | `./gradlew assembleDebug` **EXIT=0** in the main tree, installed **once** with `adb install -r app-debug.apk`; never rebuilt afterwards |
| Services | `settings put secure enabled_accessibility_services "com.x8bit.bitwarden/.Accessibility.AccessibilityService:dev.k3vinb5.freesocial/dev.k3vinb5.freesocial.filter.FilterService:dev.k3vinb5.freesocial/dev.k3vinb5.freesocial.block.BlockService"` — `dumpsys accessibility` shows both **Bound** (Content filtering + Whole-app blocking) |
| App versions | Instagram 454.0.0.54.77, YouTube 21.43.523, TikTok 47.6.3 (match the rule pins) |
| Screencaps | `locksettings set-disabled true` for the session, restored to **false** at the end |
| Log | continuous `adb logcat -v time` of FilterService / BlockService / ScheduleAlarm / ActivityTaskManager |

**Facts about this fix that hold across every scenario:** `GLOBAL_ACTION_HOME` /
`REDIRECT_HOME` never appears in the log (**0 occurrences** in ~40 min). `mCurrentFocus` was
checked before/after every step and was always the target app or our own activity — no launcher
bounce anywhere.

---

## Part 1 — re-verification of the previously failing scenarios

### 1. Instagram feed with a playing video cell — **PARTIAL (bounce fixed, hiding not)**

- The old failure (launcher within 0.6 s, then again on every relaunch) is gone. Instagram stayed
  `com.instagram.android/.activity.MainTabActivity` for the whole session; 164
  `feed_reel_cell via SCROLL_PAST` lines, **median interval 1.30 s**, and **no** Home redirect
  (`01a`–`01h`, `02f`, log).
- The feed does advance sometimes (`01g-sw7-b` → `01h-video-post-6s`: the "225 g manteiga" video
  post is replaced by a Daily Mail video post while the phone is untouched), so the action is not
  inert everywhere.
- **But** a full-bleed video post can sit on screen indefinitely while the rule keeps firing:
  `02f-video-hold-A.png` and `02f-video-hold-B-6s.png` are pixel-identical apart from the header
  strip (`diff bbox (150,239)-(743,593)`, mean 0.30) after **3 hits in 6 s** — the cell is
  neither swiped away nor removed. Same on the "Suggested reels" shelf (`01f-shelf-A/B/C`, diff
  bbox only the caption strip).
- Verdict: **stays in Instagram = PASS** (the regression is fixed); **"the cell scrolls away" =
  FAIL** in the stable state, and the rule now busy-loops every ~1.3 s.

### 2. Instagram Profile / Home / Reels tab — **PASS**

- Profile (`02a-ig-profile-1s` → `02b-ig-profile-4.5s`): tap Profile at 01:15:03, still Profile
  4.5 s later, no `reels_tab` action, no navigation. The old "<400 ms hijack into Reels" is gone.
- Home (`02c-ig-home-3s`): tapping Home from Reels keeps the Home feed with the Home tab selected.
- Reels (`02d-ig-reels-tap-1s` → `02e-ig-reels-tap-3.5s`):

  ```
  11-05 01:16:09.146 I/FilterService( 1198): neutralising com.instagram.android/reels_tab via TAP_HOME_TAB
  ```

  The real `feed_tab` is clicked (Home icon filled in `02e`), the viewer is not shown, and no
  further `reels_tab` line appears — the loop of the previous pass is gone. `selected:true`
  keeps Profile/Home out of the rule entirely.

### 4. YouTube Home Shorts shelf — **PARTIAL (stays in YouTube, shelf still visible)**

- `com.google.android.youtube/…Shell$HomeActivity` stays foreground; the previous
  `REDIRECT_HOME` kick is gone (`04a-yt-home-6s`, `04b-yt-home-10s`).
- **The shelf is not hidden**: the Shorts row with four cards is fully visible at 6 s and at 10 s
  while 38 `shorts_shelf via SCROLL_PAST` lines are emitted (`01:17:49.2`, `50.7`, `55.7`,
  `01:18:12.5`, `14.3`, `15.8`, `17.8`, …). The gesture targets a ~60 px header/shelf node, so
  the swipe is too short to move the shelf and nothing changes on screen.
- Verdict: **stay in YouTube = PASS**, **shelf hidden = FAIL**.

### 5. YouTube Shorts player — **PASS**

- In budget: tapping a Shorts shelf card (`05a-yt-shorts-player-2s`, `05b-yt-shorts-player-5s`)
  opens the player and it plays; no `shorts_player` neutralisation; the shared stopwatch moves
  `budget_used_seconds` **55 → 61 in 6 s** (`run-as cat shared_prefs/settings.xml`).
- Budget exhausted (`05c-yt-exhausted-3s`, `05d-yt-exhausted-6s`): the player is reachable again
  (YouTube resumed on it) and is backed out **exactly once**:

  ```
  01:19:43.739 I/FilterService( 6567): neutralising com.google.android.youtube/shorts_player via BACK
  ```

  Landing is YouTube Home (`05d`), one action, no loop. The layer-2 player is reachable now —
  the old blocked-by-shelf failure is fixed.

### 7. Budget-exhausted Instagram reel deep link — **PASS (one escape, no loop)**

- Procedure (same as the previous pass, `run-as`, see "run-as procedures" below): after
  `budget_limit_minutes=1`, `budget_used_seconds=200`, force-stop + relaunch + service re-toggle,
  open `https://www.instagram.com/reel/C8bGqM9yQnZ/`.
- Exactly one escape action:

  ```
  01:20:04.354 I/FilterService( 6567): neutralising com.instagram.android/clips_viewer via BACK
  ```

  `07a` 1 s → `07d` 12 s: Instagram lands on the Home feed (`mCurrentFocus=MainTabActivity`),
  **no** `reels_tab` tap, no playing reel, no re-entry. The "Reels keeps playing" defect of the
  previous pass is gone.

### 8. Schedule-locked reel deep link — **PASS (one escape, no loop)**

- Schedule 00:00–23:59 for all 7 weekdays (`08a` hub: `Schedule On · 00:00–23:59`, *20 min left*).
  Deep link → **one** action and Home feed afterwards:

  ```
  01:21:31.629 I/FilterService( 7291): neutralising com.instagram.android/clips_viewer via BACK
  ```

  `08b` 1 s → `08d` 10 s show the feed, no Reels screen, no repeat. The same schedule lock also
  backs out the YouTube player (covered in §5/§10).

### Spot-confirmations of the previously passing scenarios

- **3. IG reel deep link in budget — PASS.** After the schedule boundary cleared (01:27) the same
  deep link plays the reel with **no** FilterService action (`10c-after-boundary-reel-4s`); the
  previous pass measured the tick 66 → 72 s.
- **6. TikTok — FAIL (regression).** FYP video playing for ~30 s (`06b-tt-fyp-16s`,
  `06c`), `video_seek_bar` present in the uiautomator tree
  (`class="android.widget.LinearLayout" bounds="[0,2130][1080,2204]"`, TikTok 47.6.3), **no**
  `tiktok_playback` line and `budget_used_seconds` stayed **0** over the whole session. The
  previous pass measured 80 → 90 s on the same build, so playback accounting is no longer firing.
- **9. Rule inspector — FAIL.** Dump is written (`12e`, `12f`, 8607 bytes, share chooser opens),
  but the content is **our own window**: 35 nodes, `"package": "dev.k3vinb5.freesocial"`,
  `"screen": "Instagram"`, even with Instagram alive in the background. `captureForDump()` only
  sees focused app windows, so it can never capture IG/YT/TikTok from our own UI.

---

## Part 2 — BlockService (first acceptance run)

### 9. Whole-app block — **PASS** (4/4 sub-checks)

Prefs: `app_mode_com.instagram.android=block`, `schedule_enabled=false`, then force-stop + relaunch
+ service re-toggle.

| check | result | evidence |
| --- | --- | --- |
| IG launch → block screen | `BlockActivity` focused, text "Instagram is blocked", reason "This app is blocked by Free Social." (APP_BLOCK) | `09a-block-screen.png`, uiautomator dump |
| "Go home" | `mCurrentFocus=com.google.android.apps.nexuslauncher/…NexusLauncherActivity` | `09b-after-go-home.png` |
| re-open IG → re-blocks | `BlockActivity` focused again | `09c-block-again.png` |
| system BACK on a fresh block screen | launcher | `09d-after-system-back.png` |
| schedule wins over app-block | with 00:00–23:59 active: "Instagram is blocked" + **"Sleep mode is active — go to bed."** | `09f-block-reason-schedule.png` |
| mode back to `filter` | IG opens normally on the Home feed | `09e-ig-unblocked-5s.png` |

### 10. Schedule boundary flip via the exact alarm — **PASS (0 s drift)**

- Window armed at 01:22 → 01:27 with wind-down/morning-lock 0 (now = 01:23:47). Immediately after
  binding, the lock is active and the reel deep link is neutralised **once**
  (`01:24:00.662 clips_viewer via BACK`, `10a-flip-locked-reel-3s`). The phone was then left alone.
- Armed alarm, read from `dumpsys alarm` at 01:23:55:

  ```
  tag=*walarm*:dev.k3vinb5.freesocial/.block.ScheduleAlarmReceiver
  origWhen=2026-11-05 01:27:00.000 window=0 exactAllowReason=policy_permission
  ```

- After the boundary (checked at 01:27:42, screenshot `10b-after-boundary-untouched`), the same
  dump shows the **next** boundary re-armed (`origWhen=2026-11-06 01:22:00.000`) and the history
  line `2026-11-05 01:27:00.000` / `#1: Reason=alarm_cancelled rtc=2026-11-05 01:27:01.509`.
  Boundary 01:27:00.000 → fired at 01:27:00.000 (≤1.5 s until the receiver finished), i.e. **no
  drift**, inside the §2.3/§6.5 exact-alarm requirement (FAIL threshold was >3 min).
- State flip verified without touching prefs: the same reel deep link now plays
  (`10c-after-boundary-reel-4s`, no FilterService line).

---

## run-as procedures used (all reverted; defaults at the end)

```sh
# write a prefs file (pattern used for every mode/budget/schedule change)
{ echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"; echo "<map>"; \
  echo '  <boolean name="first_run_completed" value="true" />'; ... ; echo "</map>"; } \
  > /tmp/opencode/settings.xml
adb push /tmp/opencode/settings.xml /data/local/tmp/settings.xml
adb shell "run-as dev.k3vinb5.freesocial sh -c 'cat /data/local/tmp/settings.xml > shared_prefs/settings.xml'"
# a prefs edit never reaches the running services: restart and re-bind both of them
adb shell am force-stop dev.k3vinb5.freesocial
adb shell am start -n dev.k3vinb5.freesocial/.MainActivity
adb shell settings put secure enabled_accessibility_services \
  "com.x8bit.bitwarden/.Accessibility.AccessibilityService:dev.k3vinb5.freesocial/dev.k3vinb5.freesocial.filter.FilterService:dev.k3vinb5.freesocial/dev.k3vinb5.freesocial.block.BlockService"
```

Concrete sets used:

- **budget exhausted (7):** `budget_limit_minutes=1`, `budget_used_seconds=200`,
  `budget_date=2026-11-05`, `schedule_enabled=false`.
- **schedule locked (8):** `schedule_enabled=true`, `wind_down_minutes=0`,
  `morning_lock_minutes=0`, `schedule_day_N_bedtime=0` / `schedule_day_N_wake=1439` for N=1..7.
- **block (9):** `<string name="app_mode_com.instagram.android">block</string>`, schedule off.
- **boundary flip (10):** all days `bedtime = now−1 min`, `wake = now+5 min`, wind-down and
  morning-lock 0.
- **defaults restored (end):** all three `app_mode_* = filter`, `budget_limit_minutes=20`,
  `budget_used_seconds=0`, `schedule_enabled=true`, `schedule_day_N_bedtime=1350` (22:30),
  `schedule_day_N_wake=420` (07:00), `wind_down_minutes=30`, `morning_lock_minutes=30`
  (`13b-hub-restored-final.png`: *20 min left*, *Schedule On · 22:00–07:30*, Permissions 2 of 4).

---

## SPEC §6 acceptance checklist — graded line by line

| # | Criterion (§6) | Verdict | Evidence / what happened |
| --- | --- | --- | --- |
| 1 | IG in Filter mode: feed reel cells + Reels tab hidden; deep-link reel plays and ticks the budget | **PARTIAL** | Reels tab → Home via `feed_tab` (`02e`, 01:16:09.146); deep-link reel plays when unlocked (`10c`, previous pass 66→72 s). Feed reel cells are **not** hidden: video posts stay on screen for 6 s+ while `feed_reel_cell via SCROLL_PAST` fires every 1.3 s (`02f`, `01f-shelf-*`) |
| 2 | YT Shorts shelf hidden; Shorts player plays within budget, Back-ed when exhausted | **PARTIAL** | Player: plays in budget (`05b`, budget 55→61 s) and is Back-ed once when exhausted (`05d`, 01:19:43.739). Shelf: **not hidden** — visible at 10 s (`04b`) with 38 SCROLL_PAST hits |
| 3 | TikTok playback counts against the same budget; after exhaustion playing videos get backed out; DMs still usable | **FAIL** | Playing FYP video for ~30 s, `budget_used_seconds` stayed **0**, no `tiktok_playback` hit, `video_seek_bar` present in the tree (TikTok 47.6.3). Exhaustion/DM carve-out untestable while nothing accrues |
| 4 | Budget: set 1 min → consumption across all three apps stops at 1 min total | **PARTIAL** | IG deep link and YT player stop when `limit=1 / used=200` (`07a`, `05c`, one BACK each). TikTok never accrues, so it never stops — the combined total cannot be enforced across all three apps |
| 5 | Schedule: window now+2 min → all short-video filtered at the boundary, returns at wake (exact alarm) | **PASS** | Locked at 01:24 (`10a`), exact alarm `origWhen=01:27:00.000`, history `01:27:00.000`, re-armed to 2026-11-06 01:22; reel plays again at 01:27:48 (`10c`) |
| 6 | Opt-in Block mode on one app → block screen, back goes home, re-open re-blocks | **PASS** | `09a`–`09d`: block screen with the APP_BLOCK reason, Go home → launcher, re-open → block, system back → launcher; `09f` shows the SCHEDULE reason winning when locked; `09e` IG normal after `filter` |
| 7 | Disable the accessibility service → settings shows the failure banner | **FAIL** | `11a-hub-service-disabled-banner.png`: with **only** Bitwarden enabled the hub still shows the green **"Filtering on"** pill and no banner; the only signal is "Permissions · 0 of 4". §2.4's "persistent banner whenever enforcement is degraded" is not implemented |
| 8 | Rule inspector dumps a live node tree for IG/YT/TikTok screens | **FAIL** | Dump written and share chooser opens (`12e`, `12f`), but every dump contains **our own inspector window** (`"package": "dev.k3vinb5.freesocial"`, 35 nodes) even with IG alive in the background — `captureForDump()` only sees the focused window |

**Counts: 2 PASS · 3 PARTIAL · 3 FAIL** (of 8).

### Items that need Kevin (not machine-verifiable)

| Item | Why USER |
| --- | --- |
| Setup wizard steps 4–5 (Display over other apps, battery exemption) | Permissions page reports both as **Needed** ("2 of 4"); granting them is a device-owner action. Block screen works without the overlay today |
| Daily use on the real accounts | Needs a normal day of IG/YT/TikTok use, not a 35-minute scripted pass |
| Battery-saver / OEM task-killer sanity | Needs the phone to sit in Doze/battery saver for hours |
| TikTok DM carve-out on a shared video | Requires an inbound DM with a video while TikTok accounting works (currently FAIL) |

---

## Findings to fix next (no code touched here)

1. **`SCROLL_PAST` is a no-op loop.** 164 IG + 38 YT hits, median 1.30 s apart, screen unchanged
   in the measured windows — the gesture targets a tiny header node or an off-screen preloaded
   node, and the return value of `dispatchGesture` is never checked. Layer-1 surfaces ("feed reel
   cell", "Shorts shelf") are therefore not hidden, only logged.
2. **TikTok playback no longer accrues budget** on 47.6.3 even though `video_seek_bar`
   (`LinearLayout`) is on screen — either the node lands beyond the 200-node view-id budget or
   visibility filtering drops it. Previous pass measured 80 → 90 s.
3. **No degraded-enforcement banner** (item 7) — the hub pill is hard-coded to *Filtering on*.
4. **Rule inspector can only dump its own window** (item 8) — it needs a target-window source
   (e.g. `getWindows()` while the target app is foreground, or a floating overlay trigger).
5. Perf: ~46 no-op gestures/minute while IG/YT are open is wasted battery and a risk of
   interference on slower frames.

## Evidence index (`reverse/runtime-verify2/`)

| files | proves |
| --- | --- |
| `01a`–`01h`, `02f-*` | IG feed stays in app; SCROLL_PAST runs but video cells/shelves persist |
| `02a`–`02e` | Profile/Home untouched; Reels tab → Home tab via `feed_tab` |
| `04a`,`04b` | YT Home stays, Shorts shelf still visible at 10 s |
| `05a`,`05b`,`05c`,`05d` | YT Shorts player reachable, plays in budget, one BACK when exhausted |
| `06a`–`06d` | TikTok FYP plays but budget stays 0 |
| `07a`–`07d` | IG exhausted deep link: one BACK, Home feed, no loop |
| `08a`–`08d` | schedule-locked deep link: one BACK, Home feed, no loop |
| `09a`–`09f` | BlockService: block screen, Go home, re-block, system back, schedule reason, unblock |
| `10a`–`10c` | lock active → reel backed; exact alarm 01:27:00.000; reel plays after boundary |
| `11a` | hub with FilterService disabled (no banner, still "Filtering on") |
| `12a`–`12f` | Rule inspector UI + dumps (own window only) |
| `13a`,`13b` | IG restored, final hub (20 min left, Schedule 22:00–07:30) |
| `logcat-excerpt.txt` | raw FilterService / BlockService / ScheduleAlarm / ActivityTaskManager lines |
