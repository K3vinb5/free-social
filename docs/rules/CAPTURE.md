# Guided node capture & runtime rule healing

The **Surfaces & rules** screen (Settings hub → *Surfaces & rules*) is a normal feature, not a
developer tool. It answers two questions: *which nodes is the app actually reading from this
screen?* and *which rules are in use, and where can I fix one after an app update?*

## Guided capture

1. Pick an app, then tap **Capture nodes** on a surface row (e.g. YouTube · Home feed).
2. If notifications are not allowed, Free Social asks once. If the grant is refused it shows an
   in-app step card instead (what to open, for how long, when it stops) — the capture still runs.
3. The target app is launched for you.
4. A persistent notification says
   `Recording <app> <surface> — scroll/do the thing ~15 s (auto-stops)` and offers **Stop now**.
5. Recording starts only when the surface is **detected**: the accessibility service reads the
   target app's windows and matches the surface's own markers from the rule catalog (for Home
   surfaces, a feed-row anchor that only exists on the feed). A Reels capture therefore cannot be
   polluted by the home screen. Every marker is verified against real device dumps by
   `CaptureSurfacesTest`, so a marker the app can never expose fails the build instead of the
   capture (D15).
6. The capture stops after 15 s of recording, when **Stop now** is tapped, or when the surface is
   left. If the surface is not detected within 30 s, the notification says so and **no dump is
   written**.
7. On completion: `Capture complete — N nodes`, and the dump appears in the inspector.

Filtering is paused for the whole flow by holding the `DumpArm` guided arm (detection + recording,
120 s ceiling); the service delegates the dump to the guided session instead of consuming the arm
itself. Enforcement resumes when the session ends.

## Surface-verified dumps

A dump header records `surface` only when that surface's markers matched:

* `"surface": "home"`, `"surfaceVerified": true` — the screen proved itself.
* `"screen": "unknown-surface"`, `"surfaceVerified": false`, `"markersSeen": ["viewId=…", "desc=…"]`
  — the markers did not match; the file says what was on screen instead.

The existing per-window header stats (`windows[]`, `nodeCount`, `nodesWithViewId`, `grafted`,
`truncated`) are unchanged.

Expanding a dump row runs the existing `RuleMatcher` offline over the exported nodes and lists every
**enabled** rule for that package that matches, with its anchor evidence (`viewId=…`, `text="…"`,
`desc="…"`, `class=…`, `selected=…`).

## Rule override (healing without a new APK)

The override file is:

```
filesDir/rules-override.json      # /data/data/dev.k3vinb5.freesocial/files/rules-override.json
```

It uses the **same JSON schema and the same loader validation** as the shipped
`app/src/main/assets/rules.json`, and **replaces** the built-in rules wholesale when valid.

* **Export rules** writes the current effective rules to that path and opens the system file picker
  so the same document can be saved/edited in any text app.
* **Import rules** opens a document, validates it, writes it to the override path and applies it.
  An invalid document changes nothing: the previous rules stay active and the validator's error is
  shown in the screen.
* On load (app start or on demand) an existing override is used. A validation failure falls back to
  the built-in asset and surfaces the error.

Applying an import drops the service's cached engine dependency, so the next accessibility event
recompiles the new rules — no app restart, no reinstall. If the service is not connected, the
override is picked up when it next connects.

## Rules section

Each rule row shows its id, app, surface, enabled/disabled state, and the app version it was last
verified against (the rule's own stamp, else the static map copied from `docs/rules/RULES.md`).

## Files

* `filter/GuidedCapture.kt` — session clock, launch, notifications, window polling, dump.
* `filter/CaptureSurfaces.kt` — surface registry + marker verification + seen-evidence.
* `filter/DumpAnalysis.kt` — offline matcher-over-dump + provenance stamps.
* `filter/RuleOverride.kt` / `filter/RuleCatalog.kt` — override validation/selection and live reload.
* `filter/DumpExporter.kt` — surface-verified dump fields.
* `filter/DumpArm.kt` — guided arm (filtering pause window).

## Capture pause (filtering *and* blocking)

While a capture owns the device, both enforcement engines stand down:

* **Filtering** — `FilterService` sees `DumpArm.isArmed()` and skips the gate (no escapes, no
  scroll, no taps) so the user can navigate freely.
* **Blocking** — `BlockService` asks `DumpArm.remainingMillis()`: while an arm is active it skips
  the gate and schedules one re-evaluation exactly at the arm's deadline (`CapturePauseSchedule`,
  D16), so the block screen comes back for the app the user is sitting on even when no further
  accessibility event arrives. Without that re-check the app stayed unblocked until the next
  window-state change.
  A Block-mode app would otherwise get the block screen laid over it mid-capture and the dump
  would record our own screen instead of the target surface.

The pause is the guided session (`DumpArm.GUIDED_EXPIRY_MS`; the old manual 60 s arm was removed in
D17). The guided arm is re-stamped by `DumpArm.refreshGuided(now)`
at the detection → recording transition, where `GuidedCapture.tick()` passes
`SystemClock.elapsedRealtime()` — and `DumpArm` keeps every arm in that same elapsed-realtime
domain (its default clock is `SystemClock.elapsedRealtime()`, injectable for tests). Mixing a
wall-clock stamp with that refresh used to mark the arm expired the moment recording started, so
blocking resumed mid-capture; the regression is pinned by
`DumpArmTest.the guided arm survives the detection to recording handover and expires after the window`
plus the source-level domain check `the guided session and the arm use one clock source`.

`BlockGate.decide` takes the state as a `dumpArmed` flag and returns `Ignore` before touching its
500 ms dedupe map, so blocking resumes at full strength the instant the arm is consumed, cancelled
or expires.

## Dump delete

The inspector's dump list has a **Delete dump** action per row (inside the expanded row) and a
**Delete all dumps** button under the list. Both are two-step: the tap only opens a confirm dialog,
and only its **Delete** button calls `DumpExporter.deleteOne(file)` / `DumpExporter.deleteAll(context)`.

This is user-triggered on top of `DumpPruner`, not a change to it: the pruner's 14-day / newest-25
policy still runs on every capture and list, and its "newest per package+surface" survivors remain
deletable on request. Delete all removes every parsable dump in the dumps directory and leaves any
other file there (foreign files, half-written JSON) untouched. The list reloads after a delete.
