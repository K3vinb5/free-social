# SocialLite `WebBlockerVpnService` — reverse-engineering notes

Target: `com.sociallite.android.data.WebBlockerVpnService`
(`/tmp/opencode/dec/jadx/sources/com/sociallite/android/data/WebBlockerVpnService.java`, 379 lines, fully decompiled).
TUN reader recovered separately with jadx `--show-bad-code` into `/tmp/opencode/rd3/heg.java` (jadx had failed on `he.g.invoke()`); line refs to that file are marked `heg:`.

---

## 1. Mechanism — it is a DNS proxy, not an IP stack

**Verdict up front: there is no userspace IP stack.** No TCP state machine, no NAT, no flow table, no ICMP, no IPv6, no default route. One `FileInputStream` on the TUN fd is read in a loop, only IPv4/UDP/dst-port-53 packets are looked at, everything else is silently dropped, and only DNS replies are ever written back.

### Tunnel configuration (`WebBlockerVpnService.java:356`)

```java
new VpnService.Builder(this)
  .setSession(getString(R.string.web_blocker_notif_title))
  .addAddress("10.111.222.1", 24)          // local tunnel address
  .addDnsServer("10.111.222.2")            // the "resolver" apps are told to use
  .addRoute("10.111.222.2", 32)            // <-- the ONLY route: a /32 host route
  .setMtu(1500)
```

The single `/32` route is the whole design: **nothing except packets addressed to the fake DNS server 10.111.222.2 ever enters the tunnel.** All other traffic keeps using the physical interface untouched, so there is nothing to forward — hence no need for a stack. MTU is set but essentially decorative (DNS queries are <512 B).

- `establish()` at `:363`; on `null` it logs and `stopSelf()`s (`:364-366`).
- Reader thread: `new b(new g(this, 9, establish), 2)` at `:370` → `oa/b.java:102` (`default: ((g) this.f11071u).invoke()`) → `he/g.invoke()` case `9`.

### The read loop (`heg.java:146-182`)

```java
FileInputStream  in  = new FileInputStream(pfd.getFileDescriptor());   // heg:150
FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());  // heg:151
ByteBuffer buf = ByteBuffer.allocate(32767);                          // heg:152
while (svc.f3381t) {                                                  // heg:153
    int read = in.read(buf.array());                                  // heg:157
    if (read > 0) svc.d(buf, read, out);                              // heg:160
}
```

One packet at a time, 32 KB buffer, no batching, no poll/select on both directions (writes happen from the answer path, guarded by `synchronized (fileOutputStream)` at `:258`).

### Packet parsing (`WebBlockerVpnService.d()`, `:142-282`)

Only this shape is accepted, everything else `return`s:

| Check | Line | Meaning |
|---|---|---|
| `len >= 28` | `:147` | IPv4(20)+UDP(8) minimum |
| `(b & 0xF0) >>> 4 == 4` | `:153` | IPv4 **only** — IPv6 dropped |
| `ihl = (b & 0x0F)*4 >= 20` | `:153` | header length |
| `array[9] == 17` | `:153` | **UDP only** — TCP/ICMP dropped |
| dst port `== 53` | `:156` | DNS only |

Question name is parsed inline at `:159-185`, **not** via a real DNS library:

- manual label walk, max 63 labels, max 255-ish bytes;
- compression pointers (`b12 & 0xC0 != 0`) are rejected → `break` → `str == null` → packet dropped (`:170-171`);
- labels are appended as raw chars, so punycode/UTF-8 names are mangled (unverified how badly — IDN labels would be non-ASCII bytes cast to `char`);
- `www.` is stripped (`:191`), name lowercased, then `ib.c(name, blockedSet)` decides.

No EDNS0/OPT handling, no TC bit handling, no support for multiple questions, no ID rewriting (unnecessary — one socket per query).

### Blocked path — built by hand, `a()` at `:62-95`

`a(pkt, len, ipOff, ipOff, udpOff)` returns a copy of the original packet with:

