"""Flash-wait helpers of the flash_escape_visibility scenario (no device needed).

Regression: the evidence-capture step waited for the flash to leave with
`while _flash_present(d): time.sleep(0.1)` — no deadline. A stuck ContentFlashActivity
would spin that loop forever and hang the whole release gate (no summary, no exit code).
`_wait_flash_gone` is bounded and FAILs on timeout.
"""

from __future__ import annotations

import importlib.util
import sys
import time
import unittest
from pathlib import Path

DEVICE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEVICE_DIR))

SCENARIO = DEVICE_DIR / "scenarios" / "flash_escape_visibility.py"

FLASH_FOCUS = (
    "  mCurrentFocus=Window{1 u0 dev.k3vinb5.freesocial/"
    "dev.k3vinb5.freesocial.flash.ContentFlashActivity}"
)
OTHER_FOCUS = "  mCurrentFocus=Window{2 u0 com.instagram.android/.activity.MainTabActivity}"


def load_scenario():
    spec = importlib.util.spec_from_file_location("flash_escape_visibility", SCENARIO)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeDevice:
    """`shell()` answers the focused-window query from a script of replies."""

    def __init__(self, replies: list[str]) -> None:
        self.replies = list(replies)
        self.calls = 0

    def shell(self, *args, **_kwargs) -> str:
        self.calls += 1
        if not self.replies:
            return FLASH_FOCUS
        return self.replies.pop(0)


class FlashPresentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mod = load_scenario()

    def test_flash_is_detected_from_the_focused_window(self) -> None:
        self.assertTrue(self.mod._flash_present(FakeDevice([FLASH_FOCUS])))
        self.assertFalse(self.mod._flash_present(FakeDevice([OTHER_FOCUS])))


class WaitFlashGoneTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mod = load_scenario()

    def test_returns_true_when_the_flash_leaves(self) -> None:
        d = FakeDevice([FLASH_FOCUS, FLASH_FOCUS, OTHER_FOCUS])
        self.assertTrue(self.mod._wait_flash_gone(d, timeout=2.0, poll=0.0))
        self.assertEqual(d.calls, 3)

    def test_returns_immediately_when_no_flash_is_on_screen(self) -> None:
        d = FakeDevice([OTHER_FOCUS])
        self.assertTrue(self.mod._wait_flash_gone(d, timeout=2.0, poll=0.0))
        self.assertEqual(d.calls, 1)

    def test_a_stuck_flash_times_out_instead_of_hanging(self) -> None:
        d = FakeDevice([FLASH_FOCUS] * 10_000)
        started = time.monotonic()
        self.assertFalse(self.mod._wait_flash_gone(d, timeout=0.3, poll=0.02))
        self.assertLess(time.monotonic() - started, 1.5, "bounded wait overran its deadline")

    def test_no_unbounded_flash_loop_is_left_in_the_scenario(self) -> None:
        source = SCENARIO.read_text(encoding="utf-8")
        self.assertNotIn(
            "while _flash_present(d):",
            source,
            "an unbounded wait for the flash is back — it can hang the gate",
        )


if __name__ == "__main__":
    unittest.main()
