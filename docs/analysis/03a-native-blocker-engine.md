# 03a — NativeReelsBlockerService: engine mechanics

Source: `reverse/rd-work3/NRBS.java` (jadx `--show-bad-code`) and smali ground truth at
`reverse/dec/smali/smali/com/sociallite/android/data/NativeReelsBlockerService.smali`.
Scope: engine only. Per-app rule bodies (`d/e/f/g/h`, inline X/Twitter and Snapchat bodies) are
covered elsewhere — referenced here by entry point only. Line numbers are `NRBS.java` unless
prefixed `smali:`.

---

## 1. Lifecycle & event pipeline

### 1.1 `onServiceConnected` (4547-4690)

| Step | Detail | Line |
|---|---|---|
| Kill switch | `accountType == "kid"` → `disableSelf()` and return; service never runs. | 4550-4553 |
| Detectors | Rebuilt: `Z=i5` (Instagram), `f3354a0=n7` (X/Twitter), `f3356c0=s6`, `f3355b0=y6` (Snapchat Spotlight lock), `f3357d0=r4` (FB Reels lock), `f3363j0=b`, `f3370v=c4`, plus `w7.i` back-handlers `f3372x` ("Facebook Feeds Back") / `f3373y` ("Facebook System Back") and `f3371w=i4`. | 4554-4623 |
| Global flag | `H0 = true` — static "engine connected" flag other components read. | 4624 |
| Analytics | `j2` timestamp + `i9`/`p9`/`r2`/`u2` singletons armed. | 4625-4643 |
| ServiceInfo | `eventTypes = 4200485` = `0x4016E5` = **TYPE_WINDOWS_CHANGED(4194304) \| TYPE_VIEW_SCROLLED(4096) \| TYPE_WINDOW_CONTENT_CHANGED(2048) \| TYPE_WINDOW_STATE_CHANGED(32) \| TYPE_VIEW_SELECTED(4) \| TYPE_VIEW_CLICKED(1)**; `feedbackType=16` (FEEDBACK_GENERIC); `flags \|= 114` (`0x72` = RETRIEVE_INTERACTIVE_WINDOWS(0x40) \| INCLUDE_NOT_IMPORTANT_VIEWS(0x20) \| REPORT_VIEW_IDS(0x10) \| 0x2, the last being `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` — **UNVERIFIED**; whether the key-filter flag `onKeyEvent` needs comes from the XML is also unverified); `notificationTimeout = 50` ms; `packageNames = null` → listens to **all** apps, code filters by `G0`. | 4644-4650 |
| Prefs | `registerOnSharedPreferenceChangeListener(G)` on the `native*` prefs. | 4651-4657 |
| Screen receiver | `H` for `SCREEN_OFF`/`SCREEN_ON`/`USER_PRESENT` — blocking dies the moment the phone locks. | 4658-4671 |
| First scan | `if (F()) N()`. | 4672-4674 |

### 1.2 Master gate `F()` (1006-1009)

```
nativeReelsBlocking == true && accountType != "kid"
&& PowerManager.isInteractive() && !KeyguardManager.isKeyguardLocked()
```

Every event path starts here; failure ⇒ teardown (`O()`), never a scan.

### 1.3 `onAccessibilityEvent` (3964-4400)

1. `null` → return (3999-4001).
2. `!F()` → if the poller is scheduled (`B.I()`), `O()` (teardown), return (4002-4010).
3. `N()` — arm the debounced scan; `if (!B.I()) return;` (4015-4018).
4. Feed raw events to stateful detectors in order:
   * `n7` — X: `4096` scroll (per-window id match, `.8/.5` size ratios, 8px dead zone, dominant axis, direction check) and `1`/`4` click/select with text classifier `s7.a()` (4021-4121).
   * `r4` — FB Reels lock: `com.facebook.katana` + `4096`, scrolled node ≥ ½ screen width, arms a 700 ms window (4123-4147).
   * `32` / `4194304` (window state / windows changed) → **reset**: `i5`, `n7`, `r4`, `y6` re-evaluate (4148-4178).
   * `com.snapchat.android` or 32/4194304 → `s6.d()`, `y6.d()` (4180-4189).
   * `com.instagram.android` → `i5` window-id/FrameLayout visibility, `2048` content-changed handling, `4096` sample pushed into three scroll trackers `t5 N`, `t5 O`, `e7 S` (4191-4353).
