# NativeReelsBlockerService — per-app rules in `f()` and `d()`

Source: `reverse/rd-work3/NRBS.java` (jadx pseudo-Java), cross-checked against
`reverse/dec/smali/smali*/com/sociallite/android/data/NativeReelsBlockerService*.smali`.
Prefs: `reverse/rd-work3/prefs_nrbs.txt`. Supporting classes: `reverse/dec/jadx/sources/ie/`.

Shared context:

- `ie.b9` app-surface enum (ie/b9.java:43-58): `f7122z`=INSTAGRAM, `A`=YOUTUBE,
  `B`=TWITTER(X), `C`=TIKTOK, `D`=FACEBOOK, `E`=LINKEDIN, `F`=SNAPCHAT, `G`=REDDIT.
- `this.J` = "timestamp of last block"; doubles as the 800 ms re-entry throttle.
- `b(boolean allowBack, long now)` (NRBS.java:1434-1470), the generic FB block:
  - 1200 ms rate limit on `f3364k0` (1450);
  - if top-bar text matches `f3351x0` `^(reels|video|videos|watch|…)([,،]|$)` it
    **taps the Home tab** (`P(root,0,f3352y0)`) instead of going back (1454-1455);
  - else if `allowBack && w(root)` → `performGlobalAction(1)` = GLOBAL_ACTION_BACK
    (1456-1457); success stamps `f3364k0`, `J`, `G()` (1460-1463).
- `a(long now, String reason)` (NRBS.java:1404-1408): unconditional BACK
  (`J = now; G(); performGlobalAction(1)`), reason used only for logging.

---

## 1. `f()` — global dispatcher + X and Snapchat rules
*(NRBS.java:2443-3323, ~880 lines)*

### 1.1 Packages served

`G0` (NRBS.java:272), hard-coded allow-list: `com.instagram.android`,
`com.google.android.youtube`, `com.snapchat.android`, `com.facebook.katana`,
`com.facebook.orca`, `com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`,
`com.twitter.android`, `com.twitter.android.lite`. `com.android.systemui` and
`android` are excluded everywhere (2580, 2594, 2600, 2603, 2630). TikTok set
`f3347t0` (144).

| package | what `f()` does |
|---|---|
| instagram | dispatch `e()` (other analysis) |
| youtube | dispatch `h()` (other analysis) |
| snapchat | inline Spotlight/Discover rules (§1.6) |
| facebook.katana | dispatch `d()` (§2) when a FB pref is on |
| facebook.orca | no-op guard |
| tiktok (both pkgs) | only grandfather/entitlement handling here |
| twitter + lite | inline video-feed rule (§1.5) |

### 1.2 Preconditions

| gate | line |
|---|---|
| `F()` false → `O()` reset, return | 2486-2489 |
| root is not the app window (`getType() != 1`) → re-resolve from focused window via `getWindows()` (IME/overlay handling) | 2523-2564 |
| `nativeReelsBlocking == false` → drop every in-scope pkg, reset timers | 2677-2687 |
| entitlement: `G.G0()` OR (`legacyFreeTikTokGrandfathered` AND `G.I()`) OR pkg ∈ TikTok set; else drop | 2690-2710 |
| FB-only resets (`nativeFbDmsOnlyMode`, `nativeBlockFacebookFeedSuggested`, `nativeReelsBlocking`) | 2623-2634 |

`accountType == "kid"` bypasses the DM-only shortcuts (1605, 2623).

### 1.3 Surface classification `M()`

`M(node, pkg, prefs)` → `b9` or `null` (NRBS.java:1181-1280). `null` = "no rule runs":

| pkg | condition | enum |
|---|---|---|
| youtube | `B(node, f3346s0)`; `f3346s0` = `com.google.android.youtube:id/reel_recycler`, `…:id/reel_player_page_container`, `…:id/shorts_player_container` (141) | `A` |
| twitter / lite | always | `B` |
| instagram | unless `nativeIgDmsOnlyMode` | `f7122z` |
| facebook | unless `nativeFbDmsOnlyMode` / `nativeFbMarketplaceOnlyMode` / `U(node,0)` / `X(node,0)` | `D` |
| snapchat | only if `B(node,["com.snapchat.android:id/spotlight_container"])` | `F` |
| tiktok | always | `C` |

Result is cached in `f3361h0` (previous surface) and compared 1:1 later — the whole
behaviour is driven by enum identity, not strings.

### 1.4 Usage accounting + global throttle

`o6` accumulates short-form usage in 1500 ms buckets, clamped to ms-since-midnight,
rolled daily, written as `nativeShortFormUsage` JSON (2748-2801). `v(b9)` (4806-4818)
answers "daily allowance exceeded" and stops the Reels rules once used up.
`this.K` gets an app label from a `String.hashCode()` switch (2812-2907).
If `now - this.J < 800` the **entire per-app dispatch is skipped** (2810-2811, 3246-3248).

