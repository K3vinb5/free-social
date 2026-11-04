# 01b — The block screen (`BlockedAppActivity`)

Read-only RE of `com.sociallite.android`. Sources: `dec/jadx/sources/com/sociallite/android/data/BlockedAppActivity.java`,
`.../data/AppBlockerService.java`, recovered `enforcePackage` at `/tmp/opencode/rd2/AppBlockerService.java`,
`ie/s1.java` (the composable), `ie/p1.java` + `smali/smali/ie/p1.smali` (watchdog), `AndroidManifest.xml:170`,
`apktool/res/values/strings.xml`.

## 1. What it is

A full-screen **Activity**, not an overlay. The overlay code (`NativeReelsBlockerService`, `s1.i()/s1.j()`)
is a separate in-app surface for Reels/Shorts/Stories; this is the "whole app is blocked" screen.

Manifest (`AndroidManifest.xml:170`):

```xml
<activity android:name="com.sociallite.android.data.BlockedAppActivity"
    android:exported="false" android:excludeFromRecents="true"
    android:launchMode="singleInstance" android:taskAffinity=""
    android:screenOrientation="portrait"
    android:theme="@style/Theme.SocialLite.Blocked"/>
```

## 2. Intent extras

| Extra | Type | Default | Used for | Cite |
|---|---|---|---|---|
| `platform` | String | `"app"` | title word, deep-link, `postmode_<platform>` | `BlockedAppActivity.java:141-145` |
| `reason` | String | `blocked_page_reason_default` | body paragraph, printed verbatim | `:146-151` |
| `package_name` | String | `null` | decides whether the watchdog starts (kid path) | `:237-239` |
| `sleep_block` | boolean | `false` | bedtime/downtime marker; watchdog for non-kid | `:238`, `:246` |
| `in_app` | boolean | `false` | `true` → "leave" just `finish()`es instead of going Home | `:41-56` |

Producer: `AppBlockerService.launchBlockOverlay()` — `addFlags(335544320)` (NEW_TASK|CLEAR_TASK) then the
four `putExtra`s (`AppBlockerService.java:146-155`).

## 3. Rendering

Single Compose column on pure black (`-16777216`, `:134`), nav-bar contrast off on API 29+ (`:138-140`).
Body is `s1.a(...)` (`ie/s1.java:776-869`), invoked at `:241`.

1. `sociallite_logo_dark`, 80 dp.
2. Title `blocked_page_title` = **"%1$s is Blocked"**. `%1$s` from a hardcoded switch (`:166-227`):
   `youtube`→"YouTube", `twitter`→"X (Twitter)", `tiktok`→"TikTok", `instagram`→"Instagram",
   `snapchat`→"Snapchat", `facebook`→"Facebook", `linkedin`→"LinkedIn"; else `blocked_page_this_app`
   ("This app"). `reddit` and `custom` fall through to "This app is Blocked".
3. Body: the `reason` string verbatim, 15 sp (`s1.java:837`).
4. Only when `accountType=="kid"` **and** the platform is enabled (`cVar.G(this).l(b9Var2)`,
   `BlockedAppActivity.java:229-236`): hint `blocked_page_use_instead` (13 sp, `s1.java:841`) plus a filled
   button labelled `blocked_page_open_cta` (label rendered in `he/b.java:64`).
5. Always: a text button labelled `blocked_page_go_home` (`he/b.java:77`).

### Reasons actually produced

| Reason (verbatim) | When | Cite |
|---|---|---|
| `Sleep mode is active — go to bed.` | bedtime / wind-down / morning lock | `rd2/AppBlockerService.java:315` |
| `Blocked by your parent` (`parent_native_block_reason`) | parent-forced block | `:317` |
| `Downtime is active` | non-kid downtime | `:320` |
| `<Platform> limit reached for today` | daily limit hit | `:286-298` |
| `Time limit reached` | limit hit, no label | `:325` |
| `This app is blocked by SocialLite.` | generic / default | `:322`, `BlockedAppActivity.java:148` |
| `Post Mode expired — app re-locked by SocialLite.` | post-mode timer expired | `AppBlockerService.java:181,202` |

The Activity computes none of this — it prints whatever `reason` it was handed.

## 4. User actions (two)

**A. "Open in SocialLite"** (kid + platform enabled) — `BlockedAppActivity.java:99-109`:

```java
Intent i = new Intent(this, MainActivity.class);
i.addFlags(335544320);                 // NEW_TASK | CLEAR_TASK
i.setAction("android.intent.action.VIEW");
i.setData(Uri.parse("sociallite://open?platform=" + platform.toLowerCase()));
startActivity(i); finish();
```

**B. "Go Home"** — `:112-121` / `leaveBlockedApp()` `:46-56`: if `in_app` just `finish()`; else
`ACTION_MAIN` + `CATEGORY_HOME` with `FLAG_ACTIVITY_NEW_TASK`, then `finish()`.

**C. Back** — `getOnBackPressedDispatcher().a(this, new i0(this))` (`:137`); `i0.a()` case 2 calls
`leaveBlockedApp()` (`androidx/activity/i0.java:49-50`), callback constructed `super(true)` = enabled.
So Back == "Go Home", not finish-only.

There is **no** break / snooze / unlock button on this screen.

## 5. The watchdog

When `(kid && package_name != null)` **or** `(!kid && sleep_block)` the Activity starts a coroutine
(`:238-240` → `ie/p1.java` case 0, recovered from `smali/smali/ie/p1.smali:1043-1275`):

```
while (!isFinishing()) {
    delay(500ms);
    if (!<block still valid>) finish();
}
```

`<block still valid>` (p1.smali, condensed): kid+package → sleep window active, or platform allowed for
kid, or downtime/limit still valid; `in_app` → `ka.k2(platform)`; non-kid →
`!(contentBreakActive && now<contentBreakEndTime && postmode_<platform>)` && `appBlockingEnabled` &&
not sleep-allowed.

So the screen **dismisses itself within ~500 ms** once the reason evaporates (parent lifts the rule,
sleep ends, server break arrives). It never re-launches the blocked app; it just disappears, and the
accessibility service is watching for the app returning.

## 6. Escape resistance

### What is actually there

- **Task nuking.** `335544320` (NEW_TASK|CLEAR_TASK) destroys the blocked app's task when the block
  fires; Back cannot return you to Instagram because that task is gone.
- **Not in Recents.** `excludeFromRecents=true` + `taskAffinity=""` + `singleInstance` = throwaway task,
  no card to swipe back to.
- **Re-entry loop.** `AppBlockerService` is an `AccessibilityService` on `TYPE_WINDOW_STATE_CHANGED`
  (`eventTypes = 32`, `AppBlockerService.java:278`) calling `enforcePackage(pkg)` on every window change
  (`:105-144`). Any route back in — icon, Recents, notification, deep link, shortcut — re-fires the block,
  with only a **500 ms per-package debounce** (`rd2/AppBlockerService.java:309-312`). No launch cap, no
  daily allowance, no "N blocks then give up".
- **Back is handled**, not swallowed (→ Home), so the screen never looks "stuck" and invite a force-stop.
- **Home is offered**, not fought: you can always leave, you just cannot stay.

### What is not there — blunt verdict

- **It is a normal Activity, not an overlay.** No `SYSTEM_ALERT_WINDOW` draw over the blocked app, no
  `KEEP_SCREEN_ON`, no device-admin lock task, no `setShowWhenLocked`. Anything that takes foreground
  (shade pull, bubble, second app) takes the block screen with it; re-entry then depends on the
  accessibility event arriving ~0–500 ms later.
- **Two trivial code bypasses.** If `!isInteractive() || isKeyguardLocked()` the service **returns
  immediately** (`rd2/AppBlockerService.java:116-118`), so lock-screen / AOD / notification-reply resume is
  never blocked. And killing or starving the accessibility service (Force stop, OEM battery manager,
  "pause app if unused") disables everything; the heartbeat prefs (`blocker_last_event_at`,
  `blocker_last_detected_failure`, `:108-137`) are for *reporting*, not second-layer enforcement.
- **Large allow-list**: launcher, dialer, Settings, systemui, permissioncontroller, packageinstaller, all
  IMEs (`ie/s1.java:212-234`) — necessary, but any "open in Chrome" hand-off escapes.
- **Custom apps are matched by package name only** (`blockedAppPackages`); a repackaged clone, a second
  profile, or `m.instagram.com` in a browser escapes unless web blocking is on.