5. `packageName.hashCode()` switch → per-app body (§5).

Acted-on types: **4096 VIEW_SCROLLED** is the primary trigger (every detector is scroll-driven);
**1/4** for X; **2048** for IG overlay/FrameLayout; **32 / 4194304** are *reset* signals only.
`packageName == null` events fall through harmlessly.

### 1.4 Debounce / throttle layers

| Mechanism | What it does | Line |
|---|---|---|
| `notificationTimeout=50 ms` | Framework-level coalescing of event storms. | 4648 |
| `B` = `w7.i`, `N()` | Single-flight scheduler: `N()` returns at once if `B.I()`; otherwise stores a token and invokes the schedule lambda with **delay 0** → one scan per burst, never re-entrant. `O()` cancels. | 1282-1290, 1300-1305 |
| `Handler f3368t` (main looper) | Only genuinely delayed action: `c()` → `post(a8.b(this, 10, str))` (TikTok Inbox redirect). | 237, 1494 |
| 1200 ms action debounce | `b()` refuses to act if `j7 - f3364k0 < 1200`; on success stamps `f3364k0 = j7`, `J = j7`. | 1450-1463 |
| `U` visit counter | `a0()` aborts after 400 node visits. | 1412-1431 |
| `v()` per-app rate limit | `ie.c.M/c0(ka, b9, now)` → cooldown/quota per app. | 4806-4818 |

`a()/b()/c()` (1404-1496) are action helpers, not the scheduler:

* `a(long, String)` — set `J`, `G()`, then `performGlobalAction(1)` (BACK). (1404-1408)
* `b(boolean, long)` — "wrong tab" rectifier: root → `p()` (selected nav tab) → if label matches
  `f3351x0` (`^(reels|video|videos|watch|ريلز|…)`) tap the safe `home` tab via `P()`; else if
  `z3 && w(root)` do BACK. 1200 ms gated. (1434-1469)
* `c(long, String, boolean)` — TikTok: verify Inbox tab exists (`d7.j`), else log "retry on next poll";
  if `z3`, post the delayed redirect. (1471-1496)

`G()` flushes pending analytics string `K` via `m1.b.z(this).e(K)` (1011-1018). `J()` cancels aux
helpers `A`, `i4`, both `w7.i` (1044-1058). `K()` zeroes counters `n0/o0/p0` (1060-1064). `O()`
resets every detector and timer (1292-1348). `I()` removes the overlay `FrameLayout` from
WindowManager (1029-1042).

### 1.5 `onKeyEvent` (4526-4544)

Swallows **only `KEYCODE_BACK` (4)**, one press at a time:

* not BACK → `false`.
* DOWN whose `downTime` is already in `f3374z` → swallow; matching UP clears `f3374z` (4528-4538).
* DOWN, `repeatCount == 0`, and `L()` true → store `downTime`, return `true` — the press is **eaten** (4539-4543).
* otherwise `false`.

Why: `L()` (1066-1109) is the Facebook "return to feed" hijack — when
`nativeBlockFacebookFeedSuggested && !nativeFbDmsOnlyMode && !nativeFbMarketplaceOnlyMode` and Pro,
the first BACK is consumed and replaced by an in-app return (`i4.b(rect, true, false)` + callback with
the rect from `n()`, "FbReturnCheck: return requested"). Swallowing the matching UP prevents a
half-press reaching the app.

### 1.6 `onDestroy` (4403-4486) / `onUnbind` (4693-4701) / `onInterrupt` (4489-4523)

* `onDestroy`: unregister prefs listener + screen receiver; shut down every detector executor
  (`b`, `r4`, `c4`, `i5`, `n7`, `s6`, `y6`); `super`; `H0 = false`; clear `i9/p9/r2/u2`; `O()`; `I()`.
* `onUnbind`: `O()`, `H0 = false`, `i9.d(...)`, super.
* `onInterrupt`: stop all trackers + `I()` (drop overlay), objects retained.

### 1.7 `dump()` (1991-2067)

