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

**Tainted, not used:** `2312*`, `2314*`, `2316*` (`ig_feed2` / `ig_feed2b`, killed session, human
was using the phone mid-capture). Deleted at the end of this run.

## Per-rule provenance

| rule id | surface (layer) | evidence | ids / strings used | verified against | action |
| --- | --- | --- | --- | --- | --- |
| `ig_feed_reel_cell` | `feed_reel_cell` (1) | `233205`, `233157` (+ negative controls `233005`, `233528`) | `com.instagram.android:id/video_container` + class `android.view.ViewGroup` | Instagram 454.0.0.54.77 | SCROLL_PAST |
| `ig_reels_tab` | `reels_tab` (1) | `233005`, `233528`, `234011` | `id/clips_tab`, content-desc `Reels`, class `FrameLayout`, `selected: true` | Instagram 454.0.0.54.77 | TAP_HOME_TAB |
| `ig_clips_viewer` | `clips_viewer` (2) | `233528` (full tree captured while the viewer played) | `id/clips_viewer_view_pager` + class `androidx.viewpager.widget.ViewPager` | Instagram 454.0.0.54.77 | BACK |
| `ig_dm_carve_out` | `dm_carve_out` (2) | `234329` (+ `234011`) | `id/row_thread_composer_edittext` + class `EditText` | Instagram 454.0.0.54.77 | SKIP |
| `yt_shorts_shelf` | `shorts_shelf` (1) | `234610` | content-desc `^Shorts$` / `- play Short$` + class `android.view.ViewGroup` | YouTube 21.43.523 | SCROLL_PAST |
| `yt_shorts_player` | `shorts_player` (2) | `234810` | `id/reel_recycler` + class `android.support.v7.widget.RecyclerView` | YouTube 21.43.523 | BACK |
| `tiktok_playback` | `playback` (2) | `234958` (video) vs `235543` (photo, negative) | content-desc FYP chrome (`For You`, `Like video.`, `Read or add comments`, `Share video.`, favourites) **and** class `TextureView`/`SurfaceView`/`VideoView` | TikTok 47.6.3 | BACK |
| `tiktok_dm_carve_out` | `dm_carve_out` (2) | `001928` (DM video) + `001335` (thread) | `id/udm` + class `android.widget.Button` ("Message <name>...") | TikTok 47.6.3 | SKIP |

All eight surfaces are verified against a live dump of the current build (no `UNVERIFIED` rules
in this revision). Rules are version-stamped; a mismatch with the installed app should be surfaced
in the UI as "rules last verified against …" (SPEC §2.5).

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
| `yt_shorts_shelf` | content-desc `^Shorts$` / ` - play Short$` | **Yes** — the shelf header and cards stay visible while the shelf does. The header is only 58 px tall, which used to make the *gesture* useless; the gesture is now framed on the shelf's scroll container (see deviation 3), so a short anchor no longer matters. |
| `yt_shorts_player` | `id/reel_recycler` | **Yes** — the Shorts player's pager container (`234810`), present for as long as the player is. |
| `tiktok_playback` | FYP chrome content-descs + `TextureView`/`SurfaceView`/`VideoView` class | **Yes** — the "For You" tab and the right-rail buttons are on screen during playback, and the video surface only exists while a video plays. The old `video_seek_bar` anchor was `isVisibleToUser=false` during playback (D2a). |
| `tiktok_dm_carve_out` | `id/udm` + class `Button` | **Yes** — the in-thread composer button is DM chrome, not a player control (`001928`, `001335`); re-verify it on every TikTok update, like every TikTok id. |

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
   `yt_shorts_shelf` is therefore keyed on English accessibility text and `ViewGroup` (the nav
   entry is a `Button`, so the class filter separates them). Locale-fragile by nature — the device
   UI language at capture time was English.
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
