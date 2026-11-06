"""0.3.0 item 0 — every tap on the Block chip must persist app_mode=<pkg>=block.

The wave saw two Block taps that left the pref untouched (Instagram opened
normally) while Allow/Filter applied and one earlier Block tap did block. This
scenario reproduces the sequence deliberately, three times, and separates the
three possible causes:

  (a) tap lands, pref unchanged  -> state-wiring bug
  (b) tap misses the chip        -> hit-target bug (bounds vs tap point recorded)
  (c) pref written, engine stale -> propagation bug in the settings seam

Each trial records the chip's uiautomator bounds, the tap point, the pref before
and after the tap, a screencap on both sides, and the next Instagram launch
(block screen or not) with the FilterService logcat excerpt. Trials interleave
Filter and Allow taps so the Block tap always changes the state, mirroring the
wave. State is restored to Filter and the run ends on the Free Social hub.
"""

import time

from driver import by_text

FS_PKG = "dev.k3vinb5.freesocial"
IG_PKG = "com.instagram.android"
MODE_KEY = f"app_mode_{IG_PKG}"

MODE_LABEL = by_text("Mode", match="exact")
IG_ROW = by_text("Instagram", match="exact")
CHIP_BLOCK = by_text("Block", match="exact")
CHIP_ALLOW = by_text("Allow", match="exact")
CHIP_FILTER = by_text("Filter", match="exact")
GO_HOME = by_text("Go home", match="exact")

TRIALS = (
    ("t1-filter-to-block", "filter"),
    ("t2-filter-via-chip-then-block", "filter"),
    ("t3-allow-via-chip-then-block", "allow"),
)

MAX_BLOCK_LATENCY_S = 6.0


def _open_ig_detail(d):
    """Free Social may resume on the hub or on the last detail screen."""
    d.launch(FS_PKG, settle=1.5)
    for _ in range(3):
        nodes = d.dump(retries=2)
        if d.find(MODE_LABEL, nodes):
            return
        if d.find(IG_ROW, nodes):
            d.tap(IG_ROW, nodes)
            d.wait_for(MODE_LABEL, timeout=10)
            return
        d.back()
        d.wait(0.8)
    raise AssertionError("could not reach the Instagram detail screen in Free Social")


def _read_mode(d):
    return d.app_settings_read(FS_PKG).get(MODE_KEY)


def _top_activity(d):
    return d.shell("dumpsys", "activity", "activities")


def run(d):
    _open_ig_detail(d)
    if _read_mode(d) != "filter":
        d.tap(CHIP_FILTER)
        d.wait(1.0)
        d.log(f"normalise: pref={_read_mode(d)!r}")

    for index, (label, pre_mode) in enumerate(TRIALS):
        if index > 0:
            _open_ig_detail(d)  # "Go home" leaves the app, not the detail screen
            chip = CHIP_FILTER if pre_mode == "filter" else CHIP_ALLOW
            d.tap(chip)
            d.wait(1.0)
            d.log(f"{label}: pre-tap {pre_mode} -> pref={_read_mode(d)!r}")

        nodes = d.dump(retries=2)
        chip = d.find_one(CHIP_BLOCK, nodes)
        before = _read_mode(d)
        d.screencap(f"{label}-before")
        d.save_dump(f"{label}-before.xml")
        d.log(
            f"{label}: Block chip bounds={chip.bounds} clickable={chip.clickable} "
            f"selected={chip.selected} pref_before={before!r}"
        )

        d.tap(CHIP_BLOCK, nodes)
        d.wait(1.2)
        after = _read_mode(d)
        d.screencap(f"{label}-after-tap")
        d.log(f"{label}: tap center={chip.center} pref_after={after!r}")
        if after != "block":
            d.save_dump(f"{label}-PREF-UNCHANGED.xml")
            raise AssertionError(
                f"{label}: Block tap left {MODE_KEY!r}={after!r} "
                f"(chip bounds={chip.bounds}, tap={chip.center})"
            )

        d.shell("logcat", "-c")
        started = time.monotonic()
        d.launch(IG_PKG, settle=0.0)
        latency = None
        while time.monotonic() < started + MAX_BLOCK_LATENCY_S:
            if "BlockActivity" in _top_activity(d):
                latency = time.monotonic() - started
                break
            time.sleep(0.2)
        d.screencap(f"{label}-ig-launch")
        d.logcat_since("FilterService")
        if latency is None:
            raise AssertionError(
                f"{label}: pref=block but Instagram launched with no block screen "
                f"(engine stale — propagation bug)"
            )
        d.log(f"{label}: block screen after {latency:.2f}s with pref=block")
        d.tap(GO_HOME)
        d.wait(1.0)

    _open_ig_detail(d)
    d.tap(CHIP_FILTER)
    d.wait(1.0)
    d.log(f"restore: pref={_read_mode(d)!r}")
    d.launch(FS_PKG, settle=1.2)
    if FS_PKG not in d.current_package():
        raise AssertionError(f"expected Free Social hub, got {d.current_package()!r}")
    d.screencap("final-hub")