### 1.5 X / Twitter video rule (inline)

Target: full-screen **video tab** (`VideoTab`), not the timeline.

1. `nativeXDmsOnlyMode` → bail (2968).
2. previous surface `== b9.B` → clear key, return (2972-2978).
3. previous surface `== b9.B` via `f3361h0` → `a(now,"X media feed")` = BACK (2979-2984).
4. require `u(root,"VideoTab")` (view-id/resource-name probe, 4781-4803: id lookup +
   visibility check, fallback DFS by resource name, depth ≤120, 400-node budget);
   else probe `"MainLanding"` and return (2985-2990).
5. extract a video key with `q(root,0,…)` (two probes, second truncated to 20 chars);
   if it differs from `this.T` → `a(now,"X media feed")` = BACK; if `T` is null, just
   remember it (2991-3007). So: one BACK per new video, not a one-shot exit.

Prefs: `nativeBlockTwitter` (def. **false**, 2940), `nativeXDmsOnlyMode` (def. false, 2968).

### 1.6 Snapchat Spotlight / Discover (inline)

Prefs: `nativeSnapchatSpotlightBlocking` (def. **false**, 3069),
`nativeSnapchatBlockDiscover` (def. **TRUE**, 3070 — the only on-by-default rule).
Both off → return. Collects **all** `com.snapchat.android` window roots into an
`ArrayList` (3087-3114), then loops them (3121-3185):

| rule | match | action | line |
|---|---|---|---|
| already in Spotlight | surface `== b9.F` and (`B(root,"com.snapchat.android:id/spotlight_container")` or `s1.B(root)`) | return, no action | 3128-3139 |
| Spotlight strict | `nativeSnapchatSpotlightBlocking` and (container id or (`D(root)` and `!v(b9.F)`)) | `a(now,"Spotlight")` → BACK | 3140-3150 |
| Spotlight loose | either flag and `s1.B(root)` | `a(now,"Spotlight")` → BACK | 3151-3161 |
| Discover | `nativeSnapchatBlockDiscover` and (`T(root,F0,rect,0)` or `Z(root,rect,0)`) | `a(now,"Discover")` → BACK | 3162-3176 |

`F0` (268) = `chrome_subscribe_button`, `AdsFloatingPillView`,
`sc_composer_ad_cta_tappable_view` (ad/story CTA markers). `D()`, `s1.B()`, `T()`,
`Z()` semantics **UNVERIFIED** (other analyses).

### 1.7 YouTube gate

NRBS.java:2608-2618 reads `nativeReelsBlocking` then `nativeYoutubeSearchOnly` inside
an **empty** `if` — the real behaviour is in `h()`. Treat this branch as a
decompiler artefact (**UNVERIFIED**); the key is still consumed by the service.

### 1.8 Loop structure in `f()`

No recursion inside `f()`. Walks live in helpers: `u()` (`findAccessibilityNodeInfosByViewId`
+ `a0()` DFS, depth ≤120, `this.U` budget 400, 1410-1432), `B()` (id list probe),
`q()` (bounded text DFS), Snapchat (iteration over `getWindows()` roots).

### 1.9 Rule table — `f()`

| rule | surface | match | action | prefs key |
|---|---|---|---|---|
| master gate | all | `nativeReelsBlocking == false` | drop all, reset | `nativeReelsBlocking` (def. false) |
| free-tier gate | all | `!G.G0()` and not (`legacyFreeTikTokGrandfathered` and `G.I()`) and pkg ∉ TikTok | drop package | `legacyFreeTikTokGrandfathered` |
| global throttle | all | `now - J < 800` | skip dispatch | — |
| classify | all | `M()` → `null` | no rules | — |
| X DMs-only | X | `nativeXDmsOnlyMode` | never block media | `nativeXDmsOnlyMode` (def. false) |
| X media detected | X | `f3361h0 == b9.B` | `a(now,"X media feed")` BACK | `nativeBlockTwitter` (def. false) |
| X video tab | X video tab | `u(root,"VideoTab")` (+`MainLanding`) | proceed | `nativeBlockTwitter` |
| X new video | X video tab | key ≠ `this.T` | `a(now,"X media feed")` BACK | `nativeBlockTwitter` |
| FB enable | FB | any of 5 FB flags / `p4.a` | call `d()` | `nativeBlockFacebookReels`, `nativeBlockFacebookFeedSuggested`, `nativeFbMarketplaceOnlyMode`, `nativeFbDmsOnlyMode` |
| Messenger | `com.facebook.orca` | always | recycle, no-op | `nativeBlockFacebookReels` |
| Snapchat off | Snapchat | both flags false | return | `nativeSnapchatSpotlightBlocking`, `nativeSnapchatBlockDiscover` |
| Snap in Spotlight | Spotlight | surface `== b9.F` + container id / `s1.B` | no action | both |
| Snap Spotlight strict | Spotlight | spotlight flag + (id or (`D()` and allowance left)) | `a(now,"Spotlight")` BACK | `nativeSnapchatSpotlightBlocking` |
| Snap Spotlight loose | Spotlight | either flag + `s1.B(root)` | `a(now,"Spotlight")` BACK | both |
| Snap Discover | Discover | discover flag + (`T(root,F0,…)` or `Z(root,…)`) | `a(now,"Discover")` BACK | `nativeSnapchatBlockDiscover` (def. **true**) |

