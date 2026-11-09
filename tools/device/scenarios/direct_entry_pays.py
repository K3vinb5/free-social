"""Instagram Reels entry with budget left must PAY, never escape (D9 regression).

Direct-engagement policy §2.1 rules 2–3: while the daily budget lasts a direct
surface pays the stopwatch and is left alone; the entry outcome is deterministic.

Flow: assert the budget is not exhausted, tap the Reels tab, wait 10 s, then
prove all three signals from that window:

  * still in Instagram, on the Reels surface (clips_tab selected or the clips
    viewer pager present),
  * no `neutralising`/escape action line in the FilterService window,
  * `budget_used_seconds` grew (read through `run-as` before and after).

The D9 bug was: an entry with budget left was escaped anyway.
"""

from driver import DumpError, by_id, policy

PKG = "com.instagram.android"
CLIPS_TAB = by_id("com.instagram.android:id/clips_tab")
CLIPS_VIEWER = by_id("com.instagram.android:id/clips_viewer_view_pager")


def _ensure_home(d):
    policy.ensure_ig_home(d)  # dumpable Home feed, tab bar visible
    d.launch(PKG, settle=2.5)
    d.wait_for(CLIPS_TAB, timeout=25)


def run(d):
    _ensure_home(d)
    before = policy.budget(d)
    d.log(f"budget before entry: {before}")
    if int(before["used"]) >= int(before["limit"]) * 60 and int(before["limit"]) != 0:
        # A previous budget scenario left the allowance spent; restore it through the
        # shared helper (stepper + fixture + process reload) instead of skipping the gate.
        d.log("allowance already spent — restoring 20 min / 0 used before the paid entry")
        before = policy.restore_budget(d)
        d.log(f"budget after restore: {before}")
    if int(before["used"]) >= int(before["limit"]) * 60:
        raise AssertionError(
            f"scenario needs budget left, but used={before['used']}s "
            f"limit={before['limit']}min — restore the budget before re-running"
        )
    d.screencap("ig-home-before")

    mark = policy.log_stamp(d)
    tab = d.tap(CLIPS_TAB)
    d.log(f"tapped Reels tab at {tab.center}; window from {mark}")
    d.wait(10.0)
    d.screencap("ig-reels-10s")

    lines = policy.log_lines(d, since=mark)
    policy.save_lines(d, "engine-window.log", lines)
    actions = policy.action_lines(lines)
    pays = [line for line in lines if "gate=PAY" in line]
    after = policy.budget(d)
    d.log(f"budget after entry: {after} (used delta {int(after['used']) - int(before['used'])}s)")

    pkg = d.current_package()
    d.log(f"foreground after Reels tap: {pkg or 'unknown'}")

    selected = None
    viewer = False
    try:
        nodes = d.dump(retries=1, save=True, label="ig-reels-surface.xml")
        selected = any(n.selected for n in d.find(CLIPS_TAB, nodes))
        viewer = bool(d.find(CLIPS_VIEWER, nodes))
        d.log(f"surface check: clips_tab selected={selected}, clips_viewer={viewer}")
    except DumpError as exc:
        d.log(f"Reels hierarchy not dumpable (expected while a reel plays): {exc}")

    d.screencap("ig-reels-surface")

    if actions:
        raise AssertionError(
            f"escape while the budget lasts ({len(actions)} action line(s)): {actions[:2]}"
        )
    if "instagram" not in pkg.lower():
        raise AssertionError(f"left Instagram on a paid entry (foreground={pkg!r})")
    if selected is False and not viewer:
        raise AssertionError(
            "neither clips_tab selected nor clips_viewer visible after the Reels tap"
        )
    if int(after["used"]) <= int(before["used"]):
        raise AssertionError(
            f"budget did not grow: used {before['used']}s -> {after['used']}s"
        )
    if pays:
        d.log("gate=PAY line present in the window")
    else:
        d.log("gate=PAY line absent (log limiter) — budget growth is the payment proof")
    d.log("PASS: in-budget Reels entry paid and was not escaped")
