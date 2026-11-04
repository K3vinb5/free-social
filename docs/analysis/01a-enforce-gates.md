# 01a — `AppBlockerService.enforcePackage` gate chain + usage accounting

Sources (already decompiled, no re-unpack):
- `B` = `/tmp/opencode/dec/bad/AppBlockerService.java` (the 570-line pre-decoded copy); line numbers below are from that file
- `ie/*.java` = `/tmp/opencode/dec/jadx/sources/ie/…`; smali cross-checks in `/tmp/opencode/dec/smali/smali/…`

JADX note: the tail of the method (`B:337-360`) is a duplicated/broken pseudo-copy of the branch tree (empty `if (z3) {…}` bodies). `B:167-197` is the authoritative shape; smali agrees. Each duplicated branch is shown once below.

---

## 1. Ordered gate list (pseudo-code)

Called from `handleAccessibilityEvent` on `TYPE_WINDOW_STATE_CHANGED` (eventType 32) → `enforcePackage(pkg)` at `B:401`. No try/catch inside; the caller wraps it (`B:497-503`).

```
 0. GATE device state                                          B:116
    if (!powerManager.isInteractive() || keyguard.isKeyguardLocked()) return

 1. GATE "blocker entitled to run"                             B:118-125
    ka = ka.f7587i.G(ctx)
    if (!ka.z0() && !ka.G0()) return
       ka.z0()  = accountType == "kid"                          ie/ka.java:2266-2268
       ka.G0()  = legacyFreeTikTokGrandfathered || lifetimeProGift
                  || (kid OR (subscription active AND (internal allowlist
                      email OR tier pro OR tier parent)))        ie/ka.java:360-378
       => free adult, no gift, no grandfather flag: no enforcement at all.

 2. GATE own app / system pkgs / kid-protected pkgs             B:126-141
    prefs = s1.C(this)
    if (accountType == "kid"):                                   B:127-134
        if (protectedPackages.isEmpty() || now - protectedPackagesAt > 60_000)
            protectedPackages = s1.J(this); protectedPackagesAt = now
        if (protectedPackages.contains(pkg)) return              // 60 s cache
    else:                                                        B:135-137
        if (pkg startsWith "com.android."
            || pkg == "com.google.android.permissioncontroller") return
    if (pkg == "com.sociallite.android") return                  B:139-141  (outside the else -> also for kids)

 3. RESOLVE platform for pkg                                   B:142-162
    if (kid)  allowed = c.X(prefs)?.b ?: ∅     // parent session package set
    else      allowed = prefs.blockedAppPackages ?: ∅
    if (BLOCKED_PACKAGES[pkg] != null) platform = that value
    else if (allowed.contains(pkg))    platform = "custom"
    else return                                                  // untracked app
       BLOCKED_PACKAGES (B:60): instagram; youtube; tiktok (musically, trill);
       snapchat; twitter (twitter.android, .lite, com.atebits.Tweetie2);
       facebook (katana, lite); linkedin.

 4. RESOLVE parent override a10: Boolean?                      B:163
    a10 = c.i(prefs, pkg) ? TRUE : y8.a(prefs, platform)
       c.i  -> parent app session, TRUE = force block   (§2.1)
       y8.a -> parent native control: TRUE/FALSE/null    (§2.2)

 5. RESOLVE exclusions                                         B:164-165
    inSleepAllow = prefs.sleepAllowedPackages?.contains(pkg) ?: false

 6. RESOLVE sleep window                                       B:166-171
    sleepActive = (kid) && c.e0(prefs, now, 0, 4).active
    sleepBlock(z7) = !sleepActive
                     || (kid && c.d(prefs, null, 6) != null && !inSleepAllow)

 7. GATE expired kid block (self-heal)                         B:172-180
    if (kid && !sleepBlock && blockExpiryMillis > 0 && now >= blockExpiryMillis):
        appBlockingEnabled = false; blockExpiryMillis = 0; commit; return

 8. GATE does blocking apply at all                            B:181-183
    if (!(kid || sleepActive || a10 == TRUE)) return
       => adults: enforcement only during the sleep window or forced by parent.
          No daily limits for adults.

 9. GATE parent allow beats sleep                              B:184 (test 1)
    if (sleepBlock && a10 == FALSE) return

10. GATE master switch                                         B:184 (test 2)
    if (!(!sleepBlock || a10 == TRUE || appBlockingEnabled)) return

11. GATE platform/package lock (f.b)                           B:185 (test 2)
    if (!(!sleepBlock || a10 == TRUE || f.b(prefs, platform, pkg))) return   // §2.3

12. POST-MODE timed unlock                                     B:186-197
    breakActive = contentBreakActive; breakEnd = contentBreakEndTime
    if (sleepBlock) cancelPostModeExpiry()
    if (kid && !sleepBlock && breakActive && now < breakEnd):
        if (postmode_<platform>) { schedulePostModeExpiry(breakEnd, pkg, platform); return }
    else if (breakActive && now >= breakEnd) cleanUpPostMode()

13. USAGE / DOWNTIME / LIMIT                                   B:199-300
    usageTracker.c()                       // refresh downtime flag + label
    if (usageTracker.f7382i)  blocked = TRUE, reason = "Downtime until HH:MM"
    else:
        kid     -> limitMin = getInt("<ig|yt|tt|tw|snap|fb|li|reddit>_daily_limit_minutes", 0)
        adult   -> limitMin = usageTracker.f7381h[platform.toLowerCase()]
        if (limitMin == null || limitMin <= 0
            || usageTracker.e(platform) < limitMin*60)  blocked = FALSE
        else blocked = TRUE, reason = "<Platform> limit reached for today"

14. DEDUPE 500 ms per package, in-memory                       B:309-313
    last = lastBlockedTimes[pkg]; if (now - (last?:0) < 500) return
    lastBlockedTimes[pkg] = now

15. REASON PRIORITY                                            B:314-326
    sleepBlock      -> "Sleep mode is active — go to bed."
    a10 == TRUE     -> getString(R.string.parent_native_block_reason)
    downtime        -> "Downtime is active"
    !blocked        -> getString(R.string.blocked_page_reason_default)
    reason == null  -> "Time limit reached"

16. SHOW                                                       B:327 -> B:407-415
    launchBlockOverlay(platform, reason, pkg, sleepBlock)
```

