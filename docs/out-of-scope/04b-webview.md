# 04b — In-app WebView shell + injected JS filters

Static RE of `com.sociallite.android` (version from `/tmp/opencode/xapk/com.sociallite.android.apk`).
Package names are obfuscated: the app's own code sits in `com.sociallite.android` (37 classes:
manifest entry points only) and in two single-letter packages — `qe/*` (the browser/session
layer) and parts of `ie/*`, `ve/*`, `oe/*` (settings + rollout flags). All `file:line` citations
are against the jadx tree at `/tmp/opencode/dec/jadx/sources/` unless prefixed `smali:` (apktool
tree) or `assets:` (`/tmp/opencode/dec/apktool/assets/`). `qe/t0.shouldOverrideUrlLoading` did
not decompile; it was recovered with
`jadx --show-bad-code --single-class qe.t0` → `/tmp/opencode/rd4/t0.java`.

---

## 1. The rendering model

There is no single browser: **one long-lived `WebView` per platform**, cached in a static map
`y0.f16154a` (`qe/y0.java:135-145, 320-400`). Navigating away and back re-uses the same
instance, so scroll position, cookies and the logged-in session survive.

Eight platforms, each with a hard-coded start URL and brand colour (`ie/b9.java:38-46`):

| Enum | key used in prefs/deep links | start URL | notes |
|---|---|---|---|
| `b9.f7122z` | `instagram` | `https://www.instagram.com/accounts/login/` | desktop-ish UA override, document-start bundles |
| `b9.A` | `youtube` | `https://m.youtube.com/` | sometimes a separate player `WebView` owned by `f1` |
| `b9.B` | `twitter` (X) | `https://x.com/home` | |
| `b9.C` | `tiktok` | `https://www.tiktok.com/inbox` | forced desktop-ish viewport |
| `b9.D` | `facebook` | `https://m.facebook.com/` | |
| `b9.E` | `linkedin` | `https://www.linkedin.com/feed/` | |
| `b9.F` | `snapchat` | `https://www.snapchat.com/web/` | desktop UA, `setInitialScale` hack (`y0.java:337-339, 606-609`) |
| `b9.G` | `reddit` | `https://www.reddit.com/` | excluded from `f7120x` (kid bundle list) |

`MainActivity` (`com/sociallite/android/MainActivity.java`, 458 lines) is a Compose host; it
never inflates a `WebView` itself — it only clears caches on memory pressure
(`MainActivity.java:444`). The `WebView` is created and parented by the views in `qe`
(`y0.java:320-400` creates, `i0.java` is the swipe/zoom container, `r.java` the IG tab chrome).

### WebView configuration (`qe/y0.java:344-375`)

```
setJavaScriptEnabled(true)          setDomStorageEnabled(true)
setDatabaseEnabled(true)            setMediaPlaybackRequiresUserGesture(...)  // false for IG in data-saver
setAllowFileAccess(false)           setMixedContentMode(2 /* ALWAYS_ALLOW */)
setCacheMode(-1 /* LOAD_DEFAULT */) setSupportMultipleWindows(true)
setJavaScriptCanOpenWindowsAutomatically(true)
setUserAgentString(m(context, platform))
```
Plus `WebView.setWebContentsDebuggingEnabled(false)` (`y0.java:334`), hardware layer
`setLayerType(2, null)`, and optional `FORCE_DARK` / algorithmic darkening via the AndroidX
WebKit boundary interfaces (`y0.java:196-232`).

**User agent** (`qe/y0.java:606-624`): default UA (cached once) for most platforms; a hard-coded
desktop Chrome 131 UA for Snapchat; a derived desktop-ish UA for Instagram (`l(context)`, the
default UA with the `Version/x.y` token stripped, `y0.java:590-604`). UA is re-checked per
main-frame navigation and the page is reloaded when it does not match
(`rd4/t0.java:505-516`) — that is how IG/"continue in browser"-style flows are kept in the shell.

**Login / cookies / session**
- `CookieManager.getInstance().setAcceptCookie(true)` and a `flush()` on every language sync
  (`y0.java:236-246`). Cookie jar is the system persistent one — no custom store, no incognito.
- Third-party cookies explicitly enabled for each `WebView` (`y0.java:470-474`) — needed for
  IG/FB CDN + login iframes.
