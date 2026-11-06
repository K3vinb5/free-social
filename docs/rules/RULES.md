# Rule provenance — `app/src/main/assets/rules.json`

Authored 2026-11-05 from **fresh live-device dumps** on the Pixel 9a (`56191JEBF26582`,
Android 17 / API 37). Schema source of truth: `app/src/main/java/dev/k3vinb5/freesocial/core/RuleModel.kt`.
Gate semantics: `filter/RuleGate.kt` — SKIP beats everything; exactly **one** action runs per
scan, chosen most-escape-like first (`TAP_HOME_TAB` > `BACK` > `SCROLL_PAST` > `CLICK_VIEW_ID` >
`REDIRECT_HOME`); layer 1 always neutralises, layer 2 only when the budget is exhausted or the
nightly window is locked.

App versions captured: Instagram **454.0.0.54.77**, YouTube **21.43.523**, TikTok **47.6.3**
(`dumpsys package <pkg> | grep versionName`).

## Evidence dumps (all under `reverse/device-dumps/`)

| label | what it proves |
| --- | --- |
| `233005-ig_home` | IG home feed ("For you", story tray, bottom nav). Photo-only feed, no `video_container`. |
| `233149` / `233157` / `233205-ig_feed_scroll*` | Feed reel/video cells: `video_container` (+`video_states`) on a playing feed video, nav visible. |
| `233528-ig_reels_tab` | IG Reels screen: `clips_viewer_*` ids, `clips_tab selected="true"` (vs `selected="false"` in `233005`). |
| `233758` / `234011-ig_dm_inbox*` | IG Direct inbox: `direct_inbox_action_bar`. Screenshot is black — IG Direct is FLAG_SECURE; the XML tree is the evidence. |
| `234329-ig_dm_thread` | IG DM thread with two *shared reels* on screen + composer `row_thread_composer_edittext`. |
| `234610-yt_home` | YT home: masthead + feed + Shorts shelf. Shelf and bottom nav carry **no resource-ids**; only content-descs ("Shorts", "`<title>` - play Short"). |
| `234810-yt_shorts_player` | YT Shorts player: `reel_recycler`, `reel_player_page_container`, `reel_watch_fragment_root`. |
| `234958-tt_consent_check` | TikTok **past onboarding** (consent sheet gone, account signed in), FYP video playing → `player_view`, `video_seek_bar`, `video_container_area`. |
| `235543-tt_fyp_next2` | Negative control: FYP **photo** post — `player_view` and `video_container_area` are present, `video_seek_bar` is **absent**. |
| `235326-tt_fyp_next` | FYP live preview — none of the player ids; live is not a short-video surface. |
| `001335-tt_activity_carol` | TikTok DM thread (shared videos + composer). |
| `001928-tt_dm_video_paused` | Video opened from a DM: `player_view` + `video_seek_bar` **and** composer `udm` ("Message Carol...") — the case the carve-out must protect. |

Secondary (earlier, still clean) evidence: `165537-ig_feed`, `170110-yt_home`, `170126-yt_shorts`.

### Locale capture — Reno4 `CPH2195` (2026-11-05, pt-PT)

The Reno4 test phone (`192.168.50.20:37161`, `persist.sys.locale=pt-PT`) runs the same YouTube
21.43.523 with a Portuguese UI: every shelf card is described `"<title> – reproduzir Short"`
(en dash **U+2013**, not a hyphen) and the shelf header is still `"Shorts"` (product name).

| label | what it proves |
| --- | --- |
| `reverse/runtime-reno/pt-yt-home3.xml` (+ `.png`) | YT Home, Shorts shelf on screen: header `content-desc="Shorts"` `ViewGroup` `[192,443][353,509]` and four card `ViewGroup`s ending ` – reproduzir Short` (e.g. `Mac mini M6: is 32GB enough to run local AI? – reproduzir Short`). |
| `reverse/runtime-reno/pt-yt-home.xml` | Same device, Home feed above the shelf — ordinary card `"… – reproduzir vídeo"` only, no `Short` string (negative control: `reproduzir vídeo` is not a Short). |
| `reverse/runtime-reno/pt-yt-home2.xml` | Same device, mid-feed watch page after an accidental tap — no shelf, no Short string; not used. |

