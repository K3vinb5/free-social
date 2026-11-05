# Defect diagnosis — SCROLL_PAST no-op loop, TikTok budget accrual

Read-only investigation on build `cbaedb1`, 2026-11-05. No code changed. Line numbers are from
`cbaedb1`; evidence files are read from the tree, not re-captured.

---

## D1 — `SCROLL_PAST` is a no-op and busy-loops

### Observed

- `reverse/runtime-verify2/logcat-excerpt.txt`: **164** `com.instagram.android/feed_reel_cell via
  SCROLL_PAST` and **38** `com.google.android.youtube/shorts_shelf via SCROLL_PAST` lines (202
  total). Median interval 1.27 s; bursts stop at 18–20 hits and resume 20–44 s later.
- `02f-video-hold-A.png` vs `02f-video-hold-B-6s.png`: pixel-identical except the header strip
  (diff bbox `(150,239)-(743,593)`) after 3 hits in 6 s. `01f-shelf-A/B/C` likewise.
- `FINAL-ACCEPTANCE.md` §1/§4. The feed does advance occasionally (`01g-sw7-b` → `01h`), so the
  gesture is not inert everywhere.

### What the engine actually logs

`FilterService.kt:163` — `Log.i(TAG, "neutralising ${appPackage}/${hit.rule.surface} via ${hit.rule.action}")`.
Precondition: `RuleGate.decide` == NEUTRALISE **and** `RuleGate.chosen` returned a hit **and**
`ActionThrottle.tryAcquire` granted. The line is emitted **before** `perform()` (`:164`), so it
proves only that the engine decided; it says nothing about dispatch or effect. `scrollPast()`
(`:218-226`) calls `dispatchGesture(builder, null, null)` and **discards the boolean return**, with
no `GestureResultCallback`; `ScrollPastGesture.swipePast()` returning null (`:219`) also returns
silently with no log. There is no "no-op" log statement anywhere (grep: the only `no-op` string in
the tree is a comment in `ActionThrottle.kt:59`). A no-op therefore looks exactly like a success in
the log.

### Geometry actually emitted

`ScrollPastGesture.swipePast` (`ScrollPastGesture.kt:22-29`): start = `(centerX, bottom − 0.1·h)`,
end = `(centerX, start − 0.6·h)`, 220 ms (`FilterService.SWIPE_DURATION_MS`). Recomputed from the
dump XML bounds (1080×2424 device):

| rule / dump | matched node bounds | h | emitted stroke |
| --- | --- | --- | --- |
| `ig_feed_reel_cell` — `233205-ig_feed_scroll3.xml` idx 23 | `[0,152][1080,1096]` | 944 | `(540,1002) → (540,436)`, 566 px, 220 ms |
| `ig_feed_reel_cell` — `233157-ig_feed_scroll2.xml` idx 23 | `[0,152][1080,673]` | 521 | `(540,621) → (540,309)`, 312 px |
| `ig_feed_reel_cell` — `165537-ig_feed.xml` idx 101 | `[0,2030][1080,2172]` | 142 | `(540,2158) → (540,2073)`, 85 px |
| `yt_shorts_shelf` — `170110-yt_home.xml` idx **81** (header `content-desc="Shorts"`) | `[169,1380][310,1438]` | 58 | `(239,1433) → (239,1399)`, **34 px** |
| `yt_shorts_shelf` — same file idx 85 (first `"… - play Short"` card, *not* used) | `[32,1477][531,2225]` | 748 | `(281,2151) → (281,1703)`, 448 px |

The YT anchor is the **58 px shelf header label**, not a card: `RuleMatcher` takes the first visible
match of `contentDescriptionRegex` in pre-order (`RuleMatcher.kt:58-63`), and the header
(`"Shorts"`, idx 81) precedes the cards (idx 85). A 34 px drag is far below any scroll/touch-slop
threshold and starts on a non-clickable, non-scrollable `ViewGroup` — it cannot move the shelf.
`MIN_HEIGHT_PX` (32) does not save this: 58 > 32, so a gesture is built and logged as a hit.