**Verdict:** resistance is *structural, not forceful* — clear the task, re-fire the interstitial on every
foreground transition, nothing more. Defeated by (a) lock-screen entry, (b) killing/starving the
accessibility service, (c) any browser or repackaged client, (d) simply staying out of the app. The
genuinely hard-to-escape part of the product is **not** this screen but Hard Mode's staged friction for
turning blocking *off* (hold two fingers 5 s → typed phrase → 10 s wait → five math questions → 60 s
cooldown that resets on leaving) plus "Prevent easy uninstall" — see `block_hardcore_*` / `hard_mode_*`.

## 7. Break / post-mode economy

**There is no break button in this build, and the post-mode flags are never written.**

- Reads: `contentBreakActive`, `contentBreakEndTime`, `postmode_<platform>` — `AppBlockerService.java:186-198`,
  `rd2/AppBlockerService.java:186-198,351-352`, `p1.smali:1149-1180`.
- Writes: **only false/0**. `cleanUpPostMode()` sets `contentBreakActive=false`, `contentBreakEndTime=0`,
  `postmode_{instagram,youtube,tiktok,snapchat,twitter,facebook,linkedin}=false`
  (`AppBlockerService.java:69-78`). A full grep of every `smali*/` for `contentBreak` / `postmode` returns
  only this cleanup, the `ie/n2.java` kid-settings merge (which forwards the *falsy* values,
  `n2.java:495-520`), and the reads. **Nothing ever sets a break true or an end time.**
- Intended semantics: the client honours a break handed down by parent/server config, for **one platform at
  a time** (`postmode_<platform>` plus a global `contentBreakActive`), bounded by one absolute
  `contentBreakEndTime`.
- If populated: while `active && now < endTime && postmode_<platform>` the platform is allowed; a
  `Handler.postDelayed` at `endTime` calls `cleanUpPostMode()` and **re-shows the block screen** with
  `"Post Mode expired — app re-locked by SocialLite."` (`AppBlockerService.java:176-206`).
- **No** one-break-per-day rule, **no** cooldown, **no** unlock counter exists in code. The only counters
  are `<code>_used_today_seconds` and `lastBlockedTimes` (500 ms debounce).

Clone model: break = `{active, endTime, platformSet}` with a hard timer and re-block on expiry; a per-day
budget or cooldown would be new policy, not existing behaviour.

## 8. String inventory (English, `res/values/strings.xml`)

### 8.1 Block screen

| Name | Value |
|---|---|
| `blocked_page_title` | `%1$s is Blocked` |
| `blocked_page_this_app` | `This app` |
| `blocked_page_reason_default` | `This app is blocked by SocialLite.` |
| `blocked_page_use_instead` | `Use SocialLite instead for a healthier experience` |
| `blocked_page_open_cta` | `Open in SocialLite` |
| `blocked_page_go_home` | `Go Home` |
| `parent_native_block_reason` | `Blocked by your parent` |
| `parent_native_block_app` | `Block %1$s` |
| `parent_native_update_needed` | `App controls need a service update.` |

Hardcoded in code, not in resources: `Sleep mode is active — go to bed.`, `Downtime is active`,
`Time limit reached`, `Post Mode expired — app re-locked by SocialLite.`.

### 8.2 Blocking setup / native app blocking