---

## 2. `d(AccessibilityNodeInfo, x2, long)` — Facebook (`com.facebook.katana`)
*(NRBS.java:1533-1988, ~455 lines)*

Serves **Facebook only** (log tag `"FB check error"`, 1585). Called from `f()` at 3033.

### 2.1 Preconditions

| gate | line |
|---|---|
| `nativeFbDmsOnlyMode` or `accountType == "kid"` → skip all FB rules, reset widgets (`d4.b()`, `c4.b()`) | 1603-1605, 1679-1683 |
| root resolution; monitor fed via `r4.e(root)` | 1568-1594 |
| once per scan: `w(root)` (3-rows heuristic) and `W(root,0)` | 1607-1608 |

### 2.2 Feed-suggested machinery (delegated to `ie.d4`)

`d4.a(node, now, isShortForm, isFeed)` (ie/d4.java:33-88):

- first call only records a timestamp (39-44);
- at most **one attempt per 1500 ms**, at most **2 clicks** (`f7206e < 2`, 45-47);
- target node found by `s1.z(budget 1500, rect, node, 0, false)` (semantics
  **UNVERIFIED**) and clicked with `performAction(16)` (48-66) — a *click*, no scroll;
- after 3000 ms without success it invokes the `i6` callback and returns `true`
  (68-85); the service then stamps `this.J` = "blocked" (1621-1623, 1640).

`d4.f7204c` = "feed is in suggested-post state": while true the `c4` overlay is hidden
(`c4Var.b()`), otherwise it is positioned from the root bounds
(`c4.f(c4Var, rect, false, 6)`, 1624-1634).

### 2.3 Card rect + overlay

When the top bar matches `f3351x0` **or** predicate `R(…)` succeeds, `z15 = true`
(1700-1724, 1783-1789). That flag feeds `s1.V(node, new j4(m4, now, z15, g4, node))`,
which returns the **Rect of the suggested-post/ad card** (1738, 1803). `g4` is the
parsed `nativeFacebookFeedSection` enum (`ie.c.u(string)`, 1729-1731).

`rect` (card) and `rect2` (secondary probe `k4(2)`, only when not already in the reels
viewer and `!m4.a()`) drive `f3371w` (`i4`: `a()` start, `b(rect,isFeed,flags)`
reposition), `f3372x`/`f3373y` (`w7.i` timers: `u()` start, `R(null)` stop)
(1740-1781). These are **visual overlays**, not blocks.

### 2.4 The Reels rules

State: `V` (last reels transition), `W` (media key of current reel), `Y` (last reels
frame), `f3360g0` (current surface), `f3361h0` (previous surface), `q4.b` /
`q4.f7835a` ("seen" flags).

1. **Not a Facebook surface** — `f3361h0 != b9.D` → `b(true, now)` = BACK (1817-1824).
2. **Reels tab in top bar** — `nativeBlockFacebookReels` and allowance left
   (`!v(b9.D)`): top-bar text matches `^(reels|video|videos|watch|…)([,،]|$)` →
   reset `V`/`W`, `b(false, now)` → **taps Home tab**, not BACK (1826-1836).
3. **Per-reel blocking** — only when `nativeBlockFacebookReels` is OFF, not on the FB
   feed surface, and `w10` (full-screen media heuristic) true (1869-1875):
   - `q4.b` already true → `b(true, now)` BACK (1883-1889);
   - `V <= 0` or `now - V >= 15000` → `b(true, now)` BACK (1890-1895)
     (**max one BACK per 15 s**);
   - else `m(root,0)` (author/caption line) vs `this.W`: first sighting → remember +
     `q4.f7835a = true` (no action); **different key → `b(true, now)` BACK**
     (1896-1909) — i.e. leave each newly revealed reel.
4. `this.Y = uptimeMillis()` on every scan reaching the reels branch (1881).

### 2.5 Rule table — `d()`

