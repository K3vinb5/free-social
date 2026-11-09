"""0.3.0 item 2 — the "Free Social blocked this" flash after an escape.

Contract under test (exhausted budget):
  * a real escape shows the dark flash once, with the BUDGET copy,
  * the flash auto-dismisses within 2.5 s,
  * a flash never stacks: an escape episode yields exactly one flash, and a second escape
    landing inside `FlashPolicy`'s 3 s window must not show a second one — the run FAILs
    if that ever happens,
  * the flash never leaves the user stuck (activity is gone afterwards).

Scope note: the 3 s same-episode throttle is pinned by `FlashPolicy`'s JVM tests
(`app/src/test/java/dev/k3vinb5/freesocial/filter/FlashPolicyTest.kt`), not by this
scenario. The engine ends an escape episode as soon as another surface is on screen, so a
UI-driven re-escape inside 3 s is not producible on this device (measured ~16 s); the
on-device assertion here is therefore "no stacked flash, ever", and the in-window check is
a guard that only fires if the device does produce a pair that fast.

The flash is detected from the focused window (`dumpsys window`, no uiautomator
dump: a dump on a 2.2 s screen would miss it; `dumpsys activity` keeps a finished
flash's task record for ~1 s) and its copy is captured by screencap for review.

Precondition: budget exhausted. If it is not, the scenario walks the in-app
stepper to 5 min and watches Instagram Reels until the allowance is spent, so the
scenario is self-contained rather than silently dependent on test order.
"""

import re
import time
from datetime import date, datetime, timedelta

from driver import StepError, by_id, by_text, by_texts, policy

FS_PKG = "dev.k3vinb5.freesocial"
IG_PKG = "com.instagram.android"

CLIPS_TAB = by_id("clips_tab")
FEED_TAB = by_id("feed_tab")  # IG home tab: a seen non-reels surface ends the episode
# Locale variants are the resource values in values/strings.xml + values-pt/strings.xml.
BUDGET_ROW = by_texts("Reels & Shorts budget", "Orçamento de Reels e Shorts")  # hub_budget_row
DECREASE = by_text("−")  # stepper_minus (translatable="false", identical in both)

FLASH_ACTIVITY = "ContentFlashActivity"
FLASH_DURATION_BUDGET_S = 2.5
FLASH_THROTTLE_S = 3.0  # FlashPolicy's window
FLASH_WINDOW_S = 3.0
MAX_EXHAUST_WAIT_S = 6.5 * 60


def _log_ts(line: str):
    """`11-08 17:06:50.216` -> datetime (current year), or None."""
    match = re.search(r"(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+)", line)
    if not match:
        return None
    month, day, hour, minute, second, frac = match.groups()
    return datetime(
        date.today().year, int(month), int(day), int(hour), int(minute), int(second),
        int(frac.ljust(6, "0")[:6]),
    )


def _flash_present(d):
    # mCurrentFocus, not `dumpsys activity`: a finished flash leaves its task record
    # visible to `activity` for ~0.9 s, which would inflate the on-screen lifetime.
    focus = d.shell("dumpsys window | grep mCurrentFocus")
    return FLASH_ACTIVITY in focus


def _settings(d):
    raw = d.app_settings_read(FS_PKG)
    if not raw:
        raise AssertionError("could not read Free Social's prefs — cannot judge the budget")
    today = date.today().isoformat()
    used = int(raw.get("budget_used_seconds", "0") or 0) if raw.get("budget_date") == today else 0
    limit = int(raw.get("budget_limit_minutes", "0") or 0)
    return used, limit


def _set_min_limit(d):
    """Walk the in-app stepper down to its 5-minute floor (no out-of-band writes)."""
    d.launch(FS_PKG, settle=1.2)
    # Hub-ready anchor: the budget row itself, not the status pill. With budget left the
    # pill reads hub_status_paused_budget ("Paused · N min left" / "Pausado · faltam N min"),
    # so the old "Filtering on" anchor only ever matched an exhausted-budget hub.
    d.wait_for(BUDGET_ROW, timeout=10)
    d.tap(BUDGET_ROW)
    d.wait_for(DECREASE, timeout=10)
    for _ in range(6):
        used, limit = _settings(d)
        if limit <= 5:
            break
        d.tap(DECREASE)
        d.wait(1.0)
    d.back()
    d.wait(1.0)


