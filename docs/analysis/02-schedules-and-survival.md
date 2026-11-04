# Schedules, downtime and survival mechanisms

Scope: `com.sociallite.android.sleep.*`, alarm/reboot handling, health workers, notification
blocker, device-admin "uninstall protection". Verified against jadx (`--show-bad-code` where
needed) and smali.

## 1. Sleep / downtime model

Two parallel models coexist. The legacy one is still wired into the enforcer.

### Legacy global schedule (`ie.c.e0`, c.java:676-763)

| Pref key | Meaning |
| --- | --- |
| `scheduleEnabled` | bool, master switch for the legacy schedule |
| `schedule_start` | `"22:00"` string |
| `schedule_end` | `"07:00"` string |

`c.e0()` parses these into `e3(start, end, enabled, active)`. Still consulted by
`AppBlockerService.enforcePackage` for kid accounts (`c.e0(C2, calendar, 0, 4).f7245d`).

### Per-day schedule (the real one)

| Pref key | Meaning |
| --- | --- |
| `sleepEnabled` | bool, master switch |
| `sleepPerDayScheduleEnabled` | bool, default `true` |
| `sleepBedtimeMinutes` | int, default `1320` = 22:00 (minutes from midnight) |
| `sleepWakeMinutes` | int, default `420` = 07:00 |
| `sleepWindDownMinutes` | int, default `30` — window starts this much *before* bedtime |
| `sleepMorningLockMinutes` | int, default `30` — window ends this much *after* wake |
| `sleepBedtimeMinutes_<1..7>` / `sleepWakeMinutes_<1..7>` | per-weekday overrides, resolved in `e8.b` (e8/b.java:166-178) → `ie.oa` |
| `sleepAllowedPackages` | string set of packages still usable while sleeping (phone, SMS, clock...) |
| `parent_weekly_schedule` | JSON array `{day, enabled, start, end}` — kid mode override, read by `c.f0()` (c.java:770-795) when `accountType=="kid"` and `scheduleEnabled==false` |

Window computation: `ie.c.d()` (c.java:520-558) iterates day offsets `-2..+1`, builds start/end
`Calendar`s, rolls `+1 day` when `end < start` (overnight window), and widens by wind-down and
morning-lock. The live predicate used by the enforcer is `i9.i(prefs, now, zone)` (i9.java:197-218):
active when `now` is within `[start - windDown, end + morningLock]`, returning a `d0(startMs, endMs, date)`.

### How sleep interacts with enforcement

`enforcePackage` computes `z7 = sleepActive && !sleepAllowedPackages.contains(pkg)`. When `z7` is
true the block reason is hard-coded to `"Sleep mode is active — go to bed."`
(AppBlockerService.java:314-315) and the app is blocked **even if `appBlockingEnabled` is false**
— sleep is not subordinate to the master blocking switch. That is the single most important
architectural fact for a clone: downtime is an independent enforcement axis.

## 2. Alarms and reboot: almost entirely vestigial

- `BootReceiver` (BootReceiver.java:11-21) handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED` → `e9.u.d(context)`.
- `e9.u.d()` (e9/u.java:293-311) **cancels** alarms for request codes `100,101,102,103,200`
  using `PendingIntent.getBroadcast(..., FLAG_NO_CREATE|FLAG_IMMUTABLE)` + `alarmManager.cancel()`.
  There is no `set*` alarm call anywhere in app code (only androidx.work's internal `setExact`).
- `WakeAlarmReceiver` (WakeAlarmReceiver.java:31-37) also just calls `u.d(context)`.

So no alarm is ever armed: the `USE_EXACT_ALARM`/`SCHEDULE_EXACT_ALARM` permissions and the
wake receiver are leftovers. Reboot survival actually comes from the OS re-binding the
`BIND_ACCESSIBILITY_SERVICE` service, not from app scheduling.

Sleep boundaries are detected **in-process only**: `ie.k.run()` (ie_k.java:32-216) re-posts
itself on the accessibility service's main `Handler` every `60020 - (now % 60000)` ms (top of
each minute), gated on `sleepEnabled` or (kid account and legacy `scheduleEnabled`). SCREEN_OFF
removes the callback (`ie.l`); SCREEN_ON / USER_PRESENT / TIME_SET / TIMEZONE_CHANGED /
DATE_CHANGED trigger `requestSleepCheck()`. Everything recomputes from `Calendar.getInstance()`
at check time, so timezone/DST changes are handled — as long as the process survives. If the
accessibility service dies, downtime silently stops being enforced until it is re-bound by the OS
(no alarm, no self-revive).

Manifest declares `RECEIVE_BOOT_COMPLETED`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` — all unused
for enforcement. Clone implication: use one `AlarmManager.setExactAndAllowWhileIdle`-driven
boundary check instead of minute polling, since a clone should not depend on a living accessibility
process for schedule edges (overlay/FGS can be revived by alarm; accessibility cannot).