| rule | surface | match | action | prefs key |
|---|---|---|---|---|
| DMs-only / kid | all FB | `nativeFbDmsOnlyMode` or `accountType=="kid"` | reset widgets, no rule | `nativeFbDmsOnlyMode` (def. false), `accountType` |
| feed-suggested | FB feed card | `d4.a()` finds node via `s1.z(...)` | `performAction(16)` click, ≤2 attempts / 1500 ms; 3 s later callback → "blocked" | `nativeBlockFacebookFeedSuggested` (def. false), `nativeFbMarketplaceOnlyMode` |
| feed-section | FB feed | `nativeFacebookFeedSection` → `g4` enum | selects which section the card heuristics target | `nativeFacebookFeedSection` (def. null) |
| not-FB guard | any | previous surface `!= b9.D` | `b(true,now)` BACK | — |
| reels tab | Reels tab | `nativeBlockFacebookReels` + allowance + top-bar regex | `b(false,now)` → **tap Home tab** | `nativeBlockFacebookReels` (def. false) |
| reels new item | Reels viewer | blockReels off, full-screen, key ≠ `W` | `b(true,now)` BACK | `nativeBlockFacebookReels` |
| reels time floor | Reels viewer | `now - V >= 15000` or `V <= 0` | `b(true,now)` BACK | `nativeBlockFacebookReels` |
| reels re-entry | Reels viewer | `q4.b` set | reset `V`/`W`, `b(true,now)` BACK | — |
| overlay | feed / reels | `s1.V(j4(...))` / `s1.V(k4(2))` | show/move `i4` overlay, drive `f3372x`/`f3373y` | `nativeFacebookFeedSection` |
| allowance | Reels | `v(b9.D)` | skip reels blocking | usage accounting |

### 2.6 Loop structure

`d()` itself never recurses. The only notable walker is
`i(node, depth, y, LinkedHashSet acc, int[] budget)` (3540-3567), reached via
`w()` → `x()` (4852-4853):

- **DFS, depth ≤ 3** (3542);
- **node budget 60** (`new int[]{60}`, decremented per visit), also stops at 3 hits
  (3543-3545, 4853);
- collects normalised content-descriptions (`l()`) of nodes passing `y()` (visible,
  label ∈ hard-coded `f3367q0`, regex `A0`) whose vertical bounds contain the same
  screen Y (`i10 < top || i10 >= bottom` → skip, 3549);
- `x()` returns true when ≥3 distinct such labels sit on one horizontal line under a
  parent **wider than 2× the child and not taller than 3×** (4840-4869) — the
  "full-screen swipe feed" heuristic that lets `b()` decide BACK is safe.

Constants that matter: DFS depth 3 / 60 nodes / 3 rows / parent width > 2×child;
400 nodes / depth 120 for view-id searches; 800 ms global, 1200 ms `b()`, 1500 ms +
2 attempts and 3000 ms in `d4`, 15 000 ms reels floor.

---

## 3. Brittleness

**Hard-coded resource ids / names** — break on any app update or A/B variant:

- `com.instagram.android:id/clips_viewer_view_pager`, `…/clips_viewer_container`,
  `…/clips_viewer_video_layout` (138);
- `com.google.android.youtube:id/reel_recycler`, `…/reel_player_page_container`,
  `…/shorts_player_container` (141);
- `com.snapchat.android:id/spotlight_container` (1223, 3130, 3140);
- X resource names `"VideoTab"`, `"MainLanding"` (2985-2987);
- ad/story CTA names `chrome_subscribe_button`, `AdsFloatingPillView`,
  `sc_composer_ad_cta_tappable_view` (268).

**Hard-coded natural language** — the app ships its own translations, so a missing
locale silently disables a rule:

- tab regexes `f3351x0` (reels/video/watch), `f3352y0` (home), `f3353z0`
  (marketplace): 8 locales, anchored, with an optional trailing comma because real
  labels look like "Reels, tab 1 of 5" (260-262);
- audio/author markers `f3349v0`, reel-detail markers `f3350w0`, DM markers
  `f3348u0` ("Back to chat", "Reply privately"), the big navigation list `f3367q0`
  (257-270); `d4.f7202g` = {messenger, messages, chats, …} (ie/d4.java:11).

**Geometry instead of semantics** — `w()`/`i()`/`x()` infer "full-screen feed" from
row widths/heights sharing a Y; `A()` requires a node to cover >½ of a rect's width
*and* height. Tablets, foldables, split screen or a density change flip these.

**Timing is the real control surface** — 800 ms global, 1200 ms per block, 1500 ms +
3000 ms inside `d4`, 15 000 ms reels floor. On a slow device a whole scan can be
dropped by the 800 ms gate.

**Decompiler damage** — `d()` tail (1912-1976) is jadx "code decompiled incorrectly"
with branches duplicated backwards; the ordering in §2.4 rules 2-3 (the
`z13`/`z14` evaluation between 1856 and 1869) is reconstructed and **UNVERIFIED**.

**Cross-app leakage** — `u()`, `p()`, `m()` are shared text/id probes, so an X tab
rename or a missing FB caption line changes behaviour for the whole rule set, not one
branch.


