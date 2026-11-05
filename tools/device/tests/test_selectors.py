"""Offline checks for selector matching and bounds parsing (no device needed)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from driver.selectors import by_desc, by_id, by_text, matches  # noqa: E402
from driver.xmlnodes import parse_xml  # noqa: E402

XML = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
        package="com.instagram.android" content-desc="" bounds="[0,0][1080,2400]">
    <node index="0" text="" resource-id="com.instagram.android:id/clips_tab"
          class="android.widget.FrameLayout" package="com.instagram.android"
          content-desc="Reels" bounds="[240,2060][408,2222]" selected="false" />
    <node index="1" text="Shorts" resource-id="" class="android.view.ViewGroup"
          package="com.google.android.youtube" content-desc="Shorts"
          bounds="[24,900][1056,1000]" />
  </node>
</hierarchy>
"""


class SelectorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.nodes = parse_xml(XML)

    def test_bounds_and_center(self) -> None:
        tab = self.nodes[1]
        self.assertEqual(tab.bounds, (240, 2060, 408, 2222))
        self.assertEqual(tab.center, (324, 2141))
        self.assertTrue(tab.visible)

    def test_short_resource_id_matches(self) -> None:
        self.assertTrue(matches(self.nodes[1], by_id("clips_tab")))
        self.assertTrue(matches(self.nodes[1], by_id("com.instagram.android:id/clips_tab")))

    def test_desc_exact_and_contains(self) -> None:
        self.assertEqual(len([n for n in self.nodes if matches(n, by_desc("Shorts"))]), 1)
        self.assertEqual(
            len([n for n in self.nodes if matches(n, by_desc("Short", match="contains"))]),
            1,
        )
        self.assertFalse(any(matches(n, by_text("Nope")) for n in self.nodes))


if __name__ == "__main__":
    unittest.main()