- Locale is pushed *into* the site as cookies, not just headers: `hl`, `app_language` and
  `ig_lang` set on `Domain=.instagram.com; Max-Age=31536000` (`y0.java:238-245`), and every
  `loadUrl` also sends an `Accept-Language` header (`y0.java:731-734`).
- `navigator.language` / `navigator.languages` are patched at document-start for IG, and the
  user's locale for everyone else (`y0.java:380-424`).
- Wipe path: `y0.C()` clears cache/history/form data/SSL prefs for every live WebView,
  `removeAllCookies`, `WebStorage.deleteAllData()` (`y0.java:150-175`). Called from the
  "privacy restart" activity, not on normal exit — sessions are meant to persist.
- `onReceivedSslError` **cancels**, never proceeds (`qe/t0.java:172-177`).

---

## 2. Widgets — deep link, never the native app

`PlatformWidgetProvider` (base) and seven 10-line subclasses (`InstagramWidgetProvider` etc.)
plus `QuickLaunchWidgetProvider` (4 slots: Instagram, Snapchat, YouTube, TikTok).

Tap target is always a `PendingIntent.getActivity` with
`sociallite://open?platform=<key>` (`PlatformWidgetProvider.java:59-64`,
`QuickLaunchWidgetProvider.java:97-101`). **No widget ever launches `com.instagram.android`
or any native package.** Non-Pro users get `sociallite://upgrade` instead
(`PlatformWidgetProvider.java:65-70`), gated on the RevenueCat entitlement check
`ka.G0()` (`PlatformWidgetProvider.java:36-39`).

So the tap path is: widget → `sociallite://open?platform=…` → `MainActivity` →
`y0` builds/attaches the per-platform `WebView` and calls `loadUrl(startUrl)`.

The router lives in `MainActivity.c(Intent)` (`MainActivity.java:156-224`): it accepts
`sociallite://open?platform=<key>&url=<optional deep URL>` (line 190-191, the `url` param lets a
widget or notification land on a specific post), the HTTPS equivalent
`https://sociallite.app/open/…` (line 193), plus `sociallite://upgrade` and
`sociallite://offer?campaign=…` for paywalls. Unknown platform keys are logged and dropped
(line 224). Both intent filters (`MAIN/LAUNCHER` + `VIEW`) are on the same activity
(manifest lines 75-82), and `onNewIntent` re-runs the same router (`MainActivity.java:407-410`).

---

## 3. How a blocked/native launch is prevented

`qe/t0.shouldOverrideUrlLoading` (recovered: `/tmp/opencode/rd4/t0.java:218-676`) is the
enforcement point. Shape of the logic:

1. `http`/`https` → host must be in the platform's allowed-host list (`this.f16124i`, built
   from `s1.l(platform)`); otherwise the request is **swallowed** (`return true`) and logged
   (`rd4/t0.java:486-547`).
2. Path blocklist per platform: `b9Var.b(context)` yields blocked path prefixes; a match posts
   a navigation-refused callback and returns `true` (`rd4/t0.java:524-530`).
3. Snapchat special case: `/web`, `/accounts`, `/login`, `/auth` are allowed; anything else is
   redirected to the `accounts.snapchat.com/v2/login?continue=…/sso…` URL so the web session
   stays inside the shell (`rd4/t0.java:531-542`).
4. `intent://` URLs: parsed, and only `http`/`https` are followed — via
   `browser_fallback_url`, and in kid mode additionally only if the fallback host is in the
   allowlist (`rd4/t0.java:548-607`). Everything else is rejected with a log line.
5. **Native app schemes are hard-blocked, always:** if the scheme is one of
   `instagram, fb, fb-messenger, twitter, tiktok, snapchat, linkedin` the method returns `true`
   without starting anything (`rd4/t0.java:609-612`). `market://` never reaches the store for
   these (it is not in the allowed `http/https` set and is not `intent`).
6. Any *other* custom scheme: in kid mode → swallowed; in adult mode → `startActivity(ACTION_VIEW)`
   as a last resort, with `No handler for scheme` logged on failure (`rd4/t0.java:613-621`).

Second layer, outside the WebView: `WebBlockerVpnService` (`com/sociallite/android/data/`,
`<service … BIND_VPN_SERVICE>` in the manifest) blocks the platforms at DNS/packet level when
the app is not the one in the foreground — that is what stops "just open Chrome instead".

---

## 4. How in-app web time is counted

There is **no per-second timer in the WebView path**. What exists:

