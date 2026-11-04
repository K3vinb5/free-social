# 02b — Notification redirection & setup/permission flow

Scope: `NotificationBlockerService` policy, and the onboarding permission gate (checks, intents,
degradation). Sleep/alarms/health/device-admin are covered in `02-schedules-and-survival.md` and are
not repeated here.

Decompiled sources under `/tmp/opencode/dec/jadx/sources/`; line numbers refer to those files.
`NotificationBlockerService.a()` does not decompile in the default tree — the analysis below uses the
`--show-bad-code` recovery at `/tmp/opencode/rd5/NBS.java` (same class, reconstructed body).

---

## 1. `com.sociallite.android.data.NotificationBlockerService`

### 1.1 Registration

`AndroidManifest.xml:159-163`:

```xml
<service android:exported="false"
         android:label="SocialLite Notification Blocker"
         android:name="com.sociallite.android.data.NotificationBlockerService"
         android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService"/>
    </intent-filter>
</service>
```

Standard `NotificationListenerService` binding: the user must grant **Notification access** in system
settings before the service is ever bound (`onListenerConnected` never runs otherwise).

### 1.2 Coverage — packages and platform slugs

`f3375t` (`NotificationBlockerService.java:20`) is a hard-coded 12-package → 7-slug map. Nothing outside
this list is ever touched:

| Platform slug | Packages |
|---|---|
| `instagram` | `com.instagram.android` |
| `youtube` | `com.google.android.youtube` |
| `tiktok` | `com.zhiliaoapp.musically`, `com.ss.android.ugc.trill` |
| `snapchat` | `com.snapchat.android` |
| `twitter` | `com.twitter.android`, `com.twitter.android.lite`, `com.atebits.Tweetie2` |
| `facebook` | `com.facebook.katana`, `com.facebook.lite`, `com.facebook.orca` (Messenger) |
| `linkedin` | `com.linkedin.android` |

Companion maps: `f3376u` display names (Instagram, YouTube, TikTok, Snapchat, **X**, Facebook, LinkedIn)
and `f3377v` notification-channel names (`sl_msg_<slug>`).

So: this is a *social-app-only* filter with a closed list. No wildcard, no user-added packages, no
per-sender logic — it filters at **package** granularity, reading title/text for display only.

### 1.3 Entry gate (`onNotificationPosted`, line ~100)

Bails out immediately when any of these is true:

1. `accountType == "kid"` → the whole notification feature is disabled for kid accounts
   (`s1.C(this).f8174a.getString("accountType","personal")`).
2. notification is null, or posted by SocialLite itself.
3. package is not in `f3375t`.

Then it reads three prefs (same SharedPreferences file as the rest of the app — `sociallite_settings`,
see `SocialLiteApplication.java:160`):

| Key | Type / default | Meaning |
|---|---|---|
| `notificationRedirectEnabled` | bool, `false` | master switch: replace notifications with SocialLite ones |
| `redirect_notif_<slug>` | bool, `false` | per-platform switch inside redirect mode |
| `appBlockingEnabled` | bool, `false` | app-blocking master switch (shared with the accessibility blocker) |
| `block_<slug>` | bool, default **true** on upgrade (`ie/f.d`) | per-platform app block |
| `blockedAppPackages` | StringSet | per-package blocks (custom list) |
| `blockExpiryMillis` | long, `0` | expiry for temporary blocks (`ie/f.a`) |
| `notificationBlockingEnabled` | bool | feature-level flag; triggers the permission reconciler (see §2.4), not read in this service |
| `blockPerAppOptInV1` | bool | one-shot migration marker set by `ie.f.d` |

### 1.4 The two independent behaviours

```java
if (redirectEnabled && redirect_notif_<slug>) {
    a(sbn, slug);                       // replace then cancel
} else if (appBlockingEnabled && f.b(prefs, slug, pkg)) {
    cancelNotification(sbn.getKey());   // cancel only
}
```

* **Redirect mode** (`a()`): builds a replacement notification, posts it, *then* calls
  `cancelNotification(statusBarNotification.getKey())`. So yes — it **cancels the original** in both
  modes; the difference is only whether something takes its place.
* **Block mode**: `cancelNotification` only, no replacement. Visibility gate is
  `ie.f.b()` (`ie/f.java:33-44`): slug `"custom"` always blocks; otherwise `blockedAppPackages`
  contains the package, or `block_<slug>` is true.
