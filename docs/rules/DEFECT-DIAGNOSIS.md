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

---

## D8 fix — two layers for the ghost tree

### Confirmed cause (on-device, after the probe)

The probe settled **H-OURS**:

```
probe com.google.android.youtube rootChildCount=2 idProbe=menu_item_0=1
```

`findAccessibilityNodeInfosByViewId` on the *same* root reaches content that the child links do
not expose. The framework has the tree; the service's `getChild` links are pruned to 2–3 decor
nodes (`navigationBarBackground`, empty `content`), so `NodeTreeBuilder` could only snapshot those
and rules matched nothing (`4 nodes, 1 with viewId, matched=0`). This is not vendor stripping
(the id lookup works), not the service config (the XML flags are present and compiled), and not the
walk (unfiltered `getChild`). The declared flags were not in effect on the runtime links.

### Layer (a) — flag re-assertion (general fix)

`FilterService.onServiceConnected` now ORs `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`,
`FLAG_REPORT_VIEW_IDS` and `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` into the live `serviceInfo.flags`,
assigns it back through `setServiceInfo` (`ServiceFlags.withCapture`), and logs the effective flags
once. Event types, feedback and the service timeout are untouched. Guarantee: when the XML flags
silently failed to resolve at bind, every subsequent event gets full child links, so the normal
walk sees the whole tree again.

### Layer (b) — per-id lookup graft (guaranteed fallback)

The scan collects the view ids the applicable rules actually declare (`RuleSetMatcher.lookupViewIds`:
match `viewId` plus `viewIdTarget`) together with the engine's own known ids (`HomeTabResolver`),
and calls `findAccessibilityNodeInfosByViewId` on every captured window root
(`NodeTreeReader.captureAll(viewIdLookups = …)`). Each returned live node is grafted into the
snapshot (`NodeTreeBuilder.graft`) with its real viewId, class, text, content description, bounds,
visible/selected/clickable/scrollable flags. Only declared ids are queried — unknown ids cannot be
enumerated, which is why (a) remains the general fix and this layer is the per-rule guarantee.

- **Ancestry:** the graft walks the live parent chain and hangs the node under the nearest ancestor
  that is already in the snapshot (matched by `viewId` + `bounds`). When the pruned hierarchy
  exposes no such ancestor, it becomes a `lookup-graft` child of the top-most window root (the
  synthetic root only when the capture has no window). Either way the node is marked
  `NodeSnapshot.grafted`.
- **Live mapping:** grafted nodes are appended to the capture's live list at their own `index`, so
  `CapturedTree.live(...)`, `firstByViewId`, `TAP_HOME_TAB` and every action still run on the live
  `AccessibilityNodeInfo`. `RuleMatcher` and `rules.json` are unchanged.
- **Dedupe:** a node already walked through the links has the same `(viewId, bounds)` pair and is
  skipped, so it appears exactly once and is not counted as a graft. The 400-node cap bounds the
  grafts too; a dropped graft marks the capture capped.
- **Diagnostics:** the per-target `scan` line now carries `grafted=N` (see `ScanDiagnostics`); the
  D8 `probe` lines are unchanged and still run for every package.

Guarantee: content reachable by a declared view id is in the snapshot and matches its rule even if
the child links stay pruned; `grafted>0` on the scan line is the evidence that layer (b) did the work.


---

## D8 round 2 — text-lookup graft + mechanism probes

### What round 1 proved on the device (Reno4, ColorOS, Android 13)

Layer (a) (flag re-assert at bind) does **not** restore the child links on this ROM. After the
install, the engine still captures `rootChildCount=2` for YouTube, while
`findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/menu_item_0")` on the same root
still returns 1 — the framework's node cache is complete, only the `getChild` links are pruned.