Diagnostics only: `native_monitor polling=<B.I()> scans=<D> rootReads=<E>`, then
`X controls: visible/tabHidden/statusBackground/action/feed/attempts/failed`,
`Snapchat Spotlight lock: visible/enabled/dark/target`,
`Facebook Reels lock: visible/window/area/enabled/pro/kid/master/reels/dmsOnly`.
`D`/`E` are the scan and root-read counters — the cheapest way to confirm the engine is live.

---

## 2. Node access & geometry

### `n()` (3792-3844) — root access
Iterates `getWindows()`, keeps the window that is **focused and `type == 1` (TYPE_APPLICATION)**,
takes its root, and returns it **only** if the root package is `com.facebook.katana` *and*
`s1.V(root, …)` says the surface is a real FB feed. Everything else is recycled → `null`.
So `n()` is the FB-specific "give me the actual app root, not the overlay/IME window" accessor.

### `o()` (3846-3893) — screen/rect math
Builds the full-screen `Rect` from `DisplayMetrics`, then **overwrites** it with
`getDefaultDisplay().getRealMetrics()` (real pixels incl. system bars). Then scans windows of
**`type == 3` (TYPE_SYSTEM)** owned by `com.android.systemui` and runs `s1.S(u, rect, root, 0)` with a
250-node budget to return the system-UI obstruction rect (status/nav bar, keyguard area). `null` if none.
Used to avoid acting on nodes hidden behind system chrome.

`x0.i(node)` is the shared bounds helper (`getBoundsInScreen` into a fresh `Rect`) used everywhere.

---

## 3. Predicate table

"walk" = recursion over children with a depth cap (120 or 200) and `recycle()` in `finally`.

| m | Tests | Core? |
|---|---|---|
| `A(n, rect)` 284-290 | `isVisibleToUser()` **and** node bounds wider than ½ `rect` **and** taller than ½ `rect` **and** `Rect.intersects`. "This node really covers that area." | ★ geometry |
| `B(n, List<viewId>)` 292-324 | For each id: `findAccessibilityNodeInfosByViewId` → any hit satisfying `A(hit, bounds(n))`; if nothing found, falls back to deep walk `C()` (budget 200, depth ≤ 32). | ★ view-id |
| `C(counter, list, rect, n, depth)` 326-350 | Bounded walk: `list.contains(n.getViewIdResourceName()) && A(n, rect)`. | ★ (B fallback) |
| `D(n)` 352-376 | `com.snapchat.android:id/ngs_spotlight_icon_container` exists **and** `isSelected()` → Spotlight tab active. | |
| `E(n)` 378-403 | `com.instagram.android:id/clips_tab` is `isSelected()` → Reels tab active. | |
| `Q(n, depth≤200)` 412-448 | Finds `com.instagram.android:id/direct_tab`, clicks it (`performAction(16)`) or climbs ≤ 6 parents to the first clickable and clicks that. **Mutates UI** — an action disguised as a predicate. | |
| `R(counter, cb, n, depth≤120)` 450-473 | Bounded walk applying predicate `cb` to each node; generic walker. jadx mangled the "found" edge: the callback result is swallowed and `true` returned on match. | ★ walker |
| `S(n, list, rect, depth≤120)` 482-519 | `contentDescription` matched against `list` (case-insensitive substring, `k.O(...,true)`), visible, bounds > 10×10 px, intersects `rect`. **jadx dropped the `return true`** — treat the trailing Rect checks as UNVERIFIED. | ★ text |
| `T(n, list, rect, depth≤200)` 528-564 | Same, but matches `getViewIdResourceName()` (case-**sensitive**). | ★ view-id + geometry |
| `U(n, depth≤120)` 566-633 | Any visible editable / `EditText` whose hint, desc or text contains a DM-composer string (`B0`: "message", "escribe un mensaje", "écris un message", "رسالة", …). "We are in a chat composer" ⇒ allow. | ★ allow-list |
| `V(n, depth≤120)` 635-691 | Any visible node whose text/desc contains a Reels-player marker (`f3349v0`: " · original audio", "'s reels", " reels de ", "ريلز", "originalton", …). | ★ reels marker |
| `X(n, depth≤120)` 700-749 | Any visible node whose text/desc contains `C0` ("search messenger/chats/messages", ES/FR/AR) → inside a DM inbox/search. | |
| `Y(n, depth≤200, viewId)` 751-768 | Walks for `viewIdResourceName == str` **and** visible; jadx lost the return edge (returns `true` whenever the budget survives) — semantics "this view id is present & visible". | |
| `Z(n, rect, depth≤200)` 777-810 | Any visible node (> 10×10 px) whose text/desc contains **"View Profile"** — the overlay label of a short-form player. | |
| `l(str)` 812-833 | Truncates at the first ASCII **or Arabic `،` (U+060C)** comma, diacritic-normalises (`k.u0`), lowercases: "Home, tab, 1 of 5" → "home". | helper |
| `m(n, depth≤120)` 835-879 | Extracts a username from `contentDescription` via `D0` ("X · Original audio") or `E0` ("View X's reels", FR/IT/AR variants). | helper |
| `p(n, depth≤120)` 4703-4722 | First node where `x(n) && z(n)` → returns its `contentDescription`: **the currently selected bottom-nav tab label**. | ★ nav-tab |
| `q(n, depth≤120, cb)` 881-902 | First node whose `text` satisfies `cb`; generic text search. | helper |
| `r(n)` 904-938 | Instagram only: collects `clips_author_username` (fallback walk, budget 100) into a `LinkedHashSet`, returns one name — the reel's author, for the "allow a reel I sent" logic. | |
| `t(n, y, depth≤120)` 940-976 | Visible non-empty node with `centerY < y` whose text/desc is `marketplace` / `ماركت بليس` → marketplace entry above a line. | |
| `z(n)` 978-1004 | Node `isSelected()`, or a **selected ancestor within 3 levels** whose bounds are non-empty, ≤ 2× the node's size and contain it → "this is the active tab". | ★ state |
| `w(n)` 4820-4838 | **"Is a full-screen short playing?"**: `R` with predicate `o0(bounds, flag, this)` **or** (`R` with `c8.d` predicate **and** flag set **and** `V(n)` reels markers). | ★★ reels-player |
| `x(n)` 4840-4869 | `y(n)` **and** within ≤ 3 ancestors a parent with height ≤ 3× and width > 2× the node's (a tab strip) **and** ≥ 3 similar siblings on the same `centerY` (`i(parent, 0, centerY, set, {60})`) → the selected **Reels tab** of a bottom bar. | ★★ reels-tab |
| `y(n)` 4871-4885 | Visible, non-empty `contentDescription`, label (`l()`) ∈ `f3367q0` (`home/reels/video/marketplace/notifications/profile/menu/friends/groups`, 8 languages) **and** `A0` regex matches `", <n> of <m>"` (incl. Arabic-Indic digits) → "looks like an Android bottom-nav caption". | ★ nav-caption |