* There is **no snooze, no deferral, no per-sender or keyword filter**, and no tracking/analytics call —
  it does not log or persist the notifications it sees. "Snooze" semantics live in the scheduler, not here.

### 1.5 What the replacement notification carries (`a()`, rd5/NBS.java)

* Tag `sl_<slug>`, id = the original notification id (so `onNotificationRemoved` can match it).
* Channel `sl_msg_<slug>`.
* Title = `android.title`; body = `android.bigText` if present, else `android.text`, else
  `"New notification"`; subtext = `via <Platform>` (`R.string.notifblocker_via_format`).
* Deep link `sociallite://open?platform=<slug>&url=<encoded>` → `MainActivity`, `FLAG_ACTIVITY_NEW_TASK |
  ...` (flags `805306368`), auto-cancel PendingIntent.
  Per-platform URL: `https://www.instagram.com/direct/inbox/`, `https://x.com/messages`,
  `https://www.tiktok.com/messages`, `https://web.snapchat.com/web`, `https://m.facebook.com/messages/`;
  fallback `sociallite://open?platform=<slug>`.
* `when` = original `postTime`, `setAutoCancel(true)`, `FLAG_SHOW_LIGHTS` (`flags |= 1`),
  **`defaults = -1` (= `DEFAULT_ALL`)**, plus a `BigTextStyle` when the body is > 50 chars and the
  original `android.largeIcon` bitmap (downscaled below API 27).
* If anything throws while building/posting, it logs `"Failed to post replacement"` — and notably does
  **not** cancel the original on that path.

`onNotificationRemoved` (line ~127): when the removal reason is `3` (`REASON_CANCEL_ALL`) or `8`
(`REASON_LISTENER_CANCEL`), it cancels its own `sl_<slug>` notification with the same id, so a
"clear all" doesn't leave orphaned SocialLite copies.

### 1.6 Sound / vibration / shade — what it can and cannot suppress

* The listener callback fires **after** the original notification was posted, so the original's sound and
  vibration have already played. This service therefore **cannot suppress the original alert**; it can
  only remove/replace the shade entry after the fact.
* The replacement notification explicitly requests `DEFAULT_ALL` — i.e. the redirect is designed to
  **re-alert** (sound + vibrate + lights) under SocialLite's own channel. Redirection is a rebranding, not
  a silencing, mechanism.
* Only "app blocking" mode is a pure removal, and even that is post-hoc: the banner/sound may already
  have fired before `cancelNotification` lands.
* The channels **are** created elsewhere, built dynamically: `ie.s1.m(Context)`
  (`ie/s1.java:1201-1231`) deletes the legacy `sociallite_notif_*` channels, creates the channel group
  `sociallite_notifications`, then for every entry of `f3377v` creates
  `new NotificationChannel("sl_msg_<slug>", "<Platform> via SocialLite", 4)` — importance **4 =
  `IMPORTANCE_HIGH`** — with `enableVibration(true)`, `enableLights(true)`, `setShowBadge(true)`,
  lockscreen visibility 0, and no explicit sound (so the default notification sound is used).
  Consequence: in redirect mode the user gets the original app's alert **and** a second high-importance
  SocialLite alert (double sound/vibration); the volume/importance can afterwards be turned down in system
  settings, but the app cannot silence the original.
* `hVar.f6794o = "msg"` in `a()` is passed to a `setGroup`-style helper (`i3/h.java:155`), i.e. the
  replacements are grouped, not on a separate channel (UNVERIFIED exact field semantics — the channel id
  comes from the `h(Context, String)` constructor argument, which is `sl_msg_<slug>`).
* Android-version behaviour: `NotificationChannel` + `BigTextStyle`/large-icon handling and the API-27
  bitmap downscale branch (`Build.VERSION.SDK_INT < 27`) are the only version-specific code. Pre-O
  devices honour `defaults = -1` directly; O+ devices need the channel to be created with importance/sound.

### 1.7 Listener approval requirement

If notification access is not granted, the system never binds the service: `onListenerConnected` doesn't
run, `f3378w` stays `false`, and `onNotificationPosted` is never called. Nothing else in the app can
substitute for it. `f3378w` is the only *true* liveness flag for this feature (see §2.3).

---

## 2. Setup / onboarding permission flow

### 2.1 The checks

All permission probing funnels through a small number of helpers:

* `me.g.e(Context, String serviceClassName)` — accessibility service enabled? Tries
  `AccessibilityManager.getEnabledAccessibilityServiceList(-1)` and matches package + service class short
  name (`me/g.java:103-125`); on failure falls back to parsing `Settings.Secure.enabled_accessibility_services`
  and matching `pkg/com.sociallite.android.data.<Name>` or `pkg/.data.<Name>` (`me/g.java:128-145`).
  Wrapped in `try/catch(Throwable)` returning `false`.
* `me.g.c(Context)` = `e(ctx,"AppBlockerService")` (`me/g.java:78-80`).
* `me.g.f(Context)` = `e(ctx,"NativeReelsBlockerService")` (`me/g.java:147-150`).
* `me.g.d(Context)` — battery exemption via `PowerManager.isIgnoringBatteryOptimizations`
  (`me/g.java:81-88`).
* `Settings.canDrawOverlays(ctx)` — overlay, used inline (`he/r.java:63,86,100`, `oe/tc.java:122`,
  `oe/sd.java:16`).
* `ie.f7.b()` — usage access via `AppOpsManager.checkOpNoThrow("android:get_usage_stats", ...)`
  (`ie/f7.java:189-196`, `unsafeCheckOpNoThrow` on API 29+).
* `NotificationBlockerService.f3378w` — listener live? (`NotificationBlockerService.java:28,75,82`).
* `VpnService.prepare(ctx) != null` — VPN consent still needed (`he/a0.java:143`).

There is **no** `ACTION_USAGE_ACCESS_SETTINGS` and **no** `Settings.ACTION_USAGE_ACCESS_SETTINGS`
string anywhere in the tree (grep over all sources), and no `ACTION_VPN` constant — VPN uses the
`VpnService.prepare()` consent intent.

### 2.2 Table

| Permission | Check (class:method) | Intent launched | Degradation if missing |
|---|---|---|---|
| Notification access | `NotificationBlockerService.f3378w` (set in `onListenerConnected`/`onListenerDisconnected`), consumed at `he/a0.java:246`, `oe/k7.java:1894`, `he/y.java:109` | `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (`oe/d9.java:39,50`; `oe/e.java:38` with `FLAG_ACTIVITY_NEW_TASK`), fallback `android.settings.SETTINGS` (`oe/d9.java:41,52`) | Notification redirect and notification-cancel both dead; shade entries from all 7 platforms stay untouched |
| Accessibility — app blocker | `me.g.c` → `me.g.e(ctx,"AppBlockerService")` (`me/g.java:78`) | `android.settings.ACCESSIBILITY_SETTINGS`, flags `335544320` (`me/g.java:160-163`) | No app-launch interception: downtime, time-limit blocks and app blocks never show a block screen |
| Accessibility — native reels | `me.g.f` → `me.g.e(ctx,"NativeReelsBlockerService")` (`me/g.java:147`); composable watcher `me.g.i` (`me/g.java:193-211`) | same accessibility intent via `me.g.g` (`oe/o8.java:52,63,67`, `oe/e.java:47-59`, `he/r.java:111`) | In-app feed filtering (Reels/Shorts/FYP) off; `canTryConfiguredApps` false (`oe/sd.java:16`) so the "configure apps" entry point is gated |
| Display over other apps | `Settings.canDrawOverlays` (`he/r.java:63`, `oe/tc.java:122`) | `android.settings.action.MANAGE_OVERLAY_PERMISSION` + `package:<pkg>`, flags `268435456` (`he/r.java:91,105`) | Block screen and reel-block overlay cannot be drawn — accessibility detection fires but the user sees the real app instead of a stop screen |
| Battery optimisation exemption | `me.g.d` (`me/g.java:81-88`) | `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:`, flags `268435456`; fallback `IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (`me/g.java:212-224`) | Blocker/VPN/workers get killed in Doze; protection silently stops after a while (see 02-schedules doc) |
| Usage access | `ie.f7.b()` AppOps `android:get_usage_stats` (`ie/f7.java:189-196`) | **none found in the APK** — no usage-access settings intent exists; UI only shows `locale_approve_usage_access` text (UNVERIFIED how the user is sent there — possibly a generic app-details/settings screen or a WebView link) | Usage stats/limits read 0 minutes for the 7 tracked packages; time-spent charts and usage-based limits are blind |
| VPN (web blocker) | `VpnService.prepare(ctx) != null` (`he/a0.java:143`) | the `VpnService.prepare()` consent `Intent`, delivered through `f.j` launcher (`he/a0.java:145-147`); on grant starts `WebBlockerVpnService` with `com.sociallite.android.WEB_BLOCKER_START` | Web/DNS blocking never starts; native app blocking still works |
| POST_NOTIFICATIONS (API 33+) | AppOps watcher `android:post_notification` + `android:system_alert_window` (`SocialLiteApplication.java:180-190`) | no dedicated intent — watcher only | SocialLite's own replacement notifications and progress/foreground notifications are silently dropped |

