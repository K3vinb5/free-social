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
   surfaces, a stable container id). A Reels capture therefore cannot be polluted by the home
   screen.
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
