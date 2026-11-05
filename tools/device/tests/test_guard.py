"""Offline checks for the wall guard (no device needed).

Regression: `_guard` read the node's class through a `cls` attribute that does
not exist on `Node`, so `tap()` crashed with AttributeError on any screen that
contained a node without a resource-id — every real screen.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from driver.device import Device  # noqa: E402
from driver.xmlnodes import parse_xml  # noqa: E402

XML = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
        package="com.instagram.android" content-desc="" bounds="[0,0][1080,2400]">
    <node index="0" text="" resource-id="" class="android.widget.EditText"
          package="com.instagram.android" content-desc="" bounds="[24,100][1056,180]" />
    <node index="1" text="Mensagens" resource-id="" class="android.widget.TextView"
          package="com.instagram.android" content-desc="" bounds="[24,200][1056,260]" />
  </node>
</hierarchy>
"""


class GuardTest(unittest.TestCase):
    def test_guard_handles_id_less_nodes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            device = Device(serial="test", evidence_dir=tmp, scenario="guard")
            # Must not raise AttributeError for nodes without a resource-id.
            device._guard(parse_xml(XML))  # noqa: SLF001 - unit-testing the guard


if __name__ == "__main__":
    unittest.main()


PREFS_XML = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="budget_used_seconds" value="42" />
    <int name="budget_limit_minutes" value="20" />
    <boolean name="filtering_enabled" value="true" />
    <string name="budget_date">2026-11-05</string>
</map>
"""


class PrefsReadTest(unittest.TestCase):
    def test_typed_prefs_read_value_attribute(self) -> None:
        import subprocess

        with tempfile.TemporaryDirectory() as tmp:
            device = Device(serial="test", evidence_dir=tmp, scenario="prefs")

            def fake_adb(*args, **kwargs):  # noqa: ANN001, ANN003 - test double
                return subprocess.CompletedProcess(args=list(args), returncode=0,
                                                   stdout=PREFS_XML, stderr="")

            device._adb = fake_adb  # type: ignore[method-assign]  # noqa: SLF001
            values = device.app_settings_read("dev.k3vinb5.freesocial")
        self.assertEqual(values["budget_used_seconds"], "42")
        self.assertEqual(values["budget_limit_minutes"], "20")
        self.assertEqual(values["filtering_enabled"], "true")
        self.assertEqual(values["budget_date"], "2026-11-05")
