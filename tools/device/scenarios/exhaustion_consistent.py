"""Instagram: with the budget exhausted, every Reels entry is escaped identically.

Direct-engagement policy §2.1 rule 3 ("no allow-then-deny"): once the allowance
is spent each entry gets exactly one escape, including immediate re-entries, and
the outcome never alternates allow/deny.

Fixture: the allowance is driven to 0 min through Free Social's own stepper
(0 is expressible; 20 → 15 → 10 → 5 → 0). A settings write is applied by the
engine when its accessibility connection re-reads prefs — every `uiautomator
dump` re-binds that connection, which this scenario relies on deliberately.
The prefs file is restored to limit 20 / used 0 at the end (recorded fixture).
"""

from driver import DumpError, by_id, policy

PKG = "com.instagram.android"
CLIPS_TAB = by_id("com.instagram.android:id/clips_tab")
FEED_TAB = by_id("com.instagram.android:id/feed_tab")

ENTRIES = 3
GAP_SECONDS = 4.0


def _ensure_ig_home(d):
    d.launch(PKG, settle=2.0)
    d.wait_for(CLIPS_TAB, timeout=25)
    for _ in range(2):
        if not d.find_one(CLIPS_TAB).selected:
            return
        d.log("still on a Reels surface; tapping Home")
        d.tap(FEED_TAB)
        d.wait(2.0)


def _ensure_exhausted(d):
    policy.open_budget(d)
    limit = policy.budget_limit_text(d)
    mark = policy.log_stamp(d)
    if limit != 0:
        d.log(f"budget limit is {limit} min; stepping it to 0 via the budget screen")
        limit = policy.set_budget_limit(d, 0)
    else:
        d.dump(save=True, label="budget-already-zero.xml")
    if limit != 0:
        raise AssertionError(f"could not set the budget limit to 0 (reads {limit} min)")
    d.wait(2.0)
    reconnects = [
        line for line in policy.log_lines(d, since=mark)
        if "connected, rules=" in line
    ]
    d.log(f"engine reconnects since the fixture: {len(reconnects)}")
    if not reconnects:
        d.log("WARN: no reconnect line seen — the engine may still hold the old limit")
    return limit


def _entry(d, index):
    _ensure_ig_home(d)
    d.screencap(f"ig-home-entry-{index}")
    mark = policy.log_stamp(d)
    tab = d.tap(CLIPS_TAB)
    d.log(f"entry {index}: tapped Reels tab at {tab.center}; window from {mark}")
    d.wait(GAP_SECONDS)

    lines = policy.log_lines(d, since=mark)
    policy.save_lines(d, f"engine-entry-{index}.log", lines)
    actions = policy.action_lines(lines)
    d.screencap(f"ig-after-entry-{index}")

    selected = None
    try:
        nodes = d.dump(retries=1, save=True, label=f"after-entry-{index}.xml")
        selected = any(n.selected for n in d.find(CLIPS_TAB, nodes))
    except DumpError as exc:
        d.log(f"entry {index}: post-entry dump unavailable: {exc}")
    pkg = d.current_package()
    escaped = bool(actions)
    d.log(
        f"entry {index}: escaped={escaped} actions={len(actions)} "
        f"clips_tab_selected={selected} foreground={pkg or 'unknown'}"
    )
    return {
        "index": index,
        "escaped": escaped,
        "actions": len(actions),
        "selected": selected,
        "pkg": pkg,
    }


def run(d):
    _ensure_exhausted(d)

    results = [_entry(d, i + 1) for i in range(ENTRIES)]
    outcomes = [r["escaped"] for r in results]
    total_actions = sum(r["actions"] for r in results)
    d.log(f"outcomes={outcomes} total_actions={total_actions} entries={ENTRIES}")

    if len(set(outcomes)) != 1:
        raise AssertionError(f"non-deterministic outcomes across entries: {outcomes}")
    if not all(outcomes):
        raise AssertionError(
            f"an entry was allowed although the budget is exhausted: {results}"
        )
    if any(r["actions"] == 0 for r in results):
        raise AssertionError(f"an entry produced no escape action: {results}")

    # Restore: allowance back to 20 min through the app's own stepper, then the
    # used counter back to 0 with a recorded prefs fixture.
    restored = policy.set_budget_limit(d, 20)
    if restored != 20:
        raise AssertionError(f"could not restore the budget limit to 20 (reads {restored})")
    today = d.shell("date", "+%Y-%m-%d").strip()
    policy.prefs_fixture(d, {
        "budget_used_seconds": ("long", "0"),
        "budget_date": ("string", today),
        "budget_limit_minutes": ("int", "20"),
    })
    after = policy.budget(d)
    for _ in range(5):
        if after["used"] == 0 and after["limit"] == 20:
            break
        d.wait(1.0)
        after = policy.budget(d)
    d.log(f"restored budget: {after}")
    if after["used"] != 0 or after["limit"] != 20:
        raise AssertionError(f"budget restore failed: {after}")
    d.log("PASS: 3/3 exhausted Reels entries escaped identically")
