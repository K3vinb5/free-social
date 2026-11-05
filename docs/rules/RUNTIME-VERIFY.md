# Runtime verification — FilterService + real rules on the Pixel 9a

Status: **evidence pass, 2026-11-05 00:37–00:51** on the real device, no code changes.
Everything below is observed behaviour, with the screenshot/logcat file that proves it.
All evidence lives in `reverse/runtime-verify/`.

## Environment

| | |
| --- | --- |
| Device | Pixel 9a `56191JEBF26582`, Android 17 (API 37), 1080×2424 @ 420 dpi |
| Build | `d1f241a` (`feat(rules): real IG/YT/TikTok filter rules…`), debug APK, `versionName 0.1.0` |
| Build note | `./gradlew assembleDebug` in the main tree **failed** at first: an untracked `app/src/main/java/dev/k3vinb5/freesocial/block/` (another task in progress, `ScheduleAlarmScheduler.kt` referencing a missing `ScheduleAlarmReceiver`) broke `compileDebugKotlin`. HEAD was therefore built in a throwaway worktree (`/tmp/opencode/fs-head`, `local.properties` copied in) and that APK installed once. The main tree was not touched. |
| App versions under test | Instagram 454.0.0.54.77, YouTube 21.43.523, TikTok 47.6.3 (match the `verifiedAgainstAppVersion` pins) |
| Service | `dev.k3vinb5.freesocial/dev.k3vinb5.freesocial.filter.FilterService` enabled via `settings put secure enabled_accessibility_services`, bound (`dumpsys accessibility` → `Service[label=Content filtering, …]`) |
| Prefs at start | `first_run_completed=true`, `schedule_enabled=false` — the hub opened directly (`00-hub.png`, pill **Filtering on**) |
| Prefs at end | same two keys, `budget_used_seconds=0`, `budget_date=2026-11-05`; schedule back to defaults 22:30/07:00 (keys removed, defaults apply), budget limit back to 20 min (`09z-hub-restored-final.png`: *20 min left*, *Schedule Off*, *1 of 4*) |
| Left enabled | `enabled_accessibility_services` = bitwarden + **our FilterService** (as required); `locksettings set-disabled` restored to `false` |

Only adb was used after the single install. Nothing was rebuilt or reinstalled afterwards.

---

## Scenario results

### 1. Instagram feed with a video/reel cell — **PASS (rule fires), behaviour harsh**

- Feed at launch was photo-only: no hit, no action (`01f-ig-feed-clean-4s.png`, empty logcat).
- With `hide_feed_reels` switched off (to see the cell), the first feed post is a **playing video** with `id/video_container` present ×3 in `uiautomator` (`01l-ig-feed-toggles-off-video-cell.png`).
- With the rule on, one `input swipe` to the next cell and the app is **immediately thrown to the launcher**:

  ```
  11-05 00:41:43.650 22757 I FilterService: neutralising com.instagram.android/feed_reel_cell via REDIRECT_HOME
  ```

  `01g-ig-feed-swipe1.png` = Pixel launcher, `mCurrentFocus=…nexuslauncher…`.
- The bounce is **not** a one-off: Instagram resumes on the same video cell, so every relaunch is kicked again within ~0.6 s (`01j-ig-feed-0.6s.png`, log `00:42:14.773`, `00:42:41.325`). The feed is unusable while any video post is in view — the cell is never swiped past, the whole app leaves.

### 2. Instagram bottom-nav Reels tab — **FAIL (rule fires, wrong action)**

- Tapping the Reels tab lands on the clips viewer. The rule fires on a loop, ~1 per 1.3 s, and **nothing changes** — we stay on Reels (`02c-ig-reels-tab-4s.png`):

  ```
  11-05 00:44:00.281 I FilterService: neutralising com.instagram.android/reels_tab via TAP_HOME_TAB
  11-05 00:44:01.766 … 00:44:03.186 … 00:44:04.446 … (repeats for as long as the screen is up)
  ```

