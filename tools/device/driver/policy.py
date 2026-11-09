"""Wave D policy helpers shared by the acceptance scenarios.

Everything here is evidence collection, never enforcement: it reads the
`FilterService` logcat stream for a bounded window, reads the budget out of the
app's own `settings.xml`, writes documented prefs fixtures through `run-as`
(fixtures are recorded in the step log), and drives the Free Social hub with
semantic selectors.

Log windows are keyed on the **device** clock (`date +%m-%d %H:%M:%S`), which is
the same clock logcat prints, so a window starts exactly at the marker line and
never depends on host/device clock skew.
"""

from __future__ import annotations

import base64
import re
import time

from .selectors import by_text, by_texts

APP_PACKAGE = "dev.k3vinb5.freesocial"
SYSTEM_UI_PACKAGE = "com.android.systemui"
ENGINE_TAG = "FilterService"
GUIDED_TAG = "GuidedCapture"
PREFS_PATH = "shared_prefs/settings.xml"
DUMPS_DIR = "/sdcard/Android/data/dev.k3vinb5.freesocial/files/dumps"

# Hub labels, observed in both shipped locales (values/strings.xml + values-pt/strings.xml):
#   hub_budget_row        "Reels & Shorts budget" / "Orçamento de Reels e Shorts"
#   app_name              "Free Social" (identical in both locales)
#   stepper_plus/minus    "+" / "−" (translatable="false", identical in both)
HUB_BUDGET_ROW = by_texts("Reels & Shorts budget", "Orçamento de Reels e Shorts")
# budget_title is the same text as hub_budget_row, so the row cannot prove "we are on the
# hub" — it also matches the budget screen. The app bar title (app_name, identical in both
# locales) sits in the fixed top bar, so it is reachable whatever the scroll offset is.
HUB_ANCHOR = by_text("Free Social")

# "11-05 17:42:24.481 I/FilterService(14486): ..." -> "11-05 17:42:24"
_TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+\s")

# Anything the engine emits when it *acts* (as opposed to scanning/deciding).
ACTION_RE = re.compile(
    r"neutralising |action failed |gesture completed |gesture failed "
    r"|ACTION_SCROLL_FORWARD",
    re.IGNORECASE,
)


# --------------------------------------------------------------------- logcat
def log_stamp(d) -> str:
    """Current device wall clock as `MM-DDTHH:MM:SS` (single shell token).

    The format deliberately has no space: `adb shell` re-joins the argv and the
    device shell splits on whitespace, so `+%m-%d %H:%M:%S` used to truncate to
    `%m-%d` and every window silently started at the oldest buffered line.
    """
    return d.shell("date", "+%m-%dT%H:%M:%S").strip()


def log_lines(d, since: str | None = None, tags: tuple[str, ...] = (ENGINE_TAG,)) -> list[str]:
    """Logcat lines for `tags`, optionally only those at/after `since` (device clock)."""
    raw = d.shell("logcat", "-d", "-v", "time", "-s", *tags, timeout=30)
    out: list[str] = []
    for line in raw.splitlines():
        match = _TS.match(line)
        if not match:
            continue
        stamp = match.group(1).replace(" ", "T")
        if since is not None and stamp < since:
            continue
        out.append(line)
    return out


def action_lines(lines: list[str]) -> list[str]:
    return [line for line in lines if ACTION_RE.search(line)]


def save_lines(d, name: str, lines: list[str]) -> None:
    header = f"# {name}: {len(lines)} line(s), saved {time.strftime('%Y-%m-%d %H:%M:%S')}\n"
    d.save_dump(name, header + "\n".join(lines) + ("\n" if lines else ""))


# ---------------------------------------------------------------------- budget
def _int(value: str | None, default: int = 0) -> int:
    try:
        return int(value or default)
    except (TypeError, ValueError):
        return default


def budget(d) -> dict[str, int | str]:
    """Read limit/used/date straight out of the app's prefs file (read-only)."""
    values = d.app_settings_read(APP_PACKAGE)
    return {
        "used": _int(values.get("budget_used_seconds")),
        "limit": _int(values.get("budget_limit_minutes")),
        "date": values.get("budget_date", ""),
    }


def _read_prefs(d) -> str:
    return d.shell("run-as", APP_PACKAGE, "cat", PREFS_PATH, timeout=30)