The pt-PT UI string is what the dump proves; the same wording (`reproduzir Short`) is what the
rule authors for pt-BR too, but **no pt-BR dump exists yet**, so pt-BR itself stays `UNVERIFIED`
by dump — the branch is authored from, and cited to, the pt-PT file above.

**Tainted, not used:** `2312*`, `2314*`, `2316*` (`ig_feed2` / `ig_feed2b`, killed session, human
was using the phone mid-capture). Deleted at the end of this run.

## Per-rule provenance

| rule id | surface (layer) | evidence | ids / strings used | verified against | action |
| --- | --- | --- | --- | --- | --- |
| `ig_feed_reel_cell` | `feed_reel_cell` (1) | `233205`, `233157` (+ negative controls `233005`, `233528`) | `com.instagram.android:id/video_container` + class `android.view.ViewGroup` | Instagram 454.0.0.54.77 | SCROLL_PAST |
| `ig_reels_tab` | `reels_tab` (1) | `233005`, `233528`, `234011` | `id/clips_tab`, content-desc `Reels`, class `FrameLayout`, `selected: true` | Instagram 454.0.0.54.77 | TAP_HOME_TAB |
| `ig_clips_viewer` | `clips_viewer` (2) | `233528` (full tree captured while the viewer played) | `id/clips_viewer_view_pager` + class `androidx.viewpager.widget.ViewPager` | Instagram 454.0.0.54.77 | BACK |
| `ig_dm_carve_out` | `dm_carve_out` (2) | `234329` (+ `234011`) | `id/row_thread_composer_edittext` + class `EditText` | Instagram 454.0.0.54.77 | SKIP |
| `yt_shorts_shelf` | `shorts_shelf` (1) | `234610` (en-US), `pt-yt-home3` (pt-PT) | content-desc `^Shorts$` / `- play Short$` / ` – reproduzir Short$` + class `android.view.ViewGroup` | YouTube 21.43.523 | SCROLL_PAST |
| `yt_shorts_player` | `shorts_player` (2) | `234810` | `id/reel_recycler` + class `android.support.v7.widget.RecyclerView` | YouTube 21.43.523 | BACK |
| `tiktok_playback` | `playback` (2) | `234958` (video) vs `235543` (photo), `235326` (live, negative); Reno4 audit `anchor-audit/tt-a`,`tt-b`,`tt-c` (video: **no surface node**) vs `tt-d` (photo) | **proof** `id/player_view` alone; content-desc FYP chrome (`For You`, `Like video.`, …) is a hint only | TikTok 47.6.3 | BACK |
| `tiktok_dm_carve_out` | `dm_carve_out` (2) | `001928` (DM video) + `001335` (thread) | `id/udm` + class `android.widget.Button` ("Message <name>...") | TikTok 47.6.3 | SKIP |

All eight surfaces are verified against a live dump of the current build (no `UNVERIFIED` rules
in this revision). Locale coverage is partial: only the YouTube shelf has a non-English dump
(pt-PT, see *Locale variants*); IG and TikTok are EN-only. Rules are version-stamped; a mismatch
with the installed app should be surfaced in the UI as "rules last verified against …" (SPEC §2.5).

## Locale-proof anchors (rule #271, 2026-11-05)

The phone's UI language is **not fixed** (EN today, pt/es tomorrow), so filtering must work
regardless. Every **enabled** rule is proved by at least one **locale-independent** signal — view
id, class name, geometry/structure or a flag. Localized `textRegex` / `contentDescriptionRegex`
values are
**auxiliary hints only**: they may confirm a match (a class-only anchor prefers the candidate that
also carries the hint), but a missing or reworded string must **not** kill it. Per-locale string
lists are explicitly not the mechanism; the EN/pt alternations kept below exist for the text graft
(`RuleLookup.literalQueries`) and for documentation, not for proving a surface.

Enforcement is in code, not prose:

* `core/RuleModel.kt` — the proof set is one named helper, `isLocaleProofCriterion(kind)`
  (`VIEW_ID`, `CLASS_NAME`, `SELECTED` are proof; `TEXT`, `CONTENT_DESCRIPTION` are hints).
  `validate()` throws `RuleSetException` for any rule with no proof criterion.
* `filter/RuleMatcher.kt` — only proof criteria are AND-combined; a hint-only spec can never
  match (fail open), and a present hint never causes a null when the proof holds.
* `LocaleProofRulesTest` — asserts the shipped catalog satisfies both, and that each enabled rule
  below cites a dump file.