| Name | Value |
|---|---|
| `accessibility_service_description` | `SocialLite uses this service to block native social media apps during downtime and after time limits are reached. When a blocked app is opened, SocialLite redirects you to a healthier, filtered experience. This is a digital wellbeing and parental control feature.` |
| `block_screen_title` | `Block Native Apps` |
| `block_screen_a11y_required_title` | `Accessibility Service Required` |
| `block_screen_a11y_required_body` | `Tap to enable in system settings` |
| `block_screen_master_toggle_title` | `Block Native Apps` |
| `block_screen_master_toggle_on` | `Native apps are blocked — use SocialLite instead` |
| `block_screen_master_toggle_off` | `Block native social media apps on this device` |
| `block_screen_choose_prompt` | `Choose which apps to block:` |
| `block_screen_blocked_apps_header` | `BLOCKED APPS` |
| `block_screen_redirect_note` | `Blocked apps will show a redirect screen when opened.` |
| `block_screen_back_cd` | `Back` |
| `block_screen_app_facebook` | `Facebook` |
| `block_screen_app_instagram` | `Instagram` |
| `block_screen_app_linkedin` | `LinkedIn` |
| `block_screen_app_snapchat` | `Snapchat` |
| `block_screen_app_tiktok` | `TikTok` |
| `block_screen_app_twitter` | `X (Twitter)` |
| `block_screen_app_youtube` | `YouTube` |
| `block_screen_notif_dialog_title` | `Block Notifications Too?` |
| `block_screen_notif_dialog_body` | `To fully block native apps, SocialLite also needs notification access. This prevents blocked apps from sending you notifications that pull you back in.` |
| `block_screen_notif_dialog_enable` | `Enable` |
| `block_screen_notif_dialog_skip` | `Skip` |
| `block_app_picker_title` | `Pick apps to block` |
| `block_app_picker_subtitle` | `These apps will be blocked when blocking is on.` |
| `block_app_picker_done_format` | `Done (%1$d)` |
| `block_app_picker_search_placeholder` | `Search apps` |
| `block_app_picker_group_social` | `Social` |
| `block_app_picker_group_other` | `All apps` |
| `block_group_social` | `Social` |
| `block_group_social_sub` | `Tap an app to include it in blocking.` |
| `block_group_other` | `Other apps` |
| `block_full_app_info_title` | `Full App Blocking` |
| `block_full_app_info_cd` | `What is Full App Blocking?` |
| `block_full_app_info_body` | `"This blocks whole apps outright. A blocked app will not open at all - not the feed, not messages, nothing. The Block Box is the softer version: the app opens, only the short-form inside it is stripped.` |
| `block_primary_start` | `Start blocking` |
| `block_primary_start_kid` | `Block %1$s's apps` |
| `block_primary_start_timed_format` | `Start %1$s block` |
| `block_primary_start_timed_kid` | `Block %1$s for %2$s` |
| `block_primary_stop` | `Stop blocking` |
| `block_primary_fix` | `Fix blocking` |
| `block_duration_prompt` | `Set blocking time.` |
| `block_duration_prompt_kid` | `Set how long for.` |
| `block_duration_until_off` | `Until turned off` |
| `block_duration_hr_format` | `%1$d hr` |
| `block_duration_hr_min_format` | `%1$dh %2$dm` |
| `block_duration_min_format` | `%1$d min` |
| `block_step_less` | `Less time` |
| `block_step_more` | `More time` |
| `block_step_scrub` | `Duration. Drag left or right to change.` |
| `block_preset_deep` | `deep work` |
| `block_preset_half_day` | `half day` |
| `block_preset_sprint` | `focus sprint` |
| `block_preset_study` | `study session` |
| `block_preset_work_day` | `work day` |
| `block_swipe_for_sessions` | `swipe for sessions` |
| `block_active_a11y_elapsed` | `Timer, blocked for %1$d minutes %2$d seconds` |
| `block_active_a11y_remaining` | `Timer, %1$d minutes %2$d seconds remaining` |
| `block_web_blocker_section` | `More controls` |
| `block_web_blocker_row_title` | `Web Blocking` |
| `block_web_blocker_row_subtitle_active` | `Filtering DNS for %1$d domains` |
| `block_web_blocker_row_subtitle_inactive` | `Block sites system-wide` |
| `block_pro_feature_title` | `Pro Feature` |
| `block_pro_feature_upgrade` | `Upgrade to Pro` |
| `block_pro_feature_not_now` | `Not Now` |

### 8.3 Hard Mode (the anti-escape friction)

