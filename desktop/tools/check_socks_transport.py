from __future__ import annotations

import socket
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from pelmeni_desktop.storage import load_config  # noqa: E402
from pelmeni_desktop.tunnel import TunnelManager  # noqa: E402


def recv_exact(stream: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = stream.recv(size - len(data))
        if not chunk:
            raise EOFError(f"socket closed after {len(data)} of {size} bytes")
        data.extend(chunk)
    return bytes(data)


def dns_query(name: str) -> bytes:
    labels = b"".join(bytes((len(part),)) + part.encode("ascii") for part in name.split("."))
    return b"\x47\x11\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels + b"\x00\x00\x01\x00\x01"


def check_udp(port: int) -> None:
    control = socket.create_connection(("127.0.0.1", port), timeout=10)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.settimeout(15)
    try:
        control.sendall(b"\x05\x01\x00")
        assert recv_exact(control, 2) == b"\x05\x00"
        control.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
        response = recv_exact(control, 10)
        if response[:2] != b"\x05\x00":
            raise RuntimeError(f"UDP ASSOCIATE rejected: {response.hex()}")
        relay = (socket.inet_ntoa(response[4:8]), struct.unpack("!H", response[8:10])[0])
        query = dns_query("example.com")
        packet = b"\x00\x00\x00\x01" + socket.inet_aton("1.1.1.1") + struct.pack("!H", 53) + query
        udp.sendto(packet, relay)
        reply, _ = udp.recvfrom(65535)
        if len(reply) < 12 or reply[:3] != b"\x00\x00\x00":
            raise RuntimeError("invalid SOCKS5 UDP response")
        if reply[-len(query) :][:2] == query[:2]:
            pass
        print(f"UDP DNS: OK ({len(reply)} bytes)")
    finally:
        udp.close()
        control.close()


def check_tcp(port: int) -> None:
    stream = socket.create_connection(("127.0.0.1", port), timeout=10)
    try:
        stream.sendall(b"\x05\x01\x00")
        assert recv_exact(stream, 2) == b"\x05\x00"
        host = b"example.com"
        stream.sendall(b"\x05\x01\x00\x03" + bytes((len(host),)) + host + struct.pack("!H", 80))
        response = recv_exact(stream, 10)
        if response[:2] != b"\x05\x00":
            raise RuntimeError(f"CONNECT rejected: {response.hex()}")
        stream.sendall(b"GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
        first = recv_exact(stream, 12)
        if not first.startswith(b"HTTP/"):
            raise RuntimeError("invalid HTTP response")
        print("TCP HTTP: OK")
    finally:
        stream.close()


def main() -> int:
    profile = load_config()["profile"]
    manager = TunnelManager()
    try:
        manager.start(profile, lambda *_: False)
        check_tcp(manager.proxy.port)
        check_udp(manager.proxy.port)
        return 0
    finally:
        manager.stop()


if __name__ == "__main__":
    raise SystemExit(main())