**Reachability summary**

| account | sleep window | a10 | outcome |
|---|---|---|---|
| free adult | — | — | gate 1 returns |
| paid/gift adult | off | null | gate 8 returns (no limits) |
| paid adult | on | null | sleep window decides; gates 11/13 can also block |
| kid | off | null | gates 11/13 decide (platform lock, downtime, daily limit) |
| kid | off | TRUE | forced block, reason = parent block string |
| kid | off | FALSE | gate 11 passes via `!sleepBlock`; `f.b`/downtime/limit still apply |
| kid | on | TRUE, pkg ∉ sleepAllowedPackages | block, "Sleep mode…" |
| kid | on | FALSE | gate 9 → allow |

Key asymmetry: **`a10 == FALSE` only suppresses the *sleep* block.** During the day a kid is still blocked by `block_<platform>`, `blockedAppPackages`, downtime, or a daily limit. `a10 == null` suppresses nothing.

---

## 2. The previously unproven gates

### 2.1 `c.i(prefs, pkg)` — parent app session (temporary unlock list)

`ie/c.java:852-865`:

```java
public static boolean i(x2 p, String pkg) {
    if (accountType != "kid") return false;
    w8 X = X(p);                            // ParentAppSession
    if (X == null || !X.a) return false;    // not active
    long until = X.c;
    return (until == 0 || now < until) && X.b.contains(pkg);
}
```

