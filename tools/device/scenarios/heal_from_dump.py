"""Heal-from-dump, end to end: capture → analyse → break → heal → revert.

The scenario drives the documented override loop from `docs/rules/CAPTURE.md` on a real
device and proves the two properties the feature exists for:

* an imported override takes effect **live** (engine cache drop, the inspector's
  "rules that match this dump" changes for the *same* dump file, no restart), and
* healing is a **file** feature — a corrected anchor is imported from the picker and the
  rule matches again with no APK rebuild and no process restart.

Steps (all semantic selectors; no coordinates are ever stored):

1. capture the IG "Reels tab" surface and assert the dump is surface-verified;
2. open that dump and assert `ig_reels_tab · reels_tab` is listed;
3. Export rules (which publishes the override), break the `clips_tab` anchor in the
   exported document, import it from the documents picker, and assert the rule stops
   matching the same dump + the service logged the cache drop;
4. import the corrected document and assert the rule matches again, same process;
5. tap "Use built-in rules", confirm, and assert the override file is gone and the
   screen reads "Built-in rules".

Evidence: `reverse/runtime-reno/driver-<ts>/heal_from_dump/` (numbered PNGs, steps.log,
the three override documents, the dump the analysis ran on).
"""

import base64
import json
import re
import time

from driver import by_desc, by_id, by_text, policy

FS = policy.APP_PACKAGE
IG = "com.instagram.android"
OVERRIDE_REMOTE = "files/rules-override.json"
DOWNLOAD = "/sdcard/Download"
BROKEN_ANCHOR = f"{IG}:id/clips_tab_gone"

HUB = by_text("Reels & Shorts budget")
INSPECTOR = by_text("Surfaces & rules")
INSTAGRAM_CHIP = by_text("Instagram")
REELS_SURFACE = by_text("Reels tab")
CAPTURE_DONE = by_text("Capture complete", match="contains")
NODES_ROW = by_text(r"^\d+ nodes$", match="regex")
ANALYSIS = by_text("Rules that match this dump")
REELS_MATCH = by_text("ig_reels_tab · reels_tab", match="contains")
NO_MATCH = by_text("No rule matches this dump", match="contains")
REVERT = by_text("Use built-in rules")
OVERRIDE_SOURCE = by_text("Your override (rules-override.json)", match="contains")
BUILTIN_SOURCE = by_text("Built-in rules", match="exact")
IMPORT = by_text("Import rules")
EXPORT = by_text("Export rules")

# The documents picker is a system app; these are the observed EN/PT labels only. The
# roots toggle is an icon button (content-desc), the root entries are drawer titles.
ROOTS_LABELS = ("Show roots", "Mostrar raízes")
DOWNLOAD_LABELS = ("Download", "Transferir", "Transferências")


def _scroll_back_until(d, selector, max_swipes=10, pause=0.8):
    """Swipe towards the top of the page until `selector` is visible.

    The "Rules in use" block sits *above* the Export/Import row, and `policy.scroll_until`
    only swipes forwards, so a check made from the buttons needs the opposite direction.
    """
    for _ in range(max_swipes + 1):
        if d.find(selector):
            return True
        d.swipe(direction="down")
        d.wait(pause)
    return bool(d.find(selector))


def _open_inspector(d):
    policy.to_hub(d)
    if not d.find(INSPECTOR):
        policy.scroll_until(d, INSPECTOR)
    d.tap(INSPECTOR)
    d.wait(1.5)


def _confirm_revert(d):
    """Tap the 'Use built-in rules' button and its confirmation dialog."""
    policy.scroll_until(d, REVERT, max_swipes=10)
    d.tap(REVERT)
    d.wait(1.5)
    # The dialog's confirm button carries the same label as the screen button; `pick="last"`
    # taps the lowest match (the dialog's copy) through the semantic primitive, so the bounds
    # still come from the live dump and the consent-wall guard still runs.
    d.tap(REVERT, pick="last")
    d.wait(2.0)


def _revert_leftover_override(d):
    """Precondition: a run that died mid-heal leaves an override active, and the capture
    surface markers come from the *active* rule set — so a stale override would make the
    first capture time out. Start from the shipped rules."""
    _open_inspector(d)
    if not policy.scroll_until(d, REVERT, max_swipes=10):
        return False
    _confirm_revert(d)
    d.log("precondition: reverted a leftover override to the built-in rules")
    return True


def _app_screen(d, surface):
    if not d.find(INSTAGRAM_CHIP):
        d.tap(by_text("Instagram"))
        d.wait(1.0)
    row = by_text(surface)
    if not policy.scroll_until(d, row):
        raise AssertionError(f"surface row '{surface}' not found in the inspector")
    d.tap(row)