## 3. Health workers: notification only, no remediation

`AppBlockerHealthWorker` — `CoroutineWorker`, unique work `sl_blocker_health`, **15-minute periodic**
interval, enrolled from `MainActivity` (androidx/lifecycle/p.smali ~3035-3051). `doWork` swallows
every throwable and returns success. It calls, in order:

1. `i9.d(context)` — persists sleep/profile snapshot state.
2. `p9.a(context)` (ie_p9.java:46-194) — "protection health" notifier. Enumerates requirement enum
   `r9`; for each *missing mandatory* requirement (plus `r9.y` always) it posts a notification on
   channel `sociallite_blocker_health` (importance 3) with a `PendingIntent` to the matching settings
   screen (`c.g0`), rate-limited to one per requirement per 6 h (`<name>_sent` minus 21 600 000 ms),
   cancelling notification id `31719` first, and persists flags in a `protection_health` prefs block.
   **It only notifies. It never re-enables anything.**
3. `r2.a(context)` (ie_r2.java:78-405) — kid status reporter: checks `Settings.canDrawOverlays`,
   `AppBlockerService` enabled + `isRunning`, `NativeReelsBlockerService` enabled + `H0`, and pushes
   `(healthy, reelsHealthy)` to the parent backend via `w7.m`; throttled 60 s (snapshot) / 900 s (report).

Also one-shot work `sl_protection_now` (500 ms delay, ie/k9.java:48-50) fired from
`AppBlockerService.onServiceConnected` / `onDestroy` via `p9.b()`, and `sl_kid_health_now` from `r2.b()`.

## 4. Uninstall protection: theatre

`UninstallProtectionReceiver` extends `DeviceAdminReceiver`; `res/xml/device_admin.xml` declares
`<uses-policies/>` — **empty**, i.e. zero device-admin policies. It only:

- returns `uninstall_protection_disable_warning` from `onDisableRequested` (scary confirm dialog),
- writes `a2(true/false)` into `ka` prefs from `onEnabled`/`onDisabled`.

A policy-less device admin cannot block uninstall on modern Android; `DevicePolicyManager` is never
asked to set uninstall-blocked. Verdict: vestigial, kept for the warning copy + a state flag the UI
may read. Do not reproduce as a security claim.

## 5. Permission / setup surface

Special access this app needs for its blocking to function at all:

| Access | Manifest / mechanism | Consequence when missing |
| --- | --- | --- |
| Accessibility service (`AppBlockerService`) | `BIND_ACCESSIBILITY_SERVICE`, `res/xml/accessibility_service_config.xml`: `typeWindowStateChanged`, `flagIncludeNotImportantViews`, `canRetrieveWindowContent`, timeout 300 ms | no foreground-package detection → app blocker completely inert |
| Accessibility service (`NativeReelsBlockerService`) | `res/xml/native_reels_blocker_config.xml`: `typeViewClicked|typeViewScrolled|typeViewSelected|typeWindowContentChanged|typeWindowStateChanged`, `flagReportViewIds`, `flagRetrieveInteractiveWindows`, `canRequestFilterKeyEvents`, `canPerformGestures`, `canTakeScreenshot`, timeout 50 ms | content filtering inert |
| Notification listener (`NotificationBlockerService`) | `BIND_NOTIFICATION_LISTENER_SERVICE` | notifications from blocked apps still arrive |
| `SYSTEM_ALERT_WINDOW` | overlay | `Settings.canDrawOverlays` false; used by the block overlay path and kid health checks |
| `PACKAGE_USAGE_STATS` | `AppCompatActivity`-side `AppOpsManager` check | daily limits cannot be measured |
| Battery-optimisation exemption | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | background workers/alarms get deferred |
| `POST_NOTIFICATIONS` (13+) | runtime permission | health notifications invisible |
| VpnService consent (`WebBlockerVpnService`) | `BIND_VPN_SERVICE` + `foregroundServiceType="specialUse"`, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE=content_filtering` | network filtering inert |

<!-- TODO(remaining): NotificationBlockerService policy; UnusedAppSettingsActivity and
PrivacyRestartActivity relevance; battery-optimisation request flow (me.g helpers) and onboarding
copy per permission. -->
