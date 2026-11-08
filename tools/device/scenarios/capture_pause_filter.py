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

from driver import DumpError, by_id, by_text, by_texts, policy

IG = "com.instagram.android"
CLIPS_TAB = by_id("com.instagram.android:id/clips_tab")
VIDEO_CELL = by_id("com.instagram.android:id/video_container")

# Locale variants are the resource values in values/strings.xml + values-pt/strings.xml.
INSPECTOR = by_texts("Surfaces & rules", "Superfícies e regras")  # hub_rule_inspector
APP_INSTAGRAM = by_text("Instagram")  # app_instagram (identical in both locales)
HOME_FEED = by_texts("Home feed", "Feed inicial")  # surface_name_home_feed
STEP_START = by_texts("Open app and capture", "Abrir a app e capturar")  # inspector_step_start
CAPTURE_DONE = by_texts("Capture complete", "Captura concluída", match="contains")  # inspector_capture_done


def _open_inspector(d):
    policy.to_hub(d)
    entry = INSPECTOR
    policy.scroll_until(d, entry)
    d.tap(entry)
    d.wait(1.5)
    d.tap(APP_INSTAGRAM)
    d.wait(1.0)
    row = HOME_FEED
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
    policy.ensure_ig_home(d)
    _open_inspector(d)
    d.screencap("inspector-before-capture")

    mark = policy.log_stamp(d)
    d.log(f"guided window starts at device clock {mark}")
    d.tap(HOME_FEED)  # row action = Capture nodes
    d.wait(2.0)

    try:
        step_card = d.find(STEP_START)
    except DumpError:
        # The target app is already in front and refusing a dump (a reel is playing):
        # notifications are on, so the capture armed itself — nothing to tap.
        step_card = None
        d.log("IG already in front; notifications are on, the capture is armed")
    if step_card:
        d.log("notifications are off; using the in-app step card instead")
        d.screencap("step-card")
        d.tap(step_card)
        d.wait(2.0)

    # The app launched Instagram for us; navigate while the arm is held. A reel already
    # playing refuses `uiautomator dump`; that is not a failure here — the capture's own
    # verified dump is the surface evidence.
    try:
        d.wait_for(CLIPS_TAB, timeout=10, recover=False)
    except Exception as exc:  # noqa: BLE001 - undumpable playing surface, reported below
        d.log(f"IG tree not dumpable on arrival ({exc}); continuing with the recording window")
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
    # Only the armed window must be action-free: the arm is released the moment the
    # capture completes, and an escape after that is correct product behaviour (the
    # scenario keeps driving Instagram until it reads the log).
    armed_until = finish[:17]  # "MM-DD HH:MM:SS.mmm" prefix, same shape as every log line
    actions = [
        line for line in policy.action_lines(engine)
        if not armed_until or line[:17] <= armed_until
    ]
    late = len(policy.action_lines(engine)) - len(actions)
    d.log(f"engine lines during the guided window: {len(engine)}; actions while armed: "
          f"{len(actions)}; after the capture finished: {late}")
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
    banner = CAPTURE_DONE
    try:
        d.wait_for(banner, timeout=10, recover=False)
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