`ie/w8.java:9-20,36` → `ParentAppSession(active, packages:Set, untilMillis)`. Semantics: while a parent is using the kid's phone (parent-session mode with an allow-list + optional expiry), the listed packages are force-blocked (`a10 = TRUE`) and `y8.a` is not consulted. **Per-package**, honored only at gates 11/15 — sleep (gate 6/9) still wins if the session is expired or the sleep window is on.

### 2.2 `y8.a(prefs, platform)` — persistent parent native-block flag

`ie/y8.java:31-37`:

```java
public static Boolean a(SharedPreferences p, String platform) {
    if (accountType == "kid"
        && p.getBoolean("parent_native_controls_ready", false)
        && KNOWN.contains(platform))                    // ie/y8.java:20
        return p.getBoolean("parent_native_block_" + platform, false);
    return null;
}
```

`KNOWN` = instagram, youtube, tiktok, snapchat, twitter, facebook, linkedin (`ie/y8.java:20`). **"custom" is not in `KNOWN`**, so a custom package can only get `null`. Tri-state: TRUE block / FALSE allow (sleep only) / null no opinion. **Per-platform**, not per-package. `y8.b` (`y8.java:39-63`) is only the decoder for the parent push payload (`native_controls_version == 1`).

### 2.3 `f.b(prefs, platform, pkg)` — is this platform/package locked?

`ie/f.java:28-43`:

```java
if (platform.equals("custom")) return true;                                 // custom pkgs come from blockedAppPackages
if (prefs.getStringSet("blockedAppPackages", ∅).contains(pkg)) return true;  // explicit pkg beats platform
return prefs.getBoolean("block_" + platform, false);                        // platform switch
```

This is the all-day lock, independent of schedule/downtime/limits.

### 2.4 `ka.z0()` / `ka.G0()` — "blocker enabled"

- `ka.z0()` (`ie/ka.java:2266-2268`) = `accountType.equals("kid")`. No feature flag.
- `ka.G0()` (`ie/ka.java:360-378`):
  ```
  if (legacyFreeTikTokGrandfathered || S()) return true;   // S() = lifetimeProGiftConfirmedUser == userEmail, ka:769
  if (!z0()) {
      if (!H()) return false;                              // H(): internal email allowlist || (J() && sub not expired)
      if (email not in f7595h && tier != "pro" && tier != "parent") return false;
  }
  return true;
  ```
  `H()` `ie/ka.java:380-393`; `J()` `:462-467` = `legacyFreeTikTokGrandfathered || S() || K()`; `K()` `:485` = `hasPaid && !Q()` (Q = refund/revocation, UNVERIFIED). Net: kid always; adult needs paid/pro/parent, lifetime gift, or the legacy TikTok flag.

### 2.5 The 500 ms dedupe

`B:309-313`. `lastBlockedTimes` is an in-memory `LinkedHashMap<String,Long>` (`B:61`), never persisted, never pruned (bounded by tracked-package count). `now - (last ?: 0) < 500` → return; first block for a package has `last == null` → 0 → proceeds. Stops splash→main activity churn from stacking two overlays.

### 2.6 `launchBlockOverlay` Intent extras

`B:407-415`:

```java
Intent i = new Intent(this, BlockedAppActivity.class);
i.addFlags(335544320);            // 0x14000000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP
i.putExtra("platform",     str);   // arg1 -> "instagram" … "custom"
i.putExtra("reason",       str2);  // arg2 -> gate-15 string
i.putExtra("package_name", str3);  // arg3 -> blocked package
i.putExtra("sleep_block",  z3);    // arg4 -> z7 (true only for the sleep block)
startActivity(i);
```

Argument names are inverted vs the call `launchBlockOverlay(str2, str6, str, z7)` (`B:327`) — trust the extras, not the names. `sleep_block` is hardcoded `false` on the post-mode expiry path (`B:449`).

---

## 3. Usage / time-limit accounting

### 3.1 Own foreground stopwatch, not `UsageStatsManager`

`ie/gb.java`, singleton via `ie/kb.java:7`:

