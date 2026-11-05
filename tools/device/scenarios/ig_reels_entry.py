"""Instagram Reels entry from the bottom tab bar.

Selectors keyed on (from a live Reno dump):
  - Reels tab: com.instagram.android:id/clips_tab (resource-id; desc "Reels")

The flow taps Reels, waits 2 s, screenshots, and saves a FilterService logcat
excerpt. If Instagram is replaced/blocked/escaped, that is recorded as data,
not a failure — only a wall or a missing tab fails the run.
"""

from driver import DumpError, by_id, by_text

PKG = "com.instagram.android"
CLIPS_TAB = by_id("com.instagram.android:id/clips_tab")
# Observed home-feed tab strings only (pt-PT "Para ti"; EN "For You").
FEED_TAB_TEXTS = ("Para ti", "For You")


def run(d):
    d.launch(PKG, settle=2.0)
    d.wait_for(CLIPS_TAB, timeout=25)
    d.screencap("ig-home")
    tab = d.tap(CLIPS_TAB)
    d.log(f"tapped Reels tab at {tab.center} ({tab.label(50)})")

    d.wait(2.0)
    d.screencap("ig-after-reels-tap")
    d.logcat_since("FilterService")

    pkg = d.current_package()
    d.log(f"foreground package after Reels tap: {pkg or 'unknown'}")
    if pkg and "instagram" not in pkg.lower():
        d.log(f"ESCAPE/BLOCK: left Instagram (foreground={pkg!r}); recorded as data, not failure")
        d.screencap("ig-escaped-screen")
        return

    # Playing reels make uiautomator dump fail ("could not get idle state").
    try:
        nodes = d.dump(retries=1, save=True, label="ig-reels-dump.xml")
        selected = any(n.selected for n in d.find(CLIPS_TAB, nodes))
        if selected:
            d.log("REELS_REACHED: clips_tab is selected in the bottom bar")
        elif any(d.find(by_text(v, match="contains"), nodes) for v in FEED_TAB_TEXTS):
            d.log("ESCAPE/BLOCK recorded: clips_tab not selected; screen shows the "
                  "Home feed — data, not failure")
        else:
            d.log("REELS_STATE_UNKNOWN: clips_tab not selected; see ig-reels-dump.xml")
    except DumpError as exc:
        d.log(f"Reels hierarchy not dumpable (expected while a reel plays): {exc}")
    d.log("still in Instagram after Reels tap")