def prefs_fixture(d, updates: dict[str, tuple[str, str]]) -> str:
    """Write prefs keys through `run-as` (allowed fixture; recorded in steps.log).

    `updates` maps key -> (xml type, value), e.g.
    {"budget_used_seconds": ("long", "0")}.

    Note recorded in the report: an external write lands on disk but the running
    process keeps its SharedPreferences map, so the *live* engine only sees it
    after the app process restarts (a plain a11y re-bind is not enough).
    """
    raw = _read_prefs(d)
    if "<map" not in raw:
        raise RuntimeError(f"cannot read {PREFS_PATH} via run-as")
    lines = raw.splitlines()
    for key, (kind, value) in updates.items():
        if kind == "string":
            pattern = re.compile(rf'(<string name="{re.escape(key)}">)[^<]*(</string>)')
            replacement = rf"\g<1>{value}\g<2>"
        else:
            pattern = re.compile(rf'(<{kind} name="{re.escape(key)}" value=")[^"]*(")')
            replacement = rf"\g<1>{value}\g<2>"
        hit = False
        for index, line in enumerate(lines):
            if pattern.search(line):
                lines[index] = pattern.sub(replacement, line)
                hit = True
        if not hit:
            opening = lines.index("<map>") + 1
            if kind == "string":
                lines.insert(opening, f'    <string name="{key}">{value}</string>')
            else:
                lines.insert(opening, f'    <{kind} name="{key}" value="{value}" />')
    body = "\n".join(lines) + "\n"
    encoded = base64.b64encode(body.encode("utf-8")).decode("ascii")
    # One argv element: adb re-joins argv and the device shell re-splits it, so a nested
    # `sh -c "<pipeline>"` passed as separate args collapses to `sh -c echo` and the
    # redirect runs in `/` (measured: the fixture silently never landed, used stayed 301).
    d.shell(
        f"run-as {APP_PACKAGE} sh -c 'echo {encoded} | base64 -d > {PREFS_PATH}.new "
        f"&& mv {PREFS_PATH}.new {PREFS_PATH}'",
        timeout=30,
    )
    d.log(f"FIXTURE prefs write {updates} -> {PREFS_PATH} (run-as)")
    return _read_prefs(d)


def restore_budget(d, limit: int = 20) -> dict[str, int | str]:
    """Put the allowance back to `limit` min / used 0 and prove the prefs file holds it.

    The limit goes back through the app's own stepper. `used` can only be reset by a
    prefs fixture, and the live process keeps its SharedPreferences map in memory — any
    later commit rewrites the file. So stop the target apps first (nothing playing, no
    accrual), then write and verify. A durable reset across a process restart is the
    gate preflight's job (`drive.py preflight`), not a scenario's.
    """
    restored = set_budget_limit(d, limit)
    if restored != limit:
        raise AssertionError(
            f"could not restore the budget limit to {limit} (reads {restored})"
        )
    # A target app still playing in the background keeps the engine accruing, and every
    # accrue commit re-writes this process's in-memory counter over the fixture (measured:
    # the fixture read 0, then 1200 after the next accrual commit). Stop the targets first:
    # they are not the app under test, and this is the end of a budget scenario.
    for package in (IG_PACKAGE, "com.google.android.youtube", "com.zhiliaoapp.musically"):
        d.shell("am", "force-stop", package)
    d.home()
    d.wait(2.0)
    today = d.shell("date", "+%Y-%m-%d").strip()
    updates = {
        "budget_used_seconds": ("long", "0"),
        "budget_date": ("string", today),
        "budget_limit_minutes": ("int", str(limit)),
    }
    prefs_fixture(d, updates)
    after = budget(d)
    d.log(f"restored budget: {after}")
    return after


def wait_for_log(d, needle: str, timeout: float = 10.0, tags: tuple[str, ...] = (ENGINE_TAG,)) -> str:
    """Poll the bounded logcat tail until `needle` appears; returns the matched lines.

    A settings change reaches the engine on its next scan, which can lag the app coming to
    the foreground; a single immediate read is a race (measured on the Allow check).
    """
    text = ""
    deadline = time.monotonic() + timeout
    while True:
        text = "\n".join(log_lines(d, tags=tags))
        if needle in text or time.monotonic() >= deadline:
            return text
        d.wait(0.8)


def newest_dump(d) -> str:
    out = d.shell("ls", "-t", DUMPS_DIR, timeout=30)
    files = [ln.strip() for ln in out.splitlines() if ln.strip().endswith(".json")]
    return f"{DUMPS_DIR}/{files[0]}" if files else ""


def read_dump(d, remote_path: str) -> str:
    return d.shell("cat", remote_path, timeout=30)