| field | meaning |
|---|---|
| `f7380g : ConcurrentHashMap<String,Integer>` | **seconds** used today, keyed by lowercased platform |
| `f7378e : volatile String` | foreground platform (lowercase), null when idle |
| `f7379f : volatile long` | timestamp of last checkpoint/entry |
| `f7381h : ConcurrentHashMap` | per-platform limits for non-kid (from server) |
| `f7382i / f7383j` | downtime flag / downtime label |

- `i(platform)` `gb.java:243-251` — enter: `g(); d();` then set `f7378e`, `f7379f = now`.
- `d()` `gb.java:179-206` — leave: add `(now - f7379f)/1000`, persist, `f7378e = null`, `f7379f = 0`.
- `a(gb)` `gb.java:89-118` — periodic checkpoint: same add, keeps session open (`f7379f = now`).
- `e(platform)` `gb.java:208-221` — read used by the gate: `map[platform] + liveSeconds(f7379f)`, side-effect free.
- `g()` `gb.java:232-244` — day rollover: if `usage_date != today (yyyy-MM-dd, Locale.US)` → snapshot into history (`bb.f7129c.I(ctx).c(date, map)`), `clear()`, `h()`.
- `h()` `gb.java:246-250` — persist `usage_today = "platform=seconds;…"` + `usage_date`.
- `f(map)` `gb.java:223-231` — merge-max from parent/server payload (`w7/m.java:2184`) — server can only raise counts.
- `k(map)` `gb.java:253-258` — install `f7381h` from `ka.w()` (`w7/m.java:2190`).

Divisor: `CredentialOption.PRIORITY_PASSWORD_OR_SIMILAR = 1000` (`androidx/credentials/CredentialOption.java:21`) → ms→seconds, integer division; `gb.java:96` skips ticks under 1 s. Granularity 1 s.

Who drives it: two Compose call sites call `gb.i(<platform enum>.name())` — `smali/smali/oe/x3.smali:88`, `smali/smali/he/g0.smali:659` — and `gb` installs a `ProcessLifecycleOwner` observer (`a8/a.java:480-486`, tag `[USAGE-LIFECYCLE-V2]`) that flushes/clears on background. So `f7380g` measures **time inside SocialLite's own platform screens**, and *that* is what gate 13 compares against the daily limit. The accessibility service never feeds it (no `usageTracker.i(...)` in `AppBlockerService`; only `gb.f7374k.a(this)` at `B:276`). Whether that is intended or a bug: it is what the code does.

`UsageStatsManager` appears exactly once: `ie/f7.java:99-155`, `f7.a(Calendar)` — the parent-dashboard screen-time report. `queryEvents(dayStart, min(dayStart+24h, now))` for the current day, pairs ACTIVITY_RESUMED(1)/ACTIVITY_PAUSED(2), **clamps each session to 3 h** (`clamp(dt,0,10_800_000)`, `f7.java:127,137`), maps pkg→platform via `NotificationBlockerService.f3375t`, sums seconds (`f7.java:150`), caches to `native_usage_history` (`f7.java:181`). Requires `PACKAGE_USAGE_STATS` (`f7.java:186-199`). **Not used by `enforcePackage`.**

### 3.2 Period

Today only: local calendar day, `SimpleDateFormat("yyyy-MM-dd", Locale.US)`; cleared on the first `g()` after midnight (constructor, `i()`, and the block path `B:200`). No rolling window, no weekly accumulation in `gb` (7/30-day data lives in the separate `f7` history).

### 3.3 Per-platform limit keys (gate 13)

Kid branch `B:212-281` maps `platform.toLowerCase()` → 2-letter code, then `getInt(code + "_daily_limit_minutes", 0)`:

| platform | key | unit | default |
|---|---|---|---|
| instagram | `ig_daily_limit_minutes` | minutes | 0 |
| youtube | `yt_daily_limit_minutes` | minutes | 0 |
| tiktok | `tt_daily_limit_minutes` | minutes | 0 |
| twitter | `tw_daily_limit_minutes` | minutes | 0 |
| snapchat | `snap_daily_limit_minutes` | minutes | 0 |
| facebook | `fb_daily_limit_minutes` | minutes | 0 |
| linkedin | `li_daily_limit_minutes` | minutes | 0 |
| reddit | `reddit_daily_limit_minutes` | minutes | 0 |
| other / `custom` | — | — | `null` → never limited |