For IG the swipe is 60 % of the *media cell*, not of the scrollable: the parent that actually
scrolls is `androidx.recyclerview.widget.RecyclerView id=list` (`scrollable=true`,
`[0,152][1080,2172]`, ancestor of every `video_container` in `233149/233157/233205`). The matched
node itself is `clickable=false` and wraps `media_group` (**clickable=true**) and a `TextureView`
covering the whole video area (`233205` idx 35/36), i.e. the touch lands on the video surface, and
the stroke ends 566 px up — not one cell (≈845–944 px) or one viewport, and with no fling velocity
to speak of, so the list can settle back to the same post. On the sliver case above the stroke is
85 px and starts 14 px above the bottom gesture area.

### Why it re-fires every ~1.3 s

`ActionThrottle` debounce is **1200 ms per surface key** (`ActionThrottle.kt:39`, `:20`), so the
loop floor is 1.2 s + scan latency ≈ the observed 1.27–1.30 s median. The loop is closed by
accessibility events: a playing video / animated shelf card keeps emitting
`TYPE_WINDOW_CONTENT_CHANGED`, so `onAccessibilityEvent → requestScan` (`:102`) runs again, the
same node still matches (`decide` == NEUTRALISE), and the debounce has just expired. Bursts end at
18–20 hits and resume 20–44 s later — the per-app quota (20 per 60 000 ms, `ActionThrottle.kt:40-41`,
`:24`) is being spent on no-ops. The retry cadence is intended (debounce + quota); the *unbounded
retry* is not: nothing records that the previous SCROLL_PAST failed, and nothing re-checks the
outcome, so the service retries forever on an unchanged surface.

### Root cause

`FilterService.kt:218-226` drives a fire-and-forget synthetic swipe computed from the matched
node's own bounds (`ScrollPastGesture.kt:22-29`) — not from the scrollable ancestor — and never
reads the dispatch result or verifies the surface afterwards (two `dispatchGesture(..., null, null)`
call sites, `:204` and `:225`). Combined with the shelf rule anchoring on a 58 px header label
(`rules.json` `yt_shorts_shelf` + `RuleMatcher.kt:58-63`), the emitted gesture cannot move the
target, the hit persists, and the 1200 ms throttle turns the persistence into a 1.3 s loop.

### Proof

- The log line cannot distinguish success from failure: `:163` precedes `:164`, and `:225` throws
  away the only return value (no callback).
- Geometry table above: 34 px on the YT header; 566 px on a 944 px IG cell inside a 2020 px
  scrollable that needs ≥ one cell height.
- Two screenshots 6 s and 3 hits apart are pixel-identical in the body (`02f`), while the same log
  line is emitted 3 times — so the logged action demonstrably had no effect.
- Burst sizes 18–20 with 20–44 s silences match `quotaMax=20 / quotaWindowMs=60 000`; inter-hit
  median 1.27 s matches `debounceMs=1200`.

### Fix direction

Stop using the matched node as the gesture frame: resolve the nearest scrollable ancestor
(`RecyclerView id=list` for IG, `results` RecyclerView / `browse_fragment_layout_coordinator_layout`
ScrollView for YT) and perform `ACTION_SCROLL_FORWARD` on it, or emit a swipe that spans a full
viewport (start ≈ 0.8·scrollable.top..bottom, end ≈ 0.2) so one gesture is guaranteed to displace a
post/shelf; require the YT rule to match only `content-desc` ending in ` - play Short` (or the shelf
container) so a 58 px header can never be the anchor; attach a `GestureResultCallback` and treat
`onCancelled`/`false` as failure with backoff; re-capture after each action and, if the same surface
is still present after N consecutive attempts, stop (escalate to BACK) instead of looping.

### Alternatives ruled out

- *Rect degenerate / rule not matching*: no — bounds are non-empty, height 58 and 521–944 > 32, and
  the 164/38 logged hits prove the match and the gate both fired.
- *Throttle is broken*: no — 1.27 s median and 20-per-minute bursts are exactly the configured
  debounce and quota; the throttle is working, it is just being fed a surface that never goes away.
- *The gesture is never dispatched*: possible but unprovable and not required — either way the
  engine has no result, which is the defect. A 220 ms stroke cannot be observed from logcat (no
  callback, no return-value log), which is why one device repro would not resolve it either.

---

## D2 — TikTok budget accrual "regression"

### Observed

