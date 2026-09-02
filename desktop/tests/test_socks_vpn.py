from __future__ import annotations

import socket
import struct
import unittest
from unittest.mock import Mock

from pelmeni_desktop.socks_vpn import SocksProxy, _dns_over_ssh, _parse_udp_packet


class SocksVpnTests(unittest.TestCase):
    def test_dns_uses_ipv4_tcp_inside_ssh(self) -> None:
        query = b"dns-query"
        answer = b"dns-answer"
        channel = Mock()
        channel.recv.side_effect = [struct.pack("!H", len(answer)), answer]
        transport = Mock()
        transport.open_channel.return_value = channel

        self.assertEqual(_dns_over_ssh(transport, query), answer)
        transport.open_channel.assert_called_once_with(
            "direct-tcpip", ("1.1.1.1", 53), ("127.0.0.1", 0), timeout=8
        )
        channel.sendall.assert_called_once_with(struct.pack("!H", len(query)) + query)
        channel.close.assert_called_once()
    def test_parses_ipv4_dns_packet(self) -> None:
        payload = b"dns-query"
        header = b"\x00\x00\x00\x01" + socket.inet_aton("1.1.1.1") + struct.pack("!H", 53)

        target, parsed_payload, response_header = _parse_udp_packet(header + payload)

        self.assertEqual(target, ("1.1.1.1", 53))
        self.assertEqual(parsed_payload, payload)
        self.assertEqual(response_header, header)

    def test_parses_domain_dns_packet(self) -> None:
        domain = b"dns.google"
        header = b"\x00\x00\x00\x03" + bytes((len(domain),)) + domain + struct.pack("!H", 53)

        target, payload, response_header = _parse_udp_packet(header + b"query")

        self.assertEqual(target, ("dns.google", 53))
        self.assertEqual(payload, b"query")
        self.assertEqual(response_header, header)

    def test_rejects_fragmented_udp_packet(self) -> None:
        with self.assertRaises(ValueError):
            _parse_udp_packet(b"\x00\x00\x01\x01\x01\x01\x01\x01\x00\x35")

    def test_accepts_tun2socks_udp_associate(self) -> None:
        class Transport:
            active = True

            def is_active(self) -> bool:
                return self.active

        transport = Transport()
        proxy = SocksProxy(transport, 0)
        proxy.start()
        try:
            with socket.create_connection(("127.0.0.1", proxy.port), timeout=5) as control:
                control.sendall(b"\x05\x01\x00")
                self.assertEqual(control.recv(2), b"\x05\x00")
                control.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
                reply = control.recv(10)
                self.assertEqual(reply[:4], b"\x05\x00\x00\x01")
                self.assertNotEqual(struct.unpack("!H", reply[8:10])[0], 0)
        finally:
            transport.active = False
            proxy.stop()


if __name__ == "__main__":
    unittest.main()