- src/dst IPv4 swapped (offsets are **hard-coded** 12..15 ↔ 16..19, `:64-70` — fine for IHL=20, wrong if IP options were ever present);
- IP header checksum recomputed (`:71-75`, `b()` at `:97-111` is a standard one's-complement sum);
- UDP src/dst ports swapped (`:76-82`), UDP checksum zeroed (`:83-84`);
- **flags byte set to `0x83`** (`:87`, `copyOf[i12+3] = -125`), QR bit set (`:86`), and **ANCOUNT/NSCOUNT/ARCOUNT all zeroed** (`:88-93`).

`0x83` = `RA | RD | rcode=3` → **NXDOMAIN**, authoritative-less, with zero answer records. IP total-length is not rewritten but doesn't need to be (same length as the query).

So a blocked lookup gets a clean, well-formed **NXDOMAIN**. No 0.0.0.0 sinkhole, no HTML block page, no TCP reset. That is deliberate and cheap: no answer parsing, no TTL to manage.

### Allowed path — plaintext UDP forwarding to a hard-coded Google resolver (`:207-275`)

```java
DatagramSocket s = new DatagramSocket();
if (!protect(s)) { /* warn + abort */ }        // :216-220 — mandatory, else loop
s.setSoTimeout(5000);                          // :221
s.send(query payload -> 8.8.8.8:53);           // :222  <-- hard-coded
s.receive(2048-byte buffer);                   // :223-225
// rebuild IPv4/UDP reply in place: swap IPs/ports, fix lengths+checksums,
synchronized (out) { out.write(reply); }       // :258-260
```

- Runs on a fixed pool of 8 threads (`:54`) so a slow upstream doesn't block the read loop.
- **`protect(socket)` is load-bearing** — without it the forwarded query would re-enter its own tunnel and loop forever. Failure is handled (`:217`) but only by dropping the query.
- **No DoH, no DoT, no caching, no proxy.** Every allowed query is a fresh plaintext UDP round-trip to **8.8.8.8:53**, regardless of what resolver the user/network actually configured.
- Upstream answers larger than 2048 B are truncated by the receive buffer (`:223`); no TC bit is set in the reply, so the client just sees a malformed/short answer and fails. TCP fallback is impossible (TCP is never routed).

### Lifecycle

- `onStartCommand` (`:317`): **refuses to run for kid accounts** (`accountType == "kid"` → `c(); stopSelf(); return 2`, `:318-322`). Also refuses on action `com.sociallite.android.WEB_BLOCKER_STOP` (`:324-328`).
- Guarded by `f3381t` so repeated starts don't build a second tunnel (`:349`).
- `onRevoke` (`:302-313`) clears the `webBlockerEnabled` pref and stops itself — correct handling of "user killed the VPN from system settings".
- `onDestroy` unregisters the pref listener and calls `c()` (`:293-299`).
- Pref listener (`ie/i.java:51-82`): any change to a `webBlocker*` key recomputes the blocked set live (no tunnel rebuild needed, it just swaps `f3385x`); setting `webBlockerEnabled=false` sends the STOP intent.

---

## 2. Where the block list comes from — hard-coded, ~104 domains, no network

**No asset file, no remote fetch, no download endpoint.** The list is compiled-in Kotlin constants in `ie/ib.java` (`HashSet` literals), plus a user-supplied custom list from SharedPreferences.

`ie/ib.java:15` also defines a stale baseline `f7505a` (6 domains) that is only used as a "did the set change" sentinel/reference — the real set is `ib.b(ka)` at `:65-87`:

| Set | Line | Count | Contents (shape) |
|---|---|---|---|
| social (`f7508e`) | `:43` | 16 | instagram, facebook, youtube, tiktok, snapchat, messenger, twitter, **x.com**, reddit, threads, pinterest, linkedin, discord, bereal, vsco, tumblr |
| adult (`b`) | `:37` | 25 | pornhub, xvideos, xnxx, redtube, youporn, xhamster, spankbang, eporner, chaturbate, stripchat, livejasmin, cam4, bongacams, onlyfans, manyvids, fansly, clips4sale, brazzers, naughtyamerica, bangbros, realitykings, adulttime, erome, motherless, porn.com |
| gambling (`f7506c`) | `:39` | 24 | pokerstars, 888casino/888poker, casino.com, bet365, betmgm, unibet, partycasino, leovegas, royalpanda, casumo, mrgreen, jackpotcity, spincasino, planet7casino, bovada.lv, ignitioncasino.eu, cafecasino.lv, wildcasino.ag, slotocash.im, goldenlionwins, megaways.casino, stake.com, roobet.com |
| gaming (`f7507d`) | `:41` | 21 | roblox, miniclip, kongregate, poki, y8, crazygames, friv, armorgames, newgrounds, addictinggames, coolmathgames, agame, gamehouse, kizi, silvergames, 1001games, gamefaqs, twitch, kick, chess.com, lichess |
| sports betting (`f7509f`) | `:45` | 22 | draftkings, fanduel, bet365, betmgm, caesars, williamhill, sportingbet, betway, pinnacle, sportsbetting.ag, bovada.lv, mybookie.ag, betus.com.pa, unibet, bwin, ladbrokes, paddypower, skybet, coral.co.uk, betfred, prizepicks, underdogfantasy |

Total ≈ **108 entries, ~104 unique** (bet365, betmgm, unibet, bovada.lv appear in two presets). Presets are independent booleans (`webBlockerPresetSocial/Adult/Gambling/Gaming/SportsBetting`, `ib.java:69-84`); union is taken, then the user's custom domains are added (`:85`, pref list via `ka.t0()`). The social preset additionally *subtracts* a per-app allowlist (`:71 hashSet.removeAll(ka.v0())`).

Second list `f7511h` (`:48`, 16 social domains) is **not** used for blocking — it is only passed to the Compose UI as the "suggested / known platforms" strip (`oe/tq.java:619`), which drives the "Also blocking your SocialLite tab?" warning dialog (`strings.xml:1718`).

Matching (`ib.c`, `:89-114`) is suffix-based, not exact: it strips a leading `www.`, then walks up to 8 labels removing the leftmost each time, so `m.instagram.com` and `cdn.tiktok.com` match. No wildcard regex, no path matching, no substring/typo-squat matching (`instgram.com` is *not* caught).

Grep of `assets/` turned up only `kid_web_controls.js` / `kid_facebook_controls.js` (removed-content manifests for the WebView, unrelated to the VPN list). No bundled blocklist asset, no `raw/` list, no domains in `res/`.

---

## 3. Bypass surface — wide

The `/32` route plus DNS-only handling makes this very easy to walk around:

1. **IP literals / cached answers** — the filter only sees name resolution. Any pinned IP, previously cached A record, or app shipping its own IP list connects fine.
2. **DoH / DoT** — nothing but dst-port 53 is routed. Chrome's Secure DNS (on by default in many regions), Firefox DoH, Android *Private DNS* (DoT :853), and any app with a baked-in DoH endpoint (Cloudflare/Google/NextDNS JSON API over 443) bypass completely. **This is the biggest hole.**
3. **Any hard-coded resolver** — only `10.111.222.2` is routed, so an app using `8.8.8.8` directly (or a v6 resolver) leaves the tunnel immediately.
4. **IPv6** — the version nibble check drops anything non-IPv4 (`:153`) and no AAAA/v6 address or route is added, so IPv6-only / v6-preferred resolvers are untouched.
5. **TCP DNS** — dropped silently; a client that gets a truncated/mangled reply and retries over TCP gets nowhere (fails closed, at least).
6. **Second VPN already active** — Android allows one VPN; `establish()` returns `null` → service logs and stops (`:364-366`), with only the persistent notification to hint at failure.
7. **Own app is excluded** — `addDisallowedApplication(getPackageName())` at `:359`, wrapped in try/catch. So SocialLite's own WebView resolves normally and its tab keeps working even when `instagram.com` is blocked. No `addAllowedApplication` calls at all → the filter is **device-wide** for every other installed app.
8. **Captive portals / split-horizon DNS** break, because every allowed query is forced to 8.8.8.8 rather than the network's resolver (`:222`) — a side effect worth knowing.
9. **Kid mode disables it entirely** (`:318`) — the service won't run at all when `accountType == "kid"`, which is arguably backwards for a parental-control feature. (The kid path presumably relies on the remote/supervision side instead — outside this file's scope.)

