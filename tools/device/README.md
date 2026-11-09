# Semantic device driver

Playwright-style scenario runner for the real target apps, built only on `adb`
(no uiautomator2/appium). Every action resolves bounds from a **fresh**
`uiautomator dump` — scenarios contain no x/y coordinates.

```bash
uv run tools/device/drive.py list
uv run tools/device/drive.py run yt_home_shelf_visibility   # exit 0 PASS / 1 FAIL|WALL
uv run tools/device/drive.py run ig_reels_entry --device <serial>
```
Device target, in priority order: `--device <serial>`, `$FS_DEVICE_SERIAL`, the legacy
`$FREE_SOCIAL_DEVICE`, then `auto` — the first entry `adb devices` reports as `device`
(USB serial or `host:port` from `adb tcpip 5555`). Nothing is hardcoded, and a run with
no attached/authorised device fails fast with a `DriverError` instead of guessing.
Evidence: `reverse/runtime-reno/driver-<ts>/<scenario>/` — numbered PNGs, every
dump, `steps.log`, `summary.txt`, logcat excerpts, last-good/failure dumps.
All adb calls are pinned to one serial and bounded to 15 s.

## Primitives (`tools/device/driver/`)
- `dump(retries=2, save=False, label=None)` → parsed node list; playing-video
  "could not get idle state" gets 2 retries, then a screencap + `DumpError`
  (never acts on stale bounds).
- `find(sel)`, `find_one(sel)` — selector on `id` / `text` / `desc` / `cls`,
  exact | contains | regex (`by_id`, `by_text`, `by_desc`, `by_class`).
- `tap(sel)`, `long_press(sel)` — center of the node's runtime bounds.
- `swipe(direction=, container=, times=)`, or `from_selector`/`to_selector`.
  Without a container the gesture is confined to the screen minus the top 15 % and
  bottom 10 % (`device.content_rect`): a swipe starting in the top band is a
  notification-shade pull on ColorOS, not a scroll.
- `launch(pkg|component)`, `back()`, `home()`, `wait(sec)`.
- `wait_for(sel, timeout)` — polls; on timeout backs out once, retries once.
- `assert_visible(sel)`, `assert_gone(sel, after=sec)`.
- `screencap(name)`, `logcat_since(tag, marker=None)`.
- `app_settings_read(pkg)` — read-only `run-as ... cat shared_prefs/settings.xml` dict.
- Consent/login/permission walls are detected and abort with status `WALL`
  before any tap; password fields are never touched.

## Add a scenario (≤10 lines)
1. Create `tools/device/scenarios/my_flow.py`.
2. `from driver import by_id, by_desc` and name your selectors as module constants.
3. `def run(d):` — call primitives in order; `d.screencap("label")` for evidence.
4. Assert with `d.assert_visible(...)` / `raise AssertionError(...)`.
5. End by asserting the expected package (`d.current_package()`).
6. Run `uv run tools/device/drive.py run my_flow`; exit 0 means PASS.

## Locale & size independence

Scenarios must stay independent of screen size and phone language:

- **Size:** every tap/swipe resolves node bounds from the live dump at runtime;
  never store coordinates (grep scenarios for digit-heavy taps as a self-check).
- **Locale:** prefer resource-id / class selectors (locale-proof). String selectors
  (text/content-desc) carry a list of **observed** variants only — never guess a
  translation — and scenarios report `ABSENT_OR_LOCALE` when no variant matches.
  `by_texts("Mode", "Modo")` / `by_descs(...)` take one label per shipped locale; every
  variant is copied from `app/src/main/res/values/strings.xml` and
  `values-<locale>/strings.xml` and the selector line names the string key it came from.
  Matching folds diacritics (`Orçamento` also matches `Orcamento`), so a dump that drops
  accents needs no duplicate variant. UI copy from *other* apps (Instagram, YouTube,
  TikTok, DocumentsUI) is not in our resource tables: those lists stay observed-only,
  with the dump or run that observed them noted in the scenario.
- **Walls:** detection is deaccented and multilingual (EN/PT/ES/FR/DE markers) plus
  a structural password-field signal (`PASSWORD_FIELD_WORDS` on ids/hints); a wall
  aborts the scenario untapped (`WALL` status).
