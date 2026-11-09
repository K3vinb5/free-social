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

from driver import DumpError, by_id, by_text, by_texts, policy

CLIPS_TAB = by_id("com.instagram.android:id/clips_tab")
# inspector_nodes_count: "%1$d nodes" / "%1$d nós"
NODES_VALUE = by_texts(r"^\d+ nodes$", r"^\d+ nós$", match="regex")
# Rule/surface ids (untranslated); separator_dot renders trimmed, so allow optional spaces.
MATCH_ROW = by_text(r"ig_reels_tab\s*·\s*reels_tab", match="regex")

# Locale variants are the resource values in values/strings.xml + values-pt/strings.xml.
INSPECTOR = by_texts("Surfaces & rules", "Superfícies e regras")  # hub_rule_inspector
APP_INSTAGRAM = by_text("Instagram")  # app_instagram (identical in both locales)
REELS_TAB = by_texts("Reels tab", "Separador Reels")  # surface_name_reels_tab
STEP_START = by_texts("Open app and capture", "Abrir a app e capturar")  # inspector_step_start
CAPTURE_DONE = by_texts("Capture complete", "Captura concluída", match="contains")  # inspector_capture_done
ANALYSIS = by_texts("Rules that match this dump", "Regras que correspondem a esta captura")  # inspector_analysis


def _open_inspector(d):
    policy.to_hub(d)
    entry = INSPECTOR
    policy.scroll_until(d, entry)
    d.tap(entry)
    d.wait(1.5)
    d.tap(APP_INSTAGRAM)
    d.wait(1.0)
    row = REELS_TAB
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
    policy.ensure_ig_home(d)
    _open_inspector(d)
    d.screencap("inspector-before-capture")

    mark = policy.log_stamp(d)
    d.log(f"guided window starts at device clock {mark}")
    d.tap(REELS_TAB)
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
        d.tap(step_card)
        d.wait(2.0)

    # The app launched Instagram; get onto the Reels tab while the arm is held
    # (the engine must not escape this tap — filtering is paused). A reel already
    # playing refuses `uiautomator dump`, and then the capture's own verified dump is
    # the surface evidence (same fallback as heal_from_dump / capture_pause_filter).
    try:
        d.wait_for(CLIPS_TAB, timeout=10, recover=False)
        d.screencap("ig-before-reels-during-capture")
        if not d.find_one(CLIPS_TAB).selected:
            d.tap(CLIPS_TAB)
            d.log("tapped the Reels tab inside the guided capture")
    except Exception as exc:  # noqa: BLE001 - undumpable playing surface, reported below
        d.log(f"IG tree not dumpable during the capture window ({exc}); "
              "the verified capture dump is the surface assertion")
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
        d.wait_for(CAPTURE_DONE, timeout=10, recover=False)
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
    d.assert_visible(ANALYSIS)
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