That also explains why layer (b) reported `grafted=0`: the rules that matter on YouTube and TikTok
are anchored on **text / content description** (`yt_shorts_shelf`:
`(?i)(^shorts$| - play short$)`; `tiktok_playback`: the "For You"/"Like video."/"Read or add
comments"/"Share video." chrome), and a view-id graft has nothing to query for those rules. The
Pixel dumps always matched these rules because their links were intact (D1/D2 were post-match
defects), so ghost trees are platform-conditional and the engine has to work on both.

### Layer (c) — text-lookup graft

`RuleLookup` derives, at scan time, the literal strings a rule's `textRegex` /
`contentDescriptionRegex` degenerates to. A pattern is queryable when it is a literal, optionally
wrapped in `(?i)`, `^`/`$` anchors and groups, possibly with top-level alternation:
`(?i)(^shorts$| - play short$)` → `shorts`, `- play short`;
`(?i)^for you$|like video\.|read or add comments|share video\.|add or remove this video from
favorites` → `for you`, `like video.`, `read or add comments`, `share video.`, `add or remove this
video from favorites`. Escaped punctuation (`\.`) decodes to the literal; any real metacharacter
(`.`, `*`, `+`, `?`, `[...]`, `{...}`, a class escape like `\d`) disqualifies the branch.

`RuleSetMatcher.lookupTexts(app, package)` feeds those values through
`NodeTreeReader.captureAll(textLookups = …)`, which calls
`findAccessibilityNodeInfosByText(value)` on every window root and grafts the returned live nodes
exactly like layer (b): nearest captured ancestor by `(viewId, bounds)`, else the top-most window
root, `NodeSnapshot.grafted` marker, the shared 400-node cap, and one de-duplication map for both
lookup kinds. `grafted=N` on the `scan` line counts both kinds. `rules.json`, `Rule`, `MatchSpec`
and the schema are unchanged.

- **Content descriptions:** asked through the same text API. `View.findViewsWithText` compares the
  query against the view's *text and content description*, case-insensitively and by containment,
  which is why the desc-only rules (there is no `textRegex` in the shipped catalog) work here.
- **Limitation — regex criteria cannot be queried.** `findAccessibilityNodeInfosByText` takes a
  plain string; a rule whose branch keeps regex syntax (`^shorts.*shelf`, a character class, a
  quantifier) contributes no query and can only match a tree the ordinary walk can see. The
  catalog currently has no such branch, but new rules must stay literal (or accept that they will
  not survive link pruning).
- **Limitation — over-matching is harmless but real.** Containment is not equality: `shorts`
  also hits a video title containing that word. A graft is only extra evidence for the untouched
  `RuleMatcher`, so a spurious graft cannot cause a hit; it does spend node budget.
- **Limitation — the strings are locale-bound.** The rules are English-locale. The confirm device
  runs pt-BR, where YouTube's Home has no "Shorts"/"play Short" strings at all, so `matched=0`
  there is a locale effect, not a graft failure.

### Layer (d) — mechanism probes

The rate-limited `probe` line (one per package per 10 s, on every `TYPE_WINDOW_STATE_CHANGED`) now
ends with two more fields:

```
probe <pkg> rootPkg=… rootChildCount=… rootViewId=… child0Grandchildren=… idProbe=… \
  eventSourceChildCount=<n|none> refreshChildCount=<n|none>
```

- `eventSourceChildCount` — `event.source.childCount` for the event that triggered the probe
  (`none` when the framework hands the service no source).
- `refreshChildCount` — `root.refresh()` and then `root.childCount`; `rootChildCount` is read
  *before* the refresh, so the pair is the discriminator.

| observation | verdict |
| --- | --- |
| `eventSourceChildCount` rich, window root pruned | the event carries a live subtree: the capture should walk `event.source` subtrees (future fix). |
| `refreshChildCount` rich, `rootChildCount` pruned | stale-links snapshot: call `refresh()` at capture time. |
| both pruned | vendor link pruning confirmed; the view-id/text lookup grafts are the permanent mitigation. |

### Device confirm (round 2)

`adb install -r app-debug.apk`, `logcat -c`, our app, YouTube Home, one swipe (no taps):

```
probe dev.k3vinb5.freesocial rootPkg=dev.k3vinb5.freesocial rootChildCount=3 rootViewId=none child0Grandchildren=1 idProbe=n/a eventSourceChildCount=none refreshChildCount=3
probe com.google.android.youtube rootPkg=dev.k3vinb5.freesocial rootChildCount=3 rootViewId=none child0Grandchildren=1 idProbe=menu_item_0=0 app_bar_wrapper=0 results=0 eventSourceChildCount=none refreshChildCount=3
scan com.google.android.youtube (YOUTUBE): 5 nodes, 1 with viewId, rules=2/8, matched=0, grafted=1, windows=1 [android.widget.FrameLayout(0)=3/1 ids], rootChildCount=2, idProbe=menu_item_0=1 app_bar_wrapper=0 results=2
scan com.google.android.youtube (YOUTUBE): 4 nodes, 1 with viewId, rules=2/8, matched=0, grafted=0, windows=1 [YouTube(0)=3/1 ids], rootChildCount=2, idProbe=menu_item_0=1 app_bar_wrapper=0 results=2
```

Reading it:

- The `scan` lines are measured on the real YouTube window root: `rootChildCount=2` with
  `menu_item_0=1` and `results=2` — links still pruned, framework cache complete, exactly as in
  round 1. Layer (a) remains ineffective on this ROM.
- `grafted=1` on the first scan: a node the child links never exposed entered the snapshot through
  a lookup. On the second scan the same root yields `grafted=0` because nothing on that screen
  carried a queryable anchor. `matched=0` in both: the device locale is pt-BR, so YouTube's Home
  exposes no English "Shorts"/"play Short" string (verified with a `uiautomator dump` of the same
  screen) and there is no Shorts shelf to match.
- `eventSourceChildCount=none` for both packages: the events reach the service without a source,
  so the event-subtree path has nothing to walk. The `probe` lines fire before the window root
  switches (the YouTube probe still reports `rootPkg=dev.k3vinb5.freesocial`), so their
  `refreshChildCount` describes the previous active root; the `scan` lines are the authoritative
  window-root measurement.
- `refreshChildCount == rootChildCount` on the root the probe saw: no stale-snapshot effect.

Verdict: vendor link pruning confirmed; the id/text lookup grafts are the permanent mitigation.
The text layer reaches content on a pruned tree (`grafted=1`), but proving `matched>0` needs a
screen whose strings are in the rules' locale (or localized rules — a separate change).

---

## D8 round 3 — container anchors, stale-tree escape refire, dump seam (Wave A retry gaps)

Three defects the Wave A retry left open (`docs/rules/REVERIFY.md` → *Wave A retry*), all measured
on the Reno4 with APK `620e74e`, all fixed without schema changes.

### R1 — the shelf is id-less and the text lookup cannot reach it

The retry proved the surface is real (135 `uiautomator` nodes, `content-desc="Shorts"` +
4×`"… – reproduzir Short"`) while the scan reported `4 nodes, matched=0, grafted=0` and
`idProbe: results=2`. Reading `reverse/runtime-reno/pt-yt-home3.xml`:

- header and all four cards: empty `resource-id`, class `android.view.ViewGroup`;
- their nearest id-bearing ancestor: `com.google.android.youtube:id/results`, a scrollable
  `RecyclerView` `[0,398][1080,2158]`, two `ViewGroup` levels above the header.

So this is neither the rule (desc + class match the dump verbatim) nor the palette of graft layers —
there simply is no id on the nodes the rule matches, and `findAccessibilityNodeInfosByText` returns
nothing from the pruned root on this ROM. Fix (b) from the brief:

1. `filter/CaptureAnchors.kt` declares `id/results` for `com.google.android.youtube`, with the dump
   as provenance. It is appended to the capture's `viewIdLookups` in **both** the scan and the armed
   dump; it is not a match criterion (a rule keyed on `results` would match every home feed).
2. `NodeTreeBuilder.graft` now walks the child links of a lookup-grafted node and splices that
   subtree (`graftSubtree`), marking every recovered node `grafted`. Children are de-duplicated
   against the walk and earlier grafts by the existing `(viewId, bounds, text, desc)` key and count
   against the 400-node cap.

Device result (12:29): `44 nodes, 4 with viewId, matched=1, grafted=40, … idProbe … results=2` and
`neutralising … shorts_shelf via SCROLL_PAST (ACTION_SCROLL_FORWARD Bounds(0,398,1080,2124))` — the
rule matched the pt-PT shelf and the action framed on `results` (the scrollable ancestor), not the
58 px header. Hands-off: the shelf moved off the top of the feed, the next scan `matched=0`, and
YouTube stayed foreground. Children below the grafted container are intact on this vendor; had they
not been, `grafted` would have stayed at 2 and no test would have invented an anchor.

### R3 — two extra BACKs after exhaustion (stale-tree refire, not a same-scan double-fire)

Log excerpt from the retry (exhaustion at the 5-min limit):

```
11:42:03.535 neutralising …/shorts_player via BACK     ← action 1 (debounce starts)
11:42:04.006 …/shorts_player: action suppressed by throttle/quota
11:42:04.815 neutralising …/shorts_player via BACK     ← 1.28 s later: debounce expired
11:42:06.147 neutralising …/shorts_player via BACK     ← 1.33 s later: still on the stale tree
```

Same-scan double-fire is impossible by construction (`scan()` picks one hit through
`RuleGate.chosen` and returns after `perform`), and the 04.006 line proves the 1200 ms debounce was
active. The gap is between scans: `performGlobalAction(BACK)` returns before the exit animation
ends, the navigation's accessibility events trigger scans against the **pre-navigation tree**, and
once 1200 ms have passed each scan presses Back again on a screen the engine already left. Three
BACKs on a surface one level from the root would have exited the app.

Fix: `EscapeGuard` / `ActionArbiter` (`filter/EscapeGuard.kt`), a per-package+surface hold in front
of the unchanged throttle:

- only a **confirmed** `BACK` / `TAP_HOME_TAB` / `REDIRECT_HOME` opens the hold;
- a later scan with the same surface still present is suppressed for a 5 s window (the observed
  stale window was 1.3 s), so the 04.815 / 06.147 style refires cannot run;
- the hold is released immediately by any capture that no longer shows that surface — the normal
  exit to the home feed clears it, so a deliberate re-entry is not blocked;
- `SCROLL_PAST` / `CLICK_VIEW_ID` stay on the 1200 ms debounce only (a shelf that is partly
  displaced must be scrollable again), and the per-app quota is unchanged.

The working single-escape behaviour ("stays out 25 s quiet, no re-entry") is untouched: after the
one BACK the home capture has no `shorts_player` hit, so no further action exists to take.
`EscapeGuardTest` drives `RuleGate` → `ActionArbiter` with two stale-tree scans at 1.28 s / 2.61 s
and asserts exactly one action, plus release-on-content-change and per-surface keying.

### R4 — the armed dump skipped every lookup layer

`FilterService.captureArmedDump` called `NodeTreeReader.captureAll(windows, appPackage)` with no
`viewIdLookups`/`textLookups`, while `scan()` passed both. The inspector therefore wrote the 4-node
ghost tree (`nodesWithViewId 1`, null-id/null-text node lines) a few seconds after the scan path
`grafted=2` on the same window. Fix: one `captureLookups(app, package)` helper feeds the same lists
(rule ids, Home-tab ids, `CaptureAnchors`, literal texts) to both paths; the engine is asked once on
the dump path so a dump taken before the first scan still has the loaded matcher.

The dump now also reports what it recovered: `DumpDocument.grafted` (same count as the scan's
`grafted=`) and `DumpNode.grafted` per node, alongside the existing per-window stats.
`DumpExporterTest` builds a pruned fake tree, grafts the container, and asserts the serialized
document contains the grafted nodes, their pt-PT `content-desc` and the container id.

Two capture-timing guards were needed for the dump to actually show that content (both found on
the device, both bounded):

- **Target-window guard.** A launch's `TYPE_WINDOW_STATE_CHANGED` reaches the service while the
  previous app's window is still `rootInActiveWindow` (Wave A A5: a `navigationBarBackground` tree
  labelled "YouTube"). `DumpArm.readyFor(target, activePackage)` keeps the one-shot armed and the
  capture waits for the next state change instead of consuming the arm on the transition.