| rule (enabled) | proof anchor(s) | dump citation | auxiliary hints (never a key) |
| --- | --- | --- | --- |
| `ig_reels_tab` | `com.instagram.android:id/clips_tab` + `android.widget.FrameLayout` + `selected=true` | `233528-ig_reels_tab.xml` (selected); `233005-ig_home.xml`, `234011-ig_dm_inbox2.xml` (selected=false, negative) | content-desc `Reels` |
| `ig_clips_viewer` | `com.instagram.android:id/clips_viewer_view_pager` + `androidx.viewpager.widget.ViewPager` | `233528-ig_reels_tab.xml` | — |
| `ig_dm_carve_out` | `com.instagram.android:id/row_thread_composer_edittext` + `android.widget.EditText` | `234329-ig_dm_thread.xml` | `Message…` (not authored as a criterion) |
| `yt_shorts_player` | `com.google.android.youtube:id/reel_recycler` + `android.support.v7.widget.RecyclerView` | `234810-yt_shorts_player.xml`, `170126-yt_shorts.xml` | — |
| `tiktok_playback` | `com.zhiliaoapp.musically:id/player_view` (id alone — the video-surface class was dropped 2026-11-06) | `234958-tt_consent_check.xml` (Pixel, video); `reverse/runtime-reno/anchor-audit/tt-a-fresh-swipe.json`, `tt-b-midplay.json`, `tt-c-tap-focus.json` (Reno4, video, **no surface node at all**), `tt-d-photo-post.json` (photo control); `235326-tt_fyp_next.xml` (live: `SurfaceView`, no `player_view`, negative) | FYP chrome descriptions (`For You`, `Like video.`, …) |
| `tiktok_dm_carve_out` | `com.zhiliaoapp.musically:id/udm` + `android.widget.Button` | `001928-tt_dm_video_paused.xml`, `001335-tt_activity_carol.xml` | `Message <name>…` (observed, not a key) |
| `ig_feed_reel_cell` (RETIRED) | `com.instagram.android:id/video_container` + `android.view.ViewGroup` | `233205-ig_feed_scroll3.xml`, `233157-ig_feed_scroll2.xml` | — |
| `yt_shorts_shelf` (RETIRED) | **UNPROOFED** — see below | `234610-yt_home.xml`, `reverse/runtime-reno/pt-yt-home3.xml` | desc `Shorts` / ` - play Short` / ` – reproduzir Short` |

Why TikTok uses `player_view` alone (2026-11-06 anchor audit): the Reno4 / TikTok 47.6.3 trees
expose **no** `TextureView`/`SurfaceView`/`VideoView` node at all while a video plays
(`tt-a-fresh-swipe.json`, `tt-b-midplay.json`, `tt-c-tap-focus.json`) — requiring a class made the
rule unreachable (`matched=0` live). The only video-vs-photo discriminators in the dumps are
`video_seek_bar` + `vrp` (SeekBar), both D2a-proven transient player chrome the host hides during
clean playback; anchoring on them would reintroduce the leak class at exhaustion. So the key is
`id/player_view` alone (locale-proof, `visible` viewport present in video AND photo — photos render
inside the same viewport, `tt-d-photo-post.json`). **Recorded trade-off:** a photo post on the FYP
matches too (accrues budget; escapes at exhaustion like any FYP entry) — accepted, because
"never leak a short video at 0 budget" outranks "never over-count a photo", and FYP is the
short-video surface either way. The DM carve-out (`id/udm`) is unaffected.

**`yt_shorts_shelf` — UNPROOFED as a locale-independent anchor.** No dump (EN `234610-yt_home.xml`
or pt-PT `pt-yt-home3.xml`) shows a view id or a distinctive structural/geometry fingerprint for
the shelf; the only non-string criterion available is the generic `android.view.ViewGroup` class,
which every card and many unrelated nodes share. It is **retired** (`enabled=false`, direct-
engagement policy) so it never matches at runtime. Missing data needed to un-retire it: a dump with
either a shelf-container id (the nearest id-bearing ancestor is `id/results`, which also exists on
shelf-free Home feeds) or a stable structural fingerprint (e.g. the four-card grid geometry
relative to the screen), plus its negative control. Its strings are hints only.

## Anchor sweep — persistent chrome only (defect D2a)