**Core match primitives** (everything else composes them): `A` (visibility + area ≥ ½ rect),
`B`/`C` (view-id lookup + deep fallback), `S`/`T` (text or view-id match on a visible node),
`U` (in a DM composer ⇒ allow), `w` (reels player on screen), `x`+`y` (reels tab is the selected
bottom-nav tab), `p` (which tab is selected).

---

## 4. Actions — how a hit is neutralised

Exhaustive from `dispatchGesture|performGlobalAction|performAction|takeScreenshot|GLOBAL_ACTION`:

| Action | Implementation | Where | Trigger |
|---|---|---|---|
| **BACK (global)** | `performGlobalAction(1)` = `GLOBAL_ACTION_BACK`, wrapped as `a(j, "Reels"/"Spotlight"/"Discover"/…)` (+ analytics label) | `a()` 1407, `b()` 1457, 3694 | Primary neutraliser: leave the reels/spotlight surface entirely. |
| **Tap the safe Home tab** | `P(root, 0, f3352y0)`: 1-point `Path` at node centre, `dispatchGesture` 60 ms stroke, **plus** `performAction(16)` fallback | `P()` 1350-1383 (gesture 1371, fallback 1375) | Current tab matches `f3351x0` ("reels/video/watch"); also used by the poller `b()`. |
| **Redirect to DMs** | `performAction(16)` (`ACTION_CLICK`) on `direct_tab` or first clickable ancestor (≤ 6) | `Q()` 412-448 | DMs-only modes — redirect instead of block. |
| **Direct click** | `performAction(16)` | 3692 | Instagram DM/thread navigation. |
| **In-app "return to feed"** | `i4.b(rect, true, false)` + registered callback, rect from `n()` | `L()` 1066-1109 | FB feed-suggested blocking; paired with `onKeyEvent` eating the BACK. |
| **Overlay show/remove** | `FrameLayout f3369u` added/removed via `WindowManager` | `I()` 1029-1042 (remove); add path lives in `c4`/`i4` (out of scope) | Hard-block overlay card. |
| **Self-disable** | `disableSelf()` | 4551 | `accountType == "kid"` at bind. |
| **Delayed redirect** | `Handler f3368t.post(a8.b)` | `c()` 1494 | TikTok Inbox redirect retry. |
| **Screenshot** | — | — | **Not used.** No `takeScreenshot()` anywhere in this class. |
| **Scroll synthesis** | — | — | **Not used.** Scroll is only *observed* (`getScrollDeltaX/Y`, `getFromIndex`). |