- **Ghost retry.** Even with the active package right, the target's window can still be the 3-node
  decor tree for a few hundred milliseconds (`4 nodes, 1 with viewId, 0 grafted`). A capture that
  `DumpArm.looksGhost(nodeCount, grafts)` (≤ 4 nodes, no grafts) is retried up to 4 × 600 ms inside
  the 60 s arm; only then is the arm consumed and the file written. Device result: the same arm now
  writes `9 nodes, 2 with viewId, 5 grafted`, including `id/results` and the pt-PT strings.

`GraftNode.subtree` is set **only** for `CaptureAnchors` containers. Walking every grafted node's
children was tried first and pulled the Shorts player's whole subtree into the capture, where the
id-less shelf text made the layer-1 `yt_shorts_shelf` rule match *while the player was up*
(`matched=2` on the player). A rule-declared id and a text hit stay single-node grafts, which keeps
that false positive out; `CaptureAnchorGraftTest` pins both behaviours.

## D9 — in-budget Reels entry escaped, then allow/deny alternation

### Observed (user, 2026-11-05)

Opening Instagram Reels escaped even though the daily budget still had time left. After exhaustion
the opposite symptom appeared: *"click a reel → goes back, click again → allows, scroll → goes back
again, on and on."* Both contradict the direct-engagement policy: while the allowance lasts the
surface must PAY with no action; once exhausted every entry must escape identically.