def _capture_finish(d, mark, timeout=80):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        hits = [
            line
            for line in policy.log_lines(d, since=mark, tags=(policy.GUIDED_TAG,))
            if "guided capture" in line
        ]
        if hits:
            return hits[-1]
        d.wait(2.0)
    return ""


def _override_text(d):
    return d.shell("run-as", FS, "cat", OVERRIDE_REMOTE, timeout=30)


def _write_download(d, name, text):
    encoded = base64.b64encode(text.encode("utf-8")).decode("ascii")
    # One argv element: `adb shell a b c` is re-split by the device shell, so a nested
    # `sh -c "<pipeline>"` silently collapses to `sh -c echo` and writes 0 bytes.
    written = d.shell(
        f"echo {encoded} | base64 -d > {DOWNLOAD}/{name}; wc -c < {DOWNLOAD}/{name}",
        timeout=30,
    )
    size = int((written or "0").strip().split()[0] or 0) if written.strip() else 0
    if size != len(text.encode("utf-8")):
        raise AssertionError(f"could not write {DOWNLOAD}/{name} ({size} bytes)")
    # DocumentsUI only lists MediaProvider rows; the broadcast is what makes the file
    # visible to the picker (measured: without it the file is absent for minutes).
    d.shell(
        "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", f"file://{DOWNLOAD}/{name}",
        timeout=30,
    )
    d.wait(3.0)


def _ensure_download_root(d):
    """Reach the picker's Download root.

    The picker remembers the last folder, so navigating again would fight it: when the
    Download listing is already on screen nothing is tapped. Otherwise the roots drawer is
    opened and its Download row is picked by the *lowest* matching label — the breadcrumb of
    the folder we are leaving carries the same text at the top of the screen.
    """
    if d.find(by_text("Ficheiros em Transferências", match="contains")) or d.find(
        by_text("Files in Downloads", match="contains")
    ):
        return True
    for label in ROOTS_LABELS:
        if d.find(by_desc(label)):
            d.tap(by_desc(label))
            d.wait(1.2)
            break
    for label in DOWNLOAD_LABELS:
        nodes = [n for n in d.dump() if (n.text or "") == label]
        if nodes:
            pick = max(nodes, key=lambda n: n.bounds[1])
            d.tap(by_text(label), nodes=[pick])
            d.wait(1.8)
            return True
    return False


def _import_file(d, name):
    """Open the picker and pick `name` from Download; returns the RuleCatalog log lines."""
    policy.scroll_until(d, IMPORT)
    mark = policy.log_stamp(d)
    d.tap(IMPORT)
    d.wait(3.0)
    if d.current_package() != "com.google.android.documentsui":
        raise AssertionError(f"the import picker did not open (foreground={d.current_package()})")
    target = by_text(name, match="exact")
    if not d.find(target):
        _ensure_download_root(d)
    if not policy.scroll_until(d, target, max_swipes=8):
        d.screencap("import-file-missing")
        raise AssertionError(f"'{name}' is not listed by the document picker")
    d.tap(by_text(name, match="exact"))
    d.wait(3.0)
    if d.current_package() != FS:
        raise AssertionError(f"the import did not return to the app (foreground={d.current_package()})")
    lines = policy.log_lines(d, since=mark, tags=("RuleCatalog",))
    d.log(f"import '{name}' -> {lines}")
    if not any("cache dropped" in line for line in lines):
        raise AssertionError(f"no engine-cache drop after importing '{name}': {lines}")
    return lines


def _newest_dump_document(d):
    path = policy.newest_dump(d)
    if not path:
        raise AssertionError("no dump on the device after the capture")
    return path, policy.read_dump(d, path)


def _open_newest_dump(d):
    if not policy.scroll_until(d, NODES_ROW):
        raise AssertionError("no dump row in the inspector")
    d.tap(NODES_ROW)
    d.wait(1.2)
    if not policy.scroll_until(d, ANALYSIS, max_swipes=8):
        raise AssertionError("the expanded dump does not show its rule analysis")


def _analysis_lists_reels(d):
    return bool(policy.scroll_until(d, REELS_MATCH, max_swipes=8))


def _rewrite_anchor(document, view_id):
    data = json.loads(document)
    for rule in data["rules"]:
        if rule["id"] == "ig_reels_tab":
            rule["match"]["viewId"] = view_id
            break
    else:
        raise AssertionError("the exported document has no ig_reels_tab rule")
    return json.dumps(data, indent=4, ensure_ascii=False)