| Name | Value |
|---|---|
| `block_hardcore_title` | `Hard Mode` |
| `block_hardcore_confirm_title` | `Turn on Hard Mode?` |
| `block_hardcore_commitment_text` | `I turned this on when I was thinking straight. Nothing has changed since then except that I want to scroll.` |
| `block_hardcore_stage_cooldown_title` | `Wait a minute` |
| `block_hardcore_stage_cooldown_subtitle` | `Leaving the app starts it over.` |
| `block_hardcore_stage_cooldown_breathe` | `seconds left` |
| `block_hardcore_stage_commitment_title` | `Type this out, word for word` |
| `block_hardcore_stage_commitment_placeholder` | `Start typing…` |
| `block_hardcore_stage_math_title` | `Five questions, no calculator` |
| `block_hardcore_stage_math_problem_format` | `%1$d × %2$d = ?` |
| `block_hardcore_stage_math_progress_format` | `Question %1$d of %2$d. A wrong answer starts them over.` |
| `block_hardcore_stage_math_wrong` | `Not right. Back to the first one.` |
| `block_hardcore_stage_math_answer_placeholder` | `Answer` |
| `block_hardcore_stage_confirm_title` | `This turns blocking off` |
| `block_hardcore_stage_confirm_body` | `If you continue, full app blocking turns off and stays off until you turn it back on yourself.` |
| `block_hardcore_stage_confirm_pause` | `Turn blocking off` |
| `block_hardcore_stage_confirm_stay` | `Keep blocking` |
| `block_hardcore_action_continue` | `Continue` |
| `block_hardcore_action_cancel` | `Cancel` |
| `block_hardcore_action_submit` | `Submit` |
| `block_hardcore_action_wait` | `Wait…` |
| `block_hardcore_confirm_cancel` | `Not yet` |
| `block_pause_challenge_title` | `Hold the screen to unblock` |
| `block_pause_challenge_step_hold` | `Hold anywhere on the screen` |
| `block_pause_challenge_step_two_fingers` | `Place two fingers on the screen` |
| `block_pause_challenge_keep_holding` | `Keep holding…` |
| `block_pause_challenge_cancel` | `Never mind — keep blocking` |
| `block_pause_challenge_duration` | `for five seconds` |
| `block_pause_challenge_seconds` | `seconds` |
| `hard_mode_activated` | `Hard Mode Activated` |
| `hard_mode_action_title` | `Make this change?` |
| `hard_mode_action_body` | `Type the phrase, then take 10 seconds. Hard Mode stays on.` |
| `hard_mode_action_phrase` | `I choose this change` |
| `hard_mode_action_type` | `Type the phrase above` |
| `hard_mode_action_wait` | `Wait %1$d seconds` |
| `hard_mode_action_continue` | `Allow this change` |
| `hard_mode_enable_continue` | `Continue` |
| `hard_mode_enable_protect` | `Prevent easy uninstall` |
| `hard_mode_enable_protect_error` | `Protection was not enabled. Try again, or uncheck it to continue without it.` |
| `hard_mode_handle_on` | `Hard Mode on. Tap or swipe up to deactivate` |
| `hard_mode_handle_off` | `Hard Mode off. Tap or swipe up to activate` |
| `goal_limits_action_body` | `Type the phrase, then take 10 seconds to confirm your new limits.` |
| `goal_limits_challenge_hint` | `Changes always require the Hard Mode challenge.` |
| `goal_limits_confirm_body` | `Your new daily limits will apply now. Time already used today will still count.` |

### 8.4 Sleep / downtime / limits

