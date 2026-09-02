from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


DESKTOP = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DESKTOP))

from pelmeni_desktop.android_api import (  # noqa: E402
    _load_scripts,
    _version,
    decode_access_code,
    profile_from_access_code,
    safe_export,
)
from pelmeni_desktop.profile_store import _normalize  # noqa: E402
from pelmeni_desktop.windows_proxy import _clean_domain  # noqa: E402


def access_code(payload: dict) -> str:
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    return "PEL1-" + base64.urlsafe_b64encode(raw).decode().rstrip("=")


class AndroidApiCompatibilityTests(unittest.TestCase):
    def test_pel1_matches_android_format(self) -> None:
        code = access_code({
            "format": 1,
            "name": "Тест",
            "host": "vpn.example.com",
            "ssh_port": "443",
            "username": "pel_test",
            "password": "secret",
            "socks_port": "1081",
            "window_kib": 4096,
            "packet_kib": 32,
            "mtu": 8500,
            "daily_mb": 500,
        })
        decoded = decode_access_code(code)
        profile, policy = profile_from_access_code(code)
        self.assertEqual(decoded["host"], "vpn.example.com")
        self.assertEqual(profile["port"], 443)
        self.assertEqual(profile["socks_port"], 1081)
        self.assertEqual(policy["daily_mb"], 500)

    def test_tls_access_code_survives_profile_normalization(self) -> None:
        code = access_code({
            "format": 1, "host": "vpn.example.com", "username": "pel_tls",
            "password": "secret", "tls_enabled": True, "tls_port": 443,
            "tls_ports": "443,8443",
        })
        profile, policy = profile_from_access_code(code)
        normalized = _normalize(profile)
        self.assertTrue(normalized["tls_enabled"])
        self.assertEqual(normalized["tls_ports"], "443,8443")
        self.assertTrue(policy["tls_enabled"])

    def test_split_domains_cannot_inject_pac_javascript(self) -> None:
        self.assertEqual(_clean_domain("https://*.example.com/path"), "example.com")
        self.assertEqual(_clean_domain('example.com\");alert(1)//'), "")
        self.assertEqual(_clean_domain("-bad.example"), "")
    def test_pel1_rejects_invalid_input(self) -> None:
        with self.assertRaises(ValueError):
            decode_access_code("not-a-pelmeni-code")
        with self.assertRaises(ValueError):
            decode_access_code(access_code({"format": 2}))

    def test_export_never_contains_password(self) -> None:
        profile = {
            "name": "Server",
            "host": "vpn.example.com",
            "port": 22,
            "username": "root",
            "password": "must-not-leak",
            "socks_port": 1080,
        }
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"USERPROFILE": folder}):
            target = safe_export(profile, {"auto_reconnect": True})
            text = target.read_text(encoding="utf-8")
            self.assertNotIn("must-not-leak", text)
            self.assertNotIn('"password"', text)
            self.assertTrue(json.loads(text)["requires_password"])

    def test_android_server_scripts_are_bundled(self) -> None:
        scripts = _load_scripts()
        self.assertIn("PELMENI_USERS=", scripts["worker"])
        self.assertIn("/etc/pelmeni-vpn/users.json", scripts["policy"])
        self.assertIn("PELMENI_PUBLIC=", scripts["public_installer"])
        self.assertIn("PEL_PUBLIC_CODE=", scripts["public_claim"])

    def test_release_version_order(self) -> None:
        self.assertGreater(_version("v1.31"), _version("1.31.0-beta.3"))
        self.assertGreater(_version("v1.32-beta.1"), _version("v1.31"))

    def test_regular_ui_hides_explanations_and_beta_channel(self) -> None:
        qml = (DESKTOP / "qml/Main.qml").read_text(encoding="utf-8")
        for removed in (
            "SSH-туннель с быстрым переключением серверов",
            "VPN — системный прокси",
            "VPN для всего компьютера",
            "Локальный прокси для приложений",
            "Huyna debug mode",
        ):
            self.assertNotIn(removed, qml)
        self.assertIn("visible: backend.developerMode", qml)
        self.assertIn("backend.checkUpdates(backend.developerMode && backend.betaUpdates)", qml)

    def test_desktop_activator_requires_a_valid_signature(self) -> None:
        from pelmeni_desktop import developer_mode

        valid_token = {
            "payload": {
                "version": 1,
                "enabled": True,
                "issued_at": 1700000000,
                "nonce": "release-test-fixture",
            },
            "signature": (
                "tos3Op+plwI5w+M9jta0VzxN9ll2wbGTXkr61Mk8+OdH0QUA7jfstzg5p3bg"
                "QltScq7et0wfnou36gzWf0fKBA=="
            ),
        }
        with tempfile.TemporaryDirectory() as folder, patch.object(
            developer_mode, "STATE_PATH", Path(folder) / "developer-mode.json"
        ):
            developer_mode.STATE_PATH.write_text(
                json.dumps(valid_token), encoding="utf-8"
            )
            self.assertTrue(developer_mode.is_developer_mode())
            valid_token["payload"]["enabled"] = False
            developer_mode.STATE_PATH.write_text(
                json.dumps(valid_token), encoding="utf-8"
            )
            self.assertFalse(developer_mode.is_developer_mode())
    def test_window_matches_amnezia_default_size(self) -> None:
        qml = (DESKTOP / "qml/Main.qml").read_text(encoding="utf-8")
        for line in (
            "width: 380",
            "height: 680",
            "minimumWidth: 380",
            "maximumWidth: 380",
            "minimumHeight: 680",
            "maximumHeight: 680",
        ):
            self.assertIn(line, qml)
        self.assertNotIn("Qt.WindowMaximizeButtonHint", qml)
        self.assertIn("height: 66", qml)
        self.assertIn("font.pixelSize: 28", qml)
        self.assertIn("width: peopleScroll.availableWidth - 40", qml)
    def test_desktop_version_is_1_39(self) -> None:
        from pelmeni_desktop.version import APP_VERSION
        self.assertEqual(APP_VERSION, "1.39")

    def test_default_window_kib_is_640(self) -> None:
        from pelmeni_desktop.profile_store import DEFAULT_WINDOW_KIB, _normalize
        self.assertEqual(DEFAULT_WINDOW_KIB, 640)
        normalized = _normalize({})
        self.assertEqual(normalized["window_kib"], 640)


if __name__ == "__main__":
    unittest.main()