- **Profile tab**: profile renders (`02d-ig-profile-0.4s.png`) and is replaced by Reels ~3 s later (`02e-ig-profile-hijacked-3.4s.png`).
- **Home tab**: tapping Home from Reels is undone in **under 400 ms** (`02f-ig-home-0.4s.png` already shows a reel; `02g-ig-after-home-3.4s.png` same).
- Root cause in the shipped code, not a rule gap: `FilterService.perform` implements `TAP_HOME_TAB` as `clickNode(hit.node)`, and the hit node **is** `com.instagram.android:id/clips_tab` (the Reels tab). `ig_reels_tab` sets no `viewIdTarget`, so every "neutralisation" is a tap on the **Reels** tab. The engine therefore drags the user *into* Reels from Profile/Home instead of tapping the safe Home tab.

### 3. Instagram reel deep link, budget intact — **PASS**

- `am start -a android.intent.action.VIEW -d https://www.instagram.com/reel/C8bGqM9yQnZ/` → `ClipsUrlHandlerActivity` (back arrow + "Reels" header, no tab bar), video plays (`03-ig-reel-deeplink.png`).
- No `ig_clips_viewer` hit (layer 2 not neutralised, as specified); the stopwatch counts: `budget_used_seconds` 66 → 72 over the ~6 s observation.

### 4. YouTube home Shorts shelf — **PASS (neutralised), same harshness**

- `com.google.android.youtube/.HomeActivity` → launcher within 5 s (`04-yt-home-shorts-shelf.png`):

  ```
  11-05 00:45:02.498 I FilterService: neutralising com.google.android.youtube/shorts_shelf via REDIRECT_HOME
  ```

### 5. YouTube Shorts player deep link — **FAIL (never reached)**

- `-d https://www.youtube.com/shorts/` opens YouTube on Home, the shelf rule fires and the app is kicked **before** the player exists (`05c-yt-shorts-root-1s.png`, `05d-yt-shorts-root-5s.png`):

  ```
  11-05 00:46:00.082 I FilterService: neutralising com.google.android.youtube/shorts_shelf via REDIRECT_HOME
  ```

- Consequence: the layer-2 Shorts player cannot be entered from a bare shorts link, and the same shelf rule blocks the in-app route, so "play while the budget lasts" is unreachable in normal use. A `/shorts/<id>` link for a non-Short video opened the ordinary watch page (`05b`) — not evidence about the player surface.
- With the budget exhausted the same launch produced **both** actions and left YouTube entirely (`07d-yt-shorts-exhausted.png`):

  ```
  11-05 00:48:13.204 …/youtube/shorts_shelf via REDIRECT_HOME
  11-05 00:48:13.289 …/youtube/shorts_player via BACK
  ```

### 6. TikTok For You feed — **PASS (plays, counts)**

- Launched on the Inbox (previous state); Home tab → FYP. A **photo** post produced no hit (`06d-tt-fyp-video1.png`, "Photo" label), a **live** produced no hit (`06f-tt-fyp-video3.png`) — both match `RULES.md` §6.
- A video post played untouched, and the shared stopwatch moved 80 → 90 s (`06g-tt-swipe-1..3.png`). No `tiktok_playback` log line: layer 2 stays passive while the budget lasts.

### 7. Budget exhausted — **partial PASS**

Procedure actually used (debug build, `run-as`):

```sh
cat > /tmp/opencode/settings.xml <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="first_run_completed" value="true" />
    <boolean name="schedule_enabled" value="false" />
    <int name="budget_limit_minutes" value="1" />
    <long name="budget_used_seconds" value="200" />
    <string name="budget_date">2026-11-05</string>
</map>
X
adb push /tmp/opencode/settings.xml /data/local/tmp/settings.xml
adb shell "run-as dev.k3vinb5.freesocial sh -c 'cat /data/local/tmp/settings.xml > shared_prefs/settings.xml'"
adb shell am force-stop dev.k3vinb5.freesocial
adb shell am start -n dev.k3vinb5.freesocial/.MainActivity
# force-stop kills the process that hosts the accessibility service and it does NOT rebind on relaunch:
adb shell settings put secure enabled_accessibility_services "com.x8bit.bitwarden/.Accessibility.AccessibilityService"
adb shell settings put secure enabled_accessibility_services "com.x8bit.bitwarden/.Accessibility.AccessibilityService:dev.k3vinb5.freesocial/dev.k3vinb5.freesocial.filter.FilterService"
```