### 2.3 "Enabled" vs "actually running"

* Accessibility: `me.g.e` proves the service is **enabled** (in the enabled list / secure setting). It does
  **not** prove the service process is bound. The enabled-list check is the primary path; the
  `Settings.Secure` string parse is a fallback for ROMs where `AccessibilityManager` lies or throws.
  Because it re-reads on every call, it is a *state query*, not a cached liveness bit.
* Notification listener: only `f3378w`, flipped in `onListenerConnected`/`onListenerDisconnected`
  (`NotificationBlockerService.java:73-84`). That is a genuine running flag. Call sites
  (`he/a0.java:246`, `oe/k7.java:1894`, `he/y.java:109`) use it to decide whether to re-prompt.
* There is no equivalent `isRunning` static for `AppBlockerService`; anything that needs "is the blocker
  live" uses `me.g.c`/`me.g.f`, i.e. the enabled state.

### 2.4 Setup order

The onboarding "3 quick permissions" screen (`ob_native_welcome_subtitle`, "Let's get SocialLite set up
with 3 quick permissions") is implemented in `oe/li.java` and walks, in order:

1. **Enable Smart Blocking** — `ob_native_page1_*`: open Accessibility, select SocialLite, toggle on, allow
   (`me.g.g` → `ACTION_ACCESSIBILITY_SETTINGS`).
2. **Show the block screen** — `ob_native_page2_*`: "Display over other apps" (`MANAGE_OVERLAY_PERMISSION`).
3. **Stay running in background** — `ob_native_page3_*`: ignore battery optimizations
   (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), then "Finish setup" (`ob_native_finish`).

Notification access is **not** in that 3-step wizard — it is requested later, from the lock-in/settings
surface (`hqs_notifications_prompt_*` strings, `oe/td.java`, hundreds of prompt sites), which opens
`ACTION_NOTIFICATION_LISTENER_SETTINGS` via `oe/d9.java`. VPN consent is requested on demand the first
time web blocking is enabled (`he/a0.java:143`).

### 2.5 Re-prompting

* **Battery exemption** is the only timer-based re-prompt: `he/r.java:69` re-asks when
  `now - batteryWhitelistLastAsked > 604800000` (7 days), suppressed by `batteryWhitelistDontAsk` and by
  `accountType == "kid"` / plan checks. `batteryWhitelistLastAsked` is stamped when the dialog is shown
  (`he/r.java:60`).
* Accessibility/overlay/notification states are **reactive**, not polled on a timer:
  `SocialLiteApplication.java:160-196` registers:
  * a `ContentObserver` on `Settings.Secure` URIs `enabled_accessibility_services` and
    `enabled_notification_listeners`,
  * `AppOpsManager.startWatchingMode` for `android:system_alert_window` and `android:post_notification`,
  * a `SharedPreferences` listener for `nativeReelsBlocking`, `appBlockingEnabled`, `sleepEnabled`,
    `scheduleEnabled`, `notificationBlockingEnabled`, `notificationRedirectEnabled`, `accountType`,
    `linkedParentCode`.
  Each change posts `ie.p9.b(ctx)` → `k9` (the reconciler, `ie/p9.java:24-27`, body not decompiled —
  **UNVERIFIED** exactly what it starts/stops, but it is the single place that re-applies protection after
  a permission change).
* In-screen "waiting" states exist for the lock-in flow (`lockin_a11y_waiting` /
  `lockin_a11y_detected`, `oe/b9.java:191-196`) — it waits for the user to come back, then flips the label;
  no timed nag loop was found.

### 2.6 Notes / gaps

* `notificationBlockingEnabled` never appears in `NotificationBlockerService`; it only feeds the reconciler.
  Whether it can stop the listener from being bound (vs just the UI) is **UNVERIFIED**.
* `accountType == "kid"` disables notification filtering entirely at the service level, independent of
  prefs — a parent's notification-redirect settings will not apply on a kid device.
* No per-sender allow/deny list, no keyword filtering, no "block only promotional" heuristic: the only
  content read from the notification is title/text/largeIcon for the replacement copy.
