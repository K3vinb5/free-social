"""Offline checks for selector matching and bounds parsing (no device needed)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from driver.selectors import (  # noqa: E402
    by_desc,
    by_descs,
    by_id,
    by_text,
    by_texts,
    matches,
    select_target,
)
from driver.xmlnodes import Node, parse_xml  # noqa: E402

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


class SelectTargetTest(unittest.TestCase):
    """`pick="last"` resolves a dialog's copy of a label by bounds, never by coordinates."""

    # The two copies of "Use built-in rules": the screen's button and, below it, the dialog's.
    BUTTON = (132, 990, 517, 1049)
    CONFIRM = (604, 1305, 900, 1351)

    def setUp(self):
        def label(bounds):
            return Node(
                index=0,
                class_name="android.widget.TextView",
                package="dev.k3vinb5.freesocial",
                resource_id="",
                text="Use built-in rules",
                content_desc="",
                bounds=bounds,
            )

        self.candidates = [label(self.BUTTON), label(self.CONFIRM)]

    def test_first_keeps_dump_order(self):
        self.assertEqual(select_target(self.candidates).bounds, self.BUTTON)

    def test_last_takes_the_lowest_match(self):
        self.assertEqual(select_target(self.candidates, "last").bounds, self.CONFIRM)

    def test_empty_candidates_raise(self):
        with self.assertRaises(IndexError):
            select_target([], "last")

    def test_unknown_pick_is_rejected(self):
        with self.assertRaises(ValueError):
            select_target(self.candidates, "middle")


class LocaleVariantTest(unittest.TestCase):
    """String selectors carry the observed resource variants; matching is diacritic-folded."""

    def node(self, text: str = "", desc: str = "") -> Node:
        return Node(
            index=0,
            class_name="android.widget.TextView",
            package="dev.k3vinb5.freesocial",
            resource_id="",
            text=text,
            content_desc=desc,
            bounds=(0, 0, 100, 100),
        )

    def test_text_variant_list_matches_every_locale(self):
        sel = by_texts("Filter", "Filtrar")
        self.assertTrue(matches(self.node("Filter"), sel))
        self.assertTrue(matches(self.node("Filtrar"), sel))
        self.assertFalse(matches(self.node("Block"), sel))

    def test_desc_variant_list_matches_every_locale(self):
        sel = by_descs("Reels", "Reels de vídeo")
        self.assertTrue(matches(self.node(desc="Reels"), sel))
        self.assertTrue(matches(self.node(desc="Reels de vídeo"), sel))
        self.assertFalse(matches(self.node(desc="Shorts"), sel))

    def test_contains_variant_list(self):
        sel = by_texts("Capture complete", "Captura concluída", match="contains")
        self.assertTrue(matches(self.node("Capture complete — 12 nodes."), sel))
        self.assertTrue(matches(self.node("Captura concluída — 12 nós."), sel))
        self.assertFalse(matches(self.node("Capture cancelled."), sel))

    def test_regex_variant_list(self):
        sel = by_texts(r"^\d+ nodes$", r"^\d+ nós$", match="regex")
        self.assertTrue(matches(self.node("12 nodes"), sel))
        self.assertTrue(matches(self.node("12 nós"), sel))
        self.assertFalse(matches(self.node("12 nó"), sel))

    def test_diacritics_fold_without_a_duplicate_variant(self):
        # One resource value ("Orçamento de Reels e Shorts") also matches a dump that lost
        # the cedilla, so scenes never need an accent-stripped duplicate in the list.
        sel = by_text("Orçamento de Reels e Shorts")
        self.assertTrue(matches(self.node("Orcamento de Reels e Shorts"), sel))
        self.assertTrue(matches(self.node("Orçamento de Reels e Shorts"), sel))

    def test_empty_variant_list_is_rejected(self):
        with self.assertRaises(ValueError):
            by_texts()

    def test_variants_are_shown_in_the_selector_repr(self):
        rendered = str(by_texts("Mode", "Modo"))
        self.assertIn("'Mode'", rendered)
        self.assertIn("'Modo'", rendered)