- Hub reads **0 min left**, pill still *Filtering on* (`07a-hub-budget-exhausted.png`).
- IG reel deep link → **BACK**, correct:

  ```
  11-05 00:47:53.738 I FilterService: neutralising com.instagram.android/clips_viewer via BACK
  ```

- …but Instagram then lands on the **tab-based Reels screen**, which also contains `clips_viewer_view_pager`. There the layer-1 `ig_reels_tab` hit outranks the layer-2 hit in `RuleGate.activeHits`, so the engine taps Reels again and **the viewer keeps playing an ad/live reel** (`07c-ig-reel-exhausted-5s.png`, further logs are all `reels_tab via TAP_HOME_TAB`). The budget exit fires once and then stops.
- YouTube: neutralised (`07d`, logs above). TikTok not re-run here (covered by 8).

### 8. Schedule locked — **partial PASS**

Procedure: same `run-as` write with `schedule_enabled=true`, `wind_down_minutes=0`, `morning_lock_minutes=0`, and for weekdays 1–7 `schedule_day_N_bedtime=0`, `schedule_day_N_wake=1439` (00:00–23:59, covers "now" = 00:48), budget reset to 20 min / 0 s; then force-stop, relaunch, rebind as above.

- Hub: **Schedule On · 00:00–23:59** (`08a-hub-schedule-locked.png`), budget *20 min left*.
- IG deep link → BACK (`08b-…-1s`, `08c-…-5s`); then the same Reels-tab loophole: `08c` shows a playing reel and the log is only `reels_tab via TAP_HOME_TAB`.
- YouTube Shorts link → shelf kick to launcher (`08d`).
- TikTok → two BACKs, app leaves to the launcher (`08e`):

  ```
  11-05 00:49:08.083 I FilterService: neutralising com.zhiliaoapp.musically/playback via BACK
  11-05 00:49:09.171 I FilterService: neutralising com.zhiliaoapp.musically/playback via BACK
  ```

Then restored: schedule keys removed (defaults 22:30/07:00), `schedule_enabled=false`, `budget_limit_minutes` key removed (default 20), `budget_used_seconds=0`.

### 9. Rule inspector dump — **PASS (own window only)**

- Hub → Rule inspector → Instagram chip → FAB (`09b-rule-inspector.png`).
- File written to the app's external dir; the share chooser opens automatically after the dump (`09c-after-dump-fab.png`):

  ```
  /sdcard/Android/data/dev.k3vinb5.freesocial/files/dumps/dev.k3vinb5.freesocial-20261105-004959-421.json
  ```

  Pulled as `09c-rule-inspector-dump.json`. The dump contains **our own inspector screen**, 35 nodes, `package: dev.k3vinb5.freesocial`, `screen: "Instagram"`, because `captureForDump()` falls back to the focused window and Instagram was not open. Sample lines:

  ```json
  {"viewId": null, "className": "android.widget.FrameLayout", "text": null, "contentDescription": null, "bounds": "[0,0][1080,2424]", "visible": true}
  {"viewId": "android:id/content", "className": "android.widget.FrameLayout", "text": null, "contentDescription": null, "bounds": "[0,0][1080,2424]", "visible": true}
  {"viewId": null, "className": "androidx.compose.ui.platform.ComposeView", "text": null, "contentDescription": null, "bounds": "[0,0][1080,2424]", "visible": true}
  {"viewId": null, "className": "android.view.View", "text": null, "contentDescription": null, "bounds": "[0,152][1080,2298]", "visible": true}
  {"viewId": null, "className": "android.view.View", "text": null, "contentDescription": null, "bounds": "[0,152][1080,2298]", "visible": true}
  ```

- Note: the list rows show "— nodes" for every entry because the inspector does not read back the written files; the dump itself is complete.

---

## Predicted rough spots confirmed?