`video_seek_bar` was a player *control*, not chrome: visible only while paused/scrubbing. Every
rule was re-checked against its dump for the same failure class — an anchor that the app hides
while the blocked surface is up. The rule is: an anchor must be a container or chrome that exists
for as long as the surface does, and a rule that matches **only** when invisible nodes are included
is mis-anchored (the fix is the anchor, never relaxing `isVisibleToUser`).

| rule | anchor | persistent? |
| --- | --- | --- |
| `ig_feed_reel_cell` | `id/video_container` | **Yes** — the feed row's video slot itself; the diagnosis confirmed it matches *visibly* (`233205` idx 23) while the post plays. Kept. |
| `ig_reels_tab` | `id/clips_tab` + `selected:true` | **Yes** — the bottom-nav tab never hides; `selected` is true only while the Reels screen is up (`233528` vs `233005`/`234011`), so it also cannot match on the feed. |
| `ig_clips_viewer` | `id/clips_viewer_view_pager` | **Yes** — the viewer's own pager container, present for as long as the viewer is open (`233528`); it is a container, not a control. |
| `ig_dm_carve_out` | `id/row_thread_composer_edittext` | **Yes** — the thread composer is thread chrome, present for the whole thread (`234329`); it is what keeps shared reels in a DM usable. |
| `yt_shorts_shelf` | content-desc `^Shorts$` / ` - play Short$` / ` – reproduzir Short$` | **Yes** — the shelf header and cards stay visible while the shelf does. The header is only 58 px tall, which used to make the *gesture* useless; the gesture is now framed on the shelf's scroll container (see deviation 3), so a short anchor no longer matters. |
| `yt_shorts_player` | `id/reel_recycler` | **Yes** — the Shorts player's pager container (`234810`), present for as long as the player is. |
| `tiktok_playback` | `id/player_view` (id alone since 2026-11-06) | **Yes, with a recorded trade-off** — the player viewport is present for every FYP post and absent on LIVE (`235326`). It also covers photo posts (`tt-d-photo-post.json`), accepted per the anchor-audit note above; the alternative discriminators (`video_seek_bar`/`vrp`) are `isVisibleToUser=false` during clean playback (D2a) and would leak at exhaustion. FYP chrome strings are hints only. |
| `tiktok_dm_carve_out` | `id/udm` + class `Button` | **Yes** — the in-thread composer button is DM chrome, not a player control (`001928`, `001335`); re-verify it on every TikTok update, like every TikTok id. |

## Locale variants (hint data only)

> Since rule #271 (see *Locale-proof anchors* above) these alternations are **not** what proves a
> surface. They are hint data: they let `RuleLookup.literalQueries` graft text-described nodes and
> they document the observed wording. A device whose language none of these branches cover matches
> on the rule's view id / class / flag instead.

The engine's capture is locale-blind: only the match strings are English, so a Portuguese device
matched nothing (`matched=0`, `grafted=1` — defect D8 round 2). `contentDescriptionRegex` /
`textRegex` are regexes and the matcher evaluates them with `containsMatchIn`, so a top-level
alternation is already an "any of these strings" hint. `RuleLookup.literalQueries` also splits
top-level alternation into one `findAccessibilityNodeInfosByText` query per branch, so every locale
alternate is graftable — proven by `TextGraftCaptureTest` and the new `YouTubeShelfLocaleTest`.

Rule: a locale string is authored **only** if it appears in a dump; otherwise the rule stays
EN-only and is recorded here as unverified. `schemaVersion` stays 1.

### `yt_shorts_shelf`

| locale | string (verbatim) | dump | what it matched |
| --- | --- | --- | --- |
| en-US | ` - play Short` (suffix) | `234610-yt_home.xml` | shelf card `ViewGroup`, e.g. `The smartest girl in the US! - play Short` |
| en-US | `Shorts` (whole desc) | `234610-yt_home.xml` | shelf header `ViewGroup` `[169,1380][310,1438]` |
| pt (pt-PT device) | ` – reproduzir Short` (suffix, U+2013 en dash) | `reverse/runtime-reno/pt-yt-home3.xml` | shelf card `ViewGroup`, e.g. `Mac mini M6: is 32GB enough to run local AI? – reproduzir Short`; all four cards on screen end this way |
| pt (pt-PT device) | `Shorts` (whole desc) | `reverse/runtime-reno/pt-yt-home3.xml` | shelf header `ViewGroup` `[192,443][353,509]` — unchanged, product name |