### Root cause — (c) the gate treated viewer entry chrome as an always-on layer-1 surface

Wave C started with `budget_used_seconds=0`, `budget_limit_minutes=20` (`REVERIFY.md` Wave C
header). On the Reels screen the capture matched two rules — the viewer and the selected tab:

```
14:26:10.597 scan com.instagram.android (INSTAGRAM): 10 nodes, 6 with viewId, rules=4/8,
             matched=2, grafted=6, windows=1 [Instagram(0)=4/2 ids]
14:26:10.713 neutralising com.instagram.android/reels_tab via TAP_HOME_TAB
```

`rules.json` declared `ig_reels_tab` as `"layer": 1` (always hidden), and
`RuleGate.eligibleHits` treated layer 1 as unconditionally eligible (`1 -> true`, `RuleGate.kt:130`
at HEAD `479bec3`). So the in-budget viewer matched `ig_clips_viewer` (layer 2 → NONE) **and**
`ig_reels_tab` (layer 1 → ESCAPE); the layer-1 tab rule won and escaped the viewer with budget to
spare. This is the root cause, not the feed-cell rule: those same C2 scans show `matched=2` for
`reels_tab` + `clips_viewer` and no `feed_reel_cell` hit.

### Why the other hypotheses do not explain the in-budget escape