| Name | Value |
|---|---|
| `sleep_header_title` | `Sleep Mode` |
| `sleep_enable_button_on` | `Turn off Sleep Mode` |
| `sleep_enable_button_off` | `Turn on Sleep Mode` |
| `sleep_bedtime_label` | `Bedtime` |
| `sleep_wake_label` | `Wake Up` |
| `sleep_night_exit` | `Exit sleep mode` |
| `sleep_parent_window_title` | `Set by your parent` |
| `sleep_schedule_repeat_every_day` | `Repeat every day` |
| `sleep_schedule_value_every_day` | `Every day` |
| `sleep_schedule_value_per_day` | `Set per day` |
| `sleep_allow_title` | `Always allowed` |
| `sleep_allow_subtitle` | `These apps still open while Sleep Mode is on.` |
| `sleep_allow_caption` | `Allowed` |
| `sleep_buffer_before_bedtime` | `before bed time` |
| `sleep_buffer_after_wake` | `after you wake up` |
| `sleep_buffer_block_apps` | `Block apps` |
| `sleep_buffer_minutes_compact_format` | `%1$dm` |
| `sleep_card_value_none` | `None` |
| `sleep_card_value_off` | `Off` |
| `sleep_card_winddown_caption` | `Wind down` |
| `sleep_card_winddown_value_format` | `%1$d min` |
| `sleep_dial_bedtime_a11y` | `Bedtime %1$s. Drag to change.` |
| `sleep_dial_wake_a11y` | `Wake time %1$s. Drag to change.` |
| `sleep_dial_duration_hours` | `%1$dh` |
| `sleep_dial_duration_hours_minutes` | `%1$dh %2$dm` |
| `sleep_dial_duration_minutes` | `%1$dm` |
| `sleep_blocker_gate_title` | `Enable Native App Blocker` |
| `sleep_blocker_gate_body` | `Sleep Mode is enforced by the Native App Blocker. Enable accessibility for SocialLite so it can actually lock your apps.` |
| `sleep_blocker_gate_confirm` | `Enable` |
| `sleep_blocker_gate_cancel` | `Cancel` |
| `sleep_blocker_required_title` | `Native App Blocker required` |
| `sleep_blocker_required_body` | `"Wind-down and morning lock can't enforce until you enable accessibility."` |
| `sleep_blocker_required_enable` | `Enable` |
| `sleep_pro_alert_title` | `Pro Feature` |
| `sleep_pro_alert_body` | `"Set a bedtime that locks your phone overnight and a short morning buffer that holds the apps off until you've actually started your day."` |
| `sleep_pro_alert_confirm` | `Upgrade to Pro` |
| `sleep_pro_alert_dismiss` | `Not Now` |
| `sleep_focus_sheet_schedule_title` | `Schedule` |
| `sleep_focus_sheet_winddown_title` | `Wind Down` |
| `sleep_focus_sheet_done` | `Done` |
| `sleep_minutes_picker_done` | `Done` |
| `sleep_minutes_picker_cancel` | `Cancel` |
| `sleep_minutes_picker_option_format` | `%1$d min` |
| `parent_web_daily_limit` | `Daily limit` |
| `parent_web_no_limit` | `No limit` |
| `parent_web_limit_title` | `%1$s daily limit` |
| `web_editor_no_daily_limit` | `No daily limit` |
| `profile_daily_limit_format` | `Daily limit: %1$s` |
| `short_form_limit_format` | `Short-form limit: %1$s` |
| `stats_limit_remaining` | `Time left of your limit` |
| `usage_breakdown_header` | `BREAKDOWN` |
| `usage_breakdown_used_format` | `%1$d min` |
| `usage_breakdown_used_of_limit_format` | `%1$d / %2$d min` |
| `usage_chart_legend_screentime` | `Screen time` |
| `usage_chart_focused_life` | `Focused life` |
| `locale_set_your_daily_limits` | `Set your daily limits` |
| `locale_per_kid_screen_time_limits` | `Per-kid screen time limits` |
| `locale_limit_hits` | `Limit hits` |
| `locale_near_limit` | `At or near limit` |
| `locale_over_limit` | `over your limit` |
| `pace_progress_current_limit` | `Current daily limit` |
| `first_scroll_allowance_unlimited` | `No limit` |
| `constellation_unblock_confirm_title` | `Are you sure you want to scroll for %1$s?` |
| `constellation_unblock_confirm_cta` | `Proceed` |
| `constellation_unblock_confirm_cancel` | `Cancel` |

### 8.5 Onboarding: accessibility / overlay / usage / notification access