- **Short-form budget tick (the real accounting).** `s0.shortFormAllowanceTick(kind, allowed)`
  is a `@JavascriptInterface` method (`qe/s0.java:190-205`, body in `smali: …/qe/s0.smali`,
  method `shortFormAllowanceTick`). It reads `SystemClock.elapsedRealtime()`, today's
  `LocalDate`, and the `sociallite_settings` prefs, and answers "is this reel/Short still
  within today's allowance?". The accounting store is the `parent_shortform_usage` JSON:
  per platform `{day, count, millis}` (`ie/c3.java:62-100`), compared against the parent
  limits `ig_reels_allowance_count/_minutes` and `yt_shorts_allowance_count/_minutes`
  (`ie/c3.java:19-40, 49-58`). So time is accumulated **in the page, per short-form item**,
  and persisted by the native tick — not by wall-clock sampling of the `WebView`.
- **Feed counters.** The IG document-start banner script counts visible `article`s with an
  `IntersectionObserver` (dedup by post id, ad/suggested classification) and calls
  `zie1shuld.bannerEvent(JSON.stringify({type:'count',kind,previousKind}))`
  (`qe/y0.java:437-441`); native parses it and dispatches to a per-platform callback
  (`qe/s0.java:24-42`, `qe/q0.java`). Same channel for `onCreateAttempt` (post/reel/story
  creation attempts, `qe/s0.java:78-130`).
- **Quick-bounce heuristic (not time accounting).** `onPageStarted` stores
  `SystemClock.elapsedRealtime()` (`qe/t0.java:149`); `onPageFinished` compares it: ≥6 s in
  page sets a 60 s "network ok" window, <3 s clears it (`qe/t0.java:69-79`). `c.a.r(context)`
  exposes that window (`c/a.java:610-613`) and `c.a.z(webView)` pushes it, plus
  `setManualPlayback`, into the page as `setNetwork(ok, dataSaver)`
  (`c/a.java:721-733`). Pure UX/rollout signal.
- Long-form native usage (App Blocker, `AppBlockerService` / `UsageStats`) is a separate
  subsystem and is not driven by these WebViews.

---

## 5. Injected scripts

Three wiring mechanisms, all present:

- **A. Document-start script** — AndroidX WebKit
  `WebViewCompat.addDocumentStartJavaScript(webView, js, allowedOriginRules)`
  (`l7/c.java:23-30`), guarded by the `DOCUMENT_START_SCRIPT` feature flag. Runs before the
  page's own JS, scoped to an origin allowlist set.
- **B. `evaluateJavascript` from `WebViewClient`** — at `onPageFinished` (`qe/t0.java:88-112`)
  or on demand from view code (`qe/r.java:334, 591`, `qe/i0.java:329-543`).
- **C. Bridge** — `addJavascriptInterface(new s0(context, platform), "zie1shuld")`
  (`qe/y0.java:379`). Methods: `bannerEvent`, `copyToClipboard`, `exitPlatform`, `haptic`,
  `logEvent` (no-op), `onBlockingEvent` (no-op), `onCreateAttempt`, `onReelBlocked`,
  `setKeepScreenOn`, `shortFormAllowanceTick`, `showToast`, `snapV2` (`qe/s0.java`).

Scripts are **idempotent by namespace**: each bundle reads/writes a global on
`Symbol.for('zie1shuld')` (or `zie1shuld904`, `zie1shuld-901`, `zie1shuld-902/903`) and bails
out early (`if (S.igScroll) return;`, `if (S.fbAdHide) return;`, `window[key].update(config)`).
Re-injection therefore *reconfigures* rather than stacking listeners. Native learns a script is
alive either by calling a namespace method (`bannerReset`, `zoom.cancel`, `setActive`,
`setNativeBar`, `setNetwork`) or by `evaluateJavascript(..., ValueCallback)` probing the DOM
(`qe/r.java:383, 622`).