def _ensure_exhausted(d):
    used, limit = _settings(d)
    if limit > 0 and used >= limit * 60:
        d.log(f"budget already exhausted (used={used}s limit={limit}min)")
        return
    d.log(f"budget not exhausted (used={used}s limit={limit}min) — forcing via the 5-min stepper")
    _set_min_limit(d)
    used, limit = _settings(d)
    if used >= limit * 60:
        d.log(f"exhausted at limit={limit}min (used={used}s)")
        return
    # Watch Reels until the allowance is spent; the surface escapes on the next scan.
    # The phone's screen sleeps after 15 s, which pauses playback and therefore accrual,
    # so each poll wakes it (WAKEUP is a no-op while the screen is on).
    policy.ensure_ig_home(d)
    d.launch(IG_PKG, settle=2.0)
    if d.find(CLIPS_TAB):
        d.tap(CLIPS_TAB)
    deadline = time.monotonic() + MAX_EXHAUST_WAIT_S
    while time.monotonic() < deadline:
        used, limit = _settings(d)
        if used >= limit * 60:
            d.log(f"exhausted after watching (used={used}s limit={limit}min)")
            return
        d.wake()
        time.sleep(3.0)
    raise AssertionError(
        f"budget did not exhaust within {MAX_EXHAUST_WAIT_S:.0f}s (used={used}s limit={limit}min)"
    )


def _wait_flash(d, timeout=8.0, poll=0.15, capture=False):
    """Time the next flash: (appeared_since_start, seconds_on_screen).

    `capture` is False for timing runs — a screencap takes ~1 s and would inflate
    the measured lifetime. Evidence screenshots are taken in a separate episode.
    """
    start = time.monotonic()
    deadline = start + timeout
    while time.monotonic() < deadline:
        if _flash_present(d):
            appeared = time.monotonic()
            if capture:
                d.screencap("flash-mid-show")
            while time.monotonic() < appeared + 6.0:
                if not _flash_present(d):
                    return appeared - start, time.monotonic() - appeared
                time.sleep(poll)
            return appeared - start, None
        time.sleep(poll)
    return None, None


