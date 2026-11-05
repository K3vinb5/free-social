"""Guided capture pauses the engine for the whole flow (policy §2.1 rule 5).

Start a guided capture from Free Social's Rule Inspector ("Capture nodes" on
Instagram · Home feed — the app launches the target itself), navigate inside
Instagram for the 15 s recording window, and prove:

  * **zero** FilterService action lines across the entire guided window (arm +
    launch + navigation + recording), i.e. filtering was paused, not racing;
  * the capture reached "Capture complete — N nodes" and a surface-verified dump
    was written;
  * bonus evidence: one clean screenshot of the recording/complete notification
    in the shade panel (the previous smoke missed it).
"""

import re
import time

from driver import by_id, by_text, policy

IG = "com.instagram.android"
CLIPS_TAB = by_id("com.instagram.android:id/clips_tab")
VIDEO_CELL = by_id("com.instagram.android:id/video_container")


def _open_inspector(d):
    policy.to_hub(d)
    entry = by_text("Surfaces & rules")
    policy.scroll_until(d, entry)
    d.tap(entry)
    d.wait(1.5)
    d.tap(by_text("Instagram"))
    d.wait(1.0)
    row = by_text("Home feed")
    policy.scroll_until(d, row)
    d.assert_visible(row)


def _capture_complete_line(d, mark):
    deadline = time.monotonic() + 50
    while time.monotonic() < deadline:
        lines = policy.log_lines(d, since=mark, tags=(policy.ENGINE_TAG, policy.GUIDED_TAG))
        hits = [line for line in lines if "guided capture" in line]
        if hits:
            return hits[-1]
        d.wait(2.0)
    return ""


def run(d):
    _open_inspector(d)
    d.screencap("inspector-before-capture")

    mark = policy.log_stamp(d)
    d.log(f"guided window starts at device clock {mark}")
    d.tap(by_text("Home feed"))  # row action = Capture nodes
    d.wait(2.0)

    step_card = by_text("Open app and capture")
    if d.find(step_card):
        d.log("notifications are off; using the in-app step card instead")
        d.screencap("step-card")
        d.tap(step_card)
        d.wait(2.0)

    # The app launched Instagram for us; navigate while the arm is held.
    d.wait_for(CLIPS_TAB, timeout=25)
    d.screencap("ig-during-capture-arrived")
    d.swipe(direction="up", times=1)
    d.wait(2.0)
    d.screencap("ig-during-capture-scrolled")

    try:
        cell = d.find(VIDEO_CELL)
        if cell:
            d.tap(VIDEO_CELL)
            d.log("tapped a feed video cell (inline reel) while the capture was armed")
            d.wait(3.0)
            d.screencap("ig-during-capture-video")
    except Exception as exc:  # noqa: BLE001 - navigation is evidence, not an assert
        d.log(f"feed video-cell navigation skipped: {exc}")

    finish = _capture_complete_line(d, mark)
    d.log(f"capture finish line: {finish or 'NOT SEEN'}")

    engine = policy.log_lines(d, since=mark)
    policy.save_lines(d, "engine-window.log", engine)
    actions = policy.action_lines(engine)
    d.log(f"engine lines during the guided window: {len(engine)}; actions: {len(actions)}")
    if actions:
        policy.save_lines(d, "ACTION-LINES.log", actions)

    # Bonus evidence: the notification in the shade.
    d.shell("cmd", "statusbar", "expand-notifications")
    d.wait(1.5)
    d.screencap("notification-panel")
    d.shell("cmd", "statusbar", "collapse")
    d.wait(1.0)

    # Back to the inspector; the session banner should read "Capture complete".
    d.launch(policy.APP_PACKAGE, settle=2.0)
    banner = by_text("Capture complete", match="contains")
    try:
        d.wait_for(banner, timeout=10)
        d.screencap("inspector-capture-complete")
    except Exception as exc:  # noqa: BLE001 - reported with the finish line
        d.log(f"completion banner not visible: {exc}")

    dump_path = policy.newest_dump(d)
    d.log(f"newest dump: {dump_path or 'none'}")
    document = policy.read_dump(d, dump_path) if dump_path else ""
    policy.save_lines(d, "newest-dump.json", document.splitlines())

    if actions:
        raise AssertionError(
            f"engine acted during a guided capture ({len(actions)} action line(s)): {actions[:2]}"
        )
    if not finish:
        raise AssertionError("no 'guided capture' completion line in the window")
    if "verified=true" not in finish:
        raise AssertionError(f"dump was not surface-verified: {finish}")
    if not dump_path or not re.search(r'"surfaceVerified"\s*:\s*true', document):
        raise AssertionError("no surface-verified dump written for the capture")
    nodes = re.search(r'"nodeCount":\s*(\d+)', document)
    d.log(f"PASS: capture complete, verified dump with {nodes.group(1) if nodes else '?'} nodes, "
          "zero engine actions while armed")