Not authored: pt-BR as distinct from pt-PT (no pt-BR dump yet — the pt branch is cited to the
pt-PT file), and any locale where the separator is a hyphen (`- reproduzir Short`) — never seen.
`pt-yt-home.xml` shows the ordinary card text `… – reproduzir vídeo`, which the rule correctly
does not match; `YouTubeShelfLocaleTest` keeps that negative.

### `yt_shorts_player`

No locale strings to author: the rule is keyed on `id/reel_recycler` + `RecyclerView`, which are
identical in every locale. No text criterion, so nothing to translate.

### Instagram / TikTok

**Out of scope / unverified.** Every IG and TikTok rule in `tiktok_playback` that carries English
accessibility chrome (`For You`, `Like video.`, `Reels`, …) has the same hole on a Portuguese
device, but no pt dump exists for either app yet, so no alternate is authored. Capture their pt
dumps first, then add branches the same way (alternation, one dump citation per string).

## Deliberate deviations and schema gaps

1. **RESOLVED — `ig_reels_tab` matched the tab bar on every screen (defect class C).**
   `MatchSpec` now carries a tri-state `selected: Boolean?` predicate (checked on the anchor node
   when another criterion provides one, so a selected sibling cannot satisfy it).
   `ig_reels_tab` sets `selected: true`, and the runtime report confirms the flag is true only on
   the Reels screen (false on feed/DM in `233005`, `234011`), so the rule no longer fires on the
   Home feed, profile or DM inbox. Swept the other chrome rules for the same gap:
   `ig_clips_viewer` keys on `clips_viewer_view_pager` (viewer only), `yt_shorts_player` on
   `reel_recycler` (player only) and `tiktok_playback` on FYP chrome plus a video surface class
   (playing video only, absent on photos in `235543`), so none needed a selected predicate.
2. **RESOLVED — `TAP_HOME_TAB` tapped the matched node (defect class A).** The engine now
   resolves the foreground app's real Home tab: known per-app id (`com.instagram.android:id/feed_tab`),
   then a clickable node with content-description matching `(?i)^home$` (YouTube's Button in
   `234610-yt_home`), then `GLOBAL_ACTION_HOME` as the last resort. The matched node (e.g.
   `clips_tab`) is never clicked. Proven in `HomeTabResolverTest`.
3. **RESOLVED (twice) — layer-1 `REDIRECT_HOME` evicted the app, then `SCROLL_PAST` swiped the
   wrong rect (defect D1).** Layer 1 first stopped leaving the app; the replacement swipe was framed
   on the *matched* node, so a 58 px YT shelf header became a 34 px drag (34 px moves nothing) and an
   IG feed cell inside a 2020 px `RecyclerView` became a 566 px drag the list settled back from — 202
   logged hits, screen unchanged. `SCROLL_PAST` now (a) resolves the matched node's **nearest
   scrollable ancestor** (walk up the captured tree to a node that is `scrollable` or whose class is
   a RecyclerView/ScrollView/ListView/GridView/ViewPager/NestedScroll/WebView), (b) frames the swipe
   on that container — full width, bottom→top, two thirds of its height — and (c) falls back to the
   largest ancestor rect when there is no scrollable one, never to a frame under 200 px. Dispatch
   order is `ACTION_SCROLL_FORWARD` on that ancestor first (a RecyclerView honours it synchronously)
   and the synthetic swipe only when it returns `false`. Both shipped SCROLL_PAST rules
   (`ig_feed_reel_cell`, `yt_shorts_shelf`) therefore exercise the forward action on a live device and
   the gesture only as fallback. No shipped rule uses `REDIRECT_HOME` any more
   (`grep -c REDIRECT_HOME rules.json` = 0); the enum value survives only as the engine's
   last-resort fallback.
3a. **RESOLVED — success was logged before the action was dispatched (defect D1c).** The
   "neutralising …" line used to be emitted *before* the action, so a rejected gesture and a real
   removal were indistinguishable in logcat. It is now emitted only when the mechanism reported
   success (`performGlobalAction`/`performAction`/`dispatchGesture` returning true); failures log
   `action failed …`. Gestures are asynchronous: the service logs `neutralising … via SCROLL_PAST
   (gesture dispatched, awaiting completion …)` and then
   `gesture completed …` or `gesture failed … (cancelled)` from the `GestureResultCallback`. The
   1200 ms per-surface debounce and the 20-per-60 s quota are unchanged — they were working as
   designed, they were just being fed a surface that never moved.