- `FINAL-ACCEPTANCE.md` item 3: FYP video playing ~30 s (`06a-tt-fyp-8s` … `06d-tt-fyp-30s`),
  `budget_used_seconds` stayed **0**, no `tiktok_playback` line, `video_seek_bar` reported present.
- Pass 1 (`docs/rules/RUNTIME-VERIFY.md` §6, build **`d1f241a`**): stopwatch 80 → 90 s. The
  FINAL-ACCEPTANCE statement "on the same build" is wrong — pass 1 predates `cbaedb1`.
- `logcat-excerpt.txt`: **zero** FilterService lines between `01:24:00.662` and `01:37:20.955`; the
  TikTok window is `01:33:41.691` (SplashActivity) → `01:37:20.543` (IG relaunched).

### Rule vs node, field by field

`tiktok_playback` is unchanged since `d1f241a` (`git diff d1f241a cbaedb1 -- rules.json` touches
only `ig_feed_reel_cell`, `ig_reels_tab`, `yt_shorts_shelf`). The only dumps in the repo that
contain the node are `001928-tt_dm_video_paused.xml` (pre-order idx 141/163) and
`234958-tt_consent_check.xml` (idx 142/164), both `[0,2130][1080,2204]`:

| criterion | rule value | node value | verdict |
| --- | --- | --- | --- |
| `viewId` | `com.zhiliaoapp.musically:id/video_seek_bar` | same | **match** |
| `classNameRegex` | `android\.widget\.LinearLayout` | `android.widget.LinearLayout` | **match** |
| `selected` | absent (`null`) | `selected="false"` | ignored — the `cbaedb1` sweep never touched this rule |
| `clickable` | not a `MatchSpec` field | `clickable="false"` | n/a — `clickable` is captured (`NodeTree.kt:34`, `NodeTreeReader.kt:67`) but no rule and no matcher branch reads it |
| `isVisibleToUser` (engine-side only) | must be `true` (`NodeTree.kt:67-68`) | **absent from uiautomator XML** | **unverified** |
| capture index | `< 200` visible for the view-id lookup (`RuleMatcher.kt:46`), `< 400` overall (`NodeTree.kt:45`) | 141 and 142 | fine in these trees; FYP tree unknown |

The three FYP dumps are not FYP dumps: `170142-tt_fyp.xml`, `170215-tt_fyp_video2.xml` and
`170230-tt_fyp_video3.xml` are **byte-identical** (md5 `2b65a2ef15d7f462e1ade06fb5aed929`, 35 nodes)
and contain the TikTok "Choose how ads are shown" bottom sheet, no `player_view` and no
`video_seek_bar` (confirmed against `170142-tt_fyp.png`). The bounds quoted in FINAL-ACCEPTANCE are
verbatim the `001928`/`234958` node. So "seek bar present in the FYP tree" is **not evidenced
anywhere in the repo**; the only real FYP tree is `235543-tt_fyp_next2.xml` (207 nodes, photo post,
no seek bar — the documented negative control).

### Root cause

Not a code regression. `cbaedb1` changed nothing on this path: the rule JSON is identical to the
pass-1 build, the `selected` criterion was applied only to `ig_reels_tab`, `clickable` is dead data,
and the budget block (`FilterService.kt:150-157`) — which runs **before** the gate — is untouched
(the diff only replaces `RuleGate.activeHits` with `chosen` at `:161`, after accounting). Therefore
neither the "tab/player chrome sweep" nor the priority gate can explain the zero.

What remains is that no *visible* `video_seek_bar` was in the tree the service captured.
**Strongest hypothesis (unproven), in order:**

1. `isVisibleToUser == false` on a playing FYP video. TikTok exposes the seek bar only while
   scrubbing/paused; the strip sits under the caption/nav area and no progress bar is visible in
   `06a`/`06d`, while uiautomator (which does not filter on `isVisibleToUser`) still lists it.
   `NodeTree.visible()` (`NodeTree.kt:67-68`) then drops it and the rule can never match, which
   suppresses both the accrual (`:150-155`) and the exhausted-BACK.
2. Wrong window root. `activeRoot()` (`FilterService.kt:251-256`) takes the first focused
   `TYPE_APPLICATION` window. TikTok demonstrably runs a second application window: the 17:01 dumps
   captured the ad-preferences sheet while a video played behind it (`170142-tt_fyp.png`). If such a
   window is focused, the 35-node sheet tree is scanned instead of the FYP.
