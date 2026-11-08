"""Device preflight for a release-gate run.

Brings the phone to the state a gate run assumes and refuses to start otherwise:

* both accessibility services enabled (FilterService + BlockService),
* budget limit 20 min / used 0, with a freshly restarted Free Social process (the live
  process caches SharedPreferences in memory, so the fixture alone is not durable),
* every app mode Filter, no rules override active,
* launcher in front.

A gate must never fail a scenario because the phone was left dirty by the previous run,
so the fixable parts (modes, budget) are reset here rather than merely reported.
"""

from __future__ import annotations

from pathlib import Path

from . import policy
from .device import Device, DriverError

FILTER_SERVICE = "FilterService"
BLOCK_SERVICE = "BlockService"
DEFAULT_LIMIT_MIN = 20
TARGET_PACKAGES = (
    "com.instagram.android",
    "com.google.android.youtube",
    "com.zhiliaoapp.musically",
)


def run_preflight(
    serial: str | None = None,
    evidence_dir: str | Path | None = None,
    timeout: int = 15,
) -> tuple[bool, list[str]]:
    """Return (ok, report lines). Every line is printed by the CLI."""
    d = Device(serial=serial, evidence_dir=evidence_dir, timeout=timeout, scenario="preflight")
    report: list[str] = []
    problems: list[str] = []

    services = d.shell("settings", "get", "secure", "enabled_accessibility_services").strip()
    for name in (FILTER_SERVICE, BLOCK_SERVICE):
        if name in services:
            report.append(f"service {name}: enabled")
        else:
            problems.append(f"service {name}: NOT enabled ({services or 'none'})")

    report.append(f"budget: {policy.restore_budget(d, DEFAULT_LIMIT_MIN)}")

    # Modes: anything but Filter is reset through the same prefs fixture the reset uses.
    prefs = d.app_settings_read(policy.APP_PACKAGE)
    changes = {
        f"app_mode_{pkg}": ("string", "filter")
        for pkg in TARGET_PACKAGES
        if prefs.get(f"app_mode_{pkg}") not in (None, "filter")
    }
    if changes:
        report.append(f"app modes: reset {sorted(changes)} to Filter")
        policy.prefs_fixture(d, changes)
    else:
        report.append("app modes: already Filter")

    # Play Protect can hold the installer on a dialog forever; a preflight reports, it
    # never tracebacks (and never leaves the gate half-started).
    try:
        reinstalled = d.reinstall(policy.APP_PACKAGE)
    except DriverError as exc:
        reinstalled = False
        report.append(f"app: reinstall did not finish ({exc})")
    if reinstalled:
        d.wait(3.0)
        report.append("app: reinstalled same APK (process restarted, data kept)")
    else:
        problems.append(
            "app: reinstall failed — answer Play Protect's app-check dialog on the device "
            "(Nao enviar) or run `adb install -r <apk>` once, then re-run the gate"
        )

    after = policy.budget(d)
    if after["used"] != 0 or after["limit"] != DEFAULT_LIMIT_MIN:
        problems.append(f"budget after reload: {after} (want used=0 limit={DEFAULT_LIMIT_MIN})")
    else:
        report.append("budget after reload: used=0 limit=20")

    modes = {
        k: v for k, v in d.app_settings_read(policy.APP_PACKAGE).items()
        if k.startswith("app_mode_") and v != "filter"
    }
    if modes:
        problems.append(f"app modes not Filter after reload: {modes}")
    else:
        report.append("app modes after reload: Filter")

    files = d.shell("run-as", policy.APP_PACKAGE, "ls", "files", timeout=30)
    if "rules-override.json" in files:
        problems.append("rules override active — revert to built-in rules before a gate run")
    else:
        report.append("rules in use: built-in (no override file)")

    d.home()
    report.append(f"device: {d.serial}")
    return not problems, report + [f"PROBLEM: {p}" for p in problems]