# ------------------------------------------------------------------- app shell
def to_hub(d, attempts: int = 5) -> None:
    """Bring the Free Social hub to the front, scrolled to the top.

    The hub remembers its scroll offset, and several flows end on a lower entry, so a
    forward-only `scroll_until` from a stale offset walks away from the row it wants.
    """
    d.launch(APP_PACKAGE, settle=1.5)
    anchor = HUB_ANCHOR
    found = False
    for _ in range(attempts):
        if d.find(anchor):
            found = True
            break
        # BACK only means "leave this screen" while the app is in front. Right after a
        # reinstall (preflight) the first launch can die before it draws, and five BACKs
        # from the launcher simply stay on the launcher — seen on 2026-11-09.
        if (d.current_package() or "") != APP_PACKAGE:
            d.launch(APP_PACKAGE, settle=1.0)
        else:
            d.back()
            d.wait(1.0)
    if not found:
        d.wait_for(anchor, timeout=10, recover=False)
    # The driver keeps no-container swipes out of the status-bar band (a swipe starting
    # there is a notification-shade pull on ColorOS), but if the shade still came down it
    # covers the hub rather than replacing it: dismiss, then retry the scroll once.
    for attempt in range(2):
        for _ in range(3):
            d.swipe(direction="down", ratio=0.8)
            d.wait(0.3)
        if SYSTEM_UI_PACKAGE not in (d.current_package() or ""):
            return
        d.log(f"notification shade opened over the hub (attempt {attempt + 1}/2); dismissing it")
        d.back()
        d.wait(0.6)
        d.launch(APP_PACKAGE, settle=1.0)


IG_PACKAGE = "com.instagram.android"


def ensure_ig_home(d, attempts: int = 4) -> bool:
    """Put Instagram on a dumpable surface with its bottom tab bar before arming.

    Instagram resumes wherever it was left — often the full-screen reel viewer, which
    refuses `uiautomator dump`, so a guided capture would wait for a tab it can neither
    see nor tap. Back out until the tab bar is readable; returns False if it never is.
    """
    from .device import DumpError
    from .selectors import by_id

    d.launch(IG_PACKAGE, settle=2.0)
    feed = by_id(f"{IG_PACKAGE}:id/feed_tab")
    clips = by_id(f"{IG_PACKAGE}:id/clips_tab")
    for _ in range(attempts + 2):
        try:
            nodes = d.dump(retries=0)
        except DumpError:
            d.back()  # a playing reel refuses the dump: leave the viewer
            d.wait(1.5)
            continue
        on_clips = any(n.selected for n in d.find(clips, nodes))
        if not on_clips and d.find(feed, nodes):
            return True
        if on_clips and d.find(feed, nodes):
            d.tap(feed, nodes)  # the Reels tab is dumpable but is not the Home feed
            d.wait(2.0)
            try:
                after = d.dump(retries=0)
            except DumpError:
                d.back()
                d.wait(1.5)
                continue
            if any(n.selected for n in d.find(clips, after)):
                # The tap went to a reel viewer/overlay on top of the tab bar.
                d.back()
                d.wait(1.5)
            continue
        d.back()
        d.wait(1.5)
    return False


def scroll_until(d, selector, max_swipes: int = 10, pause: float = 0.8) -> bool:
    """Swipe up until `selector` is visible; returns visibility, never raises."""
    for _ in range(max_swipes + 1):
        if d.find(selector):
            return True
        d.swipe(direction="up")
        d.wait(pause)
    return bool(d.find(selector))


def open_budget(d) -> None:
    to_hub(d)
    d.tap(HUB_BUDGET_ROW)
    d.wait(1.5)


def budget_limit_text(d) -> int:
    # budget_minutes_value is "%1$d min" in both locales.
    node = d.find_one(by_text(r"^\d+ min$", match="regex"))
    return _int(node.text.split()[0], -1)


def set_budget_limit(d, target: int, step: int = 5, tries: int = 12) -> int:
    """Drive the budget stepper to `target` minutes (0 or a multiple of 5)."""
    open_budget(d)
    current = budget_limit_text(d)
    for _ in range(tries):
        if current == target:
            break
        if current < target:
            d.tap(by_text("+"))  # stepper_plus (translatable="false")
        else:
            d.tap(by_text("−"))  # stepper_minus (translatable="false")
        d.wait(1.2)
        current = budget_limit_text(d)
    d.log(f"budget limit now {current} min (target {target})")
    # Every uiautomator dump re-binds the accessibility service, which re-reads
    # prefs at connect: this dump is what makes the UI change reach the engine.
    d.dump(save=True, label="budget-after-step.xml")
    d.wait(1.0)
    return current
