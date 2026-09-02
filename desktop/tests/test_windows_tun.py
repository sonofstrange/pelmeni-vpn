from __future__ import annotations

import ipaddress
import unittest
from pathlib import Path

from pelmeni_desktop.windows_tun import (
    FULL_TUNNEL_IPV6_ROUTES,
    FULL_TUNNEL_ROUTES,
    TUN2SOCKS_SHA256,
    WINTUN_SHA256,
    WindowsTunManager,
    _network_parts,
    _resolve_entries,
    _sha256,
)


class WindowsTunTests(unittest.TestCase):
    def test_bundled_runtime_is_authentic(self) -> None:
        runtime = Path(__file__).resolve().parents[1] / "runtime"
        self.assertEqual(_sha256(runtime / "tun2socks.exe"), TUN2SOCKS_SHA256)
        self.assertEqual(_sha256(runtime / "wintun.dll"), WINTUN_SHA256)

    def test_full_route_set_covers_public_ipv4(self) -> None:
        networks = [ipaddress.ip_network(value) for value in FULL_TUNNEL_ROUTES]
        for address in ("1.1.1.1", "8.8.8.8", "31.76.110.227", "192.0.2.1"):
            self.assertTrue(any(ipaddress.ip_address(address) in network for network in networks))

    def test_full_tunnel_does_not_route_unsupported_ipv6(self) -> None:
        self.assertEqual(FULL_TUNNEL_IPV6_ROUTES, ())

    def test_network_parts_normalizes_cidr(self) -> None:
        self.assertEqual(_network_parts("10.20.30.40/24"), ("10.20.30.0", "255.255.255.0"))

    def test_split_entries_accept_addresses_and_domains(self) -> None:
        ipv4, ipv6 = _resolve_entries(["10.0.0.0/8", "127.0.0.1", "localhost"])
        self.assertIn("10.0.0.0/8", ipv4)
        self.assertIn("127.0.0.1/32", ipv4)
        self.assertTrue(ipv6 == [] or "::1/128" in ipv6)

    def test_runtime_verification_uses_bundled_files(self) -> None:
        executable, driver = WindowsTunManager()._verify_runtime()
        self.assertEqual(executable.name, "tun2socks.exe")
        self.assertEqual(driver.name, "wintun.dll")


if __name__ == "__main__":
    unittest.main()