Trigger chain is always: `F()` → one of the six registered event types → per-detector geometry/text
hit → optional per-app pref (`nativeBlockX`) → optional `v()` per-app cooldown → action.

---

## 5. Top-level method → package table

Dispatch is a `sparse-switch` on `packageName.hashCode()` inside `onAccessibilityEvent`
(`smali:8970` `:sswitch_data_0`, `smali:9173` `:sswitch_data_1`, tables at `smali:10194-10216`).
Hashes computed from the literals at `smali:8962-8968` / `smali:4135-4149`:

| Package | hash | Case | Method | Gate |
|---|---|---|---|---|
| `com.instagram.android` | `-662003450` (`0xd88aa106`) | `sswitch_e` | **`e()`** 2082 (calls `s()` 4731 for Reels) | master + `nativeBlockInstagramReels`, `nativeIgDmsOnlyMode`, `nativeIgHideStories` |
| `com.google.android.youtube` | `-2075712516` (`0x844723fc`) | `sswitch_10` | **`h()`** | `nativeBlockYoutubeShorts`; view ids `f3346s0` (`reel_recycler`, `reel_player_page_container`, `shorts_player_container`) |
| `com.snapchat.android` | `2094270320` (`0x7cd40770`) | `sswitch_8` | **inline body** (no method) | `nativeSnapchatSpotlightBlocking`, `nativeSnapchatBlockDiscover`; `spotlight_container` / `ngs_spotlight_icon_container`, `D()`, `B()`, `T()`+`Z()` → `a(j,"Spotlight")` / `a(j,"Discover")` |
| `com.facebook.katana` | `714499313` (`0x2a9664f1`) | `sswitch_a` | **`d()`** 1533 → `k()` 3748 → `j()` 3581 + `d4`/`c4` | `nativeFbDmsOnlyMode`, `nativeFbMarketplaceOnlyMode`, `nativeBlockFacebookFeedSuggested`, `p4.a()`; no gate matched ⇒ teardown `r4`/`d4`/`J()` |
| `com.facebook.orca` | `908140028` (`0x36211dfc`) | `sswitch_9` | **none — allowed** | if `nativeFbDmsOnlyMode` → skip entirely (Messenger is the safe surface) |
| `com.zhiliaoapp.musically` | `543597367` (`0x2066a337`) | `sswitch_b` | **`g()`** | `nativeBlockTikTok` (+ `legacyFreeTikTokGrandfathered`, `nativeTikTokAllowSentDM` inside) |
| `com.ss.android.ugc.trill` | `330586574` (`0x13b459ce`) | `sswitch_c` | **`g()`** | same (TikTok global build) |
| `com.twitter.android` | `10619783` (`0xa20b87`) | `sswitch_d` | **inline X body** (`:goto_3e`) | `nativeBlockTwitter`, `nativeXDmsOnlyMode`; drives `n7` |
| `com.twitter.android.lite` | `-1023018315` (`0xc305fab5`) | `sswitch_f` | **inline X body** (shared target) | same |

Notes:
* `s()` 4731 is the Instagram **Reels** enforcement: checks the reel message composer
  (`reel_viewer_message_composer`), decides via `t5`/`r1` whether to "allow one sent reel"
  (`nativeAllowSentReel`, 600 ms window), then `a(j,"Reels")`.
