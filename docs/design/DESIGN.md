# Free Social — UI design brief (FigCraft)

UI toolkit: Jetpack Compose Material 3 (decided 2026-11-04). Design the app's own flows
BEFORE any UI code. Scope: our screens only — filtering behaviour inside
Instagram/YouTube/TikTok is implicit; never mock the target apps. Product semantics:
docs/SPEC.md §2.2–2.4.

## Tool recipe (validated 2026-11-04)

Drive FigCraft via the artcraft MCP `tools.artcraft.*`. Batch many `run_command` calls
inside one `execute` script — one script per frame.

- `page.add {name}` -> `page.select {id}` (page ids are integers).
- `render({app:"figcraft", page:<1-based index>, path})` — omitting `page` renders the
  WRONG page (caused a blank PNG once).
- `frame.create {x, y, width: 360, height: 800, name}` — Pixel 9a logical size; ignore
  presets. Children get `parent:<frameId>` and frame-relative coordinates.
- `shape.rectangle {x, y, width, height, parent, name, props:{cornerRadius:<n>}}`
  (radius 16 verified; use 999 for pills). `shape.ellipse {…}` likewise.
- `fill.set {ids:[id], color:"#RRGGBB"}` / `fill.add {ids:[id], color}` — fills stack
  bottom-first; a default #D9D9D9 may sit underneath (harmless). Set the FRAME's own fill
  to bg color too.
- `text.create {text, x, y, parent, name, props:{fontSize, fontFamily:"Roboto",
  fill:"#RRGGBB"}}` — `props.fill` works for text (verified).
- M3 switch: track rounded rect 52x32 (radius 16), thumb ellipse 24x24 inset 4 (left=off,
  right=on), track = primary when on, outline #453F4E when off.
- Verify each frame with `inspect_document` (absoluteBounds/fills/characters) and `render`
  + READ the PNG. No strokes unless necessary (stroke weight param unverified).
- Save: `save_file {path:"/home/kevin/Projects/free-social/docs/design/free-social-v1.fig"}`
  (fallback `file.export` format fig). Export every frame as PNG to
  `/home/kevin/Projects/free-social/docs/design/renders/NN-name.png`.

## Tokens (dark theme)

- bg #131218 · surface #1E1C24 · card #26232E · outline #453F4E
- text primary #ECEAF2 · secondary #B3AEC0 · tertiary #8E8899
- primary #FFB25C · on-primary #2B1B00 · primary-container #3A2A14
- success #86D9A0 · warning #FFD166 · danger #FF7A7A
- Type (Roboto): title 22/600 · headline 17/600 · body 15/400 · label 13/500 · caption 12/400
- Spacing 8-grid · screen pad 20 · card radius 20 (pad 16) · buttons h 52 radius 26 ·
  top bar h 64 (back arrow + title) · list row h 60 · divider 1px outline

## Frames (360x800; x = index*420 per page; frame fill = bg)

Page `01-onboarding`:
- **F1 Welcome** — title "Free Social", subtitle "Short-video, on your terms.", body
  "Reels, Shorts and TikTok clips get filtered out of the apps you already use. The apps
  stay yours.", primary button "Start setup", caption "No account. No cloud. Runs entirely
  on your phone."
- **F2 Restricted settings (1/5)** — title "Allow restricted settings", body "Android
  shields accessibility for sideloaded apps. In the app menu choose 'Allow restricted
  settings' first.", card list "1 Open app info  2 Tap the ⋮ menu  3 Allow restricted
  settings", primary "Open app settings", text button "Done — next".
- **F3 Filter service (2/5)** — title "Turn on content filtering", body "Free Social reads
  the screen of Instagram, YouTube and TikTok to hide reels and shorts. Nothing else.",
  chip "Recommended", row "Filter service · Waiting…", primary "Open accessibility settings".
- **F4 Block service (3/5)** — title "Whole-app blocking (optional)", body "Want an app
  blocked entirely? Enable the second service. Filtering works without it.",
  secondary "Enable block service", text button "Skip for now".
- **F5 Last permissions (4/5)** — title "Last two permissions", rows "Display over other
  apps · for the block screen" / "Ignore battery optimizations · so filtering survives"
  each with secondary "Allow", primary "Finish setup".

Page `02-settings`:
- **F6 Settings home** — top bar "Free Social" + status pill "Filtering on" (success);
  card row "Filtering" master switch ON; section "Apps": Instagram / YouTube / TikTok rows
  each with state chip "Filtering" + chevron; rows "Reels & Shorts budget · 13 min left",
  "Schedule · On · 22:00–07:30", "Permissions · 3 of 4"; caption "Rules verified · IG 454
  · YT 21.43 · TT 47.6"; dev row "Rule inspector".
- **F7 Instagram detail** — back+"Instagram"; segmented [Filter | Block | Allow] Filter
  selected (primary fill); card toggles "Hide reel cells in feed" ON, "Hide Reels tab" ON;
  info card "Opening a reel on purpose? It plays — and counts against your daily budget.";
  caption "Rules verified for Instagram 454.0.0.54".
- **F8 YouTube detail** — toggles "Hide Shorts shelf in Home" ON, "Shorts player counts
  against budget" ON; info "When the budget runs out, Shorts exit automatically."
- **F9 TikTok detail** — toggles "Videos count against budget" ON, "Back out when budget
  is spent" ON; info "Direct messages are never touched."

Page `03-budget`:
- **F10 Reels & Shorts budget** — top bar title; big ring (ellipse outline #453F4E +
  partial arc effect via a primary-colored ellipse behind mask-or-skip; acceptable: two
  concentric ellipses + big number) "13" + "min left today"; stepper "Daily allowance"
  [−] "20 min" [+]; card "Counts Instagram reels, YouTube Shorts and TikTok playback
  together. Resets at midnight."; caption "During locked schedule hours the budget counts
  as spent."

Page `04-schedule`:
- **F11 Schedule** — top bar "Schedule"; master row "Nightly lock" ON; 7 day rows
  Mon–Sun each with two chips bedtime→wake ("22:30 → 07:00", Friday "23:30 → 08:00");
  steppers "Wind-down 30 min" / "Morning lock 30 min"; preview pill (primary-container)
  "Locked 22:00–07:30 tonight".

Page `05-permissions`:
- **F12 Permissions** — rows with status dots + trailing secondary "Open settings":
  "Content filter service · On" (success), "Block service · Off, optional" (tertiary),
  "Display over other apps · Needed" (warning), "Battery optimization · Allowed"
  (success); banner (warning-tinted card) "Android 13+: if a toggle won't enable, allow
  restricted settings first."

Page `06-block`:
- **F13 Block screen** — full-bleed bg, centered card: small danger dot, headline
  "Instagram is blocked", body "Focus time — come back at 07:30.", primary "Go home",
  caption "Blocked by Free Social".

Page `07-dev`:
- **F14 Rule inspector** — top bar "Rule inspector"; chips [Instagram | YouTube | TikTok];
  list rows "IG · home feed · 152 nodes", "IG · reels viewer", "YT · shorts player · 74
  nodes", "TT · playback" each with chevron; FAB (primary circle, bottom-right) "↓";
  caption "Dump a screen after an app update, then patch rules.json."

Page `00-flow` — flowchart only (no phone frames): rounded boxes (280x110, card fill,
label text) + arrows (shape.arrow): "Welcome → Restricted settings → Filter service →
Block service (optional) → Permissions → Settings hub"; hub arrows to "App detail (x3)",
"Budget", "Schedule", "Permissions", "Rule inspector"; separate box "Block screen" with
note "shown when a blocked app opens". Use primary #FFB25C for arrows.