`0`/absent = unlimited (`B:282` tests `<= 0`). Comparison converts minutes→seconds: `e(platform) < limitMin*60`.

`reddit` is a live switch case (`B:230-233`) but has **no** entry in `BLOCKED_PACKAGES` and is absent from `y8.KNOWN`; a reddit package only reaches here as `platform == "custom"`, which has no limit key. So `reddit_daily_limit_minutes` is effectively dead code (UNVERIFIED — only read site is `B:275`).

Non-kid branch `B:282`: `limitMin = gb.f7381h.get(lowercase platform)`, installed by `gb.k(ka.w())` from the server payload (`w7/m.java:2190`); same minutes×60 comparison. Only reachable for adults during sleep/override (gate 8).

### 3.4 Downtime (`f7382i`) vs sleep

`gb.c()` (`gb.java:145-177`) recomputes on every block evaluation:
- kid: `f7382i = c.e0(prefs, now, 0, 6).active`, `f7383j = "Downtime until " + end` (else `""`).
- adult: `f7382i = scheduleEnabled && now ∈ [schedule_start, schedule_end)` — same day/night window as "sleep", defaults `22:00`–`07:00`, wrap-around handled (`gb.java:167-174`).

Differences:
- Sleep (`sleepBlock`, gate 6) is kid-only, honors `sleepAllowedPackages` + parent session, and is the only reason emitting `sleep_block=true`.
- Downtime is an unconditional block: gate 13 sets `blocked = TRUE` with no limit test and no allowance set. The `"Downtime until HH:MM"` label is computed but gate 15 overwrites it with the generic `"Downtime is active"` (`B:320`).
- `c.e0` (`ie/c.java:676-…`) returns `e3(Window(enabled, start, end, active))` (`ie/e3.java:8-15`); kid with `scheduleEnabled == false` falls back to `parent_weekly_schedule` JSON (`c.java:715-730`).

### 3.5 Exactly at the limit

`B:282-300`: once `e(platform) >= limitMin*60`, `blocked = TRUE`, reason `"<Platform> limit reached for today"` (name capitalized by hand, `B:288-299`), shown at `B:327` with `sleep_block = false`. Nothing is written to prefs — the state is recomputed on the next window-state event, `lastBlockedTimes` throttles re-shows to one per 500 ms, and there is no sniff/snooze. Reset only at local midnight via `gb.g()`.

---

## 4. Prefs key table

`x2` = `ie/x2` wrapper; `f8174a` is the raw `SharedPreferences`. `gb` uses a second file, `"sociallite_usage"` (`gb.java:47`).

