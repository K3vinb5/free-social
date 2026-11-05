"""Guided capture on the IG Reels tab, then the dump's rule-match list shows ig_reels_tab.

Closes Wave 2's gap: the inspector's "rules that match this dump" section was
only ever exercised on a no-match dump. `ig_reels_tab` keys on
`com.instagram.android:id/clips_tab` + `selected=true`, so the capture must run
on the Reels surface (the surface itself is "Reels tab", whose markers are that
same rule).

Flow: inspector → Instagram → Reels tab → Capture nodes (the app launches IG,
filtering paused) → navigate to the Reels tab → recording completes → back in
the inspector open the newest dump → assert the analysis lists
`ig_reels_tab · reels_tab` and screenshot it.
"""

import re
import time

from driver import by_id, by_text, policy

CLIPS_TAB = by_id("com.instagram.android:id/clips_tab")
NODES_VALUE = by_text(r"^\d+ nodes$", match="regex")
MATCH_ROW = by_text("ig_reels_tab · reels_tab", match="contains")


def _open_inspector(d):
    policy.to_hub(d)
    entry = by_text("Surfaces & rules")
    policy.scroll_until(d, entry)
    d.tap(entry)
    d.wait(1.5)
    d.tap(by_text("Instagram"))
    d.wait(1.0)
    row = by_text("Reels tab")
    policy.scroll_until(d, row)
    d.assert_visible(row)


def _finish_line(d, mark):
    deadline = time.monotonic() + 70
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
    d.tap(by_text("Reels tab"))
    d.wait(2.0)

    step_card = by_text("Open app and capture")
    if d.find(step_card):
        d.log("notifications are off; using the in-app step card instead")
        d.tap(step_card)
        d.wait(2.0)

    # The app launched Instagram; get onto the Reels tab while the arm is held
    # (the engine must not escape this tap — filtering is paused).
    d.wait_for(CLIPS_TAB, timeout=25)
    d.screencap("ig-before-reels-during-capture")
    if not d.find_one(CLIPS_TAB).selected:
        d.tap(CLIPS_TAB)
        d.log("tapped the Reels tab inside the guided capture")
    d.wait(4.0)
    d.screencap("ig-reels-during-capture")

    finish = _finish_line(d, mark)
    d.log(f"capture finish line: {finish or 'NOT SEEN'}")

    engine = policy.log_lines(d, since=mark)
    policy.save_lines(d, "engine-window.log", engine)
    actions = policy.action_lines(engine)
    d.log(f"engine actions during the guided window: {len(actions)}")

    d.launch(policy.APP_PACKAGE, settle=2.0)
    try:
        d.wait_for(by_text("Capture complete", match="contains"), timeout=10)
        d.screencap("inspector-capture-complete")
    except Exception as exc:  # noqa: BLE001
        d.log(f"completion banner not visible: {exc}")

    dump_path = policy.newest_dump(d)
    document = policy.read_dump(d, dump_path) if dump_path else ""
    policy.save_lines(d, "newest-dump.json", document.splitlines())
    d.log(f"newest dump: {dump_path or 'none'}")

    # Open the newest dump row (first "N nodes" row = newest, list is sorted desc).
    if not policy.scroll_until(d, NODES_VALUE):
        raise AssertionError("no dump row in the inspector")
    d.tap(NODES_VALUE)
    d.wait(1.0)

    if not policy.scroll_until(d, MATCH_ROW):
        d.screencap("analysis-missing")
        raise AssertionError(
            "the dump analysis does not list 'ig_reels_tab · reels_tab'"
        )
    d.assert_visible(by_text("Rules that match this dump"))
    d.assert_visible(MATCH_ROW)
    d.screencap("analysis-match-list")

    if not finish:
        raise AssertionError("no 'guided capture' completion line in the window")
    if not re.search(r'"surfaceVerified"\s*:\s*true', document):
        raise AssertionError("the Reels-tab dump is not surface-verified")
    if not re.search(r'"surface"\s*:\s*"reels_tab"', document):
        raise AssertionError("the dump's surface is not 'reels_tab'")
    nodes = re.search(r'"nodeCount"\s*:\s*(\d+)', document)
    d.log(f"PASS: reels_tab dump ({nodes.group(1) if nodes else '?'} nodes) matches ig_reels_tab "
          f"in the inspector analysis; {len(actions)} engine actions while armed")
