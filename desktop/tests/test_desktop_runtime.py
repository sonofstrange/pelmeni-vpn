from __future__ import annotations

import socket
import struct
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from pelmeni_desktop.tunnel import TunnelManager, _connect_socket


ROOT = Path(__file__).resolve().parents[1]


class DesktopRuntimeTests(unittest.TestCase):
    def test_main_application_does_not_request_admin_at_startup(self) -> None:
        build_script = (ROOT / "build-qt-final.ps1").read_text(encoding="utf-8")
        helper_build = (ROOT / "build-tun-helper.ps1").read_text(encoding="utf-8")
        installer = (ROOT / "installer.iss").read_text(encoding="utf-8")
        entry = (ROOT / "pelmeni_vpn_qt_api.pyw").read_text(encoding="utf-8")
        self.assertNotIn("--uac-admin", build_script)
        self.assertNotIn("--uac-admin", helper_build)
        self.assertIn("build-tun-helper.ps1", build_script)
        self.assertIn("PelmeniVPN-TunHelper.exe", installer)
        self.assertNotIn("--tun-helper", entry)

    def test_vpn_uses_independent_system_tun_transport(self) -> None:
        backend = (ROOT / "pelmeni_desktop" / "qt_backend.py").read_text(encoding="utf-8")
        tunnel = (ROOT / "pelmeni_desktop" / "tunnel.py").read_text(encoding="utf-8")
        self.assertIn("ElevatedTunManager", backend)
        self.assertIn("self.tun.start(", backend)
        self.assertNotIn("enable_socks_proxy", backend)
        self.assertIn("from .socks_vpn import SocksProxy", tunnel)
        self.assertIn("private_proxy = SocksProxy(transport, 0)", tunnel)
        self.assertIn("telegram_proxy = SocksProxy(transport, telegram_port)", tunnel)

    def test_vpn_and_telegram_proxy_buttons_have_independent_state(self) -> None:
        from PySide6.QtCore import QObject
        from pelmeni_desktop.qt_backend import DesktopBackend

        class ActiveTransport:
            @staticmethod
            def is_active() -> bool:
                return True

            @staticmethod
            def is_telegram_proxy_active() -> bool:
                return True

        backend = DesktopBackend.__new__(DesktopBackend)
        QObject.__init__(backend)
        backend.manager = ActiveTransport()
        backend.tun = ActiveTransport()
        backend._proxy = False
        backend._vpn = True
        self.assertFalse(backend.proxyActive)
        self.assertTrue(backend.vpnActive)
        backend._proxy = True
        self.assertTrue(backend.proxyActive)
        self.assertTrue(backend.vpnActive)
        backend._vpn = False
        self.assertTrue(backend.proxyActive)
        self.assertFalse(backend.vpnActive)

    def test_navigation_uses_android_settings_vector(self) -> None:
        qml = (ROOT / "qml" / "Main.qml").read_text(encoding="utf-8")
        self.assertNotIn('"symbol": "⚙"', qml)
        self.assertIn("M19.4,13a7.5,7.5", qml)


    def test_ssh_socket_is_pinned_to_physical_interface(self) -> None:
        raw = Mock()
        target = ("31.76.110.207", 22)
        with patch(
            "pelmeni_desktop.tunnel._physical_ipv4_source",
            return_value=(22, "192.168.8.109"),
        ), patch(
            "pelmeni_desktop.tunnel.socket.getaddrinfo",
            return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", target)],
        ), patch("pelmeni_desktop.tunnel.socket.socket", return_value=raw):
            result = _connect_socket(target[0], target[1])

        self.assertIs(result, raw)
        raw.bind.assert_called_once_with(("192.168.8.109", 0))
        raw.setsockopt.assert_any_call(
            socket.IPPROTO_IP,
            getattr(socket, "IP_UNICAST_IF", 31),
            struct.pack("!I", 22),
        )
        raw.connect.assert_called_once_with(target)


    def test_beta5_socks4_and_socks5_relay(self) -> None:
        from pelmeni_desktop.main import _self_test

        _self_test()

    def test_private_vpn_and_public_telegram_ports_are_separate(self) -> None:
        from pelmeni_desktop import tunnel as tunnel_module

        transport = Mock()
        transport.is_active.return_value = True
        client = Mock()
        client.get_transport.return_value = transport
        connection_socket = Mock()
        private_proxy = Mock()
        private_proxy.port = 49152
        private_proxy.traffic.return_value = (10, 20)
        telegram_proxy = Mock()
        telegram_proxy.port = 1080
        telegram_proxy.traffic.return_value = (30, 40)
        profile = {
            "host": "vpn.example.com",
            "port": 22,
            "username": "pelmeni",
            "password": "secret",
            "socks_port": 1080,
        }
        manager = TunnelManager()
        with patch.object(tunnel_module, "_connect_socket", return_value=connection_socket), patch.object(
            tunnel_module.paramiko, "SSHClient", return_value=client
        ), patch.object(
            tunnel_module, "KNOWN_HOSTS_PATH", Mock(exists=Mock(return_value=False))
        ), patch.object(
            tunnel_module, "SocksProxy", side_effect=[private_proxy, telegram_proxy]
        ) as proxy_factory:
            manager.start(profile, Mock(), enable_telegram_proxy=False)
            proxy_factory.assert_called_once_with(transport, 0)
            self.assertEqual(manager.vpn_socks_port, 49152)
            self.assertFalse(manager.is_telegram_proxy_active())
            manager.enable_telegram_proxy(1080)
            proxy_factory.assert_called_with(transport, 1080)
            self.assertTrue(manager.is_telegram_proxy_active())
            self.assertEqual(manager.traffic(), (40, 60))
            manager.disable_telegram_proxy()
            self.assertFalse(manager.is_telegram_proxy_active())
        manager.stop()

    def test_latency_uses_a_fresh_ssh_channel(self) -> None:
        channel = Mock()
        transport = Mock()
        transport.is_active.return_value = True
        transport.open_channel.return_value = channel
        client = Mock()
        client.get_transport.return_value = transport
        manager = TunnelManager()
        manager.client = client

        latency = manager.measure_latency()

        self.assertGreaterEqual(latency, 1)
        self.assertEqual(manager.latency_ms, latency)
        transport.open_channel.assert_called_once_with(
            "direct-tcpip", ("1.1.1.1", 443), ("127.0.0.1", 0), timeout=5
        )
        channel.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
