#!/usr/bin/env python3
"""CLI for the Free Social semantic device driver.

    uv run tools/device/drive.py list
    uv run tools/device/drive.py run yt_home_shelf_visibility
    uv run tools/device/drive.py run ig_reels_entry --device 192.168.50.20:37161

Exit code: 0 = PASS, 1 = FAIL or WALL.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from driver.runner import list_scenarios, run_scenario  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="drive.py", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="list scenarios")

    run = sub.add_parser("run", help="run one scenario")
    run.add_argument("scenario", help="scenario module name under tools/device/scenarios/")
    run.add_argument(
        "--device",
        default=os.environ.get("FREE_SOCIAL_DEVICE"),
        help="adb serial, e.g. 192.168.50.20:37161 (or $FREE_SOCIAL_DEVICE)",
    )
    run.add_argument(
        "--evidence-dir",
        default=None,
        help="override evidence directory (default reverse/runtime-reno/driver-<ts>/<scenario>)",
    )
    run.add_argument(
        "--timeout", type=int, default=15, help="per-adb-command timeout in seconds (default 15)"
    )
    args = parser.parse_args(argv)

    if args.command == "list":
        for name in list_scenarios():
            print(name)
        return 0

    if args.evidence_dir:
        Path(args.evidence_dir).mkdir(parents=True, exist_ok=True)
    result = run_scenario(
        args.scenario,
        serial=args.device,
        evidence_dir=args.evidence_dir,
        timeout=args.timeout,
    )
    return result.exit_code


if __name__ == "__main__":
    sys.exit(main())
