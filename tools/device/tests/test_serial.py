"""Serial resolution (no device needed).

Regression: the driver used to carry a hardcoded LAN address as its default, so
the repo leaked a private IP and the tool broke on any other network. The
target is now env-driven (`FS_DEVICE_SERIAL`), falls back to the first entry
`adb devices` reports, and never names a device in source.
"""

from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from driver import device as device_mod  # noqa: E402
from driver.device import DriverError, resolve_serial  # noqa: E402

ENV_VARS = ("FS_DEVICE_SERIAL", "FREE_SOCIAL_DEVICE")


def clean_env(**values: str):
    """Patch os.environ with both serial vars removed, then apply `values`."""
    env = {k: v for k, v in os.environ.items() if k not in ENV_VARS}
    env.update(values)
    return mock.patch.dict(os.environ, env, clear=True)


class SerialResolutionTest(unittest.TestCase):
    def test_explicit_serial_beats_env(self) -> None:
        with clean_env(FS_DEVICE_SERIAL="env-serial"):
            self.assertEqual(resolve_serial("cli-serial"), "cli-serial")

    def test_fs_device_serial_env_is_used(self) -> None:
        with clean_env(FS_DEVICE_SERIAL="env-serial"):
            self.assertEqual(resolve_serial(), "env-serial")

    def test_legacy_env_var_still_honoured(self) -> None:
        with clean_env(FREE_SOCIAL_DEVICE="legacy-serial"):
            self.assertEqual(resolve_serial(), "legacy-serial")

    def test_fs_device_serial_wins_over_legacy(self) -> None:
        with clean_env(FS_DEVICE_SERIAL="new-serial", FREE_SOCIAL_DEVICE="legacy-serial"):
            self.assertEqual(resolve_serial(), "new-serial")

    def test_auto_picks_first_attached_device(self) -> None:
        with clean_env(FS_DEVICE_SERIAL="auto"):
            with mock.patch.object(
                device_mod, "attached_devices", return_value=["first-serial", "second-serial"]
            ):
                self.assertEqual(resolve_serial(), "first-serial")

    def test_auto_with_no_device_raises(self) -> None:
        with clean_env():
            with mock.patch.object(device_mod, "attached_devices", return_value=[]):
                with self.assertRaises(DriverError):
                    resolve_serial()

    def test_default_constant_is_auto(self) -> None:
        self.assertEqual(device_mod.DEFAULT_SERIAL, "auto")

    def test_attached_devices_parses_adb_output(self) -> None:
        out = (
            "List of devices attached\n"
            "aaa111\tdevice\n"
            "bbb222\tunauthorized\n"
            "192.0.2.9:5555\tdevice\n"
            "\n"
        )
        proc = mock.Mock(stdout=out, returncode=0)
        with mock.patch.object(device_mod.subprocess, "run", return_value=proc):
            self.assertEqual(device_mod.attached_devices(), ["aaa111", "192.0.2.9:5555"])


if __name__ == "__main__":
    unittest.main()
