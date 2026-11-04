# 03c — `NativeReelsBlockerService`: remaining per-app content rules

Analysed: `reverse/rd-work3/NRBS.java` (jadx `--show-bad-code`; smali ground truth in
`reverse/dec/smali/smali*/com/sociallite/android/data/NativeReelsBlockerService*.smali`).
Prefs inventory: `reverse/rd-work3/prefs_nrbs.txt`. Helper classes read for context:
`reverse/dec/jadx/sources/ie/n4.java`, `ie/p4.java`.

**In scope:** `L()` (:1066-1109), `M(...)` (:1181-1280), `e(...)` (:2082-2360), `g(...)` (:3333-3465),
`h(...)` (:3474-3538), `i(...)` (:3540-3567), `j(...)` (:3581-3746), `k(...)` (:3748-3790).
**Out of scope (other agents):** `d()`, `f()` (lifecycle/event pipeline), node predicates `A`–`Z`.

> Line-range note: `e()` really ends at :2360. Lines :2362-2442 are jadx "code restructure failed" comments left
> over from the *`f()` dispatch*, not code of `e()` — but they are good evidence: they show the package→handler
> dispatch (`nativeBlockTikTok` → `g(...)`, :2368-2372) that jadx could not decompile.

---

## 0. Shared vocabulary

| Symbol | Meaning | Evidence |
|---|---|---|
| `b9` | enum of *surfaces* the service can be "on" for a given app | :1239-1274 |
| `this.f3360g0` | surface classified for the current frame | :2326, :3384, :3408, :3500 |
| `this.f3361h0` | surface classified for the previous frame | :2331, :3413 |
| `this.J` | timestamp of the last handled frame | :2106, :2271, :3378, :3698 |
| `G()` | post-handle bookkeeping / cooldown advance (body out of scope, UNVERIFIED) | :2272, :3379 |
| `a(long,String)` | record a *block* with a label (`"Reels"`, `"Shorts"`) | :2268, :2336, :3501 |
| `c(long,String,boolean)` | record a *soft/skipped* hit with an app label (`"TikTok"`) | :3400, :3414, :3421 |
| `performGlobalAction(1)` | `GLOBAL_ACTION_BACK` | :3376, :3694 |
| `performAction(16)` | `ACTION_CLICK` | :3692 |
| `S(node,list,rect,0)` | text/content-desc matcher over a list of **strings** | :147, :3356 |
| `B(node,list)` | **view-id** matcher: `findAccessibilityNodeInfosByViewId` + area/visibility check | :292-309, :3500 |
| `P(node,0,regex)` | tap a node whose label matches a `sf.i` regex (regexes :260-262) | :3694, :3763 |
| `v(b9)` | feature/int conversion of a surface tag (UNVERIFIED what it returns) | :2261, :3386 |

### Static constants these rules use

| Constant | Value | Line |
|---|---|---|
| `f3345r0` | IG Reels viewer ids: `clips_viewer_view_pager`, `clips_viewer_container`, `clips_viewer_video_layout` | :138 |
| `f3346s0` | YT Shorts ids: `reel_recycler`, `reel_player_page_container`, `shorts_player_container` | :141 |
| `f3347t0` | `{com.zhiliaoapp.musically, com.ss.android.ugc.trill}` | :144 |
| `f3348u0` | **English strings** `"Back to chat"`, `"Reply privately"` = "inside DM/comment context" | :147 |
| `f3352y0` | regex `^(home\|inicio\|início\|accueil\|startseite\|الرئيسية\|الصفحة الرئيسية)([,،]\|$)` | :261 |
| `f3353z0` | regex `^(marketplace\|ماركت بليس)([,،]\|$)` | :262 |
| `G0` | app allow-list: IG, YT, Snap, FB, Orca, TikTok×2, Twitter×2 | :272 |

### Prefs defaults observed here

`nativeBlockInstagramReels=true` (:2123) · `nativeIgHideStories=false` (:2124) ·
`nativeBlockTikTok=false` (jadx warning :2368) · `nativeTikTokAllowSentDM=true` (:3413) ·
`nativeBlockYoutubeShorts=true` (:3479) · `nativeYoutubeSearchOnly=false` (:3478) ·
`nativeReelsBlocking=false` (:1073) · `nativeBlockFacebookFeedSuggested=false` (:1073) ·
`nativeFbMarketplaceOnlyMode=false` (:3749) · `nativeBlockFacebookGroups=false` (`ie/p4.java:19`).