4. **RESOLVED — layer-1 outranked layer-2 at the action stage (defect class C loophole).**
   `RuleGate.chosen` returns exactly one hit for a scan, most-escape-like first
   (`TAP_HOME_TAB` > `BACK` > `SCROLL_PAST` > `CLICK_VIEW_ID` > `REDIRECT_HOME`), after SKIP
   shields the scan and layer 2 passes the budget/schedule gate. On the tab-based IG Reels screen
   with the budget exhausted, both `reels_tab` and `clips_viewer` match; the Home-tab tap runs and
   exits the viewer instead of tapping Reels again.
5. **`ig_clips_viewer` — modelled as layer 2, not SKIP.** The analysis (`03c` e-5) says the viewer
   is never hijacked while the budget lasts. That is exactly what `layer: 2` encodes: `RuleGate`
   only neutralises layer 2 when `budgetExhausted || scheduleLocked`. A SKIP rule would disable
   the budget exit entirely, and the schema allows only **one rule per (appPackage, surface)**
   (`validate()` rejects duplicates), so a SKIP + separate layer-2 exit-rule pair cannot be
   expressed. Documented gap rather than a second surface name; the single-action priority in
   item 4 removes the observable loophole.
6. **YouTube exposes almost no view-ids in the feed.** In 21.43.523 `reel_recycler` exists only in
   the Shorts *player*; the Home shelf and the bottom nav come through with **empty** resource-ids.
   `yt_shorts_shelf` is therefore keyed on accessibility text and `ViewGroup` (the nav
   entry is a `Button`, so the class filter separates them). Locale-fragile by nature — the device
   UI language at capture time was English. **Locale gap partly closed**: the shelf rule now carries
   the observed pt branch ` – reproduzir short$` alongside the EN ones (see *Locale variants*); the
   schema needed nothing new because regex alternation plus `RuleLookup.literalQueries` already
   express and graft alternates.
7. **`shorts_player_container` is dead.** The id listed in `03c` (`f3346s0`) does not appear in
   21.43.523; the rule uses `reel_recycler`, which does.
8. **RESOLVED — TikTok playback keyed on a hidden player control (defect D2a).**
   `video_seek_bar` is *player-control chrome*: TikTok reports it with `isVisibleToUser=false` while
   a video plays and uiautomator, which does not filter on visibility, still lists it — so the engine
   dropped it and `tiktok_playback` could never match. The rule is re-anchored on two id-free,
   always-visible signals: the FYP action chrome (content-desc `For You`, `Like video.`,
   `Read or add comments`, `Share video.`, favourites) **and** a video surface class
   (`TextureView`/`SurfaceView`/`VideoView`). The surface class is what keeps the photo negative
   control clean: `235543-tt_fyp_next2` has the same chrome and `player_view` but no video surface.
   *(Superseded 2026-11-06: the Reno4 anchor audit found no surface-class node at all while video
   played, so the class was dropped from the key — see "Why TikTok uses `player_view` alone";
   the photo trade-off is recorded there.)*
   The seek bar survives only as an *optional extra* signal — never the anchor, and never alone
   (`grep -c video_seek_bar rules.json` = 1, in the notes only). No id is used at all, so an
   obfuscation rename cannot kill the rule. See `TikTokPlaybackRuleTest`.
8a. **Engine-wide window sweep (defect D2b).** Every tree walk aggregates **all** application
   windows of the foreground package (`AccessibilityService.getWindows()`, already declared with
   `flagRetrieveInteractiveWindows`), not just `rootInActiveWindow`: one scan in the filter path and
   one in the armed rule-inspector capture. The 400-node cap is budgeted across windows in z-order
   and a node exposed by two windows is walked once (`NodeTreeBuilder`, `NodeTreeBuilderTest`).
   Visibility filtering is unchanged for actions — an invisible node is never clicked; if a rule can
   only match by including invisible nodes, that rule is mis-anchored and must be re-anchored, not
   have the visibility filter relaxed.
