"""0.3.0 item 1 — an app-mode change applies on the NEXT launch, with no app restart.

The 0.3.0 headline is instant settings: tapping a mode chip writes SharedPreferences,
every settings consumer re-reads on that write, and the engines see it immediately.
This scenario proves the observable contract on Instagram:

  Block  -> the next Instagram launch shows the block screen in <= 2 s (no FS restart)
  Allow  -> the next launch opens Instagram, engine takes no action
  Filter -> the next launch opens Instagram under the filter (no block)

Every selector is semantic; the only literal is the <= 2 s timing budget. State is
returned to Filter at the end, and the run ends on the Free Social hub.
"""

import time

from driver import by_text, by_texts, policy

FS_PKG = "dev.k3vinb5.freesocial"
IG_PKG = "com.instagram.android"

# Locale variants are the resource values in values/strings.xml + values-pt/strings.xml.
MODE_LABEL = by_texts("Mode", "Modo")  # section_mode
IG_ROW = by_text("Instagram")  # app_instagram (identical in both locales)
CHIP_BLOCK = by_texts("Block", "Bloquear")  # mode_block
CHIP_ALLOW = by_texts("Allow", "Permitir")  # mode_allow
CHIP_FILTER = by_texts("Filter", "Filtrar")  # mode_filter
GO_HOME = by_texts("Go home", "Ir para o início")  # block_go_home
# block_screen_title is "%1$s is blocked" / "%1$s está bloqueada".
BLOCKED_TITLE = by_texts("is blocked", "está bloqueada", match="contains")

# The user-facing promise: a mode tapped now is in force on the very next launch.
MAX_BLOCK_LATENCY_S = 2.0


def _top_activity(d):
    return d.shell("dumpsys", "activity", "activities")


def _open_ig_detail(d):
    """Free Social may resume on the hub, the detail screen or the inspector: go via the
    hub (the inspector also shows an "Instagram" chip, so a bare find is ambiguous)."""
    policy.to_hub(d)
    nodes = d.dump(retries=2)
    if not d.find(MODE_LABEL, nodes):
        d.tap(IG_ROW, nodes)
    d.wait_for(MODE_LABEL, timeout=10)


def _tap_chip(d, chip):
    _open_ig_detail(d)
    d.tap(chip)


def run(d):
    # --- Block: next launch is blocked, within the 2 s promise -----------------------
    _tap_chip(d, CHIP_BLOCK)
    started = time.monotonic()
    d.launch(IG_PKG, settle=0.0)
    deadline = started + 6.0
    latency = None
    while time.monotonic() < deadline:
        if "BlockActivity" in _top_activity(d):
            latency = time.monotonic() - started
            break
        time.sleep(0.2)
    if latency is None:
        raise AssertionError("block screen did not appear after the Block chip (6 s)")
    d.screencap("instant-block-screen")
    d.assert_visible(BLOCKED_TITLE, timeout=1)
    d.log(f"instant Block: block screen after {latency:.2f}s (budget {MAX_BLOCK_LATENCY_S}s)")
    if latency > MAX_BLOCK_LATENCY_S:
        raise AssertionError(
            f"block screen took {latency:.2f}s > {MAX_BLOCK_LATENCY_S}s after the tap"
        )

    # --- Allow: next launch opens, engine stays silent ------------------------------
    d.tap(GO_HOME)
    _tap_chip(d, CHIP_ALLOW)
    d.shell("logcat", "-c")
    d.launch(IG_PKG, settle=3.0)
    if IG_PKG not in d.current_package():
        raise AssertionError(f"Allow: Instagram did not come to the foreground ({d.current_package()!r})")
    if "BlockActivity" in _top_activity(d):
        raise AssertionError("Allow: the block screen still appeared (stale mode)")
    d.screencap("instant-allow-open")
    # The engine tells us on its next scan; poll instead of racing a single read.
    text = policy.wait_for_log(d, "is not filtered", timeout=10)
    excerpt = d.logcat_since("FilterService")
    if "neutralising" in text or "escape flash" in text:
        raise AssertionError(f"Allow: engine took an action ({excerpt.name})")
    if "is not filtered" not in text:
        raise AssertionError(f"Allow: the engine did not report the app as unfiltered ({excerpt.name})")
    d.log("instant Allow: Instagram foreground, engine reports 'not filtered', zero actions")

    # --- Filter: next launch behaves as filter (no block, engine scanning) ---------
    _tap_chip(d, CHIP_FILTER)
    d.shell("logcat", "-c")
    d.launch(IG_PKG, settle=3.0)
    if _top_activity(d).count("ContentFlashActivity"):
        d.wait(3.0)  # exhausted budget: let an escape flash pass, the mode is what is under test
    if IG_PKG not in d.current_package():
        raise AssertionError(f"Filter: Instagram not foreground ({d.current_package()!r})")
    if "BlockActivity" in _top_activity(d):
        raise AssertionError("Filter: the block screen appeared (mode not applied)")
    text = policy.wait_for_log(d, "scan com.instagram.android", timeout=10)
    excerpt = d.logcat_since("FilterService")
    if "com.instagram.android is not filtered" in text:
        raise AssertionError(f"Filter: the engine still treats Instagram as unfiltered ({excerpt.name})")
    if "scan com.instagram.android" not in text:
        raise AssertionError(f"Filter: no engine scan for Instagram after the launch ({excerpt.name})")
    d.screencap("instant-filter-open")
    d.log("instant Filter: Instagram opens under the filter, engine scanning, no block screen")

    # --- Leave the hub in front, Instagram back to Filter --------------------------
    d.launch(FS_PKG, settle=1.2)
    if d.find(MODE_LABEL):
        d.back()
        d.wait(1.0)
    d.assert_visible(by_text("Apps"), timeout=8)  # hub_apps_section (identical in both locales)
    d.screencap("final-hub")
    if FS_PKG not in d.current_package():
        raise AssertionError(f"expected Free Social hub, got {d.current_package()!r}")
