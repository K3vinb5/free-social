# free-social — rebuild spec

Status: **FINAL** — investigation complete. Scope decisions 2026-11-04 (§7): content
filtering is the focus; v1 covers **Instagram, YouTube, TikTok only**; added the
**Short-video budget** (§2.3). The reverse-engineering analysis (8 docs, with the
file:line evidence behind every claim) and the dropped-feature notes were archived out
of the distributed tree on 2026-11-06 — see the archive stubs under `docs/`.

## 1. Goal

A minimal Android app (Kotlin, Jetpack Compose + Material 3 — UI toolkit decided
2026-11-04; no webview, no network backend)
that reproduces SocialLite 3.0.1's content blocking on the **real** apps. Primary feature
(user's words): *keep me out of shorts, reels and other short-video surfaces inside the
apps — not out of the apps themselves*.

1. **Content filtering (the focus)** — `FilterService` neutralises short-video surfaces
   inside Instagram, YouTube and TikTok via AccessibilityService.
2. **Short-video budget** — one combined daily allowance of short-video across all three
   apps; when it runs out, filtering goes absolute (§2.3).
3. **Whole-app blocking (secondary, opt-in per app)** — `BlockService` + `BlockActivity`.
   Never a default.
4. **Schedule** — a nightly lock window (§2.4).

Out of scope for v1 (analysed where noted, deferred): Snapchat/X/Facebook rules
(fully analysed before archiving — re-add later from those tables), the webview social
client + JS injection, VPN/DNS filtering, notification
filtering, per-app daily limits, "Hard Mode", break/post-mode timers, billing/paywall,
kid/parent accounts and sync, widgets, awards, onboarding media, device-admin uninstall
protection (the original's is empty-policy theatre).

## 2. Product model

Per platform a three-state pref plus per-package overrides (`01a` gates 3–4):

| State | Effect | Our default |
| --- | --- | --- |
| **Filter** | App usable; short-video surfaces neutralised | **yes** (IG, YT, TikTok) |
| **Block** | Block screen on foreground | opt-in only |
| **Allow** | Nothing happens | — |

11 hard-coded packages map to 7 platform slugs in the original; v1 ships mappings for
`com.instagram.android`, `com.google.android.youtube`, `com.zhiliaoapp.musically` +
`com.ss.android.ugc.trill` (TikTok), plus a `blockedAppPackages` set for custom packages.

### 2.1 Content filtering (the focus — source of truth: `03a`, `03b`, `03c`)

`NativeReelsBlockerService` in the original — AccessibilityService (`typeViewClicked|
Scrolled|Selected|WindowContentChanged|WindowStateChanged`, 50 ms timeout,
`flagReportViewIds`, `flagRetrieveInteractiveWindows`, `canPerformGestures`,
`canRequestFilterKeyEvents`).

Engine (`03a`): one single-flight scan per event batch, 1200 ms action debounce,
400-node walk cap, per-app action quota. Detection primitives: visibility+rect tests,
view-id lookup (budget-200 deep fallback), text/view-id match on visible nodes,
DM-composer detection ("don't interfere"), player-node heuristics, selected bottom-nav
"Reels" tab detection, per-video key tracking (new key = new reel).

Neutralisation actions: global Back; gesture tap on the safe Home tab (with
`performAction` fallback); targeted click (e.g. Instagram's DM tab as "get me out");
a WindowManager overlay; `disableSelf()`. Screenshots are declared but unused.

**Direct-engagement policy (2026-11-05, authoritative):**

1. **Passive surfaces are never touched.** Feed reel cells, Shorts shelves and anything
   the user is merely scrolling past get no action at all — no scroll, no redirect, no
   tap. Auto-scrolling the target app's page was actively harmful and is retired.
2. **Direct engagement only.** The engine acts solely when the user deliberately enters a
   short-video surface: the IG Reels viewer/tab, the YT Shorts player, TikTok playback.
   While the daily budget lasts the surface *pays* (stopwatch) and is left alone; once
   exhausted every entry is escaped consistently, including immediate re-entries.
3. **No allow-then-deny.** The outcome per entry is deterministic: there is no
   post-escape suppression window. The 1200 ms action debounce only absorbs event storms
   within one surface episode and never turns an escape into an allow.
4. **Exact surface classification.** A capture has one surface identity; rules scoped to
   a feed cannot match inside the viewer/player and vice versa. `SurfaceClassifier`
   (`DIRECT` / `PASSIVE` / `UNKNOWN`) drives the verdict instead of the JSON `layer`.
5. **Inspector captures pause enforcement.** While a rule-inspector capture is armed the
   engine performs no action on any package (and the stopwatch stops), so the user can
   navigate freely; consuming or expiring the capture restores enforcement.

v1 rule set — Instagram, YouTube, TikTok only (full tables incl. deferred apps in
`03b`/`03c`):

| App | Rule | Match | Action | Policy |
| --- | --- | --- | --- | --- |
| Instagram | Reel cells in feed | `video_container` | **none** (retired) | passive |
| Instagram | Reels tab | selected bottom-nav "Reels" tab detection (`x`/`y`/`p`/`z`) | pay / Home tab when exhausted | direct |
| Instagram | Reels viewer (clips) | `clips_viewer_*` ids | pay while budget lasts; exit when exhausted | direct |
| Instagram | Deep-link open | `ClipsUrlHandlerActivity`/`MediaUrlHandlerActivity` RESUMED | lands in the viewer: pay / exit per budget | direct |
| YouTube | Shorts shelf | `Shorts` + "play Short" text | **none** (retired) | passive |
| YouTube | Shorts player | `reel_recycler` id | pay while budget lasts; Back when exhausted | direct |
| TikTok | Any playing video | FYP chrome + TextureView/`SurfaceView` | pay while budget lasts; one Back when exhausted | direct |
| TikTok | DM carve-out | "Back to chat"/"Reply privately" (8-locale text) | skip | — |

Retired rules stay in `rules.json` with `enabled=false` and their evidence in `notes`; the
loader rejects an enabled `SCROLL_PAST`, so the policy is enforced in code, not just data.


Brittleness (inherent, surface it in our UI): exact view-ids, locale string gates,
pure-geometry layout tests — all **fail open**, silently letting content through after a
redesign. Rules live in a JSON asset (updatable without code) + a per-app "rules last
verified against version X" note.

### 2.2 Short-video budget — "Reels & Shorts budget"

Name: **Short-video budget** (UI copy: "Reels & Shorts budget"). One combined daily
allowance across Instagram + YouTube + TikTok — *not* a per-app limit.

- **What counts:** actual time in a direct short-video surface — IG clips viewer (and the
  Reels tab), YT Shorts player, TikTok playback. The FilterService already identifies these
  surfaces; a stopwatch ticks while such a surface is on screen. One counter: `{date, seconds}`
  in prefs, reset at local midnight. Config: minutes per day (default 20), live "X min left
  today" in settings.
- **While the budget lasts:** every direct surface plays normally and pays the stopwatch.
  Passive feed cells and Shorts shelves are never touched (they are not hidden or scrolled
  either — the feed is left exactly as found).
- **When it runs out:** filtering goes absolute — every entry to a direct surface is escaped
  consistently (viewers Back out, the Reels tab goes Home, players Back out), including
  immediate re-entries, with no allow window. Until the midnight reset.
- **During the schedule window (§2.3):** budget is treated as exhausted.
- **While a rule-inspector capture is armed:** no package is acted on and the stopwatch stops;
  consuming or expiring the capture restores enforcement automatically.

This replaces per-app screen-time limits with what the user actually asked for: *access
to all filtered content combined, for a configured amount of time daily*.

### 2.3 Schedule (nightly sleep window)

One model: per-weekday **bedtime** and **wake** (minutes from midnight). The locked
window is exactly bedtime → wake, with no margin settings. The window wraps overnight
(default 22:30→07:00, i.e. 8 h 30 m).

- **Mandatory, ≥6 h:** the schedule cannot be disabled (no master toggle, no prefs key
  is read) and every day's window must be at least **6 hours**. The editor validates
  the candidate live ("8h 30m · minimum 6h") and refuses to save a shorter window; the
  repository rejects it too, and invalid stored days from older builds migrate to the
  default night instead of crashing.
- Effect while active: budget treated as exhausted (all direct surfaces escaped)
  and Block-mode apps get the block screen. Nothing overrides it: the filtering master
  switch does not shorten or pause the window.
- Mechanism: compute the window at every decision (timezone/DST-safe); one
  `AlarmManager.setExactAndAllowWhileIdle` at the next boundary flips state even if
  nothing else happens; screen-on/off, boot, time-change events also re-evaluate.
  (The original polls every minute inside the running service and its alarm receivers
  only *cancel* alarms — dead code; we don't copy that bug.)

### 2.4 Settings + setup

**First-run wizard** (each step verified live, can be re-run from settings):
1. Android 13+: app menu → **"Allow restricted settings"** (sideloaded a11y requirement).
2. Enable `FilterService` (system Accessibility screen, our toggle).
3. Optionally enable `BlockService` (same screen, second toggle).
4. "Display over other apps" (overlay escape hatch).
5. Battery exemption (so OEM task killers leave the services alone).

**Settings screens:** per-app rows for IG/YouTube/TikTok (Filter / Block / Allow) with
per-surface toggles (feed suggestions, Reels tab, Shorts shelf, Shorts player, TikTok
playback); budget stepper + "X min left today"; schedule editor (per-day bedtime/wake with a
live window length and the six-hour floor); permissions page with live state and
one-tap jumps; per-app
"rules last verified against version X" note + the rule inspector (§2.5).

**Live status:** reactive re-checks (ContentObserver on enabled accessibility services,
AppOps for overlay/battery) — a persistent banner whenever enforcement is degraded.

### 2.5 Rule maintenance (how we fix rules after app updates)

The app cannot know a rule went stale — rules **fail open** (a rule that no longer
matches simply does nothing), so at best we *warn*: "rules last verified for Instagram
v318, you have v322". We never need SocialLite again: it is not the source of rules —
**the target apps' own UI is**. Verification tooling we build ourselves:

1. **Rule inspector** (hidden developer screen, ships in v1): the service already walks
   node trees — dump the live hierarchy of any screen (view-ids, text, bounds,
   class names) and export it. This is how rules get authored and patched, on any device
   with the target app installed.
2. **adb/uiautomator dumps** on a device or emulator (`uiautomator dump`) — diff the
   view-id inventory across app versions to see exactly what changed.
3. **Multi-signal rules**: match on view-id + content-description + structure/geometry
   together, so one rename does not kill a rule.
4. Later (not v1): scripted scroll sessions on an emulator + automatic dump diffs.

## 3. Reproduce / drop decisions

| Mechanism | Decision | Why |
| --- | --- | --- |
| Content-filter service + per-app rules (IG/YT/TikTok) | **Reproduce, data-driven** | The core requirement |
| Short-video budget | **New (replaces per-app limits)** | User's 2026-11-04 request: combined daily allowance for filtered content |
| Three-state per-platform model | **Reproduce** | The original's shape; defaults to Filter |
| Whole-app block screen + gate tree | **Reproduce as opt-in** | Never a default |
| Nightly schedule | **Reproduce, one window** | With real alarms, unlike the original |
| Rule inspector | **New** | Only honest answer to rule maintenance (§2.5) |
| Screen-off/locked bailout | **Reproduce as-is** | Deliberate |
| Snapchat/X/Facebook rules | **Defer (analysed)** | User: focus on IG/YT/TikTok for now; tables kept in the archived analysis notes |
| Notification filter, per-app daily limits, "Hard Mode", break timers | **Drop (decided 2026-11-04)** | See the archived analysis notes |
| Paywall gate, kid/parent sync, billing, widgets, awards, webview, VPN/DNS, device-admin | **Drop** | Out of scope / theatre / bypassable |

## 4. Architecture (clone)

- **`FilterService`** (primary) — single-flight scan on the six event types, debounce/
  caps/quota as in the original; rule sets from a JSON asset; direct-engagement
  classification (`SurfaceClassifier`) per §2.1–2.2; owns the budget stopwatch and the
  schedule predicate; pauses all actions while an inspector capture is armed.
- **`BlockService`** (opt-in) — `typeWindowStateChanged` → gate tree → `BlockActivity`
  (`singleInstance`, `excludeFromRecents`, empty taskAffinity, `NEW_TASK|CLEAR_TASK`);
  500 ms per-package dedupe.
- **`SettingsActivity`** + first-run wizard (§2.4); **`ScheduleState`** — computed at
  check time, one exact alarm per boundary, broadcast on change.
- **Rule inspector** (dev screen, §2.5).
- Persistence: SharedPreferences, original key semantics where we reproduce a feature.
- Testing: unit tests for the gate tree, schedule-window math (overnight wrap, six-hour
  floor, per-day override, timezone change), and budget accounting (tick/reset/absolute mode).

## 5. Platform constraints

- Package name: **`dev.k3vinb5.freesocial`** (decided 2026-11-04); app label "Free Social".
- Android 13+: sideloaded apps need **"Allow restricted settings"** before the a11y
  toggles work. Setup flow covers it.
- Accessibility services die under aggressive battery savers → settings banner.
- Node rules break on app updates and fail open (§2.5).
- Original minSdk 32 / targetSdk 36 — match.

## 6. Verification plan (acceptance)

- `assembleDebug` green; gate-tree, schedule and budget unit tests pass.
- Runtime (device/emulator — **gap, 2026-11-04**: `adb` empty; this bare-metal Ryzen box
  has AMD-V (`svm` flag present) but the `kvm_amd` kernel module is not loaded, so
  `/dev/kvm` is missing and the Android emulator cannot start until the user runs
  `sudo modprobe kvm_amd` (persist via `/etc/modules-load.d/`). After that, an AVD with
  a Play Store system image covers development + rule authoring; a physical phone with
  logged-in IG/YT/TikTok remains the best final-acceptance target because TikTok/Play
  Integrity may refuse to run on AVDs):
  1. IG in Filter mode: the feed is untouched (reel cells left in place); opening a reel
     via the Reels tab or a deep link plays and ticks the budget while it lasts.
  2. YT Shorts shelf untouched; Shorts player plays within budget, Back-ed when exhausted.
  3. TikTok playback counts against the same budget; after exhaustion every playback entry is
     backed out, including immediate re-entries (no allow window); DMs still usable.
  4. Budget: set 1 min → consumption across all three apps stops at 1 min total.
  5. Schedule: window now+2 min → all direct surfaces filtered out at the boundary, returns at
     wake (exact alarm).
  6. Opt-in Block mode on one app → block screen, back goes home, re-open re-blocks.
  7. Disable the accessibility service → settings shows the failure banner.
  8. Rule inspector dumps a live node tree for IG/YT/TikTok screens, and enforcement pauses for
     the duration of an armed capture.
