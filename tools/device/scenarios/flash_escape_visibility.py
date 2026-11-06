"""0.3.0 item 2 — the "Free Social blocked this" flash after an escape.

Contract under test (exhausted budget):
  * a real escape shows the dark flash once, with the BUDGET copy,
  * the flash auto-dismisses within 2.5 s,
  * two escapes inside 3 s show exactly one flash (`FlashPolicy` window 3 s),
  * the flash never leaves the user stuck (activity is gone afterwards).

The flash is detected from the focused window (`dumpsys window`, no uiautomator
dump: a dump on a 2.2 s screen would miss it; `dumpsys activity` keeps a finished
flash's task record for ~1 s) and its copy is captured by screencap for review.

Precondition: budget exhausted. If it is not, the scenario walks the in-app
stepper to 5 min and watches Instagram Reels until the allowance is spent, so the
scenario is self-contained rather than silently dependent on test order.
"""

import time
from datetime import date

from driver import by_id, by_text

FS_PKG = "dev.k3vinb5.freesocial"
IG_PKG = "com.instagram.android"

CLIPS_TAB = by_id("clips_tab")
BUDGET_ROW = by_text("Reels & Shorts budget", match="exact")
DECREASE = by_text("−", match="exact")
FILTERING_ON = by_text("Filtering on", match="exact")

FLASH_ACTIVITY = "ContentFlashActivity"
FLASH_DURATION_BUDGET_S = 2.5
FLASH_WINDOW_S = 3.0
MAX_EXHAUST_WAIT_S = 6.5 * 60


def _flash_present(d):
    # mCurrentFocus, not `dumpsys activity`: a finished flash leaves its task record
    # visible to `activity` for ~0.9 s, which would inflate the on-screen lifetime.
    focus = d.shell("dumpsys window | grep mCurrentFocus")
    return FLASH_ACTIVITY in focus


def _settings(d):
    raw = d.app_settings_read(FS_PKG)
    today = date.today().isoformat()
    used = int(raw.get("budget_used_seconds", "0") or 0) if raw.get("budget_date") == today else 0
    limit = int(raw.get("budget_limit_minutes", "0") or 0)
    return used, limit


def _set_min_limit(d):
    """Walk the in-app stepper down to its 5-minute floor (no out-of-band writes)."""
    d.launch(FS_PKG, settle=1.2)
    d.wait_for(FILTERING_ON, timeout=10)
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
    d.launch(IG_PKG, settle=2.0)
    if d.find(CLIPS_TAB):
        d.tap(CLIPS_TAB)
    deadline = time.monotonic() + MAX_EXHAUST_WAIT_S
    while time.monotonic() < deadline:
        used, limit = _settings(d)
        if used >= limit * 60:
            d.log(f"exhausted after watching (used={used}s limit={limit}min)")
            return
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
    flash_shown_at = time.monotonic() - appeared2
    shown_before = d.logcat_since("FilterService", marker="escape flash").read_text(
        encoding="utf-8"
    ).count("escape flash")
    while _flash_present(d):
        time.sleep(0.1)
    d.log("flash copy captured mid-show")

    # --- two escapes inside the 3 s window -> exactly one flash --------------------
    # Episode 3: enter, and the instant the flash is on screen dismiss it with a tap
    # (the flash dismisses on tap) and re-enter Reels. That puts the second escape
    # ~0.5-1 s after the first flash, well inside FlashPolicy's 3 s window.
    d.tap(CLIPS_TAB)
    appeared3, _ = _wait_flash(d)
    if appeared3 is None:
        raise AssertionError("no flash on the rapid-pair entry")
    screen = d.screen_size()
    d.shell("input", "tap", str(screen[0] // 2), str(screen[1] // 2))  # dismiss the flash
    shown_before = d.logcat_since("FilterService", marker="escape flash").read_text(
        encoding="utf-8"
    ).count("escape flash")
    d.shell("input", "tap", str(tab_center[0]), str(tab_center[1]))
    d.wait(1.6)
    if _flash_present(d):
        raise AssertionError("a second flash stacked on the first (throttle broken)")
    excerpt_throttled = d.logcat_since("FilterService", marker="flash throttled")
    throttled = excerpt_throttled.read_text(encoding="utf-8").count("flash throttled")
    shown_after = d.logcat_since("FilterService", marker="escape flash").read_text(
        encoding="utf-8"
    ).count("escape flash")
    if throttled < 1:
        raise AssertionError("second escape inside 3 s was not throttled (no 'flash throttled')")
    if shown_after != shown_before:
        raise AssertionError(
            f"{shown_after - shown_before} extra flash shown inside one 3 s window"
        )
    d.screencap("after-rapid-escapes")
    d.log(f"rapid escapes: {throttled} throttled, no stacked flash, flash count unchanged")
    if IG_PKG not in d.current_package():
        raise AssertionError(f"escape did not land back in Instagram ({d.current_package()!r})")