9. **TikTok DM strings changed.** SocialLite's `f3348u0` = {"Back to chat", "Reply privately"}
   (English, `03c`) does not occur anywhere in 47.6.3 — a `grep` over every fresh TikTok dump
   returns nothing. The carve-out is keyed on the live DM composer `udm` instead, which is present
   precisely on the DM-opened video where `tiktok_playback` would otherwise fire. Plain chat
   threads use `ui_slot_input_layout_redesign` and never show `video_seek_bar`, so they need no
   carve-out.
10. **TikTok consent.** The earlier "Choose how ads are shown" consent sheet
   (`SplashActivity`) is gone this run: the app is signed in and the For You feed plays. Nothing
   was consented to by this capture run.
11. **IG Direct is FLAG_SECURE.** Screenshots of the DM inbox are black even though the XML tree is
   complete; provenance for DM rules rests on the hierarchy dumps (`233758`, `234011`, `234329`).
12. **TikTok has no identifiable Home tab (remaining).** Every tab id is obfuscated and the nav
   entries carry empty content-descriptions in `002347-tt_home_again`, so `HomeTabResolver`
   cannot pick one and `TAP_HOME_TAB` would fall back to `GLOBAL_ACTION_HOME` (leaving the app).
   No shipped TikTok rule uses `TAP_HOME_TAB`, so this is latent; re-capture the nav XML before
   adding one.

## Capture anchors — container id graft (D8 round 3, R1)

Provenance: `reverse/runtime-reno/pt-yt-home3.xml` (Reno4 `CPH2195`, pt-PT, 135 nodes; a fresh
`uiautomator dump` of the same YouTube Home where the service saw the 4-node ghost). The dump shows
the shelf's own nodes carry **no** `resource-id`:

| node | id | class | bounds | content-desc |
| --- | --- | --- | --- | --- |
| shelf header | *(none)* | `android.view.ViewGroup` | `[192,443][353,509]` | `Shorts` |
| card 1 | *(none)* | `android.view.ViewGroup` | `[36,554][529,1293]` | `… – reproduzir Short` |
| card 2–4 | *(none)* | `android.view.ViewGroup` | `[551,554][1044,1293]`, `[36,1314][529,2053]`, `[551,1314][1044,2053]` | `… – reproduzir Short` |
| **nearest id-bearing ancestor** | `com.google.android.youtube:id/results` | `android.support.v7.widget.RecyclerView` | `[0,398][1080,2158]`, `scrollable="true"` | — |

Two id-less `ViewGroup` levels separate the header/cards from `results`. The Wave A retry measured
`idProbe: results=2` on the pruned root while `findAccessibilityNodeInfosByText` returned nothing,
so the fix is (b): `CaptureAnchors` declares `id/results` for YouTube, the capture grafts that id
and **walks the recovered node's children** (`NodeTreeBuilder.graft` subtree), and the unchanged
`yt_shorts_shelf` desc rule then matches the shelf. `rules.json`, `Rule` and `MatchSpec` are
unchanged: the anchor is an engine-side capture hint, never a match criterion (keying the rule on
`results` would fire on every YouTube home feed, with or without a shelf).

Device confirm (Reno4, 2026-11-05 12:29, same install as the tests below):

```
scan com.google.android.youtube (YOUTUBE): 44 nodes, 4 with viewId, rules=2/8, matched=1, grafted=40,
    windows=1 [YouTube(0)=3/1 ids], rootChildCount=2, idProbe=menu_item_0=1 app_bar_wrapper=0 results=2
neutralising com.google.android.youtube/shorts_shelf via SCROLL_PAST
    (ACTION_SCROLL_FORWARD Bounds(left=0, top=398, right=1080, bottom=2124))
```

`grafted=40…68` are the children recovered below `results`; the action frames on `results` itself
(the nearest scrollable ancestor), not on the 58 px header. Proven by `CaptureAnchorGraftTest`.

13. **RESOLVED (R1) — id-less shelf unreachable through the text lookup.** On the ColorOS ghost
   tree the text graft returns nothing (`findAccessibilityNodeInfosByText` does not reach the
   pruned id-less subtree) even though the id lookup works. The shelf is now reached through the
   container id above, whose child links *are* intact (`grafted=40+`, `matched=1`). The rule keeps
   its desc+class criteria; the container id lives in `filter/CaptureAnchors.kt` with this dump as
   its provenance. Any future rule on a platform that prunes id-less subtrees needs the same test:
   does the rule's subtree have an id-bearing ancestor in a real dump?
