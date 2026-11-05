"""Scenario discovery, execution and PASS/FAIL/WALL accounting."""

from __future__ import annotations

import importlib.util
import traceback
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from .device import Device, DriverError, DumpError, StepError, WallError

SCENARIO_DIR = Path(__file__).resolve().parents[1] / "scenarios"


@dataclass
class RunResult:
    scenario: str
    status: str  # PASS | FAIL | WALL
    exit_code: int
    evidence_dir: Path
    steps: int
    message: str = ""
    started: datetime = field(default_factory=datetime.now)
    finished: datetime | None = None

    @property
    def ok(self) -> bool:
        return self.status == "PASS"


def list_scenarios() -> list[str]:
    if not SCENARIO_DIR.is_dir():
        return []
    return sorted(
        p.stem for p in SCENARIO_DIR.glob("*.py") if not p.name.startswith("_")
    )


def load_scenario(name: str):
    path = SCENARIO_DIR / f"{name}.py"
    if not path.is_file():
        raise FileNotFoundError(
            f"scenario {name!r} not found in {SCENARIO_DIR} "
            f"(available: {', '.join(list_scenarios()) or 'none'})"
        )
    spec = importlib.util.spec_from_file_location(f"fs_scenario_{name}", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load scenario module {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not callable(getattr(module, "run", None)):
        raise ImportError(f"scenario {path} does not expose run(d)")
    return module


def run_scenario(
    name: str,
    serial: str | None = None,
    evidence_dir: str | Path | None = None,
    timeout: int = 15,
) -> RunResult:
    module = load_scenario(name)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    if evidence_dir is None:
        root = Path(__file__).resolve().parents[3] / "reverse" / "runtime-reno"
        evidence_dir = root / f"driver-{stamp}" / name
    device = Device(
        serial=serial,
        evidence_dir=evidence_dir,
        timeout=timeout,
        scenario=name,
    )
    result = RunResult(
        scenario=name,
        status="FAIL",
        exit_code=1,
        evidence_dir=device.evidence_dir,
        steps=0,
    )
    print(f"=== scenario: {name}", flush=True)
    print(f"=== device:   {device.serial}", flush=True)
    print(f"=== evidence: {device.evidence_dir}", flush=True)
    try:
        module.run(device)
        result.status, result.exit_code = "PASS", 0
        result.message = "all assertions passed"
    except WallError as exc:
        result.status, result.exit_code = "WALL", 1
        result.message = str(exc)
    except (StepError, DumpError, DriverError, AssertionError) as exc:
        result.status, result.exit_code = "FAIL", 1
        result.message = str(exc)
    except Exception as exc:  # noqa: BLE001 - scenario bug must still be reported
        result.status, result.exit_code = "FAIL", 1
        result.message = f"{type(exc).__name__}: {exc}"
        (device.evidence_dir / "traceback.txt").write_text(
            traceback.format_exc(), encoding="utf-8"
        )
    finally:
        result.steps = device._step  # noqa: SLF001 - single-purpose session object
        result.finished = datetime.now()
        # Final evidence independent of what the scenario already captured.
        try:
            device.screencap(f"final-{result.status.lower()}")
        except DriverError as exc:
            print(f"LOG: final screencap failed: {exc}", flush=True)
        try:
            device.dump(retries=0, save=True, label="final-dump.xml")
        except DriverError as exc:
            print(f"LOG: final dump unavailable: {exc}", flush=True)
        device.save_dump(
            "summary.txt",
            "\n".join(
                [
                    f"scenario: {result.scenario}",
                    f"status: {result.status}",
                    f"device: {device.serial}",
                    f"started: {result.started.isoformat(timespec='seconds')}",
                    f"finished: {result.finished.isoformat(timespec='seconds')}",
                    f"steps: {result.steps}",
                    f"message: {result.message}",
                    f"evidence: {result.evidence_dir}",
                    "",
                ]
            ),
        )
    if result.status == "PASS":
        print(f"RESULT: PASS {name} ({result.steps} steps, "
              f"{device.elapsed():.1f}s) evidence={result.evidence_dir}", flush=True)
    else:
        print(f"RESULT: {result.status} {name} ({result.steps} steps) "
              f"reason={result.message}", flush=True)
        print(f"RESULT: evidence={result.evidence_dir}", flush=True)
    return result