| script | platform | what it hides / neutralises | injection wiring |
|---|---|---|---|
| `instagram_page_motion.js` (53 KB) | Instagram | SPA page-transition "motion" layer: swipe-between-tabs arbitration, carousel gesture arbitration, cached page cards, reveals only after ready. Exposes `window['zie1shuld904experience']` with `zoom`, `setActive`, `setNativeBar`, `setNetwork`, `configure`. No offscreen page loads. | Part of the IG document-start bundle concatenated in `c/a.java:678-686` (`page_motion + post_zoom + comments_open`), `/*CONFIG*/null` replaced with a JSON of `dataSaver/low/dmsOnly/blockFeed/blockExplore/blockStories/hideComments`, `__NAMESPACE__` → `zie1shuld904`; installed at `qe/y0.java:520-523` for `https://www.instagram.com` + `https://instagram.com`. |
| `instagram_post_zoom.js` (8 KB) | Instagram | Adds pinch-zoom of the *current* post media (overlay + video mirror) and reports dimming state via `report()`; guards `window !== window.top`. Consumer of `experience.zoom`, not a hider. | Same bundle as above. Cancelled by native at `qe/i0.java:329` (`?.zoom?.cancel()`). |
| `instagram_comments_open.js` (2.8 KB) | Instagram | Keeps the playing post mounted while opening its real comments in a second browser surface; intercepts taps on `[aria-label*="omment"]`, `a[href*="/comments"]`. Namespace `zie1shuld904comments`, guard key `…commentsInstalled`. | Same concatenated document-start bundle. |
| `instagram_feed_play_icon.js` (5.2 KB) | Instagram | Fades only the Home-feed "autoplay pending" glyph; never plays/pauses or removes tap targets. Config `{enabled, manualPlayback}`; `/*CONFIG*/null` and `__NAMESPACE__` substituted. | Loaded by `com/google/android/gms/internal/play_billing/b2.java:421-437` (obfuscated app code parked in a library package) and injected from the IG view code; also gated by `ig_feedPlayIcon_disabled`. |
| `instagram_comments_panel.js` (15 KB) | Instagram | The native-drawn comments sheet's DOM side: hides/neutralises IG's own comment sheet & nav chrome while the native panel is open, supplies the anchor element `[<ns>data-sl-cont]` and the emoji bar `<ns>_slEmojiBar` that native measures. | `qe/r.java:308-320` reads the asset, substitutes `__NAMESPACE__` with the view's namespace, and `evaluateJavascript`s it when the panel is opened (`qe/r.java:334`). Native then probes it (`qe/r.java:383, 622`). |
| `kid_web_controls.js` (13 KB) | shared "kid" base (IG/YT/TT/TW/FB/LI/SNAP/REDDIT) | Generic parent-flag engine: CSS-based hiding per platform from `config.rules` — comments, create/posting, grayscale, YT ad badges + "open app" bar + watch recommendations + end screens, TikTok live/shop/explore/upload/search/suggested, grayscale for all. Skips auth paths (`/accounts/login…`, `/login`, `/oauth`, `/checkpoint`) so login stays usable. **Re-injection calls `old.dispose()` first.** | Part of the kid bundle built in `ve/x.java:600-635`: payload JSON (`labels` from `kid_web_labels.json`, `platform`, `rules`, `effective`, `hosts`, `blockedPaths`, `namespace: zie1shuld-901`, `styleId`, `markAttribute`, `bridge`, `allowance`) + `kid_web_controls.js` + platform script, joined with newlines; `evaluateJavascript`ed from `qe/r.java:591`. |
| `kid_instagram_polish.js` (7 KB) | Instagram (kid) | Profile scroll-memory (restores scroll offset per path, 2-min TTL) and an audio-only DM "voice notice" — the polish layer, not a content filter. | Kid bundle, `ve/x.java:621`. |
| `kid_facebook_controls.js` (72 KB) | Facebook (kid) | The big one: parent flags → hides home/people/messenger/notifications/marketplace, marketplace-mode, sponsored + suggested posts (locale-aware label matching), feeds/reels/comments per flag. Registers as `S.fbk`. Personal mode uses a different generator. | Kid bundle, `ve/x.java:623`. |
| `kid_reddit_controls.js` (20 KB) | Reddit (kid) | Ad blocker, hide suggested, "only my communities", block popular/news/latest; deliberately preserves Reddit's sensitive-content gates. Skips `/login`, `/register`, `/account`, `/oauth`. | Mapped in the kid bundle at `ve/x.java:627`, but **dead in this build**: `ve/x.java:597-599` returns `""` for `b9.G` (Reddit), so the bundle is never built for Reddit. Shipped asset, unreachable code path. |
| `kid_snapchat_polish.js` (18 KB) | Snapchat web (kid) | Expand-messages + reply-layout rewrites with scoped quote styling; hides "Send a chat" placeholders per locale. | Kid bundle, `ve/x.java:625`; only on `web.snapchat.com` or `/web…`. |
| `youtube/personal.js` | YouTube (personal/family) | Personal-mode YouTube container: ad/`ytm` chrome hiding, "open app" bar, comments, watch recommendations, end screens, stop-feed-autoplay, volume handshake (`s.youtube.volume(...)`), config-driven. `__NS__` → `zie1shuld`, `__CONFIG__` → settings JSON. | `w8/l.java:540-556` loads + substitutes, `qe/y0.java:137-143` installs it as a document-start script for `m./www./youtube.com`. |
| `kid_web_labels.json` (2.4 KB) | all kid platforms | Localised label dictionary (`adLabels`, `suggestedLabels`, `follow`, YT ad badges, YT/Snap "open app" strings) — used both by JS and mirrored natively (`NativeReelsBlockerService.FB_SPONSORED_PREFIXES`). | Read in `ve/x.java:605-615` and embedded into the bundle payload as `labels`. |

