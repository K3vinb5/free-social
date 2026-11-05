# Semantic device driver

Playwright-style scenario runner for the real target apps, built only on `adb`
(no uiautomator2/appium). Every action resolves bounds from a **fresh**
`uiautomator dump` — scenarios contain no x/y coordinates.

```bash
uv run tools/device/drive.py list
uv run tools/device/drive.py run yt_home_shelf_visibility   # exit 0 PASS / 1 FAIL|WALL
uv run tools/device/drive.py run ig_reels_entry --device 192.168.50.20:37161
```
Default device: `192.168.50.20:37161` (override with `--device` or `$FREE_SOCIAL_DEVICE`).
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
