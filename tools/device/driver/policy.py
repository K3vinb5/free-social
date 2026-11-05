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

from .selectors import by_text

APP_PACKAGE = "dev.k3vinb5.freesocial"
ENGINE_TAG = "FilterService"
GUIDED_TAG = "GuidedCapture"
PREFS_PATH = "shared_prefs/settings.xml"
DUMPS_DIR = "/sdcard/Android/data/dev.k3vinb5.freesocial/files/dumps"

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
    d.shell(
        "run-as", APP_PACKAGE, "sh", "-c",
        f"echo {encoded} | base64 -d > {PREFS_PATH}.new && "
        f"mv {PREFS_PATH}.new {PREFS_PATH}",
        timeout=30,
    )
    d.log(f"FIXTURE prefs write {updates} -> {PREFS_PATH} (run-as)")
    return _read_prefs(d)


def newest_dump(d) -> str:
    out = d.shell("ls", "-t", DUMPS_DIR, timeout=30)
    files = [ln.strip() for ln in out.splitlines() if ln.strip().endswith(".json")]
    return f"{DUMPS_DIR}/{files[0]}" if files else ""


def read_dump(d, remote_path: str) -> str:
    return d.shell("cat", remote_path, timeout=30)


# ------------------------------------------------------------------- app shell
def to_hub(d, attempts: int = 5) -> None:
    """Bring the Free Social hub to the front (back out of any inner screen)."""
    d.launch(APP_PACKAGE, settle=1.5)
    anchor = by_text("Reels & Shorts budget")
    for _ in range(attempts):
        if d.find(anchor):
            return
        d.back()
        d.wait(1.0)
    d.wait_for(anchor, timeout=10, recover=False)


def scroll_until(d, selector, max_swipes: int = 6, pause: float = 0.8) -> bool:
    """Swipe up until `selector` is visible; returns visibility, never raises."""
    for _ in range(max_swipes + 1):
        if d.find(selector):
            return True
        d.swipe(direction="up")
        d.wait(pause)
    return bool(d.find(selector))


def open_budget(d) -> None:
    to_hub(d)
    d.tap(by_text("Reels & Shorts budget"))
    d.wait(1.5)


def budget_limit_text(d) -> int:
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
            d.tap(by_text("+"))
        else:
            d.tap(by_text("−"))
        d.wait(1.2)
        current = budget_limit_text(d)
    d.log(f"budget limit now {current} min (target {target})")
    # Every uiautomator dump re-binds the accessibility service, which re-reads
    # prefs at connect: this dump is what makes the UI change reach the engine.
    d.dump(save=True, label="budget-after-step.xml")
    d.wait(1.0)
    return current