def run(d):
    # ---------------------------------------------------------------- 0. precondition
    _revert_leftover_override(d)

    # ---------------------------------------------------------------- 1. capture
    _open_inspector(d)
    d.screencap("inspector-before-capture")
    mark = policy.log_stamp(d)
    _app_screen(d, "Reels tab")
    d.wait(2.5)

    clips_tab = by_id(f"{IG}:id/clips_tab")
    try:
        node = d.wait_for(clips_tab, timeout=25)
        if not node.selected:
            d.tap(clips_tab)
            d.log("tapped the Reels tab inside the guided window")
    except Exception as exc:  # noqa: BLE001 - a playing video may refuse the dump
        d.log(f"clips_tab not reachable for the tap ({exc}); the app was already on Reels")
    d.wait(4.0)
    d.screencap("ig-reels-during-capture")

    finish = _capture_finish(d, mark)
    d.log(f"capture finish: {finish or 'NOT SEEN'}")
    if not finish:
        raise AssertionError("no 'guided capture' completion line during the capture window")
    if "verified=true" not in finish:
        raise AssertionError(f"the dump is not surface-verified: {finish}")

    d.launch(FS, settle=2.0)
    try:
        d.wait_for(CAPTURE_DONE, timeout=10)
    except Exception:  # noqa: BLE001 - banner is transient; the file is the evidence
        pass
    d.screencap("inspector-capture-complete")

    dump_path, document = _newest_dump_document(d)
    policy.save_lines(d, "capture-dump.json", document.splitlines())
    d.log(f"capture dump: {dump_path}")
    if not re.search(r'"surfaceVerified"\s*:\s*true', document):
        raise AssertionError("the capture dump is not surface-verified")
    if not re.search(r'"surface"\s*:\s*"reels_tab"', document):
        raise AssertionError("the capture dump's surface is not 'reels_tab'")
    nodes = re.search(r'"nodeCount"\s*:\s*(\d+)', document)
    if not nodes or int(nodes.group(1)) == 0:
        raise AssertionError("the capture dump lists no nodes")

    # ---------------------------------------------------------------- 2. analyse
    _open_newest_dump(d)
    if not _analysis_lists_reels(d):
        d.screencap("analysis-missing")
        raise AssertionError("the dump analysis does not list 'ig_reels_tab · reels_tab'")
    d.screencap("analysis-match")

    # ------------------------------------------------------------- 3. break it
    policy.scroll_until(d, EXPORT)
    d.tap(EXPORT)
    d.wait(3.0)
    if d.current_package() == "com.google.android.documentsui":
        d.back()
        d.wait(1.5)
    base_document = _override_text(d)
    policy.save_lines(d, "override-exported.json", base_document.splitlines())
    if '"ig_reels_tab"' not in base_document:
        raise AssertionError("Export did not write the effective rules to the override path")
    if not _scroll_back_until(d, OVERRIDE_SOURCE):
        raise AssertionError("the screen does not report the exported document as the override")
    d.screencap("export-adopted")

    stamp = time.strftime("%H%M%S")
    broken_name = f"heal-broken-{stamp}.json"
    broken = _rewrite_anchor(base_document, BROKEN_ANCHOR)
    policy.save_lines(d, "override-broken.json", broken.splitlines())
    _write_download(d, broken_name, broken)
    _import_file(d, broken_name)
    # Re-enter the inspector so the dump row starts collapsed (a fresh composition), then
    # expand the newest dump again: the analysis must be re-evaluated against the new rules.
    _open_inspector(d)
    _open_newest_dump(d)
    if _analysis_lists_reels(d):
        d.screencap("break-did-not-take")
        raise AssertionError("the broken override did not take effect: the rule still matches")
    d.screencap("break-analysis-empty")

    # ---------------------------------------------------------------- 4. heal it
    healed_name = f"heal-fixed-{stamp}.json"
    healed = _rewrite_anchor(base_document, f"{IG}:id/clips_tab")
    policy.save_lines(d, "override-healed.json", healed.splitlines())
    _write_download(d, healed_name, healed)
    _import_file(d, healed_name)
    if not _scroll_back_until(d, OVERRIDE_SOURCE):
        raise AssertionError("the healed import did not stay the active override")
    _open_inspector(d)
    _open_newest_dump(d)
    if not _analysis_lists_reels(d):
        d.screencap("heal-did-not-take")
        raise AssertionError("the healed override did not restore the rule match")
    d.screencap("heal-analysis-match")

    # --------------------------------------------------------------- 5. revert
    _confirm_revert(d)
    d.screencap("reverted")

    left = d.shell("run-as", FS, "ls", "files", timeout=30)
    if "rules-override.json" in left:
        raise AssertionError(f"the override file survived the revert: {left}")
    if not _scroll_back_until(d, BUILTIN_SOURCE):
        raise AssertionError("'Rules in use' does not read 'Built-in rules' after the revert")
    if d.find(REVERT):
        raise AssertionError("the 'Use built-in rules' button is still offered after the revert")

    d.log("PASS: capture → analyse → break → heal → revert, no restart")