Additional inline (not asset) injections on the IG/FB path, all in `onPageFinished`/document-start:
IG "use the app" upsell CSS (`qe/t0.java:91`), the 300-line multilingual use-app banner dismisser
with `MutationObserver` + `pushState` hooks + 500 ms poll for 20 s (`qe/t0.java:93-96`,
`qe/y0.java:428`), the second-reel guard that closes `/reels/` after the first reel and falls
back to `zie1shuld.onReelBlocked(...)` (`qe/t0.java:97-99`), FB sponsored-post collapse
(`qe/t0.java:101-104`), dark-mode CSS (`qe/t0.java:105-112`), and the
`setKeepScreenOn` video-wake bridge for IG/YT/FB (`qe/y0.java:443-448`).

---

## 6. Maintenance-cost verdict for a clone

Doing "run Instagram/Facebook in our own WebView" is, in this codebase, **~90 % selector
maintenance and ~10 % WebView plumbing**. The plumbing is a weekend: one `WebView` per
platform, `WebSettings` block, cookie jar, a `WebViewClient`, a `JavascriptInterface`, and
`addDocumentStartJavaScript`. The rest is a permanent tax:

- Every injected script is tied to markup that changes without notice. The comments in the
  assets read like a changelog: `[ES-SHORT-FORM 2026-07-24]`, `[AR 2026-07-24]`,
  `[PT-PT 2026-07-21]`, `[I18N-FB-ADHIDE 2026-07-23]` — i.e. the banner-dismisser alone needed
  per-locale fixes across ES/FR/DE/IT/PT/PT-PT/JA/KO/AR, and each platform's "open in app"
  prompt is a separate regex. `kid_facebook_controls.js` is 72 KB for one site.
- The defence is layered and *all* of it is load-bearing: CSS fast-path → attribute/testid
  signal → shape check → nav guard → "real UI" climb guard (`qe/t0.java:95`). Remove one and
  you either miss the banner or hide the feed column.
- Fragility is structural: `:has()` selectors, `IntersectionObserver` accounting, `history`
  monkey-patching for SPA route changes, `Symbol.for()` namespaces for idempotency, and
  locale dictionaries shipped as data. Add the native side's `evaluateJavascript` probes
  (`qe/r.java:622`) and you have two languages that must agree on DOM shape.
- Legal/ToS risk is the same as any scraper-shell: you are injecting into logged-in sessions,
  and the script comments show the team tracks platform A/B changes continuously.
- The "block native app" part is cheap by comparison and worth copying as-is: scheme
  blocklist + host allowlist in `shouldOverrideUrlLoading` (`rd4/t0.java:486-621`) plus the VPN
  service is a few hundred lines and rarely breaks.

**Verdict:** worth it only if the product *is* the restriction layer (parental controls /
screen-time), where the WebView is the point and the selector churn is the moat. For a
free-social clone that just wants "social sites in one app", the WebView shell is fine to
reimplement (~1-2 weeks), but the 200 KB of injected scripts is a treadmill: budgeting
~2-4 dev-days/month of selector repair is realistic, and it never converges. Consider instead
loading the sites unmodified with a narrower, declarative feature set (custom UA, cookie
isolation, `shouldOverrideUrlLoading` hardening) and accepting the banners — that removes
80 % of the maintenance surface at the cost of the "polish" the assets provide.