| key | type | default | effect |
|---|---|---|---|
| `accountType` | String | `"personal"` | `"kid"` enables gates 2, 6-9 and the kid limit branch. Read by `ka.p()` `ka.java:1926-1928` |
| `appBlockingEnabled` | bool | `false` | master native block (gate 10); `f.a()` also requires `blockExpiryMillis` unexpired (`f.java:17-26`) |
| `blockExpiryMillis` | long | `0` | kid self-heal gate 7: when past, `appBlockingEnabled=false` and return |
| `blockedAppPackages` | StringSet | `∅` (`ve/u.java`) | extra packages; source of platform `"custom"` (gate 3) and `f.b`'s pkg test |
| `sleepAllowedPackages` | StringSet | `∅` | kid packages exempt from the sleep block only (gate 5-6) |
| `sleepEnabled` | bool | `false` | written by `ka.S1` (`ka.java:779`); **not read** by `c.e0` |
| `scheduleEnabled` | bool | `false` | the real sleep/downtime switch: `c.java:695`, `gb.java:156` |
| `schedule_start` | String | `"22:00"` | window start `HH:mm` (`c.java:697`, `gb.java:161`) |
| `schedule_end` | String | `"07:00"` | window end `HH:mm` (`c.java:704`, `gb.java:163`) |
| `parent_weekly_schedule` | String (JSON array) | `"[]"` | kid per-weekday schedule used when `scheduleEnabled == false` (`c.java:717`) |
| `parent_native_controls_ready` | bool | `false` | gate 4: without it `y8.a` always returns null |
| `parent_native_block_<platform>` | bool | `false` | per-platform parent force-block → `a10 = TRUE` |
| `block_<platform>` | bool | `false` | all-day platform lock, `f.b` (`f.java:42`) |
| `postmode_<platform>` | bool | `false` | timed unlock during `contentBreakActive` (gate 12); platforms: instagram/youtube/tiktok/snapchat/twitter/facebook/linkedin (`B:81`) |
| `contentBreakActive` | bool | `false` | parent-granted timed break (gate 12) |
| `contentBreakEndTime` | long | `0` | break expiry; once past, `cleanUpPostMode()` clears every `postmode_*` (`B:79-83`) |
| `ig_daily_limit_minutes` … `li_daily_limit_minutes`, `reddit_daily_limit_minutes` | int | `0` | minutes/day, `0` = unlimited (gate 13, `B:275`) |
| `hasPaid` | bool | `false` | gate 1 via `ka.K()` (`ka.java:485`) |
| `legacyFreeTikTokGrandfathered` | bool | `false` | gate 1 override — enforcement on for free accounts (`ka.java:361,463`) |
| `lifetimeProGiftConfirmedUser` | String (email) | `null` | gate 1 via `ka.S()` (`ka.java:769-771`) |
| `subscriptionTier` | String | — | `"pro"`/`"parent"` bypass of the allowlist check in `G0()` |
| `userEmail` | String | `""` | allowlist compare in `H()`/`G0()` (`ka.java:371,382`) |
| `forceFreeModeDebug`, `debugProOverride` | bool | — | in the sleep-listener watch set, so toggling re-evaluates (`B:471`) |
| `hardcoreUnblockEnabled` | bool | `false` | `ka.G()` `ka.java:357` — effect on this path UNVERIFIED |
| `blocker_last_detected_failure` | String | `""` | `"stale_events"`/`"os_disabled"` triggers a health probe + `G.J0()` (`B:390-410`) |
| `blocker_last_event_at` | long | `0` | heartbeat, refreshed when older than `HEARTBEAT_INTERVAL_MS = 300000` (`B:52`) |

`sociallite_usage` file:

| key | type | default | effect |
|---|---|---|---|
| `usage_today` | String `"platform=sec;…"` | `null` | `gb` seconds-per-platform map (`gb.java:49-66`, written `:236`) |
| `usage_date` | String `yyyy-MM-dd` | `null` | day key; mismatch → snapshot + `clear()` (`gb.java:233-243`) |
| `native_usage_history` | String (JSON) | `"{}"` | per-day per-platform seconds from `f7` (UsageStatsManager), dashboard only (`f7.java:61,181`) |

---

## 5. Loose ends / unverified

- Tick interval of the periodic `gb` checkpointer (`gb.a`) and the exact lifecycle event that calls `gb.d()` — observer installed at `a8/a.java:480-486` via `d7.c`, event not traced. Only affects checkpoint granularity, not the total.
- `ka.w()` (`ka.java:2120`) — assumed `LinkedHashMap<platform, minutes>` installed into `f7381h`; keys not enumerated. `gb.e()` lowercases its argument, so keys must be lowercase platform names.
- `Q()` (refund/revocation predicate behind `K()`) not read.
- `c.d(prefs, null, 6)` (gate 6 parent-session validity) — same `ParentAppSession` source as `c.i`, exact flags not read.
- `s1.J(this)` (kid `protectedPackages`) not traced; it is the allow-list of apps the kid may use.