- **(a) feed-cell rule matching viewer nodes.** `ig_feed_reel_cell` keys on
  `video_container`, which the 233528 viewer dump does not contain (recorded in that rule's notes),
  and C2's hits are the viewer + tab, not the feed rule. The real false-positive precedent — the D8
  round-3 text/subtree graft pulling the Shorts shelf into the player (`matched=2`) — was already
  fixed by single-node grafts (`CaptureAnchorGraftTest`) before Wave C; it is a separate capture
  bug, not D9.
- **(b) EscapeGuard alternation.** This explains the *second* symptom, not the in-budget escape.
  `EscapeGuard.suppressed` (`EscapeGuard.kt:33-40` at `479bec3`) released a hold when
  `!surfaceStillPresent` **or** after 5 s, while `ActionArbiter.confirm` recorded only a confirmed
  escape. The R3 log is the same mechanism on YouTube:

  ```
  11:42:03.535 neutralising …/shorts_player via BACK
  11:42:04.006 …/shorts_player: action suppressed by throttle/quota
  11:42:04.815 neutralising …/shorts_player via BACK
  11:42:06.147 neutralising …/shorts_player via BACK
  ```

  A time-bounded hold is an allow window: the next deliberate entry inside/after it can be
  permitted. It must be removed even though it does not cause the in-budget escape.

### Fix (policy 2026-11-05)

- `SurfaceClassifier` replaces `layer` for the verdict: `DIRECT` (IG `clips_viewer`/`reels_tab`,
  YT `shorts_player`, TikTok `playback`) PAYS while the budget lasts and ESCAPES when it is gone;
  `PASSIVE` (`feed_reel_cell`, `shorts_shelf`) and `UNKNOWN` never produce an action.
  `RuleSetMatcher` also scopes a capture to one surface identity, so a feed rule cannot match inside
  a viewer tree and vice versa.
- The two passive rules are `enabled=false` in `rules.json` (loader rejects an enabled
  `SCROLL_PAST`); their notes keep the evidence.
- `ActionArbiter` has no time window. It suppresses only repeats for the same still-visible
  surface episode (the asynchronous BACK's stale tree); a capture without the surface ends the
  episode and the next entry escapes immediately (N entries → N escapes). The 1200 ms debounce only
  spaces retries within one episode.
- `DumpArm.isArmed()` pauses every action (and the stopwatch) for the duration of an inspector
  capture; consume/expiry restores enforcement automatically.

Tests: `DirectEngagementPolicyTest`, `RuleGateTest`, `ActionArbiterTest`, `RuleModelTest`.

## D10 — the mode segment persists but the engine keeps the mode it loaded at connect

### Observed (user, 2026-11-05, build 0.2.0-e6d7fce)

*"The screen you open when pressing instagram/youtube/tiktok has 3 modes but when clicking
nothing appears to change — extremely confusing."*

### Repro (Reno4, HEAD a5402bc)

`reverse/runtime-reno/d10-01-appdetail-before.png` → tap "Block" →
`reverse/runtime-reno/d10-02-after-tap-block.png`: the Block pill is highlighted, the Filter pill
is not, and `shared_prefs/settings.xml` now holds `app_mode_com.instagram.android=block`. The
control is **not** dead and the chip **does** reflect the write. The defect is behavioural: the
engine never sees it.

- 20:33 write `block`; 20:37 open Instagram → it opens normally, the engine logs a normal filter
  scan (`matched=0`, `gate=NONE`) because it still holds the `filter` it loaded at install/connect.
- 20:38 a harness `uiautomator dump` rebinds accessibility → the engine reloads and now holds
  `block`.
- 20:39 write `filter` back; 20:40 the engine still reports
  `com.instagram.android is not filtered (master off or monitor mode)` and `BlockService` opens
  `dev.k3vinb5.freesocial/.ui.BlockActivity` — the mirror-image failure from the other snapshot.
- 20:40:48 another rebind logs `connected, rules=8`; only then does the same prefs set produce
  `neutralising …/reels_tab`.
- Supplies: `reverse/runtime-reno/d10-engine-stale-snapshot.log`.

### Root cause — one `SettingsRepository` per process consumer, never refreshed

`SettingsRepository` snapshots **every** value into Compose state at construction
(`settings/SettingsRepository.kt:157-221`: `filteringEnabled`, `budgetLimitMinutes`,
`budgetUsedSeconds`, `budgetDate`, `scheduleEnabled`, `windDownMinutes`, `morningLockMinutes`, the
`modes` map at `:197-199`, `surfaceStates` at `:201-207`, and `days` at `:209-221`). The UI's
instance is created once per activity (`MainActivity.kt:40`) and its own writes update its own
state, so the chip redraws.

The services create **separate** instances: `FilterService.loadEngine()` calls
`SettingsRepository.from(this)` (`filter/FilterService.kt:171-176`) and `engine()` only re-runs
`loadEngine` through `EngineBootstrap` on `force`/retry (`:182-188`); `BlockService` does the same
at `block/BlockService.kt:41-51`. Every decision then reads those cached fields
(`FilterService.kt:159`, `:332`, `:352`; `BlockService.kt:66-77`). SharedPreferences updates are
shared across the process, but **the instance fields are not re-read**, so a UI write is invisible
to both services until the accessibility connection re-binds (process restart / service toggle).

This also explains the "nothing appears to change" report: Allow, Block and Filter all behave like
Filter until the next rebind, and each toggle row has the same fate.

### Alternatives ruled out

- **Dead control / non-reflecting chip.** Ruled out: the tap writes prefs and the pill redraws
  (d10-02). `SegmentedControl` renders `selectedIndex` and `AppDetailScreen` reads the observable
  `mutableStateMapOf` (`ui/screens/AppDetailScreen.kt:65`), so recomposition is real.
- **`apply()` not visible cross-instance.** Ruled out by Android semantics: `apply()` updates the
  in-memory map before dispatching, so a *newly constructed* repository sees the value (proved at
  20:40:48: a rebind loads the fresh value).
- **External `run-as` writes.** A different, already known caveat (no listener fires for writer
  processes) — not this defect, and not fixed by this change.

### Fix

Live propagation at the repository seam: `PrefsStore.observe()` (implemented in
`SharedPrefsStore` with `registerOnSharedPreferenceChangeListener`, held strongly by the
repository) calls `SettingsRepository.reload()`, which re-reads every field from the store. Every
instance — UI, `FilterService`, `BlockService`, schedule alarm — is then consistent with the
current prefs after any same-process write, without touching a single caller.

Tests: `SettingsRepositoryTest` (`same-process writes keep a second instance in lock-step`).

## D11 — "still see reels and shorts with 0 minutes left"

### Observed (user, 2026-11-05)

*"Nothing is working — I can still see reels and shorts with 0 minutes of budget left."*
Hub shows `0 min left`; IG Reels/YT Shorts/TikTok playback still play.

### Root cause — same stale snapshot as D10, budget fields included

`FilterService.isBudgetExhausted` reads `repo.budgetLimitMinutes` and `repo.usedSecondsToday(now)`
(`filter/FilterService.kt:602-607`), both cached at construction (`settings/SettingsRepository.kt`
`budgetLimitMinutes`/`budgetUsedSeconds`/`budgetDate`). Driving the Budget stepper to 0 writes
`budget_limit_minutes=0` into prefs, the hub (UI instance) renders `0 min left`, and the engine
keeps paying out the limit it loaded at connect. Two snapshots, two truths — the same mechanism
D10 proved on device with the per-app mode.

The budget arithmetic itself is correct, and this was checked on the device: with prefs
`limit=0`, `used=451s` and a forced engine reload, the scan reports `budget=true` and the very next
Reels tap is `neutralising com.instagram.android/reels_tab via TAP_HOME_TAB` (log 20:40:53,
`reverse/runtime-reno/d10-08-ig-reels-after-reload.png`). So `ShortVideoBudget.isExhausted` and
`RuleGate` are fine; only the gate input was stale.

Surface detection is also fine on this device: the scan line reports `rules=3/8, matched=2` on the
Reels screen for IG; the failure was only the gate input. App versions match the rule stamps
(`com.instagram.android 454.0.0.54.77`, `com.google.android.youtube 21.43.523`,
`com.zhiliaoapp.musically 47.6.3`), so **no version drift is involved**.

### Why the existing device scenario did not catch this

`tools/device/scenarios/exhaustion_consistent.py` writes the limit through the stepper and then
relies on `uiautomator dump` — every dump rebinds the accessibility connection, which re-runs
`onServiceConnected` → `loadEngine()` → a fresh `SettingsRepository`, silently reloading prefs.
The scenario therefore exercises the "restart" path, not the live path a user has. The 20:40:48
`connected, rules=8` line in the D10 log is exactly that rebind. Do not treat a dump-driven
scenario as proof that a settings change reaches a running engine.

### Fix

D10's repository-level `observe()`/`reload()` covers the budget fields (`budgetLimitMinutes`,
`budgetUsedSeconds`, `budgetDate`) and the master/schedule switches, i.e. everything the engine's
gate reads, for every surface. One seam, all settings.

Tests: `SettingsRepositoryTest` (`budget limit written by one instance is seen by another`).