| Name | Value |
|---|---|
| `lockin_walkthrough_enable_title` | `Enable the toggle` |
| `lockin_walkthrough_enable_body` | `Allow SocialLite to use Accessibility, then come back — this screen will continue automatically.` |
| `lockin_walkthrough_open_a11y_title` | `Open Accessibility Settings` |
| `lockin_walkthrough_open_a11y_body` | `Tap the button below — Android will open the system Accessibility list.` |
| `lockin_walkthrough_cta` | `Open Accessibility Settings` |
| `lockin_walkthrough_find_app_title` | `Find "SocialLite App Blocker"` |
| `lockin_walkthrough_find_app_body` | `Scroll down to "Downloaded apps" and tap SocialLite.` |
| `lockin_a11y_waiting` | `Waiting for Accessibility…` |
| `lockin_a11y_detected` | `Accessibility detected — continuing…` |
| `lockin_reels_walkthrough_overlay_title` | `Allow display over other apps` |
| `lockin_reels_walkthrough_overlay_body` | `Required so SocialLite can show the block overlay.` |
| `lockin_reels_walkthrough_enable_title` | `Enable "SocialLite Reels Blocker"` |
| `lockin_reels_walkthrough_enable_body` | `Find SocialLite under Downloaded apps and turn the toggle on.` |
| `lockin_reels_walkthrough_open_body` | `Tap below — Android opens its system Accessibility list.` |
| `lockin_reels_sheet_title` | `Block in Your Real Apps` |
| `lockin_reels_sheet_body` | `Detect and block Reels, Shorts, and Spotlight inside the real Instagram, YouTube, and Snapchat apps.` |
| `lockin_sheet_native_blocker_body` | `Block Instagram, TikTok, Snapchat and more on your phone and redirect them to SocialLite.` |
| `lockin_sheet_done` | `Done` |
| `lockin_notif_permission_open_settings` | `Open Settings` |
| `lockin_oem_autostart_body` | `"Allow autostart + unrestricted battery so blocking doesn't get killed."` |
| `lockin_oem_keep_blocking_format` | `Keep blocking on %1$s` |
| `hqs_notifications_title` | `Notifications` |
| `hqs_notifications_description` | `Get notifications through SocialLite instead of native apps.` |
| `hqs_notifications_prompt_title` | `Notification Access Required` |
| `hqs_notifications_prompt_body` | `"To redirect notifications through SocialLite, we need notification access permission. This lets SocialLite read notifications from your social media apps and replace them with SocialLite notifications.` |
| `hqs_notifications_prompt_open` | `Open Settings` |
| `hqs_notifications_prompt_cancel` | `Cancel` |
| `locale_approve_usage_access` | `Approve Usage Access` |
| `locale_usage_access_explanation` | `Android shares time spent in other apps only if you approve Usage Access. SocialLite reads your social apps only, and it stays on this phone.` |
| `protection_setup_title` | `Finish protection setup` |
| `protection_gate_body` | `Finish setup to use these controls.` |
| `protection_apps` | `App and sleep blocking` |
| `protection_listener` | `Notification access` |
| `protection_overlay` | `Display over other apps` |
| `protection_content` | `Content blocking` |
| `protection_battery` | `Background battery access` |
| `protection_alerts` | `Protection alerts` |
| `protection_channel` | `Protection status` |
| `protection_fix` | `Fix` |
| `protection_notification_title` | `Check your protection` |
| `protection_notification_body` | `%1$s needs attention. Tap to fix.` |
| `protection_unused` | `Keep protection active` |
| `protection_unused_body` | `On the next screen, turn OFF “Manage app if unused”, “Pause app activity if unused”, or “Remove permissions if app is not used” for SocialLite. This helps Android keep protection running when you have not opened SocialLite for a while.` |
| `protection_unused_error` | `Could not open Settings. Open your phone settings, choose Apps, then SocialLite.` |
| `protection_unused_notification` | `Turn off unused-app restrictions to keep protection running.` |
| `family_native_permission_title` | `Allow app blocking` |
| `family_native_setup` | `App blocking setup` |
| `family_native_title` | `%1$s’s phone apps` |
| `family_native_details` | `Check phone setup` |
| `family_native_blocked` | `Blocked` |
| `family_native_allowed` | `Allowed` |
| `family_native_ready` | `Last reported ready` |
| `family_native_kid_help` | `With your parent, enable SocialLite App Blocker in Accessibility so phone-app rules can work.` |
| `family_native_parent_help` | `On their phone, open SocialLite → Profile → App blocking setup. Enable SocialLite App Blocker in Accessibility. Phone-app rules need this permission, which can be turned off in Android Settings. Supports the seven apps listed here.` |
| `family_native_disclosure` | `SocialLite uses Accessibility to detect which app is open and show a block screen for apps your parent has blocked, including during bedtime. Setup status is shared with your parent. This does not enable native feed filtering. App names are shared with your parent so they can choose which apps to block.` |
| `native_reels_blocker_description` | `SocialLite uses this service to detect and block addictive content surfaces (Reels, Shorts, Spotlight, Discover) within native social media apps while preserving access to DMs, posts, and stories. This is a digital wellbeing feature. This service checks the foreground app and blocked content surfaces. To match Instagram colours, it briefly captures the screen and samples the navigation bar. Captures are processed on your device, then discarded. They are never saved or shared.` |
| `native_appearance_disclosure` | `This service checks the foreground app and blocked content surfaces. To match Instagram colours, it briefly captures the screen and samples the navigation bar. Captures are processed on your device, then discarded. They are never saved or shared.` |

### 8.6 Notes for the clone

- Copy voice: short declarative sentences, em dash, second person, no exclamation marks. Blunt in the
  block screen ("This app is blocked by SocialLite."), explanatory only in permission flows.
- The **only** screen-facing strings the block Activity itself uses are the five `blocked_page_*` rows;
  every "why" sentence arrives as an Intent extra, so reason copy lives in the blocker, not the UI.
- No string exists for a break/post-mode button — the clone must invent that copy or omit the feature.
