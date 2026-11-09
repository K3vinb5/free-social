"""YouTube Home: passive feed scrolling must leave the engine completely silent.

Direct-engagement policy §2.1 rule 1 (2026-11-05): passively scrolling a feed
gets no engine action at all — no scroll, no redirect, no tap. This scenario
scrolls the YT Home feed three deliberate times and then proves two things from
a bounded logcat window:

  * the foreground app is still YouTube (nothing bounced the user out), and
  * **zero** FilterService action lines (SCROLL_PAST / neutralising /
    TAP_HOME_TAB / BACK dispatch) were emitted in the whole window. Scan lines
    are expected and fine.

Selectors are locale-proof ids: `results` is the Home feed container and exists only
on Home (D15: the old `tabs_bar` anchor never existed in any YT dump).
"""

from driver import by_id, policy

PKG = "com.google.android.youtube"
# Home feed container: present only on Home, absent from Shorts/watch (D15 anchor audit).
FEED = by_id("com.google.android.youtube:id/results")


def _ensure_home(d):
    for attempt in range(3):
        if d.find(FEED):
            return
        d.log(f"Home feed not visible (attempt {attempt + 1}); backing out once")
        d.back()
        d.wait(1.5)
    d.wait_for(FEED, timeout=15, recover=False)


def run(d):
    d.launch(PKG, settle=2.0)
    _ensure_home(d)
    d.wait_for(FEED, timeout=15)
    d.screencap("yt-home-before")

    mark = policy.log_stamp(d)
    d.log(f"action window starts at device clock {mark}")
    for i in range(3):
        d.swipe(container=FEED, direction="up", times=1)
        d.wait(2.5)
        d.screencap(f"yt-after-swipe-{i + 1}")

    lines = policy.log_lines(d, since=mark)
    policy.save_lines(d, "engine-window.log", lines)
    actions = policy.action_lines(lines)
    d.log(f"engine lines in window: {len(lines)}; action lines: {len(actions)}")
    if actions:
        policy.save_lines(d, "ACTION-LINES.log", actions)

    pkg = d.current_package()
    d.log(f"foreground after 3 passive swipes: {pkg or 'unknown'}")

    # Assertions after the window has been frozen, so the evidence dump cannot
    # influence what the window contains.
    if actions:
        raise AssertionError(
            f"{len(actions)} engine action line(s) during passive scrolling: "
            f"{actions[:2]}"
        )
    if pkg != PKG:
        raise AssertionError(f"left YouTube after passive scrolling (foreground={pkg!r})")
    d.assert_visible(FEED)
    d.screencap("yt-still-home-clean")
    d.log("PASS: passive feed scrolling produced zero engine actions")