* Package allow-list constant `G0` (272): instagram, youtube, snapchat, facebook.katana,
  facebook.orca, musically, trill, twitter.android, twitter.android.lite.
* `M(node, package, prefs)` 1181-1280 maps package → analytics enum `b9`
  (`A`=YouTube, `B`=X, `C`=TikTok, `D`=Facebook, `F`=Snapchat, `z`=Instagram) plus a second surface
  check (YouTube `f3346s0`, Snapchat `spotlight_container`, IG DMs-only, FB DMs/marketplace/`U()`/`X()`).
  `v()` uses that `b9` for the per-app rate limit.

---

## 6. Prefs keys (`prefs_nrbs.txt`)

Read through `x2` (`s1.C(this)` wrapper on the `native*` prefs file). Type = `getBoolean` unless stated.

| Key | Type | Effect |
|---|---|---|
| `accountType` | String (default `"personal"`) | `"kid"` ⇒ `disableSelf()` (1008, 4550) and every gate short-circuits; also shown in `dump()`. |
| `analytics_native_blocking_enabled_fired` | bool | One-shot analytics guard: fires `native_blocking_enabled` once, then set true (4675-4679). |
| `legacyFreeTikTokGrandfathered` | bool | TikTok: grandfather legacy free users' behaviour (consumed in `g()`). |
| `nativeAllowSentReel` | bool (default `true`) | IG `s()`: a reel opened from a DM is allowed once (`r1`, 600 ms) instead of backing out. |
| `nativeBlockFacebookFeedSuggested` | bool | FB: block suggested/sponsored feed items; also arms the `L()` back-hijack. |
| `nativeBlockFacebookReels` | bool | FB: block the Reels surface (drives `r4` lock). |
| `nativeBlockInstagramReels` | bool | IG: block Reels. |
| `nativeBlockTikTok` | bool | TikTok: block For You, route to Inbox. |
| `nativeBlockTwitter` | bool | X: block the video/For You tab. |
| `nativeBlockYoutubeShorts` | bool | YouTube: block Shorts (`h()`). |
| `nativeFacebookFeedSection` | bool (type UNVERIFIED) | Chooses the FB feed section treated as safe, in `d()`. |
| `nativeFbDmsOnlyMode` | bool | FB: DMs only — Messenger (`orca`) skipped, reels/feed never blocked. |
| `nativeFbMarketplaceOnlyMode` | bool | FB: Marketplace only. |
| `nativeIgDmsOnlyMode` | bool | IG: DMs only (also read by `M()`). |
| `nativeIgHideStories` | bool | IG: neutralise Stories. |
| `nativeReelsBlocking` | bool | **Master switch** of the whole service (`F()`). |
| `nativeSnapchatBlockDiscover` | bool | Snapchat: block Discover. |
| `nativeSnapchatSpotlightBlocking` | bool | Snapchat: block Spotlight (drives `y6`). |
| `nativeTikTokAllowSentDM` | bool | TikTok: allow a chat opened from a shared video. |
| `nativeXDmsOnlyMode` | bool | X: DMs only. |
| `nativeYoutubeSearchOnly` | bool | YouTube: search only (block home/shorts). |
| `window` | *not a pref* | The string `"window"` passed to `getSystemService` when removing the overlay (1033). |

---

## Appendix — engine state fields

| Field | Meaning |
|---|---|
| `H0` | static volatile "engine connected" flag (set 4624, cleared 4469/4695). |
| `f3368t` | `Handler(Looper.getMainLooper())` — deferred redirects only. |
| `B` (`w7.i`) | single-flight scan scheduler; `I()` = scheduled/running. |
| `f3374z` | `downTime` of the BACK press currently swallowed. |
| `D` / `E` | scans / root reads counters, exposed via `dump()`. |
| `f3364k0` | last successful action timestamp (1200 ms debounce). |
| `J` | last enforcement timestamp. |
| `U` | node-visit counter for `a0()` (cap 400). |
| `f3369u` | overlay `FrameLayout` (WindowManager), removed by `I()`. |
| `g0` (`b9`) | current app enum used by `v()` rate limiting. |
| `K` | pending analytics message flushed by `G()`. |
| `n0/o0/p0` | per-session counters zeroed by `K()`. |