---

## 4. Foreground service / enablement

- Manifest: `AndroidManifest.xml:164` — `android:permission="android.permission.BIND_VPN_SERVICE"`, `exported=false`, `foregroundServiceType="specialUse"`, intent-filter `android.net.VpnService` (`:165-167`).
- Channel `sl_web_blocker`, importance **2 (LOW)** (`:331`), persistent notification id **4711** (`:345`/`:347`), `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on API 34+ (`:344-345`). Content: "SocialLite Web Blocking" / "Filtering blocked sites on this device." (`strings.xml:1717`, `:1716`), tap → app launch intent (`:334`).
- **Enablement flow**: two identical paths, both Compose lambdas — `he/a0.java:143-160` and `he/g0.java:93-104`:
  ```
  Intent prepare = VpnService.prepare(context);
  if (prepare != null) { launch consent activity; }        // user grants
  else { ka.d2(true); startForegroundService(WEB_BLOCKER_START); }
  ```
  i.e. the standard consent dance; `webBlockerEnabled=true` (`ka.java:1089-1092`) is only written *after* consent is granted, and that pref write is what the service's own listener reacts to. UI surface is the Compose "Web Blocking" row (`strings.xml:207`, subtitle "Filtering DNS for %1$d domains", `:205`) in the settings screen rendered from `oe/tq.java` (resource IDs are R8-inlined as int literals, so the exact composable symbol is not greppable — **UNVERIFIED** which composable function owns the row).
- Preset toggles and the auto-disable guard live in `oe/tq.java:1346-1430` (`p()`, `q()`, `r()`); `p()` turns `webBlockerEnabled` off when the computed set becomes empty.
- **No boot restart.** `BootReceiver` (manifest `:178-184`) never touches the VPN; grepping `Vpn|WebBlocker|WEB_BLOCKER` in `com/sociallite/android/sleep/BootReceiver.java` and `AppBlockerHealthWorker` returns nothing. After a reboot the VPN stays off until the user re-toggles (and re-consents — Android usually remembers consent, so this is one tap). **No self-restart** except surviving `onStartCommand` re-delivery while already running.

---

## 5. Verdict

**What it buys:** a device-wide DNS blackhole for ~104 hard-coded social/porn/gambling/gaming domains, with zero server infrastructure and zero privacy leakage to the vendor — but every *allowed* query is leaked in plaintext to `8.8.8.8`. It stops casual in-app browsing to those exact domains on apps that use the system resolver, and it's cheap: ~380 lines, one route, no stack.

**What it does not buy:** it is not a network filter. DoH (which Chrome enables by default), IP literals, cached DNS, IPv6 resolvers, and any app with a hard-coded resolver all bypass it completely. It also can't block by app or by path, has no reporting, breaks captive portals, and refuses to run in kid mode. As a "focus" product this is roughly a speed bump — the kind a technical user steps over in one setting change, and even a non-technical one steps over because Chrome's Secure DNS is on by default.

**Should a simplified focus/blocker app reproduce it?** No — not this implementation. Ranked alternatives:

1. **Reuse what they already have**: `AppBlockerService` (accessibility-based per-app blocking) is the durable mechanism; blocking apps by package is unfakeable by DoH.
2. If DNS filtering is wanted, do **Private DNS / DoT to a filtering resolver** (or a local `VpnService` that routes *all* traffic and speaks DoH upstream) instead of a `/32`-route plaintext UDP proxy — otherwise you inherit every bypass above.
3. If a VPN DNS filter is still desired, the minimal correct version is: route `0.0.0.0/0` + `::/0`, handle TCP/53 at least by refusing, forward over **DoH**, respect the network's resolver for non-blocked names, add IPv6, and set the TC bit when truncating. That's an IP stack's worth of work for something a per-app blocker already solves.
4. The one genuinely reusable idea: use **NXDOMAIN** rather than a sinkhole IP — it's the cheapest correct answer, needs no answer-record construction, and can't be defeated by an app picking up a bogus A record.

**Bug/robustness notes if any of this is copied:** hard-coded IP offsets in `a()` assume IHL=20 (`:64-70`); compression pointers in the question are dropped rather than followed (`:170`); upstream buffer of 2048 with no TC bit (`:223`); `protect()` failure silently blackholes the query (`:217`); 8 threads with 5 s timeouts can pile up under a query storm.