3. Beyond the 200-node view-id budget (`RuleMatcher.kt:44-50`). Weak: the only real FYP tree (207
   nodes) places the seek-bar slot between the player subtree (idx 148-164) and the bottom nav
   (idx 187), i.e. ≈ idx 186 — inside 200 by ~14 nodes, but with no margin for a video post's extra
   player nodes.

Two test-condition confounds are also open and would produce the same 0 s on their own:
`schedule_enabled=true` with the restored 22:30/07:00 (+30/+30 → locked 22:00–07:30) would make
`:155` skip accrual by design — but `13b-hub-restored-final.png` (01:38) post-dates the TikTok
window and the scenario-10 window ended 01:27, so this is unlikely; and the 23-minute FilterService
silence means an unbound service cannot be excluded.

### Proof

- Field table: the two rule criteria that exist both match the only real instances of the node; the
  `selected`/`clickable` sweep did not add a falsifying criterion.
- `git diff d1f241a cbaedb1 -- app/.../filter app/.../core app/.../settings app/src/main/assets/rules.json`:
  9 files, none touching the TikTok rule or the stopwatch.
- Pass 1 counted (80→90 s) on `d1f241a` with the **same** rule file entry; the only runtime
  difference available in the evidence is the captured tree.
- The saved FYP dumps contain the ad sheet, so the "seek bar present" premise is unsupported.

### Missing evidence (would settle it in one run)

A service-side capture of the FYP while a video plays — i.e. `captureForDump()` pointed at the
focused target window (it currently only sees our own inspector window, defect 4), or a dump taken
through `dumpsys`/the engine's own `NodeTreeReader` — plus the `isVisibleToUser` flag and the
pre-order index of `video_seek_bar`, and `dumpsys accessibility` during the same window to prove the
service was bound.

### Fix direction

Do not depend on a node that the host can hide: either drop the `visible` requirement for this
rule's anchor (accept `isVisibleToUser || !bounds.isEmpty`) or key on a window-level signal that is
present during playback; at minimum raise/remove the 200-node view-id lookup cap so a rule anchored
on a view id scans the whole 400-node capture. In parallel, make the inspector capture the focused
target window so this class of defect is verifiable next time, and record `schedule_enabled` /
`isLocked` and the bound state alongside every budget measurement.

### Alternatives ruled out

- *`selected` criterion over-applied to player chrome*: no — `tiktok_playback` has no `selected`
  field; only `ig_reels_tab` gained one.
- *`clickable` flag*: no — `MatchSpec` has no such field and `RuleMatcher` never reads
  `NodeSnapshot.clickable`.
- *Gate/stopwatch rewiring*: no — accounting at `:150-157` is upstream of `RuleGate.chosen` and is
  byte-identical between the two builds; SKIP/priority changes cannot stop accrual.
- *Rule JSON drift in `cbaedb1`*: no — the TikTok rule is byte-identical to the pass-1 build that
  measured 80→90 s.

---

## D8 — ghost tree (ColorOS Reno4, Android 13)

### Observed

- The accessibility service receives the foreground app's window, but its root is **decor-only**.
  Every scan line is `windows=1 [YouTube(0)=3/1 ids]`: 3–4 nodes —
  `android:id/navigationBarBackground`, `android:id/statusBarBackground`, and an empty
  `android:id/content` — and no `com.google.android.youtube:id/…` node at all.
- `uiautomator dump` of the **same** window at the same time shows 136–137 nodes with real YouTube
  view ids. So the content exists in the hierarchy; only our service's view of it is empty.
- Own-package (`dev.k3vinb5.freesocial`) and system windows stay rich, which is what separates
  "the OS hands us a stub" from "our capture code drops the children".

### Ruled out (do not re-litigate)

- *Service XML / capabilities*: the compiled service config has
  `flagIncludeNotImportantViews|flagReportViewIds|flagRetrieveInteractiveWindows` and
  `canRetrieveWindowContent=true`; `dumpsys accessibility` reports `capabilities=41`
  (includes `CAN_RETRIEVE_WINDOW_CONTENT`).
- *NodeTree walk*: `NodeTreeBuilder.snapshot` calls unfiltered `getChild(i)` for every child; there
  is no visibility, class or depth filter before the 400-node cap (and the ghost tree has 3 nodes).