def _wait_flash_gone(d, timeout=6.0, poll=0.1) -> bool:
    """Wait for the flash to leave the focused window; False if it outstays [timeout].

    Bounded on purpose: a stuck `ContentFlashActivity` used to spin `while
    _flash_present(d)` forever and hang the whole gate.
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not _flash_present(d):
            return True
        time.sleep(poll)
    return not _flash_present(d)


def run(d):
    _ensure_exhausted(d)

    # Land on the feed with the Reels tab reachable, after any in-flight flash.
    d.launch(IG_PKG, settle=2.5)
    for _ in range(60):
        if not _flash_present(d):
            break
        time.sleep(0.25)

    # Resolve the tab from a fresh feed dump; the same bounds are reused for the
    # rapid re-entry (the feed layout does not move when the flash is dismissed).
    d.wait_for(CLIPS_TAB, timeout=12)
    tab_center = d.find_one(CLIPS_TAB).center
    d.log(f"Reels tab resolved at {tab_center}")

    # --- episode 1: one escape -> one flash, gone within 2.5 s (no screencap) -------
    d.tap(CLIPS_TAB)
    appeared, gone_after = _wait_flash(d)
    if appeared is None:
        raise AssertionError("no flash appeared after an escape at exhausted budget")
    d.log(f"flash appeared {appeared:.2f}s after the entry, on screen {gone_after:.2f}s")
    if gone_after is None:
        raise AssertionError("flash did not auto-dismiss within 6 s (user could be stuck)")
    if gone_after > FLASH_DURATION_BUDGET_S:
        raise AssertionError(f"flash stayed {gone_after:.2f}s > {FLASH_DURATION_BUDGET_S}s")
    excerpt = d.logcat_since("FilterService", marker="escape flash")
    text = excerpt.read_text(encoding="utf-8")
    if "reason=BUDGET" not in text:
        raise AssertionError(f"flash log missing reason=BUDGET ({excerpt.name})")
    d.log("flash log line confirms reason=BUDGET")

    # --- episode 2: screencap the copy mid-show (evidence, not timing) --------------
    d.tap(CLIPS_TAB)
    appeared2, _ = _wait_flash(d, capture=True)
    if appeared2 is None:
        raise AssertionError("no flash on the second entry (one per episode expected)")
    if not _wait_flash_gone(d):
        raise AssertionError(
            "flash still on screen 6 s after the evidence capture (user could be stuck)"
        )
    d.log("flash copy captured mid-show")

    # --- a rapid second escape must never stack a second flash ---------------------
    # Episode 3: enter, dismiss the flash while it is on screen, then ask for a new
    # episode immediately (Home tab, then Reels). Whether the engine counts that as a
    # second escape inside FlashPolicy's 3 s window depends on its own surface-episode
    # debounce (measured: a second escape 16 s later, i.e. a legitimately new episode),
    # so the assertion is the invariant that holds either way: for one rapid pair there is
    # never a second, stacked flash. "flash throttled" is reported as evidence when the
    # pair does land inside the 3 s window.
    d.tap(CLIPS_TAB)
    appeared3, _ = _wait_flash(d)
    if appeared3 is None:
        raise AssertionError("no flash on the rapid-pair entry")
    screen = d.screen_size()
    d.shell("input", "tap", str(screen[0] // 2), str(screen[1] // 2))  # dismiss the flash
    first_flash_ts = None
    for line in reversed(policy.log_lines(d)):
        if "escape flash" in line:
            first_flash_ts = _log_ts(line)
            break
    if first_flash_ts is None:
        raise AssertionError("no 'escape flash' line for the rapid-pair entry")
    # Count within a device-clock window, not the whole `logcat -d -t 2000` tail: the tail
    # rotates, so a global count can *decrease* and fake an "extra flash" (measured: -1).
    mark = policy.log_stamp(d)
    try:
        d.tap(FEED_TAB)  # end the episode on a non-reels surface
    except StepError:
        d.log("feed tab not reachable during the pair (player on top); backing out instead")
        d.back()
        d.wait(0.8)
    d.shell("input", "tap", str(tab_center[0]), str(tab_center[1]))
    d.wait(2.5)
    window = policy.log_lines(d, since=mark)
    policy.save_lines(d, "rapid-pair.log", window)
    throttle_until = first_flash_ts + timedelta(seconds=FLASH_THROTTLE_S)
    inside, after, throttled = [], [], []
    for line in window:
        if "flash throttled" in line:
            throttled.append(line)
            continue
        if "escape flash" not in line:
            continue
        stamp = _log_ts(line)
        if stamp is None:
            d.log(f"unparsable flash timestamp, not judged: {line[:80]}")
        elif stamp <= throttle_until:
            inside.append(line)
        else:
            after.append(line)
    if inside:
        raise AssertionError(
            f"{len(inside)} second flash inside the {FLASH_THROTTLE_S:.0f}s window "
            f"(throttle broken): {inside[:2]}"
        )
    d.log(
        f"rapid pair: {len(after)} flash(es) after the window, {len(throttled)} throttled "
        f"line(s), 0 flashes inside the {FLASH_THROTTLE_S:.0f}s window"
    )
    try:
        d.screencap("after-rapid-escapes")
    except Exception as exc:  # noqa: BLE001 - evidence only; ColorOS refuses some screencaps
        d.log(f"evidence screencap skipped: {exc}")
    d.log(f"rapid escapes: {throttled} throttled, no stacked flash, flash count unchanged")
    if IG_PKG not in d.current_package():
        raise AssertionError(f"escape did not land back in Instagram ({d.current_package()!r})")
    # Leave the allowance where the next scenario expects it (limit 20, used 0); this
    # scenario mutates the budget, so it restores it like exhaustion_consistent does.
    after = policy.restore_budget(d)
    if after["used"] != 0 or after["limit"] != 20:
        raise AssertionError(f"budget restore failed: {after}")
    d.log("budget restored to limit 20 / used 0")
