"""No-container swipe geometry (no device needed).

Regression: `policy.to_hub` scrolls the hub with `swipe(direction="down")` and no
container, and the driver used the raw screen rect. On the Oppo Reno4 5G that put the
start point at y=240 of 2400 — inside the ColorOS shade-pull band — so the "scroll to
top" gesture opened the notification shade instead, covering Free Social and failing
`instant_settings_mode` at "no node matches <text='Instagram'>".

Every no-container gesture must therefore start and end inside the content band, clear
of the status bar / shade zone at the top and the home/back gesture zone at the bottom.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from driver.device import (  # noqa: E402
    SWIPE_BOTTOM_BAND,
    SWIPE_TOP_BAND,
    content_rect,
    direction_points,
)

# The device the gate runs on (Oppo Reno4 5G).
SCREEN_W, SCREEN_H = 1080, 2400


class ContentRectTest(unittest.TestCase):
    def test_band_excludes_top_and_bottom_of_the_screen(self) -> None:
        _, top, right, bottom = content_rect(SCREEN_W, SCREEN_H)
        self.assertEqual(right, SCREEN_W)
        self.assertGreaterEqual(top, int(SCREEN_H * SWIPE_TOP_BAND))
        self.assertLessEqual(bottom, SCREEN_H - int(SCREEN_H * SWIPE_BOTTOM_BAND))

    def test_old_failure_point_is_outside_the_band(self) -> None:
        # y=240 of 2400 was the old `down` start point; it must never be a start again.
        _, top, _, _ = content_rect(SCREEN_W, SCREEN_H)
        self.assertGreater(top, 240)

    def test_tiny_screen_falls_back_to_the_raw_rect(self) -> None:
        self.assertEqual(content_rect(10, 2), (0, 0, 10, 2))


class DirectionPointsTest(unittest.TestCase):
    def points(self, direction: str, ratio: float = 0.8):
        rect = content_rect(SCREEN_W, SCREEN_H)
        start, end = direction_points(rect, direction, ratio)
        return rect, start, end

    def assert_inside(self, rect, *points) -> None:
        _, top, right, bottom = rect
        for x, y in points:
            self.assertTrue(0 <= x <= right, f"x={x} outside 0..{right}")
            self.assertGreaterEqual(y, top, f"y={y} is above the content band ({top})")
            self.assertLessEqual(y, bottom, f"y={y} is below the content band ({bottom})")

    def test_down_starts_below_the_status_bar(self) -> None:
        rect, start, end = self.points("down")
        self.assert_inside(rect, start, end)
        self.assertGreater(start[1], int(SCREEN_H * SWIPE_TOP_BAND))
        self.assertLess(start[1], end[1])

    def test_up_starts_above_the_navigation_bar(self) -> None:
        rect, start, end = self.points("up")
        self.assert_inside(rect, start, end)
        self.assertLessEqual(start[1], SCREEN_H - int(SCREEN_H * SWIPE_BOTTOM_BAND))
        self.assertGreater(start[1], end[1])

    def test_horizontal_swipes_stay_in_the_band(self) -> None:
        for direction in ("left", "right"):
            rect, start, end = self.points(direction)
            self.assert_inside(rect, start, end)

    def test_full_ratio_never_leaves_the_band(self) -> None:
        for direction in ("up", "down", "left", "right"):
            rect, start, end = self.points(direction, ratio=1.0)
            self.assert_inside(rect, start, end)

    def test_unknown_direction_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            direction_points(content_rect(SCREEN_W, SCREEN_H), "diagonal", 0.7)


if __name__ == "__main__":
    unittest.main()