- *Anything overriding `serviceInfo`*: no code assigns `serviceInfo` / `setServiceInfo`.
- *Stale pre-grant binding*: re-binding at 08:45 (grant 08:01) changed nothing; the capabilities
  bitmap already contains `CAN_RETRIEVE_WINDOW_CONTENT`.

### Two remaining hypotheses

- **H-OURS** — our capture path feeds the walk a stale/truncated root: `window.getRoot()` and
  `rootInActiveWindow` disagree, or the tree we walk is not the tree
  `findAccessibilityNodeInfosByViewId` sees (which would mean the content *is* reachable from our
  service and the truncation happens in our input/walk).
- **H-VENDOR** — ColorOS permanently strips foreign-app content subtrees from third-party
  accessibility services, while the app's own windows and system windows stay rich.

### Probe (one Log.i line, max 1 per package per 10 s, every `TYPE_WINDOW_STATE_CHANGED`)

Fires for **every** package, including `dev.k3vinb5.freesocial` and system packages — it is
deliberately not gated on `targetFor`. `root` is always `rootInActiveWindow`.

```
probe <pkg> rootPkg=<pkg|none> rootChildCount=<n> rootViewId=<id|none> child0Grandchildren=<n|none> idProbe=<k=v ... | n/a>
```

- `rootPkg` — `root.packageName`, so a probe whose root belongs to another window (our inspector,
  system UI) can never be mistaken for the event package's tree.
- `rootChildCount` — `root.childCount` (ghost tree: 3).
- `rootViewId` — `root.viewIdResourceName` (ghost tree: `android:id/content` or similar).
- `child0Grandchildren` — `childCount` of `root.getChild(0)`, absent when the root has no child.
- `idProbe` — `findAccessibilityNodeInfosByViewId` result **counts** for the foreground package's
  candidate ids. For `com.google.android.youtube`: `menu_item_0`, `app_bar_wrapper`, `results`.
  Other packages log `n/a` (0 is a valid, meaningful count). The existing per-target `scan` line
  carries the same `rootChildCount` / `idProbe` fields for the captured app window, so both paths
  report.

Neither line changes matching, actions or budget: diagnostics only.

### What confirms which hypothesis

| observed probe output | verdict |
| --- | --- |
| YouTube: `rootChildCount=3` **and** `idProbe menu_item_0>=1` (or `results>=1`) | **H-OURS** — the view-id lookup reaches content the raw `getChild` walk does not; our root input/walk is the truncation. |
| YouTube: `rootChildCount=3` **and** `idProbe menu_item_0=0 app_bar_wrapper=0 results=0`, **while** `probe dev.k3vinb5.freesocial rootChildCount>=2 child0Grandchildren>=1` (own app rich) **and** system-package roots rich | **H-VENDOR** — the content is unreachable from a third-party service even by view-id lookup, while the service's own/system windows stay complete; ColorOS strips foreign content subtrees. |
| YouTube: `rootChildCount` large (≫3) but `idProbe …=0` | neither as stated: WindowStateChanged fires before the content populates; re-probe on the next event (the 10 s window may hide it) and treat as a timing variant of H-OURS input. |
| probe root is decor-only **for every package including our own** | vendor-independent: the service as a whole is bound to a stub root (re-open H-VENDOR with "all windows stubbed", not just foreign ones). |

Minimal decisive run: open YouTube, wait ≥10 s, collect one `probe com.google.android.youtube …`
line and one `probe dev.k3vinb5.freesocial …` line (open our own inspector to produce the latter).

### Missing evidence

The probe lines above, plus `dumpsys accessibility` at the same moment (to prove the binding is
live). Any answer needs the YouTube line **and** an own-package line from the same session.

### Fix direction (after the probe decides)

- H-OURS: capture from the same root the view-id lookup uses and re-derive the walk input from it
  (`rootInActiveWindow` first, `window.getRoot()` only as fallback), or walk via
  `findAccessibilityNodeInfosByViewId`/`refresh` when the raw root is decor-only.
- H-VENDOR: the raw accessibility tree cannot be the only signal on this device — add a
  non-accessibility fallback (screenshot/OCR or a MediaProjection-based reader) and record the
  vendor limitation in the acceptance notes.