**#1 — feed-cell `REDIRECT_HOME` harshness: CONFIRMED, worse than predicted.**
The rule is precise (no hit on a photo-only feed, hit on the first video cell), but the action is a full `GLOBAL_ACTION_HOME`. There is no scroll-past, no in-place removal, and because Instagram restores the same feed position, reopening the app is kicked again in under a second (`01j`). A user cannot use Instagram at all while any video post is (or becomes) visible — including the very first post. Same shape on YouTube Home (`shorts_shelf`), which additionally makes the layer-2 Shorts player unreachable (scenario 5).

**#2 — `ig_reels_tab` unconditional Home-tap: CONFIRMED, and inverted.**
The prediction was "the rule matches the tab bar on every screen, so it forces you back to Home". The observed behaviour is the opposite: `TAP_HOME_TAB` taps the *matched* node, which is `clips_tab` (the **Reels** tab). On Profile and Home the engine therefore navigates **to Reels** within 0.4 s (`02d` → `02e`, `02f`); on the Reels screen itself it re-taps Reels ~1/1.3 s forever (`02c`). It is not an idempotent Home-tap and it is not a no-op — it is an unescapable redirect into the surface the app is supposed to hide.
(It also means the `RuleGate` guarantee that layer 1 cannot be *destructive* does not hold here: layer 1 actively moves the user into the blocked surface.)

`RULES.md` §"Deliberate deviations" item 1 describes the expected fix (a `selected` predicate) but does not describe this action-target bug; the two are independent.

---

## Contradictions with `docs/SPEC.md` §2.1–2.2

1. **§2.1 "gesture tap on the safe Home tab"** — the engine taps the Reels tab (`FilterService.perform`, `Action.TAP_HOME_TAB` → `clickNode(hit.node)`; rule has no `viewIdTarget`). Observed: Profile/Home → Reels in <400 ms.
2. **§2.2 "when it runs out, filtering goes absolute — viewers exit"** — only partially. On the tab-based IG Reels screen both `reels_tab` (layer 1) and `clips_viewer` (layer 2) match, and `RuleGate.activeHits()` returns only layer-1 hits, so the layer-2 BACK runs at most once (usually on the deep-link screen) and never again; the viewer keeps playing with the budget exhausted or the schedule locked (`07c`, `08c`).
3. **§2.1 "Reel cells in feed … neutralise" / "Shorts shelf … neutralise"** — implemented as `REDIRECT_HOME` (documented deviation in `RULES.md`), but the runtime consequence is app-level eviction on the first frame, not surface-level hiding; §2.1 also promises the *app stays usable* in Filter mode ("App usable; short-video surfaces neutralised", §2). It is not usable: IG feed and YT Home both bounce to the launcher.
4. **§2.2 "deep links and the Reels/Shorts tabs work" while the budget lasts** — Reels tab "works" but the user is trapped in it by the layer-1 tap (scenario 2), and the bare YouTube Shorts deep link never reaches the player (scenario 5).

Not contradictions, but worth knowing: `am force-stop <app>` followed by relaunch does **not** rebind the accessibility service (Android removes the binding until the service is re-toggled in secure settings), and the inspector's dump button always opens a share chooser.

## Evidence index (`reverse/runtime-verify/`)

| file | proves |
| --- | --- |
| `00-hub.png` | hub with *Filtering on* and the service enabled |
| `01f`, `01l`, `01g-*`, `01j` | IG feed photo (no hit), video cell with the rule off, launcher after the kick, relaunch kicked again |
| `02a`–`02g` | Reels tab stays Reels; Profile → Reels; Home → Reels |
| `03` | IG reel deep link plays, budget ticks |
| `04` | YT Home → launcher via `shorts_shelf` |
| `05`, `05c`, `05d` | YT shorts link never reaches the player |
| `06a`–`06g` | TikTok photo/live not counted, video plays and counts |
| `07a`–`07d` | budget exhausted: hub, IG BACK then Reels loophole, YT dual action |
| `08a`–`08e` | schedule locked: hub, IG, YT, TikTok |
| `09a`–`09c`, `09c-rule-inspector-dump.json` | inspector + dump of our own window |
| `09z-hub-restored-final.png` | state restored (20 min left, Schedule Off) |
| `logcat-filterservice-excerpt.txt` | raw `FilterService` lines from the final re-run |