---

## 1. `M(AccessibilityNodeInfo node, String pkg, x2 prefs)` — package → surface dispatcher

Serves **all** apps. It is a *classifier*: no gestures, no clicks, no prefs writes. Given the event's package name and
a node it answers "which `b9` surface is this frame?" or `null` ("not ours / not blockable").

Two chained `switch (str.hashCode())` blocks: the first computes `z3` = "this node really belongs to this app's
blockable surface"; the second maps the package to the `b9` constant.

* **YouTube** — `z3 = B(node, f3346s0)` (:1186), pure view-id probe of the three Shorts ids.
* **Instagram** — `z3 = !nativeIgDmsOnlyMode` (:1194-1199). No node probe: *any* IG node is tagged when DM-only is off.
* **Facebook** — `z3` true only if `!nativeFbDmsOnlyMode && !nativeFbMarketplaceOnlyMode && (U(node,0) || X(node,0))`
  (:1208-1219). `U`/`X` are feed/story predicates (other agent's scope — UNVERIFIED which is which).
* **Snapchat** — `z3 = B(node, "com.snapchat.android:id/spotlight_container")` (:1223).
* **TikTok / Twitter / Twitter Lite** — the decompiled cases just `break` with `z3` still `true` (:1191-1206); the
  real conditions were lost. The jadx failure comments recover them:
  - TikTok (both packages): `S(node, f3348u0, g4.x0.i(node), 0)` (:1122) **plus** a classifier round-trip
    `i5.u.r(this.R, node)` → `a7` with `.a` (a `b7` surface) and `.b`, then `.b == true`, `.a.a() == true`, and
    finally `ie.d7.a(a7.a, prefs)` (:1123-1140). So TikTok needs the `u`/`a7` surface extractor to say "video
    surface, not DM" — not a plain view-id test.
  - Twitter: `B(node, f3345r0)` (:1160), additionally gated by `!nativeXDmsOnlyMode` (:1152) and
    `u(node, "VideoTab")` (:1156).
* Tag mapping (:1236-1279): YT → `b9.A`, Twitter + Lite → `b9.B`, IG → `b9.f7122z`, Trill + Musically → `b9.C`,
  Katana → `b9.D`, Snapchat → `b9.F`; unknown package → `null`.

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| M-1 | YT Shorts | `B(node, f3346s0)` ∩ area/visibility | tag `b9.A` | none (tag only) |
| M-2 | IG Reels | any IG node, `!nativeIgDmsOnlyMode` | tag `b9.f7122z` | `nativeIgDmsOnlyMode` (false) |
| M-3 | FB feed/reels | `U(node)‖X(node)`, DM-only & Marketplace-only off | tag `b9.D` | `nativeFbDmsOnlyMode`, `nativeFbMarketplaceOnlyMode` (both false) |
| M-4 | Snapchat Spotlight | view-id `com.snapchat.android:id/spotlight_container` | tag `b9.F` | — |
| M-5 | TikTok feed | `S(node, f3348u0, …)` + `a7` extractor + `d7.a(surface, prefs)` | tag `b9.C` | `nativeBlockTikTok` (false, via caller) |
| M-6 | Twitter video | `B(node, f3345r0)` + `u(node,"VideoTab")` | tag `b9.B` | `nativeXDmsOnlyMode` (false) |

**Brittleness:** M-6 borrows the *Instagram* clips-viewer id list for Twitter (either it is really a generic "video
player" list or jadx mis-rendered the constant — **UNVERIFIED**). M-2 tags *every* IG node as `b9.f7122z` when DM-only
is off, so IG rules must re-check the real screen.

---

## 2. `L()` — Facebook "return" (post-block back-out)

Facebook only, and **paid accounts only**.

Guards, in order (:1068-1073):
1. `this.f3372x` (a `w7.i` overlay/gesture coordinator — UNVERIFIED which) non-null with a live `View`;
2. the `m4` state machine `this.A` is **not** in state `a()`;
3. `nativeReelsBlocking` **and** `nativeBlockFacebookFeedSuggested` both true;
4. `!nativeFbDmsOnlyMode` **and** `!nativeFbMarketplaceOnlyMode`;
5. `ka.f7587i.G(this).G0()` — logged as `"pro="` beside `"kid="` (:2061), i.e. the **pro/premium** flag;
6. `n()` returns a node — `n()` walks `getWindows()` for the **focused** window with `getType() == 1` (application)
   and takes its root (:3792-3810).

Action (:1074-1103): take the focused window's `getBoundsInScreen(rect)` (the whole viewport); arm `this.A` (state
`l4.f7608y`, `m = now+5000`, `j=0`, `k=0`, `n=now` — a **5 s lockout**, :1080-1084);
`this.f3371w.b(rect, true, false)` — a **scroll/swipe over the full-window rect** (`i4.b(Rect, boolean, boolean)`; the
boolean pair encodes direction/axis, UNVERIFIED which — the name plus the `"FbReturnCheck"` log point to *scroll back
up / return to top*); poke both `w7.i` helpers (`u()`); `Log.w("FbReturnCheck", "return requested")`; then run an `h6`
callback (mode 7) directly or via `c4Var.a(h6Var)` — a "did the scroll land" follow-up (:1095-1102); always
`return true` once the gesture is issued.

No `a(...)` call, so this is *recovery after* a block, not a block.

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| L-1 | FB feed (whole window) | focused application window + pro account + suggested-mode | one full-window swipe, 5 s lockout, callback mode 7 | `nativeReelsBlocking`=false, `nativeBlockFacebookFeedSuggested`=false, `nativeFbDmsOnlyMode`/`nativeFbMarketplaceOnlyMode` must be false |

**Brittleness:** the rect is the *entire* focused window, so a mis-timed swipe scrolls whatever is on screen (a DM
thread included if the DMs-only guard flickers). Depends on `getWindows()` exposing a focused `TYPE_APPLICATION`
window — picks the wrong one in split-screen/multi-window.

---

## 3. `e(node, prefs, now)` — Instagram (Reels + Stories tick)

Instagram only. Three stages.

### 3a. DM-only early exit (:2100-2122)
With `nativeIgDmsOnlyMode`: take root (arg or `getRootInActiveWindow()`), probe
`Y(root,0,"com.instagram.android:id/direct_inbox_action_bar")` and
`Y(root,0,"com.instagram.android:id/row_thread_composer_edittext")` (DM inbox bar, DM composer). If **neither** and
`Q(root,0)` holds → `this.J = now; return` — the frame is claimed as allowed so no later IG rule acts on it.
(`Q` = a social-surface predicate, other agent's scope — UNVERIFIED.)

### 3b. Flags + story tick (:2123-2128)
```java
z13 = accountType=="personal" && !nativeIgDmsOnlyMode && nativeBlockInstagramReels  // :2123, default true
z14 = nativeIgHideStories                                                          // :2124, default false
if (z14) this.O.h();                                                               // :2126-2128 throttled tick
```
`this.O` is an `r1` debouncer (4 slots); `h()` only *pokes* it — the actual story hiding lives in the tick callback
(out of scope).

### 3c. Reels-activity tracking via UsageStats (:2131-2226)
Gated on `z13`; `this.P` (`o` provider) yields a `j2` UsageStats reader:
* 250 ms throttle (`elapsedRealtime - f7519d >= 250`) (:2138);
* `queryEvents(max(b, now - SUPPORTED_FALLBACK_TIMEOUT_MS), now)` (:2150), then scan;
* event type `1` (ACTIVITY_RESUMED) with `packageName == "com.instagram.android"` and `className ∈`
  `{com.instagram.urlhandlers.clips.ClipsUrlHandlerActivity, com.instagram.urlhandlers.media.MediaUrlHandlerActivity}`
  → remember timestamp, `z12 = true` (:2193-2197);
* if `z12`: `this.N.c()` (reset the `t5` dwell tracker) and `f8017c = now + SUPPORTED_FALLBACK_TIMEOUT_MS`
  (:2200-2203) — "user just deep-linked into Reels, suppress blocking for a window";
* else (:2216-2226): also `this.N.c()` + `this.O.h()`, and `j2.f7518c = now`.

### 3d. Gate (:2227-2260)
`this.Z` (`i5`): null → `z11 = true`, else `z11 = iVar.i()`. Continue only if `z11 && prefs.getBoolean(
"nativeBlockInstagramReels", true)` (:2233); otherwise reset both debouncers, recycle, return. Then the node's package
must equal `com.instagram.android` (:2254).

### 3e. Classifier pass (:2258-2275)
`this.M` (class `a`) `.d(node, !nativeIgDmsOnlyMode, v(b9.f7122z))` → `c6`:
* `== c6.f7171t` → "nothing here": reset, fall through;
* anything else (`!= c6.f7171t`) → reset; if it is also `!= c6.f7172u` → `a(now, "Reels")`, **count a Reels block**
  (:2268); if it *is* `c6.f7172u` → `this.J = now; G();` — handled/cooldown path (:2271-2272).

### 3f. Reels-viewer detection & dwell (:2276-2339)
`B = B(node, f3345r0)` — the frame *is* the Reels viewer (three `clips_viewer_*` ids). If `z13 && !z14` and package
is IG:
* `z16 = !B && g6.b(node)` — "in feed, not viewer, looks like a reel cell" (UNVERIFIED `g6.b`);
* `E = E(node)` — extra numeric/text signal (UNVERIFIED);
* `r6 = B ? r(node) : null` — an id string for the current reel (UNVERIFIED `r`);
* `t5` dwell tracker (`this.N`): viewer visible → `t5Var2.d(elapsed, r6, E)` ⇒ `bool2` = "dwell threshold reached,
  block now" (also flushes the `f7879c` first-seen stamp with a 1501 ms window, :2291-2304); not viewer but `z16` →
  `t5Var2.c(); f7879c = elapsed` (arm), otherwise clear (:2306-2312);
* if `B`: refresh `t5` when `(b && now - f8020f > 1200) || (!b && now > f8017c)`, reset debouncers, recycle, **return
  without blocking** (:2316-2325) — the *viewer* is never blocked here, only feed entry points;
* `f3360g0 == b9.f7122z` → reset + return (:2326-2330);
* else if `f3361h0 != b9.f7122z` → `s(node, prefs, now, bool2)` — shared IG block handler carrying the dwell verdict
  (out of scope; this is where the swipe/BACK happens);
* else → `a(now, "Reels")` (:2336).

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| e-1 | IG DM inbox/thread | `direct_inbox_action_bar` or `row_thread_composer_edittext` | none (suppress) | `nativeIgDmsOnlyMode` (false) |
| e-2 | IG any | DM-only on, no DM node, `Q(root)` | stamp `J`, suppress pipeline | `nativeIgDmsOnlyMode` |
| e-3 | IG Stories | `nativeIgHideStories` | poke `this.O` tick (throttled) | `nativeIgHideStories`=false |
| e-4 | IG Reels deep-link | UsageStats RESUMED on `ClipsUrlHandlerActivity`/`MediaUrlHandlerActivity` | reset dwell, suppress for timeout | `nativeBlockInstagramReels`=true, `nativeIgDmsOnlyMode`=false, `accountType`=`personal` |
| e-5 | IG Reels viewer | `B(node, f3345r0)` | never blocked, refresh dwell only | as e-4 |
| e-6 | IG feed reel cell | `g6.b(node)` + `t5.d(...)` verdict | `s(node, prefs, now, verdict)` | as e-4 |
| e-7 | IG Reels (classifier) | `a.d(...)` is neither `c6.f7171t` nor `c6.f7172u` | `a(now,"Reels")` | `nativeBlockInstagramReels` |
| e-8 | IG classifier "handled" | `a.d(...) == c6.f7172u` | `J=now; G()` | — |

---

## 4. `g(node, prefs, now)` — TikTok / Douyin (Musically + Trill)

Called from the `f()` dispatcher when package ∈ `f3347t0` and `nativeBlockTikTok` (jadx warning :2368-2372).

```java
3352  rect = bounds(node)
3353  r6   = u.r(this.R, node)            // a7 { b7 surface (r6.a), boolean playing (r6.b) }
3356  S    = S(node, f3348u0, rect, 0)    // "Back to chat" / "Reply privately" → DM/comment context
3357  z3   = S || surface ∈ {b7.G, b7.D}
```

**`e7` one-shot back-out** (`this.S` state machine, :3359-3383): reset when `!playing && surface == b7.D`, reset when
`z3`, arm on `playing` (`f7250a=true`, `f7251c=now`) and keep `f7252d=now`, reset when idle > 1200 ms. Fire when
`!f7250a && b && !f7256h && playing && now - f7252d <= 1000` → `performGlobalAction(1)` (**GLOBAL_ACTION_BACK**), set
`f7256h` (once per episode), `J=now`, `G()` (:3375-3383). That is the "watch ~1 s, then back out of the TikTok player"
rule.

**Shared handler** (:3388-3393): if `surface != b7.f7108v` (comments sheet, UNVERIFIED) and `d7.i(surface, prefs, z7)`
→ `H(node, prefs, now)`, return. `z7` is true only when `f3360g0 == b9.C || v(b9.C)` (:3384-3387, :3427).

**DM carve-out** (:3394-3405): if `S` — playing (`surface.a()`) → `H(...)`; else → `c(now, surface.name(), true)`.

**Fallback** (:3406-3425): reset `u` state; if `f3360g0 == b9.C` → return; if `f3361h0 == b9.C ||
!nativeTikTokAllowSentDM` (default **true**) → `c(now,"TikTok",false)`; else extract a text key
`q(node, 0, new c8.d(18))` and compare with `this.Q` — a differing key ⇒ `c(now,"TikTok",false)` ("content changed ⇒
new, non-DM screen").

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| g-1 | TikTok player | `e7` armed + playing ≥ ~1 s, `!f7256h` | `GLOBAL_ACTION_BACK` once | `nativeBlockTikTok` (false) |
| g-2 | TikTok player (not comments) | `surface != b7.f7108v` + `d7.i(...)` | `H(node,prefs,now)` shared blocker | `nativeBlockTikTok`, `nativeTikTokAllowSentDM` |
| g-3 | TikTok DM/comment | `S(node, f3348u0)` — text "Back to chat"/"Reply privately" | playing → `H(...)`; else `c(now, surface.name(), true)` | `nativeTikTokAllowSentDM`=true |
| g-4 | TikTok generic | previous surface `b9.C` or `nativeTikTokAllowSentDM` false | `c(now,"TikTok",false)` flag | `nativeTikTokAllowSentDM` |
| g-5 | TikTok generic | text-key change vs `this.Q` | `c(now,"TikTok",false)` flag | `nativeTikTokAllowSentDM` |

**Brittleness:** DM/comment detection is **hard-coded English** (`"Back to chat"`, `"Reply privately"`, :147) — every
non-English locale loses the sent-DM carve-out and gets DMs blocked. The `b7` surface enum must stay aligned with
TikTok's tree. jadx left duplicated dead branches (:3433-3456), so g-4/g-5 ordering should be confirmed in smali.

---

## 5. `h(node, prefs, now)` — YouTube Shorts + search-only

YouTube only.

```java
3478 z7  = accountType != "kid" && nativeYoutubeSearchOnly   // default false
3479 z10 = nativeBlockYoutubeShorts                          // default true
3480 if (!z7) this.f3363j0.c();                               // clear search-only enforcer
3483 if (!z7 && !z10) -> recycle + return                     // nothing enabled
3495 if (B(node, f3346s0)) { b.c(); if (f3360g0 != b9.A) a(now,"Shorts"); return; }
3506 if (z7) { a10 = x7.a(node, sameWindowFlag); b.d(a10, windowId) else b.c(); }
```

* **Shorts**: view-id probe of `reel_recycler` / `reel_player_page_container` / `shorts_player_container`
  (`f3346s0`). Found → reset the `b` helper and `a(now,"Shorts")` unless the frame is already `b9.A` (:3495-3505).
* **Search-only**: `x7.a(node, z3)` computes an allowed-region rect, where `z3` = "stored rect belongs to the same
  `getWindowId()`" (:3509-3513). The rect is parked in `this.f3363j0` via `b.d(rect, windowId)` for the overlay/gesture
  layer to enforce; `b.c()` clears it (:3516-3523). Note `a10 = x7.a(...)` is computed **twice** (:3513 and :3515) and
  the second call passes `false` — jadx artifact or genuine double call (**UNVERIFIED**).

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| h-1 | YT Shorts | `B(node, f3346s0)` | `a(now,"Shorts")` unless already `b9.A`; clear `b` | `nativeBlockYoutubeShorts`=true |
| h-2 | YT search-only | `x7.a(node, …)` allowed rect | store rect + window id in `this.f3363j0`, clear when absent | `nativeYoutubeSearchOnly`=false, `accountType != "kid"` |

**Brittleness:** `accountType == "kid"` disables search-only but **not** Shorts blocking. Region enforcement assumes
`getWindowId()` is stable across frames.

---

## 6. `i(node, depth, y, LinkedHashSet out, int[] budget)` — band text collector

Recursive helper, depth ≤ 3 (:3542), **shared** node budget `iArr[0]` decremented for *every* visited node (walk
aborts at 0), hard stop at 3 collected strings (:3545).

Per node:
* `y(node)` must be true (a "has usable label" predicate, other agent's scope — UNVERIFIED);
* `i10` — a screen **y coordinate** — must satisfy `rect.top <= i10 < rect.bottom` (:3549): the *horizontal band* test;
* both hold → add `l(node.getContentDescription().toString())` (`l()` = a string normaliser, UNVERIFIED but almost
  certainly trim/lowercase/collapse) to the `LinkedHashSet` (dedup + order) (:3553);
* recurse into children while budget remains and `out.size() < 3` (:3557-3565).

Returns nothing; the caller reads the set. Semantics: "up to 3 distinct content-descriptions of labelled elements on
the same scanline as `y`", budget-capped so a deep tree cannot stall the service. Callers (e.g. `s(...)`, out of scope)
use it to grab the caption/author string of the item centred at that y.

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| i-1 | any | depth ≤ 3, `y(node)`, `rect.top ≤ y < rect.bottom` | collect ≤3 normalised content-descriptions | — |
| i-2 | any | visit budget exhausted or 3 collected | stop walking | — |

**Brittleness:** relies *entirely* on `contentDescription` — plain text nodes are invisible to it. The single-band
assumption breaks for multi-column grids (two cards share a y). Budget exhaustion silently yields fewer than 3 strings,
which downstream rules must tolerate.

---

## 7. `j(node, prefs, now)` — Facebook **Groups** tab strip

Facebook only. Gate: `p4.a(prefs)` = `accountType != "kid" && nativeBlockFacebookGroups` (default **false**)
(`ie/p4.java:19`).

```java
3589 p10 = p(node, 0)                                    // normalised screen/header label
3591 if (p10 == null || p4.f7799a.contains(p4.c(p10)))    // "groups"/"grupos"/"groupes"/…/"グループ"
3595   p4.b(budget, map, node, 0)                        // depth ≤ 120 walk → map<n4, node>
3605   if (rects.size() == 3) { … }                       // exactly 3 tab cells required
```

`ie/p4.b` (ie/p4.java:22-62): recursive walk (depth ≤ 120, shared budget); for each node whose
**contentDescription**, normalised by `p4.c` (strip `,`/`،`, lowercase), is in one of the three `n4` label sets, which
`isVisibleToUser()`, has non-empty bounds, and whose label matches `sf.i` `[,،]\s*[^,،]*[\d٠-٩۰-۹]+[^,،]*[\d٠-٩۰-۹]+`
(the "N posts · M members" counter — two numeral runs) → store `n4 → obtain(node)`. First match per tab wins.

The three tabs (`ie/n4.java`): `FOR_YOU` → `nativeFbBlockGroupsForYou`, `YOUR_GROUPS` → `nativeFbBlockGroupsYourGroups`,
`DISCOVER` → `nativeFbBlockGroupsDiscover`, each with localised label sets ("for you"/"para ti"/…, "your groups"/…,
"discover"/…).

Geometry sanity (:3605-3646): exactly 3 rects, `max(top) < min(bottom)` (all share a vertical band), then a nested loop
meant to require **pairwise non-intersection**. The loop body is empty in the decompiled output — jadx dropped the
negation, so "3 distinct non-overlapping tab cells" is the intent (**UNVERIFIED**).

Decision (:3649-3719): `z3 = true` only if **all three** prefs are `true`; `a10 = o4Var.a()` is the tab `o4` considers
current/blocked (UNVERIFIED).

* `!z3` (at least one tab disabled):
  - `p10` is a group name (`p4.f7799a` does not contain it) **and** `a10 == null` → close, `return false` — already
    inside a group, don't fight it (:3666-3671);
  - else, only when `now - this.f3365l0 < 1500`: pick the first `n4` whose pref is **false** (:3673-3687); if that
    tab's node exists → `performAction(16)` = **CLICK it** (:3689-3692); else → `W(node,0) ? P(node,0,f3352y0) :
    performGlobalAction(1)` — tap "home" (multilingual regex) or **BACK** (:3694); then `f3365l0 = J = now`, `return
    true` (:3696-3699). Outside the 1500 ms window → close, `return true` (:3703-3707).
* `z3` (all tabs allowed) → close, `return false` (:3709-3719).

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| j-1 | FB Groups tab strip | `p4.a` + header label ∈ {"groups",…} or null | build 3-tab map | `nativeBlockFacebookGroups`=false, `accountType != "kid"` |
| j-2 | FB Groups, some tab disabled | 3 tabs found, `z3 == false`, not inside a group | `performAction(16)` on the first **disabled** tab | `nativeFbBlockGroupsForYou` / `...YourGroups` / `...Discover` (all true) |
| j-3 | FB Groups, no tab node | `z3 == false`, disabled tab has no node | tap `home` (i18n regex) else `GLOBAL_ACTION_BACK` | as j-2 |
| j-4 | FB Groups all enabled / inside a group | `z3 == true` or header is a group name | no-op (`return false`) | as j-2 |

**Brittleness:** requires **exactly 3** tab cells with non-empty, non-overlapping rects — a 4th tab, a search pill or a
banner silently disables the rule (fail-open). Identification needs *localised* content-descriptions **plus** a counter
containing two digit runs; no text fallback. Actions are gated to a 1500 ms window.

---

## 8. `k(node, prefs, now, stale)` — Facebook **Marketplace-only** mode

Facebook only — the one rule here that **navigates with an Intent**.

```java
3749 if (!nativeFbMarketplaceOnlyMode) { K(); return false; }             // mode off
3753 p10 = p(node, 0)
3754 iVar = f3353z0                                                       // ^(marketplace|ماركت بليس)([,،]|$)
3755 if (p10 != null && iVar.a(p10)) { K(); return false; }                // already on Marketplace → allowed
3759 if (!stale) {
3760   if (p10 != null) {
3761     if (now >= n0 && p0 < 3) {
3762       n0 = now + 1500;
3763       if (o0 < 2 && P(node, 0, iVar)) { o0++; J = now; return true; }   // tap the Marketplace entry
3768       p0++; n0 = now + SUPPORTED_FALLBACK_TIMEOUT_MS;
3771       startActivity(new Intent(VIEW, Uri.parse("fb://marketplace"))
3772           .setPackage("com.facebook.katana").addFlags(268435456));      // FLAG_ACTIVITY_NEW_TASK
3772       J = now; return true;
3776     } catch ActivityNotFoundException → log "[FB-MP-ONLY] native Marketplace route unavailable"
3777     catch SecurityException          → log "[FB-MP-ONLY] native Marketplace route denied"
3780     return true;                                                       // "handled" even on failure
3782   if (!W(node,0)) {
3784     if (t(node, rect.top + rect.height()/6, 0)) K();                    // probe near the top of screen
```

`o0` = direct-tap attempts (max **2**), `p0` = deep-link launches (max **3**), `n0` = next-allowed time (1500 ms after
a tap, full `SUPPORTED_FALLBACK_TIMEOUT_MS` after a launch). `K()` = reset/park helper (out of scope, UNVERIFIED — it
is the "allow" path, not a block). `t(node, y, 0)` = geometry probe at `top + height/6` (UNVERIFIED).

| Rule | Surface | Match | Action | Prefs key |
|---|---|---|---|---|
| k-1 | FB any | mode off | `K()`, `return false` | `nativeFbMarketplaceOnlyMode`=false |
| k-2 | FB Marketplace | header matches `^(marketplace\|ماركت بليس)([,،]\|$)` | `K()`, allowed | `nativeFbMarketplaceOnlyMode` |
| k-3 | FB non-Marketplace | header present, `o0 < 2` | `P(node,0,f3353z0)` tap, 1500 ms limit | `nativeFbMarketplaceOnlyMode` |
| k-4 | FB non-Marketplace | header present, taps exhausted, `p0 < 3` | `startActivity(VIEW fb://marketplace, pkg com.facebook.katana, NEW_TASK)` | `nativeFbMarketplaceOnlyMode` |
| k-5 | FB non-Marketplace, no header | `t(node, top + h/6, 0)` | `K()` (allow) | `nativeFbMarketplaceOnlyMode` |

**Brittleness:** `fb://marketplace` is an **undocumented deep link** that can vanish in any FB release; both failure
paths are swallowed, so a Marketplace-only user simply sits on the feed. `p0` is never reset here, so after 3 failed
launches the deep-link fallback is dead for the service's lifetime. Detection is a **label regex** (English + Arabic
only).

---

## 9. Cross-cutting summary

| Method | App(s) | Actor? | Primary mechanism | Key prefs |
|---|---|---|---|---|
| `M` | all (IG, YT, FB, Snap, TikTok, X) | no (classifier) | view-id probes + per-app predicates → `b9` | `nativeIgDmsOnlyMode`, `nativeFbDmsOnlyMode`, `nativeFbMarketplaceOnlyMode`, `nativeXDmsOnlyMode` |
| `L` | Facebook | yes (recovery) | full-window swipe + 5 s `m4` lockout | `nativeReelsBlocking`, `nativeBlockFacebookFeedSuggested`, pro account |
| `e` | Instagram | yes | `clips_viewer_*` + `a.d()` classifier + `t5` dwell → `s(...)`; UsageStats deep-link suppression | `nativeBlockInstagramReels`=true, `nativeIgDmsOnlyMode`, `nativeIgHideStories`, `accountType` |
| `g` | TikTok / Douyin | yes | `e7` one-shot `GLOBAL_ACTION_BACK` + `d7.i`/`H` + DM text carve-out | `nativeBlockTikTok`=false, `nativeTikTokAllowSentDM`=true |
| `h` | YouTube | yes | `f3346s0` Shorts ids → `a(now,"Shorts")`; allowed rect for search-only | `nativeBlockYoutubeShorts`=true, `nativeYoutubeSearchOnly`=false |
| `i` | any | no (helper) | depth≤3 band walk, ≤3 content-descriptions, shared budget | — |
| `j` | Facebook Groups | yes | 3-tab strip → click the disabled tab, else home/BACK | `nativeBlockFacebookGroups`=false + 3 tab prefs |
| `k` | Facebook Marketplace | yes | label regex → tap Marketplace → `fb://marketplace` | `nativeFbMarketplaceOnlyMode`=false |

**Shared vs app-specific:** `j()` and `k()` are **Facebook-only and mutually exclusive with the rest of FB** — `j()`
needs `nativeBlockFacebookGroups`, `k()` needs `nativeFbMarketplaceOnlyMode`, and `M()` refuses to tag FB frames at all
when Marketplace-only is on (:1210). `e`/`g`/`h` are strictly single-app (IG / TikTok / YT). Only `M` and `i` are
cross-app; `i()` is a pure tree-walk utility with no policy of its own.

**Brittleness, consolidated**
1. **English-only strings where it matters**: `f3348u0 = {"Back to chat","Reply privately"}` (TikTok DM carve-out,
   :147) and the `f3352y0`/`f3353z0` label regexes (:261-262). Localisation effort went into the Groups tab labels and
   IG caption regexes, not into the *action gates*.
2. **Exact view-ids / exact counts**: IG `clips_viewer_*`, YT `reel_*`/`shorts_player_container`, Snap
   `spotlight_container`, and `j()`'s "exactly 3 non-overlapping tabs". All fail **open** (content leaks) rather than
   over-blocking.
3. **Per-app magic numbers**: 250 ms UsageStats throttle, 1200/1501 ms dwell windows, 1500 ms action gates, 5000 ms FB
   lockout, 2 taps / 3 launches — no shared constant.
4. **Logcat oracles**: `"FbReturnCheck"`, `"[FB-MP-ONLY] native Marketplace route denied"`, `"IG check error"`,
   `"TikTok check error"`, `"YT check error"` identify which branch fired in the field.
